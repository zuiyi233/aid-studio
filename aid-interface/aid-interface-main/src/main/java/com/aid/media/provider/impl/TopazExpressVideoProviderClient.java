package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Topaz video Express API. External source keeps file transfer out of the HTTP submission path. */
@Slf4j
@Component
public class TopazExpressVideoProviderClient implements VideoProviderClient {
    public static final String PROTOCOL = "topaz:video-express";
    private static final Set<String> CONTAINERS = Set.of("3gp","avi","dv","flv","m1v","m2t","m2ts",
            "m2v","m4v","mkv","mov","mp4","mpeg","mpg","mts","mxf","ser","ts","vob","webm","wmv");
    private static final Set<String> OPTION_KEYS = Set.of("referenceVideos", "referenceVideoDurations",
            "referenceVideoSeconds", "targetResolution", "interpolationMode", "targetFps", "slowMotionFactor",
            "estimatedProviderCredits", "enhancement");
    private static final Map<String, Set<String>> ENHANCEMENT_ENUMS = Map.of(
            "videoType", Set.of("Progressive", "Interlaced", "ProgressiveInterlaced"),
            "auto", Set.of("Auto", "Manual", "Relative"),
            "fieldOrder", Set.of("TopFirst", "BottomFirst", "Auto"),
            "focusFixLevel", Set.of("None", "Normal", "Strong"),
            "grainType", Set.of("silver_rich", "gaussian", "grey"));
    private static final Map<String, double[]> ENHANCEMENT_RANGES = Map.ofEntries(
            Map.entry("compression", new double[]{-1, 1}),
            Map.entry("details", new double[]{-1, 1}),
            Map.entry("prenoise", new double[]{0, 0.1}),
            Map.entry("noise", new double[]{-1, 1}),
            Map.entry("halo", new double[]{-1, 1}),
            Map.entry("preblur", new double[]{-1, 1}),
            Map.entry("blur", new double[]{-1, 1}),
            Map.entry("grain", new double[]{0, 0.1}),
            Map.entry("grainSigma", new double[]{0, 1}),
            Map.entry("grainSize", new double[]{0, 5}),
            Map.entry("recoverOriginalDetailValue", new double[]{0, 1}));
    private static final int TIMEOUT_MS = 30_000;

    @Override public String protocol() { return PROTOCOL; }
    @Override public boolean supportsProviderCode(String code) { return "topaz".equalsIgnoreCase(StrUtil.trim(code)); }
    @Override public Integer fallbackMaxReferenceImages(AiModelConfigVo model) { return 0; }
    @Override public Integer fallbackMaxReferenceVideos(AiModelConfigVo model) { return 1; }
    @Override public boolean requiresVerifiedMetadataForQuote() { return true; }

