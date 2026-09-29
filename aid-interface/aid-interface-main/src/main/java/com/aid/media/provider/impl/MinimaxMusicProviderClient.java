package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.oss.factory.OssFactory;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.tokendance.security.TokenDancePublicMediaDownloader;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MiniMax legacy Music API. The catalog remains disabled because new-account access is restricted. */
@Slf4j
@Component
public class MinimaxMusicProviderClient implements AudioProviderClient {

    public static final String PROTOCOL = "minimax-music";
    private static final Set<String> MODELS = Set.of("music-3.0", "music-2.6", "music-cover");
    private static final int MAX_AUDIO_BYTES = 50 * 1024 * 1024;

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return "minimax".equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public boolean supportsModel(String modelName) {
        return MODELS.contains(StrUtil.trim(modelName));
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        Map<String, Object> body = buildBody(config, request);
        if (StrUtil.isBlank(config.getApiKey())) {
            throw new ServiceException("MiniMax密钥未配置");
        }
        final String url;
        try {
            // The legacy MiniMax provider still hosts existing TTS settings. Only this Music
            // adapter moves its unchanged official-default host; custom gateways remain intact.
            String baseUrl = "https://api.minimaxi.com".equalsIgnoreCase(StrUtil.trim(config.getBaseUrl()))
                    ? "https://api.minimax.cn" : config.getBaseUrl();
            url = ProviderEndpointUtils.buildSubmitUrl(baseUrl, config.getApiSuffix());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("MiniMax地址无效");
        }
        String raw;
        int status;
        try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(com.aid.model.definition.ModelConfiguredRequestBody.apply(config, body, request)))
                .timeout(300_000).execute()) {
            status = response.getStatus();
            raw = response.body();
        } catch (Exception ex) {
            log.warn("MiniMax Music submit outcome unknown, modelCode={}, error={}",
                    config.getModelCode(), ex.getClass().getSimpleName());
            throw new ServiceException("上游提交状态未知");
        }
        if (status == 410) {
            return ProviderSubmitResult.builder().rawResponse("上游音乐接口已停用").build();
        }
        if (status < 200 || status >= 300) {
            return ProviderSubmitResult.builder()
                    .rawResponse(ProviderErrorSanitizer.fromHttp(status, raw)).build();
        }
        JsonNode root = ProviderResponseHelper.readTree(raw);
        JsonNode base = root == null ? null : root.path("base_resp");
        if (base == null || base.path("status_code").asInt(-1) != 0) {
            return ProviderSubmitResult.builder()
                    .rawResponse(ProviderErrorSanitizer.safeMessage(raw, "上游音乐生成失败")).build();
        }
        JsonNode data = root.path("data");
        if (data.path("status").asInt(-1) != 2) {
            return ProviderSubmitResult.builder().rawResponse("上游音乐未返回完成状态").build();
        }
        String resultUrl = data.path("audio").asText(null);
        if (!isHttpUrl(resultUrl)) {
            return ProviderSubmitResult.builder().rawResponse("上游音乐产物地址无效").build();
        }
        byte[] bytes = TokenDancePublicMediaDownloader.download(resultUrl, 10_000, 180_000);
        if (bytes.length == 0 || bytes.length > MAX_AUDIO_BYTES) {
            throw new ServiceException("音乐产物体积无效");
        }
        String format = request.getAudioFormat() == null ? "mp3" : request.getAudioFormat();
        String contentType = "mp3".equals(format) ? "audio/mpeg" : "audio/" + format;
        String ossUrl;
        try {
            ossUrl = OssFactory.instance().uploadSuffix(bytes, "." + format, contentType).getUrl();
        } catch (IOException ex) {
            throw new ServiceException("音乐产物保存失败");
        }
        if (StrUtil.isBlank(ossUrl)) {
            throw new ServiceException("音乐产物保存失败");
        }
        long durationMs = root.path("extra_info").path("music_duration").asLong(0);
        return ProviderSubmitResult.builder().ossUrl(ossUrl)
                .audioDurationMs(durationMs > 0 ? durationMs : null)
                .rawResponse("{\"music_status\":2}").build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo config, String taskId) {
        return ProviderTaskResult.builder().status("PROCESSING")
                .querySuccessful(Boolean.FALSE).terminalConfirmed(Boolean.FALSE)
                .errorMessage("音乐接口为同步生成").build();
    }

    public static Map<String, Object> buildBody(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        if (config == null || request == null || request.isPreviewMode()) {
            throw new ServiceException("音乐请求无效");
        }
        String model = StrUtil.trim(config.getRealModelCode());
        if (!MODELS.contains(model)) {
            throw new ServiceException("音乐模型不支持");
        }
        Map<String, Object> options = request.getOptions() == null ? Map.of() : request.getOptions();
        String prompt = stringOption(options, "prompt");
        if (!options.containsKey("prompt")) {
            prompt = StrUtil.trimToEmpty(request.getTtsText());
        }
        String lyrics = stringOption(options, "lyrics");
        boolean cover = "music-cover".equals(model);
        boolean instrumental = booleanOption(options, "isInstrumental", false);
        boolean optimizer = booleanOption(options, "lyricsOptimizer", false);
        String audioUrl = stringOption(options, "audioUrl");
        String featureId = stringOption(options, "coverFeatureId");
        if (options.containsKey("audioBase64")) {
            throw new ServiceException("请先上传参考音频");
        }
        if (cover) {
            if (prompt.length() < 10 || prompt.length() > 300 || instrumental || optimizer
                    || (StrUtil.isNotBlank(audioUrl) == StrUtil.isNotBlank(featureId))
                    || (StrUtil.isNotBlank(featureId) && (lyrics.length() < 10 || lyrics.length() > 1000))
                    || (StrUtil.isNotBlank(lyrics) && (lyrics.length() < 10 || lyrics.length() > 1000))) {
                throw new ServiceException("翻唱参数无效");
            }
            if (StrUtil.isNotBlank(audioUrl) && !isHttpUrl(audioUrl)) {
                throw new ServiceException("参考音频地址无效");
            }
        } else if (prompt.length() > 2000 || (instrumental && prompt.isBlank())
                || lyrics.length() > 3500
                || (!instrumental && lyrics.isBlank() && (!optimizer || prompt.isBlank()))
                || StrUtil.isNotBlank(audioUrl) || StrUtil.isNotBlank(featureId)) {
            throw new ServiceException("音乐参数无效");
        }
        String format = StrUtil.blankToDefault(request.getAudioFormat(), "mp3");
        if (!Set.of("mp3", "wav", "pcm").contains(format)) {
            throw new ServiceException("音乐格式不支持");
        }
        int sampleRate = request.getSampleRate() == null ? 44100 : request.getSampleRate();
        if (!Set.of(16000, 24000, 32000, 44100).contains(sampleRate)) {
            throw new ServiceException("音乐采样率不支持");
        }
        int bitrate = intOption(options, "bitrate", 256000);
        if (!Set.of(32000, 64000, 128000, 256000).contains(bitrate)) {
            throw new ServiceException("音乐比特率不支持");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (!prompt.isBlank()) body.put("prompt", prompt);
        if (!lyrics.isBlank()) body.put("lyrics", lyrics);
        body.put("stream", false);
        body.put("output_format", "url");
        body.put("audio_setting", Map.of("sample_rate", sampleRate, "bitrate", bitrate, "format", format));
        if (cover) {
            if (StrUtil.isNotBlank(audioUrl)) body.put("audio_url", audioUrl);
            else body.put("cover_feature_id", featureId);
        } else {
            body.put("is_instrumental", instrumental);
            body.put("lyrics_optimizer", optimizer);
        }
        if (options.containsKey("aigcWatermark")) {
            body.put("aigc_watermark", booleanOption(options, "aigcWatermark", false));
        }
        return body;
    }

    private static String stringOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new ServiceException("音乐参数格式无效");
        return text.trim();
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new ServiceException("音乐参数格式无效");
        return result;
    }

    private static int intOption(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new ServiceException("音乐参数格式无效");
        }
        return number.intValue();
    }

    private static boolean isHttpUrl(String raw) {
        try {
            URI uri = URI.create(StrUtil.trimToEmpty(raw));
            return Set.of("http", "https").contains(uri.getScheme())
                    && StrUtil.isNotBlank(uri.getHost()) && uri.getUserInfo() == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
