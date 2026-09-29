package com.aid.config.tencentmedia.controller;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.tencent.media.TencentMediaCosConfig;
import com.aid.common.tencent.media.TencentMediaCosConfigManager;
import com.aid.common.tencent.media.TencentMediaServiceSettings;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared Tencent media COS configuration; secrets are never returned to the browser. */
@Slf4j
@RestController
@RequestMapping("/aidconfig/tencent-media")
@RequiredArgsConstructor
public class TencentMediaConfigController extends BaseController {
    private static final String CATEGORY = TencentMediaCosConfigManager.CATEGORY;
    private static final Set<String> ACTIVE = Set.of("PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");
    private static final Set<String> PROTOCOLS = Set.of("tencent-ci-async-media", "tencent-mps:subtitle-erase", "tencent-mps");
    private final TencentMediaCosConfigManager manager;
    private final ConfigService configService;
    private final IAidConfigService configWriter;
    private final AidMediaTaskMapper taskMapper;
    private final TencentMediaServiceSettings serviceSettings;
    private final com.aid.media.provider.impl.MediaTaskFileRegistry taskFiles;

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/service/{service}")
    public AjaxResult service(@PathVariable String service) {
        try {
            Map<String, String> result = new HashMap<>(serviceSettings.read(service));
            result.putIfAbsent("enabled", "true");
            return AjaxResult.success(result);
        } catch (IllegalArgumentException ex) { return AjaxResult.error(ex.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "腾讯云媒体服务开关", businessType = BusinessType.UPDATE)
    @PostMapping("/service/{service}")
    public AjaxResult saveService(@PathVariable String service, @RequestBody ServiceSettings input) {
        if (!TencentMediaServiceSettings.SERVICES.contains(service) || input == null || input.enabled() == null)
            return AjaxResult.error("服务配置无效");
        configWriter.upsertConfigValue("tencent_media_" + service, "enabled", String.valueOf(input.enabled()));
        return AjaxResult.success("保存成功");
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/cos")
    public AjaxResult read() {
        Map<String, String> result = manager.publicConfig();
        Map<String, String> storage = readCategory("oss");
        result.put("siteCosAvailable", String.valueOf("cos".equalsIgnoreCase(storage.get("uploadMode"))
                && StrUtil.isAllNotBlank(storage.get("cosRegion"), storage.get("cosBucketName"),
                storage.get("cosSecretId"), storage.get("cosSecretKey"))));
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "腾讯云媒体 COS 配置", businessType = BusinessType.UPDATE)
    @PostMapping("/cos")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult save(@RequestBody CosSettings input) {
        if (input == null) return AjaxResult.error("配置不能为空");
        try {
            Map<String, String> site = Boolean.TRUE.equals(input.importFromSite()) ? siteCosConfig() : Map.of();
            if (!site.isEmpty() && (!Objects.equals(input.region(), site.get("cosRegion"))
                    || !Objects.equals(input.bucketName(), site.get("cosBucketName")))) {
                throw new IllegalArgumentException("网站 COS 配置已变化，请重新导入预览后保存");
            }
            String region = site.isEmpty() ? input.region() : site.get("cosRegion");
            String bucket = site.isEmpty() ? input.bucketName() : site.get("cosBucketName");
            String stagingPrefix = normalizePrefix(input.stagingPrefix(), "aid-ci/staging/");
            String outputPrefix = normalizePrefix(input.outputPrefix(), "aid-ci/output/");
            validate(new CosSettings(region, bucket, null, null, stagingPrefix, outputPrefix,
                    input.confirmLegacyConflict(), false));
            TencentMediaCosConfig old = manager.current();
            if (old.legacy() && "true".equals(manager.publicConfig().get("legacyConflict"))
                    && !Boolean.TRUE.equals(input.confirmLegacyConflict()))
                throw new IllegalArgumentException("旧图像识别与视频处理使用不同 COS 桶，请确认统一迁移目标后再保存");
            String effectiveId = site.isEmpty() ? secretValue(input.secretId(), old.secretId()) : site.get("cosSecretId");
            String effectiveKey = site.isEmpty() ? secretValue(input.secretKey(), old.secretKey()) : site.get("cosSecretKey");
            if (StrUtil.isBlank(effectiveId) || StrUtil.isBlank(effectiveKey))
                throw new IllegalArgumentException("请填写完整的 COS 凭证");
            boolean locationChanged = !Objects.equals(old.region(), region)
                    || !Objects.equals(old.bucketName(), bucket)
                    || !Objects.equals(old.stagingPrefix(), stagingPrefix)
                    || !Objects.equals(old.outputPrefix(), outputPrefix);
            if (locationChanged) {
                assertNoActiveTasks();
            }
            if (!Objects.equals(old.secretId(), effectiveId)) assertNoActiveTasks();
            if (locationChanged || !Objects.equals(old.secretId(), effectiveId)
                    || !Objects.equals(old.secretKey(), effectiveKey)) {
                verifyCosAccess(region, bucket, effectiveId, effectiveKey, stagingPrefix);
            }
            configWriter.upsertConfigValue(CATEGORY, "region", region);
            configWriter.upsertConfigValue(CATEGORY, "bucketName", bucket);
            configWriter.upsertConfigValue(CATEGORY, "stagingPrefix", stagingPrefix);
            configWriter.upsertConfigValue(CATEGORY, "outputPrefix", outputPrefix);
            configWriter.upsertConfigValue(CATEGORY, "secretId", effectiveId);
            configWriter.upsertConfigValue(CATEGORY, "secretKey", effectiveKey);
            return AjaxResult.success("保存成功");
        } catch (IllegalArgumentException ex) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return AjaxResult.error(ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @PostMapping("/cos/import-site")
    public AjaxResult importSiteCos() {
        try {
            Map<String, String> storage = siteCosConfig();
            return AjaxResult.success(Map.of("region", storage.get("cosRegion"),
                    "bucketName", storage.get("cosBucketName"), "credentialsAvailable", true));
        } catch (IllegalArgumentException ex) { return AjaxResult.error(ex.getMessage()); }
    }

    private Map<String, String> siteCosConfig() {
        Map<String, String> storage = readCategory("oss");
        if (!"cos".equalsIgnoreCase(storage.get("uploadMode"))
                || !StrUtil.isAllNotBlank(storage.get("cosRegion"), storage.get("cosBucketName"),
                storage.get("cosSecretId"), storage.get("cosSecretKey"))) {
            throw new IllegalArgumentException("网站当前没有可用的 COS 配置");
        }
        return storage;
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @PostMapping("/cos/check")
    public AjaxResult checkCos() {
        TencentMediaCosConfig config = manager.current();
        if (!config.configured()) return AjaxResult.error("请先保存完整的处理 COS 配置");
        try {
            verifyCosAccess(config.region(), config.bucketName(), config.secretId(), config.secretKey(), config.stagingPrefix());
            configWriter.upsertConfigValue(CATEGORY, "lastTestAt", java.time.OffsetDateTime.now().toString());
            configWriter.upsertConfigValue(CATEGORY, "lastTestStatus", "SUCCESS");
            configWriter.upsertConfigValue(CATEGORY, "lastTestError", "");
            return AjaxResult.success(Map.of("status", "SUCCESS", "bucketName", config.bucketName(),
                    "region", config.region(), "serviceVerified", false));
        } catch (IllegalArgumentException ex) {
            configWriter.upsertConfigValue(CATEGORY, "lastTestAt", java.time.OffsetDateTime.now().toString());
            configWriter.upsertConfigValue(CATEGORY, "lastTestStatus", "FAILED");
            configWriter.upsertConfigValue(CATEGORY, "lastTestError", ex.getMessage());
            return AjaxResult.error(ex.getMessage());
        }
    }

    private void verifyCosAccess(String region, String bucket, String secretId, String secretKey, String prefix) {
        String key = prefix + "connection-check/" + UUID.randomUUID() + ".txt";
        COSClient client = null;
        boolean uploaded = false;
        try {
            ClientConfig clientConfig = new ClientConfig(new Region(region));
            clientConfig.setHttpProtocol(HttpProtocol.https);
            clientConfig.setConnectionTimeout(3000);
            clientConfig.setSocketTimeout(10000);
            client = new COSClient(new BasicCOSCredentials(secretId, secretKey), clientConfig);
            byte[] content = "aid-media-cos-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            client.putObject(bucket, key, new ByteArrayInputStream(content), metadata);
            uploaded = true;
            if (client.getObjectMetadata(bucket, key).getContentLength() != content.length)
                throw new IllegalArgumentException("COS 对象读取校验失败");
            client.deleteObject(bucket, key);
            uploaded = false;
        } catch (RuntimeException ex) {
            log.warn("腾讯云媒体 COS 连通测试失败, errorType={}", ex.getClass().getSimpleName());
            throw new IllegalArgumentException("COS 读写失败，请检查地域、桶、密钥和权限");
        } finally {
            if (uploaded && client != null) {
                try { client.deleteObject(bucket, key); }
                catch (RuntimeException ex) { log.warn("腾讯云媒体 COS 测试对象清理失败, errorType={}", ex.getClass().getSimpleName()); }
            }
            if (client != null) client.shutdown();
        }
    }

    private void assertNoActiveTasks() {
        Long count = taskMapper.selectCount(new LambdaQueryWrapper<AidMediaTask>()
                .in(AidMediaTask::getStatus, ACTIVE).in(AidMediaTask::getProtocol, PROTOCOLS));
        if (count != null && count > 0) throw new IllegalArgumentException("有 " + count + " 个腾讯云媒体任务进行中，请在任务结束后更换处理桶或地域");
        long pendingFiles = taskFiles.pendingCleanupCount();
        if (pendingFiles > 0) throw new IllegalArgumentException("有 " + pendingFiles + " 个腾讯云临时对象尚待清理，请稍后重试");
    }

    private static String secretValue(String submitted, String old) {
        return StrUtil.isBlank(submitted) || submitted.contains("****") ? old : submitted;
    }

    private static void validate(CosSettings input) {
        if (StrUtil.isBlank(input.region()) || !input.region().matches("[a-z0-9-]{3,64}"))
            throw new IllegalArgumentException("COS 地域格式错误");
        if (StrUtil.isBlank(input.bucketName()) || !input.bucketName().matches("[A-Za-z0-9-]+-[0-9]+"))
            throw new IllegalArgumentException("COS 桶格式错误");
        normalizePrefix(input.stagingPrefix(), "aid-ci/staging/");
        normalizePrefix(input.outputPrefix(), "aid-ci/output/");
    }

    private static String normalizePrefix(String value, String fallback) {
        String result = StrUtil.blankToDefault(value, fallback).trim();
        if (!result.matches("[A-Za-z0-9_/-]{1,120}/") || result.startsWith("/")
                || result.contains("//") || result.contains(".."))
            throw new IllegalArgumentException("处理目录只允许安全的桶内相对路径，并以 / 结尾");
        return result;
    }

    private Map<String, String> readCategory(String category) {
        try {
            Map<String, String> values = configService.getConfigValues(category);
            return values == null ? Map.of() : new HashMap<>(values);
        } catch (RuntimeException ex) { return Map.of(); }
    }

    public record CosSettings(String region, String bucketName, String secretId, String secretKey,
                              String stagingPrefix, String outputPrefix, Boolean confirmLegacyConflict,
                              Boolean importFromSite) { }
    public record ServiceSettings(Boolean enabled) { }
}
