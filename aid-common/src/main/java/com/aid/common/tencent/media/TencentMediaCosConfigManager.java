package com.aid.common.tencent.media;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** One credential and bucket for Tencent media services; legacy settings remain readable. */
@Component
@RequiredArgsConstructor
public class TencentMediaCosConfigManager {
    public static final String CATEGORY = "tencent_media_cos";
    private final ConfigService configService;

    public TencentMediaCosConfig current() {
        Map<String, String> values = values();
        if (StrUtil.isNotBlank(values.get("region")) || StrUtil.isNotBlank(values.get("bucketName"))) {
            return new TencentMediaCosConfig(values.get("region"), values.get("bucketName"),
                    values.get("secretId"), values.get("secretKey"),
                    prefix(values.get("stagingPrefix"), "aid-ci/staging/"),
                    prefix(values.get("outputPrefix"), "aid-ci/output/"), false);
        }
        Map<String, String> detection = read("image_object_detection");
        Map<String, String> site = read("oss");
        Map<String, String> previous = "DEDICATED".equals(detection.get("credentialSource"))
                ? detection : "cos".equalsIgnoreCase(site.get("uploadMode")) ? site : Map.of();
        String region = previous.get("cosRegion");
        String bucket = previous.get("cosBucketName");
        String id = previous.get("cosSecretId");
        String key = previous.get("cosSecretKey");
        if (previous == detection) {
            region = previous.get("region");
            bucket = previous.get("bucketName");
            id = previous.get("secretId");
            key = previous.get("secretKey");
        }
        return new TencentMediaCosConfig(region, bucket, id, key,
                "aid-ci/staging/", "aid-ci/output/", true);
    }

    /** Preserve the old MPS site-bucket binding until an independent processing bucket is saved. */
    public TencentMediaCosConfig forMps() {
        Map<String, String> values = values();
        if (StrUtil.isNotBlank(values.get("region")) || StrUtil.isNotBlank(values.get("bucketName")))
            return current();
        Map<String, String> site = read("oss");
        if (!"cos".equalsIgnoreCase(site.get("uploadMode")))
            return new TencentMediaCosConfig(null, null, null, null,
                    "aid-ci/staging/", "aid-ci/output/", true);
        return new TencentMediaCosConfig(site.get("cosRegion"), site.get("cosBucketName"),
                site.get("cosSecretId"), site.get("cosSecretKey"),
                "aid-ci/staging/", "aid-ci/output/", true);
    }

    public Map<String, String> publicConfig() {
        Map<String, String> result = new HashMap<>(values());
        TencentMediaCosConfig current = current();
        result.put("region", StrUtil.nullToEmpty(current.region()));
        result.put("bucketName", StrUtil.nullToEmpty(current.bucketName()));
        result.put("stagingPrefix", current.stagingPrefix());
        result.put("outputPrefix", current.outputPrefix());
        result.put("configured", String.valueOf(current.configured()));
        result.put("legacy", String.valueOf(current.legacy()));
        TencentMediaCosConfig mps = forMps();
        result.put("legacyConflict", String.valueOf(current.legacy() && mps.configured()
                && current.configured() && (!java.util.Objects.equals(current.region(), mps.region())
                    || !java.util.Objects.equals(current.bucketName(), mps.bucketName()))));
        result.put("secretId", StrUtil.isBlank(current.secretId()) ? "" : "****");
        result.put("secretKey", StrUtil.isBlank(current.secretKey()) ? "" : "****");
        return result;
    }

    private Map<String, String> values() {
        return read(CATEGORY);
    }

    private Map<String, String> read(String category) {
        try {
            Map<String, String> values = configService.getConfigValues(category);
            return values == null ? Map.of() : values;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private static String prefix(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value;
    }
}
