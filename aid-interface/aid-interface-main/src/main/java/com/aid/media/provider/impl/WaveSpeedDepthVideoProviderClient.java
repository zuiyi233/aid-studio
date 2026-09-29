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
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** WaveSpeed Depth Anything Video: one trusted input video, one asynchronous depth video. */
@Slf4j
@Component
public class WaveSpeedDepthVideoProviderClient implements VideoProviderClient {
    public static final String PROTOCOL = "wavespeed:depth-anything-video";
    private static final String MODEL = "wavespeed-ai/depth-anything/video";
    private static final int TIMEOUT_MS = 30_000;

    @Override public String protocol() { return PROTOCOL; }

    @Override public boolean supportsProviderCode(String code) {
        return "wavespeed".equalsIgnoreCase(StrUtil.trim(code));
    }

    @Override public Integer fallbackMaxReferenceImages(AiModelConfigVo model) { return 0; }
    @Override public Integer fallbackMaxReferenceVideos(AiModelConfigVo model) { return 1; }
    @Override public boolean requiresVerifiedMetadataForQuote() { return true; }

    @Override
    public void normalizeRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (request == null) return;
        ReferenceVideoInput source = source(request);
        if (source == null || source.getDurationMs() == null) return;
        long seconds = (source.getDurationMs() + 999L) / 1000L;
        if (seconds >= 1 && seconds <= 600) request.setDurationSeconds((int) Math.max(3L, seconds));
    }

    @Override
    public void validateRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (model == null || !MODEL.equals(model.getRealModelCode())) {
            throw new ServiceException("WaveSpeed 视频模型配置不匹配");
        }
        if (request == null || request.getReferenceVideoRecordIds() == null
                || request.getReferenceVideoRecordIds().size() != 1) {
            throw new ServiceException("请提供一条有权访问的源视频记录");
        }
        if (StrUtil.isNotBlank(request.getPrompt()) || StrUtil.isNotBlank(request.getImageUrl())
                || request.getReferenceAudios() != null && !request.getReferenceAudios().isEmpty()) {
            throw new ServiceException("深度视频只支持一条源视频，不支持提示词、图片或音频");
        }
        ReferenceVideoInput input = source(request);
        if (input == null || !request.getReferenceVideoRecordIds().get(0).equals(input.getRecordId())
                || StrUtil.isBlank(input.getVideoUrl()) || !input.getVideoUrl().startsWith("https://")) {
            throw new ServiceException("源视频尚未完成鉴权或缺少可访问地址");
        }
        if (input.getDurationMs() == null || input.getDurationMs() < 1_000 || input.getDurationMs() > 600_000) {
            throw new ServiceException("源视频时长须为 1 至 600 秒");
        }
        int billableSeconds = (int) Math.max(3L, (input.getDurationMs() + 999L) / 1000L);
        if (request.getDurationSeconds() == null || request.getDurationSeconds() != billableSeconds) {
            throw new ServiceException("计费时长必须与源视频一致，且最低按 3 秒计费");
        }
        if (request.getOptions() != null) {
            Set<String> trustedKeys = Set.of("referenceVideos", "referenceVideoDurations", "referenceVideoSeconds");
            if (request.getOptions().keySet().stream().anyMatch(key -> !trustedKeys.contains(key))) {
                throw new ServiceException("深度视频模型不接受额外生成参数");
            }
            Object urls = request.getOptions().get("referenceVideos");
            if (!(urls instanceof java.util.List<?> list) || list.size() != 1
                    || !input.getVideoUrl().equals(list.get(0))) {
                throw new ServiceException("源视频记录与请求素材不一致");
            }
        }
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        validateRequest(model, request);
        String key = requireKey(model);
        String url = ProviderEndpointUtils.buildSubmitUrl(model.getBaseUrl(), model.getApiSuffix());
        try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .body(com.alibaba.fastjson2.JSON.toJSONString(Map.of("video", source(request).getVideoUrl())))
                .timeout(TIMEOUT_MS).execute()) {
            if (response.getStatus() >= 500) throw new ProviderSubmissionOutcomeUnknownException();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new ServiceException(ProviderErrorSanitizer.fromHttp(response.getStatus(), response.body()));
            }
            JsonNode root = ProviderResponseHelper.readTree(response.body());
            if (root == null || (root.has("code") && root.path("code").asInt(-1) != 200)) {
                throw new ServiceException(ProviderErrorSanitizer.safeMessage(response.body(), "WaveSpeed 提交失败"));
            }
            String taskId = ProviderResponseHelper.readText(root,
                    root.has("data") ? "data.id" : "id");
            if (StrUtil.isBlank(taskId)) throw new ProviderSubmissionOutcomeUnknownException();
            return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(response.body()).build();
        } catch (ServiceException | ProviderSubmissionOutcomeUnknownException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("WaveSpeed 提交结果待核, modelCode={}, error={}", model.getModelCode(), ex.getClass().getSimpleName());
            throw new ProviderSubmissionOutcomeUnknownException();
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo model, String taskId) {
        if (StrUtil.isBlank(taskId) || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            return unknown(null, "上游任务编号无效", null);
        }
        try {
            String url = ProviderEndpointUtils.buildTaskQueryUrl(model.getBaseUrl(), model.getTaskQuerySuffix(), taskId);
            try (HttpResponse response = HttpRequest.get(url)
                    .header("Authorization", "Bearer " + requireKey(model))
                    .timeout(TIMEOUT_MS).execute()) {
                return parseQuery(response.getStatus(), response.body(), taskId);
            }
        } catch (Exception ex) {
            log.warn("WaveSpeed 查询暂不可用, taskId={}, error={}", taskId, ex.getClass().getSimpleName());
            return unknown(null, "上游查询暂不可用", null);
        }
    }

    static ProviderTaskResult parseQuery(int httpStatus, String raw, String taskId) {
        if (httpStatus != 200) return unknown(null, "上游查询暂不可用", null);
        JsonNode root = ProviderResponseHelper.readTree(raw);
        if (root == null || (root.has("code") && root.path("code").asInt(-1) != 200)) {
            return unknown(null, "上游查询响应异常", null);
        }
        JsonNode data = root.has("data") ? root.path("data") : root;
        String returnedModel = data.path("model").asText();
        if (!taskId.equals(data.path("id").asText())
                || (StrUtil.isNotBlank(returnedModel) && !MODEL.equals(returnedModel))) {
            return unknown(null, "上游任务信息不匹配", null);
        }
        String status = data.path("status").asText();
        return switch (status) {
            case "created", "processing" -> ProviderTaskResult.builder()
                    .status("PROCESSING").providerStatus(status).rawResponse(raw)
                    .querySuccessful(true).terminalConfirmed(false).build();
            case "completed" -> {
                JsonNode outputs = data.path("outputs");
                String resultUrl = outputs.isArray() && !outputs.isEmpty()
                        ? ProviderResponseHelper.findFirstUrl(outputs.get(0)) : null;
                if (StrUtil.isBlank(resultUrl) || !resultUrl.startsWith("https://")) {
                    yield unknown(null, "上游产物尚未就绪", status);
                }
                yield ProviderTaskResult.builder().status("SUCCEEDED").resultUrl(resultUrl)
                        .providerStatus(status).rawResponse(raw).querySuccessful(true)
                        .terminalConfirmed(true).build();
            }
            case "failed", "cancelled", "timeout", "deleted" -> ProviderTaskResult.builder().status("FAILED")
                    .providerStatus(status).errorMessage("上游视频处理失败")
                    .rawErrorMessage(ProviderResponseHelper.readText(data, "error"))
                    .rawResponse(raw).querySuccessful(true).terminalConfirmed(true).build();
            default -> unknown(null, "上游任务状态未知", status);
        };
    }

    private static ReferenceVideoInput source(MediaVideoGenerateRequest request) {
        return request.getResolvedReferenceVideos() != null && request.getResolvedReferenceVideos().size() == 1
                ? request.getResolvedReferenceVideos().get(0) : null;
    }

    private static String requireKey(AiModelConfigVo model) {
        if (model == null || StrUtil.isBlank(model.getApiKey())) throw new ServiceException("WaveSpeed 密钥未配置");
        return model.getApiKey().trim();
    }

    private static ProviderTaskResult unknown(String raw, String message, String status) {
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus(status)
                .errorMessage(message).rawResponse(raw).querySuccessful(false).terminalConfirmed(false).build();
    }
}
