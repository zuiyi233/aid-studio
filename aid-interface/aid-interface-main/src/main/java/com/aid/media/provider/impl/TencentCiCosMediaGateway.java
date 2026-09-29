package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.exception.ServiceException;
import com.aid.common.tencent.media.TencentMediaCosConfig;
import com.aid.common.tencent.media.TencentMediaCosConfigManager;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/** Securely stages an HTTPS media source as the COS object required by Tencent CI. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TencentCiCosMediaGateway {
    private static final long MIN_STAGE_LIMIT = 1L * 1024 * 1024;
    private static final long MAX_STAGE_LIMIT = 20L * 1024 * 1024 * 1024;
    private static final long READ_URL_TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final TencentMediaCosConfigManager mediaCosConfigManager;
    private final OssTemplate ossTemplate;
    private final OssConfigManager ossConfigManager;
    private final VerifiedMediaMetadataService verifiedMediaMetadataService;

    /** Operational staging guard, not a Tencent product limit. */
    @Value("${aid.media.tencent-ci.max-stage-bytes:2147483648}")
    private long maxStageBytes;

    Session open() {
        return open(mediaCosConfigManager.current());
    }

    Session openForMps() {
        return open(mediaCosConfigManager.forMps());
    }

    private Session open(TencentMediaCosConfig config) {
        if (config == null || StrUtil.isBlank(config.region()) || StrUtil.isBlank(config.bucketName())
                || StrUtil.isBlank(config.secretId()) || StrUtil.isBlank(config.secretKey())
                || !config.region().matches("[a-z0-9-]{3,64}")
                || !config.bucketName().matches("[A-Za-z0-9-]+-[0-9]+")) {
            throw new ServiceException("腾讯云数据万象所需 COS 凭证未配置");
        }
        if (maxStageBytes < MIN_STAGE_LIMIT || maxStageBytes > MAX_STAGE_LIMIT) {
            throw new ServiceException("腾讯云数据万象暂存大小上限配置无效");
        }
        ClientConfig clientConfig = new ClientConfig(new Region(config.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(3000);
        clientConfig.setConnectionRequestTimeout(3000);
        clientConfig.setSocketTimeout(15000);
        COSClient client = new COSClient(
                new BasicCOSCredentials(config.secretId(), config.secretKey()), clientConfig);
        return new Session(client, config.region(), config.bucketName(), 3000,
                15000, maxStageBytes);
    }

    static URI validateHttpsSource(String source) {
        try {
            URI uri = URI.create(StrUtil.trim(source));
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || StrUtil.isBlank(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || port != -1 && port != 443) {
                throw new IllegalArgumentException("unsafe URL");
            }
            return uri;
        } catch (RuntimeException ex) {
            throw new ServiceException("媒体来源必须是有效的 HTTPS 地址");
        }
    }

    static String extension(String source, Set<String> allowed) {
        URI uri = validateHttpsSource(source);
        String path = StrUtil.blankToDefault(uri.getPath(), "");
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String extension = dot > slash && dot + 1 < path.length()
                ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!extension.matches("[a-z0-9]{2,8}") || !allowed.contains(extension)) {
            throw new ServiceException("媒体来源格式不受腾讯云数据万象任务支持");
        }
        return extension;
    }

    final class Session implements AutoCloseable {
        private final COSClient client;
        private final String region;
        private final String bucket;
        private final int connectTimeoutMs;
        private final int readTimeoutMs;
        private final long byteLimit;

        private Session(COSClient client, String region, String bucket, int connectTimeoutMs,
                        int readTimeoutMs, long byteLimit) {
            this.client = client;
            this.region = region;
            this.bucket = bucket;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.byteLimit = byteLimit;
        }

        COSClient client() {
            return client;
        }

        String region() {
            return region;
        }

        String bucket() {
            return bucket;
        }

        boolean outputRequiresTransfer() {
            OssProperties site = ossConfigManager.getOssProperties();
            return site == null || !"cos".equalsIgnoreCase(site.getUploadMode())
                    || !bucket.equals(site.getCosBucketName()) || !region.equals(site.getCosRegion());
        }

        StagedObject stage(String source, String objectKey, Set<String> allowedExtensions) {
            String extension = extension(source, allowedExtensions);
            if (!objectKey.endsWith("." + extension)) {
                throw new ServiceException("腾讯云数据万象暂存对象后缀不一致");
            }
            Path file = null;
            try {
                String trustedPath = verifiedMediaMetadataService.trustedCosObjectPath(source, bucket, region);
                if (trustedPath != null) {
                    String sourceKey = trustedPath.substring(1);
                    long sourceBytes = client.getObjectMetadata(bucket, sourceKey).getContentLength();
                    if (sourceBytes <= 0 || sourceBytes > byteLimit) {
                        throw new ServiceException("媒体来源为空或超过暂存大小上限");
                    }
                    return new StagedObject(sourceKey, false);
                }
                OssProperties site = ossConfigManager.getOssProperties();
                if (site != null && "cos".equalsIgnoreCase(site.getUploadMode())
                        && !(bucket.equals(site.getCosBucketName()) && region.equals(site.getCosRegion()))) {
                    String sitePath = verifiedMediaMetadataService.trustedCosObjectPath(
                            source, site.getCosBucketName(), site.getCosRegion());
                    if (sitePath != null) {
                        try {
                            client.copyObject(new CopyObjectRequest(new Region(site.getCosRegion()),
                                    site.getCosBucketName(), sitePath.substring(1), bucket, objectKey));
                            long copied = client.getObjectMetadata(bucket, objectKey).getContentLength();
                            if (copied <= 0 || copied > byteLimit) {
                                deleteQuietly(objectKey);
                                throw new ServiceException("媒体来源为空或超过暂存大小上限");
                            }
                            return new StagedObject(objectKey, true);
                        } catch (CosServiceException ex) {
                            log.info("腾讯云跨桶复制不可用，改用受控流式转存, status={}, code={}",
                                    ex.getStatusCode(), ex.getErrorCode());
                        }
                    }
                }
                file = download(validateHttpsSource(source), extension);
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(Files.size(file));
                try (InputStream upload = Files.newInputStream(file)) {
                    client.putObject(bucket, objectKey, upload, metadata);
                }
                return new StagedObject(objectKey, true);
            } catch (ServiceException ex) {
                deleteQuietly(objectKey);
                throw ex;
            } catch (Exception ex) {
                deleteQuietly(objectKey);
                String providerCode = ex instanceof CosServiceException cos ? cos.getErrorCode() : "none";
                int providerStatus = ex instanceof CosServiceException cos ? cos.getStatusCode() : 0;
                log.error("Tencent CI source staging failed, errorType={}, providerCode={}, providerStatus={}",
                        ex.getClass().getSimpleName(), providerCode, providerStatus);
                throw new ServiceException("媒体素材暂存失败");
            } finally {
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ex) {
                        log.warn("腾讯云数据万象本地暂存文件清理失败, errorType={}",
                                ex.getClass().getSimpleName());
                    }
                }
            }
        }

        boolean exists(String objectKey) {
            return client.doesObjectExist(bucket, objectKey);
        }

        String signedReadUrl(String objectKey) {
            Date expiration = new Date(Math.addExact(System.currentTimeMillis(), READ_URL_TTL_MILLIS));
            return client.generatePresignedUrl(bucket, objectKey, expiration, HttpMethodName.GET).toString();
        }

        PersistedResult persistOutput(String objectKey, String extension, String contentType) {
            if (StrUtil.isBlank(objectKey) || !objectKey.endsWith("." + extension)
                    || !extension.matches("[a-z0-9]{2,8}")
                    || StrUtil.isBlank(contentType)) {
                throw new ServiceException("腾讯云数据万象产物信息无效");
            }
            OssProperties site = ossConfigManager.getOssProperties();
            if (site != null && "cos".equalsIgnoreCase(site.getUploadMode())
                    && bucket.equals(site.getCosBucketName()) && region.equals(site.getCosRegion())) {
                ObjectMetadata metadata = client.getObjectMetadata(bucket, objectKey);
                long bytes = metadata.getContentLength();
                if (bytes <= 0 || bytes > byteLimit) throw new ServiceException("腾讯云数据万象产物大小无效");
                return new PersistedResult("/" + objectKey,
                        resolvedOutputContentType(contentType, metadata.getContentType()), bytes);
            }
            Path file = null;
            try {
                file = Files.createTempFile("aid-tencent-ci-output-", "." + extension);
                long size;
                String persistedContentType = contentType;
                try (COSObject object = client.getObject(bucket, objectKey);
                     InputStream input = object.getObjectContent();
                     OutputStream output = Files.newOutputStream(file)) {
                    long declared = object.getObjectMetadata() == null
                            ? -1L : object.getObjectMetadata().getContentLength();
                    if (declared == 0L || declared > byteLimit) {
                        throw new ServiceException("腾讯云数据万象产物为空或超过转存大小上限");
                    }
                    persistedContentType = resolvedOutputContentType(contentType,
                            object.getObjectMetadata() == null ? null : object.getObjectMetadata().getContentType());
                    size = copyBounded(input, output, byteLimit,
                            "腾讯云数据万象产物为空或超过转存大小上限");
                }
                String storedUrl = ossTemplate.uploadSystemGeneratedFile(
                        new PathMultipartFile(file, objectKey, persistedContentType, size), "media/tencent-ci");
                if (StrUtil.isBlank(storedUrl)) {
                    throw new ServiceException("腾讯云数据万象产物转存失败");
                }
                return new PersistedResult(storedUrl, persistedContentType, size);
            } catch (ServiceException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("腾讯云数据万象产物转存失败, objectKey={}, errorType={}",
                        objectKey, ex.getClass().getSimpleName());
                throw new ServiceException("腾讯云数据万象产物转存失败");
            } finally {
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ex) {
                        log.warn("腾讯云数据万象产物临时文件清理失败, errorType={}",
                                ex.getClass().getSimpleName());
                    }
                }
            }
        }

        void deletePersistedQuietly(String storedUrl) {
            if (StrUtil.isBlank(storedUrl)) return;
            OssProperties site = ossConfigManager.getOssProperties();
            if (site != null && "cos".equalsIgnoreCase(site.getUploadMode())
                    && bucket.equals(site.getCosBucketName()) && region.equals(site.getCosRegion())) {
                // Same-bucket results are the authoritative upstream objects, never temporary uploads.
                return;
            }
            try {
                ossTemplate.deleteByUrl(storedUrl);
            } catch (RuntimeException ex) {
                log.warn("腾讯云数据万象不完整转存结果清理失败, errorType={}",
                        ex.getClass().getSimpleName());
            }
        }

        boolean deleteQuietly(String objectKey) {
            if (StrUtil.isBlank(objectKey)) {
                return true;
            }
            try {
                client.deleteObject(bucket, objectKey);
                return true;
            } catch (RuntimeException ex) {
                log.warn("腾讯云数据万象暂存对象清理失败, objectKey={}, errorType={}",
                        objectKey, ex.getClass().getSimpleName());
                return false;
            }
        }

        private Path download(URI uri, String extension) throws IOException {
            InetAddress[] addresses = resolvePublicAddresses(uri.getHost());
            DnsResolver pinnedDns = host -> uri.getHost().equalsIgnoreCase(host)
                    ? addresses.clone() : resolvePublicAddresses(host);
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(connectTimeoutMs)
                    .setConnectionRequestTimeout(connectTimeoutMs)
                    .setSocketTimeout(readTimeoutMs)
                    .setRedirectsEnabled(false)
                    .build();
            Path file = Files.createTempFile("aid-tencent-ci-", "." + extension);
            boolean complete = false;
            try (CloseableHttpClient http = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .setDnsResolver(pinnedDns)
                    .disableRedirectHandling()
                    .disableContentCompression()
                    .build()) {
                HttpGet get = new HttpGet(uri);
                get.setHeader("Accept", "application/octet-stream,*/*;q=0.8");
                try (CloseableHttpResponse response = http.execute(get)) {
                    int status = response.getStatusLine().getStatusCode();
                    if (status < 200 || status >= 300) {
                        throw new ServiceException("媒体来源下载失败");
                    }
                    HttpEntity entity = response.getEntity();
                    long declared = entity == null ? -1L : entity.getContentLength();
                    if (entity == null || declared == 0 || declared > byteLimit) {
                        throw new ServiceException("媒体来源为空或超过暂存大小上限");
                    }
                    try (InputStream input = entity.getContent();
                         OutputStream output = Files.newOutputStream(file)) {
                        copyBounded(input, output, byteLimit, "媒体来源为空或超过暂存大小上限");
                    }
                    complete = true;
                    return file;
                }
            } finally {
                if (!complete) {
                    Files.deleteIfExists(file);
                }
            }
        }

        @Override
        public void close() {
            client.shutdown();
        }
    }

    // CI's AAC output can be an MP4 audio container even when the object key ends in .aac.
    // Keep the result modality AUDIO while reporting the actual container to clients.
    static String resolvedOutputContentType(String requested, String objectContentType) {
        if (requested != null && requested.startsWith("audio/")
                && "video/mp4".equalsIgnoreCase(objectContentType)) {
            return "audio/mp4";
        }
        return requested;
    }

    record PersistedResult(String url, String contentType, long fileSize) { }
    record StagedObject(String objectKey, boolean temporary) { }

    private static long copyBounded(InputStream input, OutputStream output, long byteLimit,
                                    String failureMessage) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            if (read == 0) continue;
            total = Math.addExact(total, read);
            if (total > byteLimit) throw new ServiceException(failureMessage);
            output.write(buffer, 0, read);
        }
        if (total == 0L) throw new ServiceException(failureMessage);
        return total;
    }

    private record PathMultipartFile(Path path, String originalFilename,
                                     String contentType, long size) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return size <= 0L; }
        @Override public long getSize() { return size; }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(File dest) throws IOException {
            Files.copy(path, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static InetAddress[] resolvePublicAddresses(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
            throw new UnknownHostException("non-public address");
        }
        return addresses;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;
            if (a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31
                    || a == 192 && b == 168) return false;
            if (a == 192 && b == 0 && (c == 0 || c == 2)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113) return false;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            if ((first & 0xfe) == 0xfc || first == 0xff) return false;
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            int fourth = Byte.toUnsignedInt(bytes[3]);
            boolean discardOnly = first == 0x01 && second == 0x00
                    && Arrays.stream(new int[] {2, 3, 4, 5, 6, 7}).allMatch(i -> bytes[i] == 0);
            boolean special2001 = first == 0x20 && second == 0x01
                    && (third == 0x00 && (fourth == 0x00 || (fourth & 0xf0) == 0x10
                    || (fourth & 0xf0) == 0x20)
                    || third == 0x00 && fourth == 0x02 && bytes[4] == 0 && bytes[5] == 0
                    || third == 0x0d && fourth == 0xb8);
            boolean documentation = first == 0x3f && second == 0xff && (third & 0xf0) == 0;
            boolean segmentRouting = first == 0x5f && second == 0x00;
            if (discardOnly || special2001 || documentation || segmentRouting) return false;
            // IPv4-mapped addresses are checked after extracting their final four bytes.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (mapped) {
                try {
                    return isPublic(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException ignored) {
                    return false;
                }
            }
            // Reject transition addresses when their embedded IPv4 destination is non-public.
            if (first == 0x00 && second == 0x64 && third == 0xff && fourth == 0x9b
                    || first == 0x20 && second == 0x02) {
                int offset = first == 0x20 ? 2 : 12;
                try {
                    return isPublic(InetAddress.getByAddress(Arrays.copyOfRange(bytes, offset, offset + 4)));
                } catch (UnknownHostException ignored) {
                    return false;
                }
            }
        }
        return true;
    }
}
