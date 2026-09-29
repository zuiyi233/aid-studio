package com.aid.media.provider.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Reclaims only recorded temporary COS objects after the authoritative task has finished. */
@Component
@RequiredArgsConstructor
@Slf4j
public class TencentMediaTemporaryCleanup {
    private final MediaTaskFileRegistry registry;
    private final TencentCiCosMediaGateway gateway;
    private final com.aid.common.tencent.media.TencentMediaCosConfigManager configManager;

    public int runOnce(int batchSize) {
        int cleaned = 0;
        TencentCiCosMediaGateway.Session ci = null;
        TencentCiCosMediaGateway.Session mps = null;
        try {
            for (MediaTaskFileRegistry.TemporaryFile file : registry.pendingCleanup(batchSize)) {
                var ciConfig = configManager.current();
                boolean isCi = file.region().equals(ciConfig.region()) && file.bucket().equals(ciConfig.bucketName());
                TencentCiCosMediaGateway.Session session;
                if (isCi) {
                    if (ci == null) ci = gateway.open();
                    session = ci;
                } else {
                    if (mps == null) mps = gateway.openForMps();
                    session = mps;
                }
                if (!session.region().equals(file.region()) || !session.bucket().equals(file.bucket())) {
                    log.warn("腾讯云临时对象桶已变更，保留待处理文件, token={}, key={}",
                            file.token(), file.objectKey());
                    continue;
                }
                if (session.deleteQuietly(file.objectKey())) {
                    registry.cleaned(file.token(), file.objectKey());
                    cleaned++;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("腾讯云媒体临时对象补偿清理待重试, errorType={}", ex.getClass().getSimpleName());
        } finally {
            if (ci != null) ci.close();
            if (mps != null) mps.close();
        }
        return cleaned;
    }
}