    @Override
    public void normalizeRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (request == null || source(request) == null || source(request).getDurationMs() == null) return;
        long seconds = (source(request).getDurationMs() + 999L) / 1000L;
        if (seconds > 0 && seconds <= 3600) request.setDurationSeconds((int) seconds);
        if (request.getOptions() != null && source(request).getWidth() != null
                && source(request).getHeight() != null && source(request).getFps() != null) {
            Map<String, Object> normalized = new LinkedHashMap<>(request.getOptions());
            normalized.remove("estimatedProviderCredits");
            normalized.put("estimatedProviderCredits", estimateCredits(request, normalized));
            request.setOptions(normalized);
        }
    }

    @Override
    public void validateRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        buildBody(model, request);
    }

    static Map<String, Object> buildBody(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (model == null || !"prob-4".equals(model.getRealModelCode())) {
            throw new ServiceException("Topaz 视频模型编码无效");
        }
        if (request == null || request.getReferenceVideoRecordIds() == null
                || request.getReferenceVideoRecordIds().size() != 1 || source(request) == null
                || !request.getReferenceVideoRecordIds().get(0).equals(source(request).getRecordId())) {
            throw new ServiceException("请提供一条有权访问的源视频记录");
        }
        ReferenceVideoInput input = source(request);
        if (StrUtil.isBlank(input.getVideoUrl()) || !input.getVideoUrl().startsWith("https://")) {
            throw new ServiceException("源视频须提供可访问的 HTTPS 地址");
        }
        if (input.getDurationMs() == null || input.getDurationMs() < 1000 || input.getDurationMs() > 3_600_000
                || input.getWidth() == null || input.getHeight() == null
                || input.getWidth() < 16 || input.getHeight() < 16) {
            throw new ServiceException("源视频时长或尺寸未完成可信探测");
        }
        if (request.getDurationSeconds() == null
                || request.getDurationSeconds() != (int) ((input.getDurationMs() + 999L) / 1000L)) {
            throw new ServiceException("处理时长必须与源视频一致");
        }
        if (StrUtil.isNotBlank(request.getPrompt()) || StrUtil.isNotBlank(request.getImageUrl())
                || request.getReferenceAudios() != null && !request.getReferenceAudios().isEmpty()) {
            throw new ServiceException("Topaz 高清仅支持源视频与高清参数");
        }
        String container = StrUtil.trimToEmpty(input.getFormat()).toLowerCase(Locale.ROOT)
                .replace("video/", "");
        if (!CONTAINERS.contains(container)) throw new ServiceException("源视频格式不受 Topaz 支持");
        Map<String, Object> options = request.getOptions();
        if (options == null || options.keySet().stream().anyMatch(key -> !OPTION_KEYS.contains(key))) {
            throw new ServiceException("Topaz 高清参数无效");
        }
        Object sources = options.get("referenceVideos");
        if (!(sources instanceof List<?> list) || list.size() != 1
                || !input.getVideoUrl().equals(list.get(0))) {
            throw new ServiceException("源视频记录与请求素材不一致");
        }
        String resolution = textOption(options, "targetResolution");
        int[] bounds = switch (resolution.toLowerCase(Locale.ROOT)) {
            case "1080p" -> new int[]{1920, 1080};
            case "2k" -> new int[]{2560, 1440};
            case "4k" -> new int[]{3840, 2160};
            default -> throw new ServiceException("Topaz 目标分辨率不支持");
        };
        int[] size = fitToBounds(input.getWidth(), input.getHeight(), bounds[0], bounds[1]);
        String interpolation = textOption(options, "interpolationMode").toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "HIGH_QUALITY").contains(interpolation)) {
            throw new ServiceException("补帧模式不支持");
        }
        int slowmo = intOption(options, "slowMotionFactor", 1);
        if (slowmo < 1 || slowmo > 16) throw new ServiceException("慢放倍数须为 1 至 16");
        int sourceFps = input.getFps() == null ? 0 : input.getFps().intValue();
        if (sourceFps < 1 || sourceFps > 240) throw new ServiceException("源视频帧率未完成可信探测");
        int outputFps = intOption(options, "targetFps", sourceFps);
        if (outputFps < 15 || outputFps > 240) throw new ServiceException("目标帧率须为 15 至 240");
        if ("NONE".equals(interpolation) && (slowmo != 1 || outputFps != sourceFps)) {
            throw new ServiceException("调节帧率或慢放需要启用补帧");
        }
        BigDecimal submittedCredits = decimalCredits(options.get("estimatedProviderCredits"));
        if (submittedCredits == null
                || submittedCredits.compareTo(estimateCredits(request, options)) != 0) {
            throw new ServiceException("Topaz 处理成本估算与可信源视频不一致");
        }
        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> proteus = new LinkedHashMap<>();
        proteus.put("model", model.getRealModelCode());
        proteus.putAll(enhancementOptions(options.get("enhancement")));
        filters.add(proteus);
        if ("HIGH_QUALITY".equals(interpolation)) {
            filters.add(Map.of("model", "apo-8", "slowmo", slowmo, "fps", outputFps));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resolution", Map.of("width", size[0], "height", size[1]));
        output.put("audioTransfer", "Copy");
        output.put("audioCodec", "AAC");
        output.put("frameRate", outputFps);
        Map<String, Object> body = Map.of("source", Map.of("container", container,
                        "external", Map.of("provider", "s3", "presignedUrl", input.getVideoUrl())),
                "filters", filters, "output", output);
        if (com.alibaba.fastjson2.JSON.toJSONBytes(body).length > 3_000) {
            throw new ServiceException("视频访问地址过长，超出 Topaz 请求限制");
        }
        return body;
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        Map<String, Object> body = buildBody(model, request);
        String url = ProviderEndpointUtils.buildSubmitUrl(model.getBaseUrl(), model.getApiSuffix());
        try (HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", requireKey(model)).header("Content-Type", "application/json")
                .body(com.alibaba.fastjson2.JSON.toJSONString(body)).timeout(TIMEOUT_MS).execute()) {
            if (response.getStatus() >= 500) throw new ProviderSubmissionOutcomeUnknownException();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new ServiceException(ProviderErrorSanitizer.fromHttp(response.getStatus(), response.body()));
            }
            JsonNode root = ProviderResponseHelper.readTree(response.body());
            String taskId = ProviderResponseHelper.readText(root, "requestId");
            if (StrUtil.isBlank(taskId)) throw new ProviderSubmissionOutcomeUnknownException();
            return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(response.body()).build();
        } catch (ServiceException | ProviderSubmissionOutcomeUnknownException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Topaz 视频提交状态待核, modelCode={}, error={}", model.getModelCode(), ex.getClass().getSimpleName());
            throw new ProviderSubmissionOutcomeUnknownException();
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo model, String taskId) {
        if (StrUtil.isBlank(taskId) || !taskId.matches("[0-9a-fA-F-]{36}")) {
            return unknown("上游任务编号无效", null);
        }
        try {
            String url = ProviderEndpointUtils.buildTaskQueryUrl(model.getBaseUrl(), model.getTaskQuerySuffix(), taskId);
            try (HttpResponse response = HttpRequest.get(url).header("X-API-Key", requireKey(model))
                    .timeout(TIMEOUT_MS).execute()) {
                ProviderTaskResult status = parseQuery(response.getStatus(), response.body());
                if (!"SUCCEEDED".equals(status.getStatus())) return status;
                // Status exposes a cost *estimate*, not the account debit. The request record
                // has committed credit transactions; settlement must use that authority.
                String detailUrl = ProviderEndpointUtils.buildTaskQueryUrl(model.getBaseUrl(), "/video/%s", taskId);
                try (HttpResponse detail = HttpRequest.get(detailUrl).header("X-API-Key", requireKey(model))
                        .timeout(TIMEOUT_MS).execute()) {
                    BigDecimal committed = committedCredits(detail.getStatus(), detail.body(), taskId);
                    if (committed == null) return unknown("供应商积分结算记录尚未就绪", "complete");
                    return ProviderTaskResult.builder().status("SUCCEEDED")
                            .resultUrl(status.getResultUrl()).providerCredits(committed)
                            .providerStatus("complete").progress(100)
                            .rawResponse(detail.body()).querySuccessful(true).terminalConfirmed(true).build();
                }
            }
        } catch (Exception ex) {
            log.warn("Topaz 视频查询暂不可用, taskId={}, error={}", taskId, ex.getClass().getSimpleName());
            return unknown("上游查询暂不可用", null);
        }
    }

    static ProviderTaskResult parseQuery(int statusCode, String raw) {
        if (statusCode != 200) return unknown("上游查询暂不可用", null);
        JsonNode root = ProviderResponseHelper.readTree(raw);
        if (root == null || !root.isObject()) return unknown("上游状态响应异常", null);
        String status = root.path("status").asText();
        return switch (status) {
            case "requested", "accepted", "initializing", "preprocessing", "processing",
                    "postprocessing", "canceling" -> ProviderTaskResult.builder().status("PROCESSING")
                    .providerStatus(status).progress(root.path("progress").isNumber()
                            ? Math.max(0, Math.min(99, root.path("progress").asInt())) : null)
                    .rawResponse(raw).querySuccessful(true).terminalConfirmed(false).build();
            case "complete" -> {
                String url = ProviderResponseHelper.readText(root, "download.url");
                if (StrUtil.isBlank(url) || !url.startsWith("https://")) {
                    yield unknown("上游产物尚未就绪", status);
                }
                yield ProviderTaskResult.builder().status("SUCCEEDED").resultUrl(url)
                        .providerStatus(status).progress(100).rawResponse(raw)
                        .querySuccessful(true).terminalConfirmed(true).build();
            }
            case "failed", "canceled" -> ProviderTaskResult.builder().status("FAILED")
                    .providerStatus(status).errorMessage("上游视频处理失败")
                    .rawErrorMessage(ProviderResponseHelper.readText(root, "message", "errorCode"))
                    .rawResponse(raw).querySuccessful(true).terminalConfirmed(true).build();
            default -> unknown("上游任务状态未知", status);
        };
    }

    private static int[] fitToBounds(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        double scale = Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight);
        int width = (int) Math.round(sourceWidth * scale / 4) * 4;
        int height = (int) Math.round(sourceHeight * scale / 4) * 4;
        if (width < 16 || height < 16) throw new ServiceException("目标视频尺寸过小");
        return new int[]{Math.min(maxWidth, width), Math.min(maxHeight, height)};
    }

    private static Map<String, Object> enhancementOptions(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> source)) {
            throw new ServiceException("Topaz 高清调节参数格式无效");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ServiceException("Topaz 高清调节参数名称无效");
            }
            Set<String> choices = ENHANCEMENT_ENUMS.get(key);
            if (choices != null) {
                if (!(entry.getValue() instanceof String choice) || !choices.contains(choice)) {
                    throw new ServiceException("Topaz 高清调节参数无效: " + key);
                }
                values.put(key, choice);
                continue;
            }
            double[] range = ENHANCEMENT_RANGES.get(key);
            if (range == null || !(entry.getValue() instanceof Number number)) {
                throw new ServiceException("Topaz 不支持的高清调节参数: " + key);
            }
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value < range[0] || value > range[1]) {
                throw new ServiceException("Topaz 高清调节参数超出范围: " + key);
            }
            values.put(key, value);
        }
        return values;
    }

    private static BigDecimal committedCredits(int status, String raw, String taskId) {
        if (status != 200) return null;
        JsonNode root = ProviderResponseHelper.readTree(raw);
        if (root == null || !taskId.equals(root.path("id").asText())
                || !"complete".equals(root.path("status").asText())) return null;
        JsonNode transactions = root.path("transactions");
        if (!transactions.isArray()) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean sawCommit = false;
        for (JsonNode transaction : transactions) {
            if (!"commit".equals(transaction.path("operation").asText())) continue;
            JsonNode amount = transaction.path("amount");
            if (!amount.isNumber() || amount.decimalValue().signum() < 0) return null;
            sawCommit = true;
            total = total.add(amount.decimalValue());
        }
        return sawCommit ? total : null;
    }

    /** Conservative official-table estimate, recalculated from verified media metadata on every request. */
    private static BigDecimal estimateCredits(MediaVideoGenerateRequest request, Map<String, Object> options) {
        ReferenceVideoInput input = source(request);
        String resolution = textOption(options, "targetResolution").toLowerCase(Locale.ROOT);
        int[] bounds = switch (resolution) {
            case "1080p" -> new int[]{1920, 1080};
            case "2k" -> new int[]{2560, 1440};
            case "4k" -> new int[]{3840, 2160};
            default -> throw new ServiceException("Topaz 目标分辨率不支持");
        };
        int[] size = fitToBounds(input.getWidth(), input.getHeight(), bounds[0], bounds[1]);
        double outputPixels = (double) size[0] * size[1];
        double duration = input.getDurationMs() / 1000.0;
        double sourceFps = input.getFps().doubleValue();
        double proteus = 0.5 + 0.125 * duration * outputPixels / (1920.0 * 1080.0) * sourceFps / 30.0;
        String interpolation = textOption(options, "interpolationMode").toUpperCase(Locale.ROOT);
        if ("HIGH_QUALITY".equals(interpolation)) {
            int slowmo = intOption(options, "slowMotionFactor", 1);
            int targetFps = intOption(options, "targetFps", (int) sourceFps);
            double newFrames = Math.max(0, duration * (targetFps * slowmo - sourceFps));
            proteus += newFrames * outputPixels / 1_000_000_000.0 * 2.0;
        }
        return BigDecimal.valueOf(proteus).setScale(0, RoundingMode.CEILING);
    }

    private static String textOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (!(value instanceof String text) || StrUtil.isBlank(text)) {
            throw new ServiceException("缺少 Topaz 参数: " + key);
        }
        return text.trim();
    }

    private static int intOption(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == number.intValue()) return number.intValue();
        throw new ServiceException("Topaz 数值参数无效: " + key);
    }

    private static BigDecimal decimalCredits(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try {
            BigDecimal credits = new BigDecimal(value.toString());
            return credits.signum() > 0 && credits.scale() <= 6 ? credits : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static ReferenceVideoInput source(MediaVideoGenerateRequest request) {
        return request.getResolvedReferenceVideos() != null && request.getResolvedReferenceVideos().size() == 1
                ? request.getResolvedReferenceVideos().get(0) : null;
    }

    private static String requireKey(AiModelConfigVo model) {
        if (model == null || StrUtil.isBlank(model.getApiKey())) throw new ServiceException("Topaz API 密钥未配置");
        return model.getApiKey().trim();
    }

    private static ProviderTaskResult unknown(String message, String status) {
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus(status)
                .errorMessage(message).querySuccessful(false).terminalConfirmed(false).build();
    }
}
