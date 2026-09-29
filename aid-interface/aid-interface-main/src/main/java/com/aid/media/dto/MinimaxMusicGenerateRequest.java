package com.aid.media.dto;

import lombok.Data;

/** Request for the default-disabled MiniMax legacy Music API. */
@Data
public class MinimaxMusicGenerateRequest {
    private String modelCode;
    private String prompt;
    private String lyrics;
    private Boolean instrumental;
    private Boolean lyricsOptimizer;
    private String audioFormat;
    private Integer sampleRate;
    private Integer bitrate;
    private Boolean aigcWatermark;
    /** Existing audio uploaded and owned by the caller; used only for music-cover. */
    private Long referenceAudioId;
    /** Feature ID obtained from the official cover preprocessing API, when available. */
    private String coverFeatureId;
}
