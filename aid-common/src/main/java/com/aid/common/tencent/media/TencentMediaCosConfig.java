package com.aid.common.tencent.media;

/** COS bucket used by Tencent CI and MPS processing, independent of site uploads. */
public record TencentMediaCosConfig(String region, String bucketName, String secretId,
                                    String secretKey, String stagingPrefix, String outputPrefix,
                                    boolean legacy) {
    public boolean configured() {
        return region != null && !region.isBlank() && bucketName != null && !bucketName.isBlank()
                && secretId != null && !secretId.isBlank() && secretKey != null && !secretKey.isBlank();
    }
}
