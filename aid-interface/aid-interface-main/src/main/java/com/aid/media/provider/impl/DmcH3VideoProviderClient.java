package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.DmcH3VideoRequestBuilder;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** DMC MiniMax-H3 异步视频提交与查询。 */
@Slf4j
@Component
public class DmcH3VideoProviderClient implements VideoProviderClient {
    private static final int TIMEOUT_MS = 120_000;
    private static final int SUBMIT_TIMEOUT_MS = 30_000;

    @Override public String protocol() { return DmcH3VideoRequestBuilder.PROTOCOL; }
    @Override public boolean supportsProviderCode(String code) { return "dmc".equalsIgnoreCase(StrUtil.trim(code)); }
    @Override public Integer fallbackMaxReferenceImages(AiModelConfigVo model) { return 9; }
    @Override public Integer fallbackMaxReferenceVideos(AiModelConfigVo model) { return 3; }

    @Override
    public void normalizeRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (request.getDurationSeconds() == null) request.setDurationSeconds(1);
        if (StrUtil.isBlank(request.getAspectRatio())) request.setAspectRatio("16:9");
        if (request.getOptions() == null) request.setOptions(new java.util.LinkedHashMap<>());
        request.getOptions().putIfAbsent("resolution", "768P");
    }

    @Override
    public void validateRequest(AiModelConfigVo model, MediaVideoGenerateRequest request, boolean planned) {
        DmcH3VideoRequestBuilder.build(model, request, planned);
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        String key = requireKey(model);
        if (StrUtil.isBlank(request.getProviderIdempotencyKey())) throw new ServiceException("任务幂等键缺失");
        if (request.getReferenceVideoRecordIds() != null && !request.getReferenceVideoRecordIds().isEmpty()
                && (request.getResolvedReferenceVideos() == null
                || request.getResolvedReferenceVideos().size() != request.getReferenceVideoRecordIds().size()))
            throw new ServiceException("参考视频未解析");
        String url = ProviderEndpointUtils.buildSubmitUrl(model.getBaseUrl(), model.getApiSuffix());
        java.util.Map<String, Object> payload = DmcH3VideoRequestBuilder.build(model, request, false);
        for (Object item : (java.util.List<?>) payload.get("content")) {
            java.util.Map<?, ?> media = (java.util.Map<?, ?>) item;
            String type = String.valueOf(media.get("type"));
            if ("text".equals(type)) continue;
            String mediaUrl = String.valueOf(((java.util.Map<?, ?>) media.get(type)).get("url"));
            if (!mediaUrl.startsWith("https://")) throw new ServiceException("素材需公网HTTPS");
        }
        String body = JSON.toJSONString(payload);
        for (int attempt = 1; attempt <= 3; attempt++) try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", request.getProviderIdempotencyKey())
                .body(body).timeout(SUBMIT_TIMEOUT_MS).execute()) {
            if (response.getStatus() >= 500) throw new SubmissionOutcomeUnknownException();
            if (response.getStatus() < 200 || response.getStatus() >= 300)
                return ProviderSubmitResult.builder()
                        .rawResponse(ProviderErrorSanitizer.fromHttp(response.getStatus(), response.body())).build();
            JsonNode result = ProviderResponseHelper.readTree(response.body());
            String taskId = result == null ? null : result.path("task_id").asText(null);
            if (StrUtil.isBlank(taskId)) throw new SubmissionOutcomeUnknownException();
            return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(response.body()).build();
        } catch (Exception ex) {
            log.warn("DMC 提交结果未知, modelCode={}, attempt={}, error={}", model.getModelCode(), attempt,
                    ex.getClass().getSimpleName());
            if (attempt == 3) throw new SubmissionOutcomeUnknownException();
        }
        throw new SubmissionOutcomeUnknownException();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo model, String taskId) {
        String key = requireKey(model);
        if (StrUtil.isBlank(taskId)) return unknown(null, "任务编号为空", null);
        try {
            String url = ProviderEndpointUtils.buildTaskQueryUrl(model.getBaseUrl(), model.getTaskQuerySuffix(), taskId);
            try (HttpResponse response = HttpRequest.get(url).header("Authorization", "Bearer " + key)
                    .timeout(TIMEOUT_MS).execute()) {
                return parseQuery(response.getStatus(), response.body(), model.getRealModelCode(), taskId);
            }
        } catch (Exception ex) {
            log.warn("DMC 查询暂不可用, taskId={}, error={}", taskId, ex.getClass().getSimpleName());
            return unknown(null, "上游查询暂不可用", null);
        }
    }

    static ProviderTaskResult parseQuery(int status, String raw, String expectedModel, String expectedTaskId) {
        if (status != 200) return unknown(null, "上游查询暂不可用", null);
        JsonNode root = ProviderResponseHelper.readTree(raw);
        JsonNode task = root == null ? null : root.path("task");
        if (task == null || !task.isObject()
                || !expectedTaskId.equals(task.path("id").asText())
                || task.hasNonNull("model") && !expectedModel.equals(task.path("model").asText())
                || task.hasNonNull("task_type") && !"generation".equals(task.path("task_type").asText())
                || task.hasNonNull("modality") && !"video".equals(task.path("modality").asText()))
            return unknown(null, "上游任务信息异常", null);
        String upstream = task.path("status").asText();
        return switch (upstream) {
            case "queued", "running" -> ProviderTaskResult.builder().status("PROCESSING")
                    .providerStatus(upstream).rawResponse(raw).querySuccessful(true).terminalConfirmed(false).build();
            case "succeeded" -> {
                String url = task.path("content").path("url").asText(null);
                JsonNode outputSeconds = task.path("usage").path("output_seconds");
                if (!outputSeconds.isNumber()) outputSeconds = task.path("duration");
                Integer seconds = outputSeconds.isNumber() ? outputSeconds.asInt() : null;
                if (StrUtil.isBlank(url) || seconds != null && (seconds < 1 || seconds > 15))
                    yield unknown(null, "上游产物尚未就绪", upstream);
                yield ProviderTaskResult.builder().status("SUCCEEDED").providerStatus(upstream)
                        .resultUrl(url).videoDurationSeconds(seconds).inputVideoSeconds(0).inputImageCount(0)
                        .rawResponse(raw).querySuccessful(true).terminalConfirmed(true).build();
            }
            case "failed", "cancelled" -> ProviderTaskResult.builder().status("FAILED")
                    .providerStatus(upstream).errorMessage("上游任务执行失败")
                    .rawResponse(raw).querySuccessful(true).terminalConfirmed(true).build();
            default -> unknown(null, "上游任务状态未知", upstream);
        };
    }

    private static ProviderTaskResult unknown(String raw, String message, String status) {
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus(status)
                .errorMessage(message).rawResponse(raw).querySuccessful(false).terminalConfirmed(false).build();
    }

    private static String requireKey(AiModelConfigVo model) {
        if (model == null || StrUtil.isBlank(model.getApiKey())) throw new ServiceException("DMC密钥未配置");
        return model.getApiKey().trim();
    }

    /** 已发出 POST 但没有可信响应；绝不可按明确失败退款。 */
    public static final class SubmissionOutcomeUnknownException extends com.aid.media.provider.ProviderSubmissionOutcomeUnknownException {
        public SubmissionOutcomeUnknownException() { super(); }
    }
}
