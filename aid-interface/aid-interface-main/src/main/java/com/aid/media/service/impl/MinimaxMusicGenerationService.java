package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidReferenceAudio;
import com.aid.aid.mapper.AidReferenceAudioMapper;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MinimaxMusicGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Adapts music and cover requests to the existing billed audio task lifecycle. */
@Service
@RequiredArgsConstructor
public class MinimaxMusicGenerationService {

    private static final Set<String> MODELS = Set.of("music-3.0", "music-2.6", "music-cover");
    private static final long MAX_COVER_BYTES = 50L * 1024 * 1024;
    private final AidReferenceAudioMapper referenceAudioMapper;
    private final MediaUrlResolver mediaUrlResolver;
    private final IMediaGenerationService mediaGenerationService;

    public MediaTaskResponse generate(MinimaxMusicGenerateRequest input, Long userId) {
        if (input == null || userId == null || userId <= 0 || !MODELS.contains(input.getModelCode())) {
            throw new ServiceException("音乐请求无效");
        }
        boolean cover = "music-cover".equals(input.getModelCode());
        if (!cover && (input.getReferenceAudioId() != null || StrUtil.isNotBlank(input.getCoverFeatureId()))) {
            throw new ServiceException("音乐模型不支持翻唱素材");
        }
        if (cover && (input.getReferenceAudioId() == null) == StrUtil.isBlank(input.getCoverFeatureId())) {
            throw new ServiceException("请选择一种翻唱素材");
        }
        String prompt = StrUtil.trimToEmpty(input.getPrompt());
        String lyrics = StrUtil.trimToEmpty(input.getLyrics());
        String displayText = prompt.isBlank() ? lyrics : prompt;
        if (displayText.isBlank()) {
            throw new ServiceException("请填写音乐描述或歌词");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("prompt", prompt);
        options.put("lyrics", lyrics);
        options.put("isInstrumental", Boolean.TRUE.equals(input.getInstrumental()));
        options.put("lyricsOptimizer", Boolean.TRUE.equals(input.getLyricsOptimizer()));
        if (input.getBitrate() != null) options.put("bitrate", input.getBitrate());
        if (input.getAigcWatermark() != null) options.put("aigcWatermark", input.getAigcWatermark());
        if (StrUtil.isNotBlank(input.getCoverFeatureId())) {
            options.put("coverFeatureId", input.getCoverFeatureId().trim());
        }
        AidReferenceAudio reference = null;
        if (input.getReferenceAudioId() != null) {
            reference = referenceAudioMapper.selectOne(new LambdaQueryWrapper<AidReferenceAudio>()
                    .eq(AidReferenceAudio::getId, input.getReferenceAudioId())
                    .eq(AidReferenceAudio::getUserId, userId)
                    .eq(AidReferenceAudio::getStatus, "0")
                    .eq(AidReferenceAudio::getDelFlag, "0"));
            if (reference == null || reference.getDurationMs() == null
                    || reference.getDurationMs() < 6_000 || reference.getDurationMs() > 360_000
                    || reference.getFileSize() == null || reference.getFileSize() > MAX_COVER_BYTES
                    || reference.getFileSize() <= 0
                    || !Set.of("mp3", "wav", "flac").contains(StrUtil.nullToEmpty(reference.getAudioFormat()).toLowerCase())) {
                throw new ServiceException("翻唱素材不可用");
            }
            String fullUrl = mediaUrlResolver.toFullUrl(reference.getAudioUrl());
            if (StrUtil.isBlank(fullUrl)) throw new ServiceException("翻唱素材不可用");
            options.put("audioUrl", fullUrl);
        }
        MediaAudioGenerateRequest request = new MediaAudioGenerateRequest();
        request.setUserId(userId);
        request.setModelName(input.getModelCode());
        request.setCapabilityCode("audio");
        request.setTtsText(displayText);
        request.setAudioFormat(input.getAudioFormat());
        request.setSampleRate(input.getSampleRate());
        request.setOptions(options);
        request.setBizTaskType("minimax_music");
        if (reference != null) request.setProjectId(reference.getProjectId());
        return mediaGenerationService.generateAudio(request);
    }
}
