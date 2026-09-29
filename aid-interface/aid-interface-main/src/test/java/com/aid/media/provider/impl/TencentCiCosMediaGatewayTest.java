package com.aid.media.provider.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TencentCiCosMediaGatewayTest {
    @Test
    void reportsAudioMp4WhenAacNamedOutputUsesMp4Container() {
        assertEquals("audio/mp4", TencentCiCosMediaGateway.resolvedOutputContentType("audio/aac", "video/mp4"));
        assertEquals("audio/aac", TencentCiCosMediaGateway.resolvedOutputContentType("audio/aac", "audio/aac"));
        assertEquals("video/mp4", TencentCiCosMediaGateway.resolvedOutputContentType("video/mp4", "video/mp4"));
    }
}
