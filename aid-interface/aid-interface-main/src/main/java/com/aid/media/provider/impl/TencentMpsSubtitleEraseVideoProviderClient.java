package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.exception.ServiceException;
import com.aid.common.tencent.media.TencentMediaCosConfig;
import com.aid.common.tencent.media.TencentMediaCosConfigManager;
import com.aid.common.tencent.media.TencentMediaServiceSettings;
import com.aid.common.moderation.tencent.TencentCloudTc3Signer;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tencent MPS SmartEraseTask: automatic or selected-area subtitle removal. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TencentMpsSubtitleEraseVideoProviderClient implements VideoProviderClient {
    public static final String PROTOCOL = "tencent-mps:subtitle-erase";
    public static final String PROVIDER_CODE = "tencent_mps";
    private static final String HOST = "mps.tencentcloudapi.com";
    private static final String API = "https://" + HOST;
    private static final String VERSION = "2019-06-12";
    private static final Set<String> ALLOWED_OPTIONS = Set.of("eraseMode", "subtitleModel", "areas", "resolution",
            "referenceVideos", "referenceVideoDurations", "referenceVideoSeconds", "inputVideoSeconds");
    private final TencentMediaCosConfigManager mediaCosConfigManager;
    private final TencentMediaServiceSettings serviceSettings;
    private final TencentCiCosMediaGateway mediaGateway;
    private final MediaTaskFileRegistry taskFiles;
    private final VerifiedMediaMetadataService verifiedMediaMetadataService;

    @Override public String protocol() { return PROTOCOL; }
    @Override public boolean supportsProviderCode(String code) { return PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(code)); }
    @Override public Integer fallbackMaxReferenceImages(AiModelConfigVo model) { return 0; }
    @Override public Integer fallbackMaxReferenceVideos(AiModelConfigVo model) { return 1; }
    @Override public boolean requiresVerifiedMetadataForQuote() { return true; }

    @Override
    public void normalizeRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (request == null) return;
        Map<String, Object> options = request.getOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOptions());
        options.putIfAbsent("eraseMode", "AUTO");
        options.putIfAbsent("subtitleModel", "standard");
        ReferenceVideoInput source = source(request);
        if (source != null && source.getDurationMs() != null) {
            request.setDurationSeconds(Math.toIntExact((source.getDurationMs() + 999L) / 1000L));
        }
        if (source != null && source.getWidth() != null && source.getHeight() != null) {
            int shortSide = Math.min(source.getWidth(), source.getHeight());
            if (shortSide > 0 && shortSide <= 2160) {
                options.put("resolution", shortSide <= 720 ? "720p" : shortSide <= 1080 ? "1080p"
                        : shortSide <= 1440 ? "2K" : "4K");
            }
        }
        request.setOptions(options);
    }

    @Override
    public void validateRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        buildPayload(model, request);
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        JSONObject payload = buildPayload(model, request);
        String session = request.getProviderIdempotencyKey();
        if (StrUtil.isBlank(session) || session.length() > 50) {
            throw new ServiceException("腾讯云去字幕任务缺少合法的服务端幂等键");
        }
        payload.put("SessionId", session);
        String raw;
        try {
            raw = call("ProcessMedia", payload.toJSONString(), true);
        } catch (ServiceException | ProviderSubmissionOutcomeUnknownException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("腾讯云去字幕提交结果待核, errorType={}", ex.getClass().getSimpleName());
            throw new ProviderSubmissionOutcomeUnknownException();
        }
        JSONObject response = parseResponse(raw);
        JSONObject error = response == null ? null : response.getJSONObject("Error");
        if (error != null) {
            String code = error.getString("Code");
            if ("InvalidParameterValue.SessionId".equals(code)) {
                throw new ProviderSubmissionOutcomeUnknownException();
            }
            throw new ServiceException("腾讯云去字幕提交失败：" + safeError(code));
        }
        String taskId = response == null ? null : response.getString("TaskId");
        if (StrUtil.isBlank(taskId)) throw new ProviderSubmissionOutcomeUnknownException();
        return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(raw).build();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo model, String taskId) {
        if (StrUtil.isBlank(taskId) || !taskId.matches("[A-Za-z0-9_-]{1,160}")) {
            return unknown("上游任务编号无效", null);
        }
        JSONObject request = new JSONObject();
        request.put("TaskId", taskId);
        try {
            String raw = call("DescribeTaskDetail", request.toJSONString(), false);
            JSONObject response = parseResponse(raw);
            if (response == null || response.getJSONObject("Error") != null) return unknown("上游查询暂不可用", raw);
            JSONObject workflow = response.getJSONObject("WorkflowTask");
            if (workflow == null || !taskId.equals(workflow.getString("TaskId"))) return unknown("上游任务详情不匹配", raw);
            String workflowStatus = workflow.getString("Status");
            if ("PROCESSING".equals(workflowStatus)) return processing(raw, null);
            if (!"FINISH".equals(workflowStatus)) return unknown("上游任务状态未知", raw);
            JSONObject result = workflow.getJSONObject("SmartEraseTaskResult");
            if (result == null) return unknown("去字幕子任务结果未就绪", raw);
            String status = result.getString("Status");
            if ("PROCESSING".equals(status)) return processing(raw, result.getInteger("Progress"));
            if ("FAIL".equals(status) || workflow.getIntValue("ErrCode") != 0) {
                return ProviderTaskResult.builder().status("FAILED").providerStatus(status)
                        .errorMessage("腾讯云去字幕处理失败").rawErrorMessage(result.getString("Message"))
                        .rawResponse(raw).querySuccessful(true).terminalConfirmed(true).build();
            }
            if (!"SUCCESS".equals(status)) return unknown("去字幕子任务状态未知", raw);
            JSONObject output = result.getJSONObject("Output");
            String url = outputUrl(output);
            if (url == null) return unknown("去字幕结果尚未就绪或输出桶不匹配", raw);
            TencentCiCosMediaGateway.PersistedResult stored;
            try (TencentCiCosMediaGateway.Session session = mediaGateway.openForMps()) {
                String objectKey = URI.create(url).getPath().substring(1);
                if (!objectKey.endsWith(".mp4")) return processing(raw, null);
                String token = taskId.length() <= 64 ? taskId :
                        java.util.UUID.nameUUIDFromBytes(taskId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .toString().replace("-", "");
                taskFiles.register(taskFiles.taskIdForProviderTask(taskId), token,
                        "OUTPUT_0", session.region(), session.bucket(), objectKey,
                        session.outputRequiresTransfer());
                MediaTaskFileRegistry.StoredOutput existing = taskFiles.storedOutput(token, "OUTPUT_0");
                stored = existing == null ? session.persistOutput(objectKey, "mp4", "video/mp4")
                        : new TencentCiCosMediaGateway.PersistedResult(
                                existing.url(), existing.contentType(), existing.fileSize());
                if (existing == null) taskFiles.storedOutput(token, "OUTPUT_0",
                        stored.url(), stored.contentType(), stored.fileSize());
            } catch (RuntimeException ex) {
                log.warn("腾讯云去字幕结果永久存储待重试, taskId={}, errorType={}",
                        taskId, ex.getClass().getSimpleName());
                return processing(raw, null);
            }
            return ProviderTaskResult.builder().status("SUCCEEDED").providerStatus(status)
                    .resultUrl(url).progress(100).rawResponse(raw).querySuccessful(true)
                    .persistedResultUrls(List.of(stored.url()))
                    .persistedResultMimeTypes(List.of(stored.contentType()))
                    .persistedResultFileSizes(List.of(stored.fileSize()))
                    .terminalConfirmed(true).build();
        } catch (Exception ex) {
            log.warn("腾讯云去字幕查询暂不可用, errorType={}", ex.getClass().getSimpleName());
            return unknown("上游查询暂不可用", null);
        }
    }

    JSONObject buildPayload(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        if (!serviceSettings.enabled("subtitle")) throw new ServiceException("腾讯云去字幕未启用");
        if (model == null || !"SmartEraseSubtitle".equals(model.getRealModelCode())) {
            throw new ServiceException("腾讯云去字幕模型配置不匹配");
        }
        if (request == null || request.getReferenceVideoRecordIds() == null
                || request.getReferenceVideoRecordIds().size() != 1) {
            throw new ServiceException("请选择一条有权访问的源视频");
        }
        if (StrUtil.isNotBlank(request.getPrompt()) || StrUtil.isNotBlank(request.getImageUrl())
                || request.getReferenceAudios() != null && !request.getReferenceAudios().isEmpty()) {
            throw new ServiceException("去字幕仅支持一条源视频，不接受提示词、图片或音频");
        }
        ReferenceVideoInput source = source(request);
        if (source == null || !request.getReferenceVideoRecordIds().get(0).equals(source.getRecordId())
                || StrUtil.isBlank(source.getVideoUrl()) || source.getDurationMs() == null
                || source.getDurationMs() < 1_000L || source.getDurationMs() > 300_000L
                || source.getWidth() == null || source.getHeight() == null) {
            throw new ServiceException("源视频未鉴权或缺少可信时长与尺寸");
        }
        Map<String, Object> options = request.getOptions() == null ? Map.of() : request.getOptions();
        if (options.keySet().stream().anyMatch(key -> !ALLOWED_OPTIONS.contains(key))) {
            throw new ServiceException("去字幕包含不支持的参数");
        }
        Object inputSeconds = options.get("inputVideoSeconds");
        if (inputSeconds != null) {
            try {
                BigDecimal trustedSeconds = BigDecimal.valueOf(source.getDurationMs(), 3);
                if (new BigDecimal(String.valueOf(inputSeconds)).compareTo(trustedSeconds) != 0) {
                    throw new ServiceException("源视频计费时长与已校验素材不一致");
                }
            } catch (NumberFormatException ex) {
                throw new ServiceException("源视频计费时长格式错误");
            }
        }
        String mode = options.get("eraseMode") == null ? null : String.valueOf(options.get("eraseMode"));
        if (StrUtil.isBlank(mode)) mode = "AUTO";
        if (!Set.of("AUTO", "CUSTOM").contains(mode)) throw new ServiceException("eraseMode 仅支持 AUTO 或 CUSTOM");
        String subtitleModel = options.get("subtitleModel") == null ? null : String.valueOf(options.get("subtitleModel"));
        if (StrUtil.isBlank(subtitleModel)) subtitleModel = "standard";
        if (!Set.of("standard", "area").contains(subtitleModel)) {
            throw new ServiceException("subtitleModel 仅支持 standard 或 area");
        }
        if (request.getDurationSeconds() == null || request.getDurationSeconds() != (int) ((source.getDurationMs() + 999L) / 1000L)) {
            throw new ServiceException("处理时长必须与源视频一致");
        }
        int shortSide = Math.min(source.getWidth(), source.getHeight());
        if (shortSide < 1 || shortSide > 2160) throw new ServiceException("源视频分辨率不受当前去字幕模型支持");
        String expectedResolution = shortSide <= 720 ? "720p" : shortSide <= 1080 ? "1080p"
                : shortSide <= 1440 ? "2K" : "4K";
        if (!expectedResolution.equals(options.get("resolution"))) throw new ServiceException("源视频分辨率计费档位不一致");

        JSONObject config = new JSONObject();
        config.put("SubtitleEraseMethod", "AUTO".equals(mode) ? "auto" : "custom");
        config.put("SubtitleModel", subtitleModel);
        Object areas = options.get("areas");
        if ("CUSTOM".equals(mode)) config.put("CustomAreas", validateAreas(areas, source.getDurationMs()));
        else if (areas != null) throw new ServiceException("自动去字幕不接受框选区域");
        JSONObject raw = new JSONObject();
        raw.put("EraseType", "subtitle");
        raw.put("EraseSubtitleConfig", config);
        JSONObject eraseTask = new JSONObject();
        eraseTask.put("Definition", 0);
        eraseTask.put("RawParameter", raw);
        TencentMediaCosConfig credentials = mediaCosConfigManager.forMps();
        if (StrUtil.isBlank(credentials.secretId()) || StrUtil.isBlank(credentials.secretKey())
                || StrUtil.isBlank(credentials.bucketName()) || StrUtil.isBlank(credentials.region())) {
            throw new ServiceException("腾讯云媒体处理凭证与 COS 输出桶未配置");
        }
        JSONObject input = sourceInput(source.getVideoUrl(), credentials);
        JSONObject outputCos = new JSONObject();
        outputCos.put("Bucket", credentials.bucketName());
        outputCos.put("Region", credentials.region());
        JSONObject storage = new JSONObject();
        storage.put("Type", "COS");
        storage.put("CosOutputStorage", outputCos);
        JSONObject payload = new JSONObject();
        payload.put("InputInfo", input);
        payload.put("OutputStorage", storage);
        payload.put("OutputDir", "/aid-mps/subtitle/");
        payload.put("SmartEraseTask", eraseTask);
        return payload;
    }

    private JSONArray validateAreas(Object value, long durationMs) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 32
                || JSON.toJSONString(value).length() > 65_536) {
            throw new ServiceException("框选去字幕须提供 1 至 32 个时间区域");
        }
        JSONArray result = new JSONArray();
        for (Object item : list) {
            JSONObject area = JSON.parseObject(JSON.toJSONString(item));
            Object beginValue = area == null ? null : area.get("beginMs");
            Object endValue = area == null ? null : area.get("endMs");
            long begin = integralMilliseconds(beginValue);
            long end = integralMilliseconds(endValue);
            JSONArray boxes = area == null ? null : area.getJSONArray("boxes");
            if (begin < 0 || end <= begin || end > durationMs || boxes == null || boxes.isEmpty() || boxes.size() > 8) {
                throw new ServiceException("框选去字幕的时间段或区域数量无效");
            }
            JSONArray mappedBoxes = new JSONArray();
            for (Object boxValue : boxes) {
                JSONObject box = JSON.parseObject(JSON.toJSONString(boxValue));
                double x1 = ratio(box, "x1");
                double y1 = ratio(box, "y1");
                double x2 = ratio(box, "x2");
                double y2 = ratio(box, "y2");
                if (x2 <= x1 || y2 <= y1) throw new ServiceException("框选去字幕坐标无效");
                JSONObject mapped = new JSONObject();
                mapped.put("LeftTopX", x1);
                mapped.put("LeftTopY", y1);
                mapped.put("RightBottomX", x2);
                mapped.put("RightBottomY", y2);
                mapped.put("Unit", 1);
                mappedBoxes.add(mapped);
            }
            JSONObject mapped = new JSONObject();
            mapped.put("BeginMs", begin);
            mapped.put("EndMs", end);
            mapped.put("Areas", mappedBoxes);
            result.add(mapped);
        }
        return result;
    }

    private double ratio(JSONObject box, String key) {
        if (box == null || !box.containsKey(key)) throw new ServiceException("框选去字幕坐标缺失");
        Object raw = box.get(key);
        if (!(raw instanceof Number number)) throw new ServiceException("框选去字幕坐标必须是数字");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new ServiceException("框选去字幕坐标须在 0 至 1 之间");
        return value;
    }

    private long integralMilliseconds(Object raw) {
        if (!(raw instanceof Number number)) return -1;
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value != Math.rint(value) || value > Long.MAX_VALUE) return -1;
        return number.longValue();
    }

    private JSONObject sourceInput(String url, TencentMediaCosConfig config) {
        URI uri = TencentCiCosMediaGateway.validateHttpsSource(url);
        JSONObject input = new JSONObject();
        String objectPath = verifiedMediaMetadataService.trustedCosObjectPath(url, config.bucketName(), config.region());
        if (StrUtil.isNotBlank(objectPath) && !"/".equals(objectPath)) {
            JSONObject cos = new JSONObject();
            cos.put("Bucket", config.bucketName());
            cos.put("Region", config.region());
            cos.put("Object", objectPath);
            input.put("Type", "COS");
            input.put("CosInputInfo", cos);
        } else {
            JSONObject publicUrl = new JSONObject();
            publicUrl.put("Url", url);
            input.put("Type", "URL");
            input.put("UrlInputInfo", publicUrl);
        }
        return input;
    }

    private String outputUrl(JSONObject output) {
        if (output == null) return null;
        String path = output.getString("Path");
        if (StrUtil.isBlank(path) || path.contains("..") || !path.matches("/[A-Za-z0-9_./-]+")) return null;
        TencentMediaCosConfig config = mediaCosConfigManager.forMps();
        JSONObject storage = output.getJSONObject("OutputStorage");
        JSONObject cos = storage == null ? null : storage.getJSONObject("CosOutputStorage");
        if (cos != null && (!config.bucketName().equals(cos.getString("Bucket"))
                || !config.region().equals(cos.getString("Region")))) return null;
        try (TencentCiCosMediaGateway.Session session = mediaGateway.openForMps()) {
            return session.signedReadUrl(path.substring(1));
        }
    }

    private String call(String action, String payload, boolean submission) {
        TencentMediaCosConfig config = mediaCosConfigManager.forMps();
        Map<String, String> headers = TencentCloudTc3Signer.buildHeaders("mps", HOST, action, VERSION,
                config.region(), payload, config.secretId(), config.secretKey(), System.currentTimeMillis() / 1000L);
        try (HttpResponse response = HttpRequest.post(API).addHeaders(headers).body(payload)
                .timeout(30_000).execute()) {
            if (response.getStatus() >= 500 || response.getStatus() == 408 || response.getStatus() == 429) {
                if (submission) throw new ProviderSubmissionOutcomeUnknownException();
                throw new ServiceException("腾讯云查询暂不可用");
            }
            if (response.getStatus() != 200) throw new ServiceException("腾讯云媒体处理请求失败：HTTP " + response.getStatus());
            return response.body();
        } catch (ServiceException | ProviderSubmissionOutcomeUnknownException ex) {
            throw ex;
        } catch (Exception ex) {
            if (submission) throw new ProviderSubmissionOutcomeUnknownException();
            throw new ServiceException("腾讯云查询暂不可用");
        }
    }

    private static JSONObject parseResponse(String raw) {
        try { JSONObject root = JSON.parseObject(raw); return root == null ? null : root.getJSONObject("Response"); }
        catch (Exception ex) { return null; }
    }
    private static String safeError(String code) {
        return StrUtil.isBlank(code) ? "供应商响应异常" : code.replaceAll("[^A-Za-z0-9._-]", "");
    }
    private static ReferenceVideoInput source(MediaVideoGenerateRequest request) {
        return request.getResolvedReferenceVideos() != null && request.getResolvedReferenceVideos().size() == 1
                ? request.getResolvedReferenceVideos().get(0) : null;
    }
    private static ProviderTaskResult processing(String raw, Integer progress) {
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus("PROCESSING")
                .progress(progress).rawResponse(raw).querySuccessful(true).terminalConfirmed(false).build();
    }
    private static ProviderTaskResult unknown(String message, String raw) {
        return ProviderTaskResult.builder().status("PROCESSING").errorMessage(message).rawResponse(raw)
                .querySuccessful(false).terminalConfirmed(false).build();
    }
}
