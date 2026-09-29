package com.aid.common.tencent.media;

import com.aid.common.aid.core.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Feature switches for Tencent media processing; model capability and billing remain authoritative. */
@Component
@RequiredArgsConstructor
public class TencentMediaServiceSettings {
    public static final Set<String> SERVICES = Set.of("portrait", "voice", "subtitle");
    private final ConfigService configService;

    public Map<String, String> read(String service) {
        if (!SERVICES.contains(service)) throw new IllegalArgumentException("未知的腾讯云媒体服务");
        try {
            Map<String, String> values = configService.getConfigValues("tencent_media_" + service);
            return values == null ? Map.of() : values;
        } catch (RuntimeException ex) { return Map.of(); }
    }

    public boolean enabled(String service) {
        return Boolean.parseBoolean(read(service).getOrDefault("enabled", "true"));
    }
}
