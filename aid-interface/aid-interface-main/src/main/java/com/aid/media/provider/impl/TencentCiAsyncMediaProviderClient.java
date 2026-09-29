package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.tencent.media.TencentMediaServiceSettings;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ciModel.common.MediaInputObject;
import com.qcloud.cos.model.ciModel.common.MediaOutputObject;
import com.qcloud.cos.model.ciModel.job.AudioConfig;
import com.qcloud.cos.model.ciModel.job.MediaJobObject;
import com.qcloud.cos.model.ciModel.job.MediaJobOperation;
import com.qcloud.cos.model.ciModel.job.MediaJobResponse;
import com.qcloud.cos.model.ciModel.job.MediaJobsRequest;
import com.qcloud.cos.model.ciModel.job.VoiceSeparate;
import com.qcloud.cos.model.ciModel.job.v2.SegmentVideoBody;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tencent Cloud CI asynchronous portrait segmentation and voice separation. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TencentCiAsyncMediaProviderClient implements VideoProviderClient {
    public static final String PROTOCOL = "tencent-ci-async-media";
    public static final String PROVIDER_CODE = "tencent_ci_media";

    private static final String SEGMENT_MODEL = "SegmentVideoBody";
    private static final String VOICE_MODEL = "VoiceSeparate";
    private static final String TAG_SEGMENT = "SegmentVideoBody";
    private static final String TAG_VOICE = "VoiceSeparate";
    private static final String BILLING_DURATION_SECONDS = "billingDurationSeconds";

    private static final Set<String> VIDEO_INPUT_FORMATS = Set.of(
            "3gp", "asf", "avi", "dv", "flv", "f4v", "m3u8", "m4v", "mkv", "mov",
            "mp4", "mpg", "mpeg", "mts", "ogg", "rm", "rmvb", "swf", "ts", "vob",
            "webm", "wmv");
    private static final Set<String> VOICE_INPUT_FORMATS;
    private static final Set<String> BACKGROUND_FORMATS = Set.of("jpg", "jpeg", "png", "webp", "bmp");
    private static final Set<String> AUDIO_CODECS = Set.of("aac", "mp3", "flac", "amr");
    private static final Set<Integer> SAMPLE_RATES = Set.of(8000, 11025, 22050, 32000, 44100, 48000, 96000);
    private static final Set<String> COMMON_OPTION_KEYS = Set.of(
            "referenceVideos", "referenceVideoDurations", "referenceVideoSeconds",
            "inputVideoSeconds", "videoDurations", "inputVideoDurations", "videoSeconds",
            BILLING_DURATION_SECONDS, "jobLevel", "userData");
    private static final Set<String> SEGMENT_OPTION_KEYS = union(COMMON_OPTION_KEYS, Set.of(
            "segmentType", "backgroundRed", "backgroundGreen", "backgroundBlue",
            "binaryThreshold", "removeRed", "removeGreen", "removeBlue"));
    private static final Set<String> VOICE_OPTION_KEYS = union(COMMON_OPTION_KEYS, Set.of(
            "audioCodec", "audioSampleRate", "audioBitrateKbps", "audioChannels"));

    static {
        LinkedHashSet<String> formats = new LinkedHashSet<>(VIDEO_INPUT_FORMATS);
        formats.addAll(Set.of("mp3", "aac", "flac", "amr", "m4a", "wav", "wma"));
        VOICE_INPUT_FORMATS = Set.copyOf(formats);
    }

    private final TencentCiCosMediaGateway mediaGateway;
    private final MediaTaskFileRegistry taskFiles;
    private final TencentMediaServiceSettings serviceSettings;
    private final com.aid.common.tencent.media.TencentMediaCosConfigManager mediaCosConfigManager;

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return capability(modelConfig) == Capability.PORTRAIT_COMBINATION ? 1 : 0;
    }

    @Override
    public Integer fallbackMaxReferenceVideos(AiModelConfigVo modelConfig) {
        return 1;
    }

    @Override
    public boolean requiresVerifiedMetadataForQuote() {
        return true;
    }

    @Override
    public void normalizeRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (request == null) return;
        Capability capability = capability(modelConfig);
        Map<String, Object> options = request.getOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOptions());
        // This key feeds the shared billing extractor and must never be accepted from a caller.
        // validateRequest repopulates it from media metadata resolved by the server.
        options.remove(BILLING_DURATION_SECONDS);
        options.putIfAbsent("jobLevel", 0);
        if (capability.isSegment()) {
            options.putIfAbsent("segmentType", "HumanSeg");
            options.putIfAbsent("binaryThreshold", 0);
            if (capability == Capability.PORTRAIT_FOREGROUND) {
                options.putIfAbsent("backgroundRed", 0);
                options.putIfAbsent("backgroundGreen", 0);
                options.putIfAbsent("backgroundBlue", 0);
            }
            if ("SolidColorSeg".equals(canonicalSegmentType(options.get("segmentType")))) {
                options.putIfAbsent("removeRed", 0);
                options.putIfAbsent("removeGreen", 0);
                options.putIfAbsent("removeBlue", 0);
            }
        } else {
            options.putIfAbsent("audioCodec", "aac");
            options.putIfAbsent("audioSampleRate", 44100);
        }
        request.setOptions(options);
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        Plan plan = buildPlan(modelConfig, request);
        assertServiceEnabled(plan);
        Map<String, Object> options = request.getOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOptions());
        options.put(BILLING_DURATION_SECONDS, plan.inputDurationSeconds());
        request.setOptions(options);
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        Plan plan = buildPlan(modelConfig, request);
        assertServiceEnabled(plan);
        String requestKey = request.getProviderIdempotencyKey();
        String token = StrUtil.isBlank(requestKey) ? UUID.randomUUID().toString().replace("-", "")
                : UUID.nameUUIDFromBytes(requestKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString().replace("-", "");
        Long mediaTaskId = mediaTaskId(requestKey);
        String sourceExtension = TencentCiCosMediaGateway.extension(plan.sourceUrl(),
                plan.capability().isSegment() ? VIDEO_INPUT_FORMATS : VOICE_INPUT_FORMATS);
        String backgroundExtension = plan.backgroundUrl() == null ? "-"
                : TencentCiCosMediaGateway.extension(plan.backgroundUrl(), BACKGROUND_FORMATS);
        String sourceObject = stagingSource(token, sourceExtension);
        String backgroundObject = plan.backgroundUrl() == null ? null
                : stagingBackground(token, backgroundExtension);
        boolean preserveStaging = false;
        boolean submitStarted = false;
        try (TencentCiCosMediaGateway.Session session = mediaGateway.open()) {
            TencentCiCosMediaGateway.StagedObject stagedSource = null;
            TencentCiCosMediaGateway.StagedObject stagedBackground = null;
            try {
                stagedSource = session.stage(plan.sourceUrl(), sourceObject,
                        plan.capability().isSegment() ? VIDEO_INPUT_FORMATS : VOICE_INPUT_FORMATS);
                sourceObject = stagedSource.objectKey();
                taskFiles.register(mediaTaskId, token, "SOURCE", session.region(), session.bucket(), sourceObject,
                        stagedSource.temporary());
                if (backgroundObject != null) {
                    stagedBackground = session.stage(plan.backgroundUrl(), backgroundObject, BACKGROUND_FORMATS);
                    backgroundObject = stagedBackground.objectKey();
                    taskFiles.register(mediaTaskId, token, "BACKGROUND", session.region(), session.bucket(), backgroundObject,
                            stagedBackground.temporary());
                }
                submitStarted = true;
                Submission submission = plan.capability().isSegment()
                        ? submitSegment(session, plan, sourceObject, backgroundObject, token)
                        : submitVoice(session, plan, sourceObject, token);
                if (StrUtil.isBlank(submission.jobId())
                        || !submission.jobId().matches("[A-Za-z0-9_-]{1,128}")) {
                    preserveStaging = true;
                    throw new ProviderSubmissionOutcomeUnknownException();
                }
                preserveStaging = true;
                String providerTaskId = Envelope.of(plan, submission.jobId(), token,
                        sourceExtension, backgroundExtension).serialize();
                return ProviderSubmitResult.builder()
                        .providerTaskId(providerTaskId)
                        .rawResponse(submission.rawResponse())
                        .build();
            } catch (CosServiceException ex) {
                if (submitStarted && ex.getStatusCode() >= 500) {
                    preserveStaging = true;
                    log.warn("腾讯云数据万象提交结果待核对, status={}, errorCode={}",
                            ex.getStatusCode(), ex.getErrorCode());
                    throw new ProviderSubmissionOutcomeUnknownException();
                }
                log.error("腾讯云数据万象提交被拒绝, status={}, errorCode={}",
                        ex.getStatusCode(), ex.getErrorCode());
                throw new ServiceException("腾讯云数据万象提交失败");
            } catch (CosClientException ex) {
                if (submitStarted) {
                    preserveStaging = true;
                    log.warn("腾讯云数据万象提交结果待核对, errorType={}", ex.getClass().getSimpleName());
                    throw new ProviderSubmissionOutcomeUnknownException();
                }
                throw new ServiceException("腾讯云数据万象暂存失败");
            } catch (ProviderSubmissionOutcomeUnknownException | ServiceException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                if (submitStarted) {
                    preserveStaging = true;
                    log.warn("腾讯云数据万象提交响应无法确认, errorType={}", ex.getClass().getSimpleName());
                    throw new ProviderSubmissionOutcomeUnknownException();
                }
                throw ex;
            } finally {
                if (!preserveStaging) {
                    if (stagedSource != null && stagedSource.temporary() && session.deleteQuietly(sourceObject))
                        taskFiles.cleaned(token, sourceObject);
                    if (stagedBackground != null && stagedBackground.temporary() && session.deleteQuietly(backgroundObject))
                        taskFiles.cleaned(token, backgroundObject);
                    taskFiles.released(token);
                }
            }
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        Envelope envelope;
        try {
            envelope = Envelope.parse(providerTaskId);
        } catch (RuntimeException ex) {
            return unknown(null, null, "上游任务编号无效");
        }
        Capability configured;
        try {
            configured = capability(modelConfig);
        } catch (RuntimeException ex) {
            return unknown(null, null, "任务能力与当前模型配置不一致");
        }
        if (configured != envelope.capability()) {
            return unknown(null, null, "任务能力与当前模型配置不一致");
        }
        try (TencentCiCosMediaGateway.Session session = mediaGateway.open()) {
            QueryDetail detail = envelope.capability().isSegment()
                    ? querySegment(session, envelope.jobId())
                    : queryVoice(session, envelope.jobId());
            if (!envelope.jobId().equals(detail.jobId())
                    || !(envelope.capability().isSegment() ? TAG_SEGMENT : TAG_VOICE)
                    .equals(detail.tag())) {
                return unknown(detail.rawResponse(), detail.state(), "上游任务信息不匹配");
            }
            String state = StrUtil.blankToDefault(detail.state(), "");
            if (Set.of("Submitted", "Running", "Pause").contains(state)) {
                return ProviderTaskResult.builder().status("PROCESSING")
                        .providerStatus(state).progress(progress(detail.progress()))
                        .rawResponse(detail.rawResponse()).querySuccessful(true)
                        .terminalConfirmed(false).build();
            }
            if ("Success".equals(state)) {
                List<String> keys = outputObjects(envelope);
                for (String key : keys) {
                    if (!session.exists(key)) {
                        return unknown(detail.rawResponse(), state, "上游产物尚未就绪");
                    }
                }
                List<String> urls = keys.stream()
                        .map(session::signedReadUrl)
                        .toList();
                List<TencentCiCosMediaGateway.PersistedResult> persisted = new ArrayList<>();
                Long mediaTaskId = taskFiles.taskIdForProviderTask(providerTaskId);
                try {
                    for (int index = 0; index < keys.size(); index++) {
                        String key = keys.get(index);
                        String role = "OUTPUT_" + index;
                        taskFiles.register(mediaTaskId, envelope.token(), role,
                                session.region(), session.bucket(), key, session.outputRequiresTransfer());
                        MediaTaskFileRegistry.StoredOutput existing = taskFiles.storedOutput(envelope.token(), role);
                        TencentCiCosMediaGateway.PersistedResult value = existing == null
                                ? envelope.capability().isSegment()
                                    ? session.persistOutput(key, "mp4", "video/mp4")
                                    : session.persistOutput(key, envelope.codec(), audioContentType(envelope.codec()))
                                : new TencentCiCosMediaGateway.PersistedResult(
                                    existing.url(), existing.contentType(), existing.fileSize());
                        if (existing == null) taskFiles.storedOutput(envelope.token(), role,
                                value.url(), value.contentType(), value.fileSize());
                        persisted.add(value);
                    }
                } catch (RuntimeException ex) {
                    log.warn("腾讯云数据万象产物永久存储待重试, jobId={}, errorType={}",
                            envelope.jobId(), ex.getClass().getSimpleName());
                    return ProviderTaskResult.builder().status("PROCESSING").providerStatus(state)
                            .rawResponse(detail.rawResponse()).querySuccessful(true)
                            .terminalConfirmed(false).build();
                }
                cleanupStaging(session, envelope);
                ProviderTaskResult.ProviderTaskResultBuilder result = ProviderTaskResult.builder().status("SUCCEEDED")
                        .resultUrl(urls.get(0)).resultUrls(urls).resultCount(urls.size())
                        .providerStatus(state).progress(100).rawResponse(detail.rawResponse())
                        .querySuccessful(true).terminalConfirmed(true)
                        .inputVideoSeconds(envelope.inputDurationSeconds());
                if (!persisted.isEmpty()) {
                    result.persistedResultUrls(persisted.stream()
                                    .map(TencentCiCosMediaGateway.PersistedResult::url).toList())
                            .persistedResultMimeTypes(persisted.stream()
                                    .map(TencentCiCosMediaGateway.PersistedResult::contentType).toList())
                            .persistedResultFileSizes(persisted.stream()
                                    .map(TencentCiCosMediaGateway.PersistedResult::fileSize).toList());
                }
                return result.build();
            }
            if (Set.of("Failed", "Cancel").contains(state)) {
                cleanupStaging(session, envelope);
                return ProviderTaskResult.builder().status("FAILED")
                        .providerStatus(state).errorMessage("腾讯云数据万象处理失败")
                        .rawErrorMessage(safeFailure(detail.code(), detail.message()))
                        .rawResponse(detail.rawResponse()).querySuccessful(true)
                        .terminalConfirmed(true).build();
            }
            return unknown(detail.rawResponse(), state, "上游任务状态未知");
        } catch (Exception ex) {
            log.warn("腾讯云数据万象查询暂不可用, jobId={}, errorType={}",
                    envelope.jobId(), ex.getClass().getSimpleName());
            return unknown(null, null, "上游查询暂不可用");
        }
    }

    private Submission submitSegment(TencentCiCosMediaGateway.Session session, Plan plan,
                                     String sourceObject, String backgroundObject, String token) {
        MediaInputObject input = new MediaInputObject();
        input.setObject(sourceObject);
        SegmentVideoBody segment = new SegmentVideoBody();
        segment.setMode(plan.capability().upstreamMode());
        segment.setSegmentType(plan.segmentType());
        segment.setBinaryThreshold(String.valueOf(plan.binaryThreshold()));
        if (plan.capability() == Capability.PORTRAIT_FOREGROUND) {
            segment.setBackgroundRed(String.valueOf(plan.backgroundRed()));
            segment.setBackgroundGreen(String.valueOf(plan.backgroundGreen()));
            segment.setBackgroundBlue(String.valueOf(plan.backgroundBlue()));
        }
        if (plan.capability() == Capability.PORTRAIT_COMBINATION) {
            segment.setBackgroundLogoUrl(session.signedReadUrl(backgroundObject));
        }
        if ("SolidColorSeg".equals(plan.segmentType())) {
            segment.setRemoveRed(String.valueOf(plan.removeRed()));
            segment.setRemoveGreen(String.valueOf(plan.removeGreen()));
            segment.setRemoveBlue(String.valueOf(plan.removeBlue()));
        }
        MediaOutputObject output = output(session, segmentOutput(token), null);
        com.qcloud.cos.model.ciModel.job.v2.MediaJobOperation operation =
                new com.qcloud.cos.model.ciModel.job.v2.MediaJobOperation();
        operation.setSegmentVideoBody(segment);
        operation.setOutput(output);
        operation.setJobLevel(String.valueOf(plan.jobLevel()));
        operation.setUserData(plan.userData());
        com.qcloud.cos.model.ciModel.job.v2.MediaJobsRequestV2 request =
                new com.qcloud.cos.model.ciModel.job.v2.MediaJobsRequestV2();
        request.setBucketName(session.bucket());
        request.setTag(TAG_SEGMENT);
        request.setInput(input);
        request.setOperation(operation);
        com.qcloud.cos.model.ciModel.job.v2.MediaJobResponseV2 response =
                session.client().createMediaJobsV2(request);
        com.qcloud.cos.model.ciModel.job.v2.MediaJobObject detail =
                response == null ? null : response.getJobsDetail();
        return new Submission(detail == null ? null : detail.getJobId(), String.valueOf(response));
    }

    private Submission submitVoice(TencentCiCosMediaGateway.Session session, Plan plan,
                                   String sourceObject, String token) {
        MediaInputObject input = new MediaInputObject();
        input.setObject(sourceObject);
        AudioConfig audioConfig = new AudioConfig();
        audioConfig.setCodec(plan.audioCodec());
        audioConfig.setSamplerate(String.valueOf(plan.audioSampleRate()));
        if (plan.audioBitrateKbps() != null) {
            audioConfig.setBitrate(String.valueOf(plan.audioBitrateKbps()));
        }
        if (plan.audioChannels() != null) {
            audioConfig.setChannels(String.valueOf(plan.audioChannels()));
        }
        VoiceSeparate voice = new VoiceSeparate();
        voice.setAudioMode(plan.capability().upstreamMode());
        voice.setAudioConfig(audioConfig);
        String background = plan.capability().hasBackgroundOutput()
                ? voiceBackgroundOutput(token, plan.audioCodec()) : null;
        String vocal = plan.capability().hasVocalOutput()
                ? voiceVocalOutput(token, plan.audioCodec()) : null;
        MediaJobOperation operation = new MediaJobOperation();
        operation.setVoiceSeparate(voice);
        operation.setOutput(output(session, background, vocal));
        operation.setJobLevel(String.valueOf(plan.jobLevel()));
        operation.setUserData(plan.userData());
        MediaJobsRequest request = new MediaJobsRequest();
        request.setBucketName(session.bucket());
        request.setTag(TAG_VOICE);
        request.setInput(input);
        request.setOperation(operation);
        MediaJobResponse response = session.client().createMediaJobs(request);
        MediaJobObject detail = response == null ? null : response.getJobsDetail();
        return new Submission(detail == null ? null : detail.getJobId(), String.valueOf(response));
    }

    private QueryDetail querySegment(TencentCiCosMediaGateway.Session session, String jobId) {
        com.qcloud.cos.model.ciModel.job.v2.MediaJobsRequestV2 request =
                new com.qcloud.cos.model.ciModel.job.v2.MediaJobsRequestV2();
        request.setBucketName(session.bucket());
        request.setJobId(jobId);
        com.qcloud.cos.model.ciModel.job.v2.MediaJobResponseV2 response =
                session.client().describeMediaJobV2(request);
        com.qcloud.cos.model.ciModel.job.v2.MediaJobObject detail =
                response == null ? null : response.getJobsDetail();
        if (detail == null) throw new ServiceException("上游查询响应为空");
        return new QueryDetail(detail.getJobId(), detail.getTag(), detail.getState(), detail.getCode(),
                detail.getMessage(), detail.getProgress(), String.valueOf(response));
    }

    private QueryDetail queryVoice(TencentCiCosMediaGateway.Session session, String jobId) {
        MediaJobsRequest request = new MediaJobsRequest();
        request.setBucketName(session.bucket());
        request.setJobId(jobId);
        MediaJobResponse response = session.client().describeMediaJob(request);
        MediaJobObject detail = response == null ? null : response.getJobsDetail();
        if (detail == null) throw new ServiceException("上游查询响应为空");
        return new QueryDetail(detail.getJobId(), detail.getTag(), detail.getState(), detail.getCode(),
                detail.getMessage(), detail.getProgress(), String.valueOf(response));
    }

    private Plan buildPlan(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        Capability capability = capability(model);
        if (request == null) throw new ServiceException("腾讯云数据万象请求不能为空");
        if (StrUtil.isNotBlank(request.getPrompt()) || request.getDurationSeconds() != null
                || StrUtil.isNotBlank(request.getAspectRatio()) || request.getAudio() != null
                || request.getBgm() != null || StrUtil.isNotBlank(request.getAudioType())
                || StrUtil.isNotBlank(request.getVoiceId())) {
            throw new ServiceException("腾讯云数据万象处理不接受生成提示词、时长、比例或音画生成参数");
        }
        Map<String, Object> options = request.getOptions() == null ? Map.of() : request.getOptions();
        Set<String> allowed = capability.isSegment() ? SEGMENT_OPTION_KEYS : VOICE_OPTION_KEYS;
        if (options.keySet().stream().anyMatch(key -> !allowed.contains(key))) {
            throw new ServiceException("腾讯云数据万象包含未声明的处理参数");
        }
        List<String> videos = videoUrls(options.get("referenceVideos"));
        List<ReferenceAudioInput> audios = request.getReferenceAudios() == null
                ? List.of() : request.getReferenceAudios().stream().filter(java.util.Objects::nonNull).toList();
        String source;
        long inputDurationMs = 0L;
        if (capability.isSegment()) {
            if (request.getReferenceVideoRecordIds() == null
                    || request.getReferenceVideoRecordIds().size() != 1 || videos.size() != 1
                    || !audios.isEmpty()) {
                throw new ServiceException("视频人像分割需要一条有权访问的源视频记录");
            }
            source = videos.get(0);
            inputDurationMs = validateSegmentDuration(request);
        } else if (!videos.isEmpty()) {
            if (request.getReferenceVideoRecordIds() == null
                    || request.getReferenceVideoRecordIds().size() != 1 || videos.size() != 1
                    || !audios.isEmpty()) {
                throw new ServiceException("人声分离只允许一个视频或音频来源");
            }
            source = videos.get(0);
            inputDurationMs = validateVoiceDuration(request, null);
        } else {
            if (request.getReferenceVideoRecordIds() != null
                    && !request.getReferenceVideoRecordIds().isEmpty() || audios.size() != 1) {
                throw new ServiceException("人声分离只允许一个视频或音频来源");
            }
            ReferenceAudioInput audio = audios.get(0);
            source = audio.getSampleUrl();
            inputDurationMs = validateVoiceDuration(request, audio);
        }
        TencentCiCosMediaGateway.validateHttpsSource(source);
        TencentCiCosMediaGateway.extension(source,
                capability.isSegment() ? VIDEO_INPUT_FORMATS : VOICE_INPUT_FORMATS);

        String backgroundUrl = null;
        if (capability == Capability.PORTRAIT_COMBINATION) {
            backgroundUrl = request.getImageUrl();
            TencentCiCosMediaGateway.validateHttpsSource(backgroundUrl);
            TencentCiCosMediaGateway.extension(backgroundUrl, BACKGROUND_FORMATS);
        } else if (StrUtil.isNotBlank(request.getImageUrl())) {
            throw new ServiceException("仅人像合成模式接受背景图片");
        }

        int jobLevel = integer(options, "jobLevel", 0);
        if (jobLevel < 0 || jobLevel > 2) throw new ServiceException("任务优先级必须为 0、1 或 2");
        String userData = text(options, "userData");
        if (userData != null && (userData.length() > 1024
                || userData.chars().anyMatch(value -> value < 32 || value > 126))) {
            throw new ServiceException("透传信息只能使用不超过 1024 个可打印 ASCII 字符");
        }

        if (capability.isSegment()) {
            String segmentType = canonicalSegmentType(options.get("segmentType"));
            if (segmentType == null) throw new ServiceException("人像分割类型无效");
            int binary = byteOption(options, "binaryThreshold", 0);
            boolean foreground = capability == Capability.PORTRAIT_FOREGROUND;
            if (!foreground && containsAny(options, "backgroundRed", "backgroundGreen", "backgroundBlue")) {
                throw new ServiceException("背景颜色参数仅适用于前景输出模式");
            }
            boolean solid = "SolidColorSeg".equals(segmentType);
            if (!solid && containsAny(options, "removeRed", "removeGreen", "removeBlue")) {
                throw new ServiceException("去背景颜色参数仅适用于纯色背景分割");
            }
            return new Plan(capability, source, backgroundUrl, jobLevel, userData, segmentType,
                    binary, byteOption(options, "backgroundRed", 0),
                    byteOption(options, "backgroundGreen", 0), byteOption(options, "backgroundBlue", 0),
                    byteOption(options, "removeRed", 0), byteOption(options, "removeGreen", 0),
                    byteOption(options, "removeBlue", 0), null, null, null, null,
                    ceilSeconds(inputDurationMs));
        }

        String codec = StrUtil.blankToDefault(text(options, "audioCodec"), "aac").toLowerCase(Locale.ROOT);
        if (!AUDIO_CODECS.contains(codec)) throw new ServiceException("人声分离输出编码不支持");
        int sampleRate = integer(options, "audioSampleRate", 44100);
        if (!SAMPLE_RATES.contains(sampleRate)
                || Set.of("aac", "flac").contains(codec) && sampleRate == 8000
                || "mp3".equals(codec) && (sampleRate == 8000 || sampleRate == 96000)
                || "amr".equals(codec) && sampleRate != 8000) {
            throw new ServiceException("人声分离采样率与输出编码不兼容");
        }
        Integer bitrate = optionalInteger(options, "audioBitrateKbps");
        if (bitrate != null && (bitrate < 8 || bitrate > 1000)) {
            throw new ServiceException("人声分离码率必须为 8 至 1000 Kbps");
        }
        Integer channels = optionalInteger(options, "audioChannels");
        Set<Integer> supportedChannels = switch (codec) {
            case "aac", "flac" -> Set.of(1, 2, 4, 5, 6, 8);
            case "mp3" -> Set.of(1, 2);
            case "amr" -> Set.of(1);
            default -> Set.of();
        };
        if (channels != null && !supportedChannels.contains(channels)) {
            throw new ServiceException("人声分离声道数与输出编码不兼容");
        }
        int inputDurationSeconds = ceilSeconds(inputDurationMs);
        return new Plan(capability, source, null, jobLevel, userData, null,
                0, 0, 0, 0, 0, 0, 0, codec, sampleRate, bitrate, channels,
                inputDurationSeconds);
    }

    private long validateVoiceDuration(MediaVideoGenerateRequest request, ReferenceAudioInput audio) {
        Long durationMs = audio == null || audio.getDurationMs() == null
                ? resolvedVideoDuration(request) : audio.getDurationMs().longValue();
        if (durationMs == null || durationMs <= 0 || durationMs >= 2_700_000L) {
            throw new ServiceException("人声分离源文件时长必须已核验且小于 45 分钟");
        }
        return durationMs;
    }

    private long validateSegmentDuration(MediaVideoGenerateRequest request) {
        Long durationMs = resolvedVideoDuration(request);
        if (durationMs == null || durationMs <= 0) {
            throw new ServiceException("视频人像分割源视频时长必须由服务端核验");
        }
        return durationMs;
    }

    private Long resolvedVideoDuration(MediaVideoGenerateRequest request) {
        List<ReferenceVideoInput> resolved = request.getResolvedReferenceVideos();
        if (resolved != null && resolved.size() == 1 && resolved.get(0) != null
                && resolved.get(0).getDurationMs() != null) {
            return resolved.get(0).getDurationMs();
        }
        return null;
    }

    private int ceilSeconds(long durationMs) {
        return Math.toIntExact(Math.floorDiv(Math.addExact(durationMs, 999L), 1000L));
    }

    private Capability capability(AiModelConfigVo model) {
        if (model == null || !PROTOCOL.equalsIgnoreCase(model.getProtocol())) {
            throw new ServiceException("腾讯云数据万象协议配置不匹配");
        }
        Capability capability = Capability.from(model.getCapabilityCode());
        if (capability == null || capability.isSegment() && !SEGMENT_MODEL.equals(model.getRealModelCode())
                || !capability.isSegment() && !VOICE_MODEL.equals(model.getRealModelCode())) {
            throw new ServiceException("腾讯云数据万象模型能力配置不匹配");
        }
        JSONObject configured;
        try {
            configured = JSON.parseObject(model.getCapabilityJson());
        } catch (RuntimeException ex) {
            throw new ServiceException("腾讯云数据万象模型输出能力配置无效");
        }
        JSONArray outputs = configured == null ? null : configured.getJSONArray("outputModalities");
        String expected = capability.isSegment() ? "VIDEO" : "AUDIO";
        if (outputs == null || outputs.size() != 1
                || !expected.equalsIgnoreCase(outputs.getString(0))) {
            throw new ServiceException("腾讯云数据万象模型输出能力配置不匹配");
        }
        return capability;
    }

    private MediaOutputObject output(TencentCiCosMediaGateway.Session session,
                                     String object, String auObject) {
        MediaOutputObject output = new MediaOutputObject();
        output.setRegion(session.region());
        output.setBucket(session.bucket());
        if (StrUtil.isNotBlank(object)) output.setObject(object);
        if (StrUtil.isNotBlank(auObject)) output.setAuObject(auObject);
        return output;
    }

    private List<String> outputObjects(Envelope envelope) {
        Capability capability = envelope.capability();
        if (capability.isSegment()) return List.of(segmentOutput(envelope.token()));
        List<String> outputs = new ArrayList<>(2);
        // Deterministic public result order: vocal first, background second.
        if (capability.hasVocalOutput()) outputs.add(voiceVocalOutput(envelope.token(), envelope.codec()));
        if (capability.hasBackgroundOutput()) outputs.add(voiceBackgroundOutput(envelope.token(), envelope.codec()));
        return outputs;
    }

    private void cleanupStaging(TencentCiCosMediaGateway.Session session, Envelope envelope) {
        if (taskFiles.hasEntries(envelope.token())) {
            for (String key : taskFiles.temporaryKeys(envelope.token())) {
                if (session.deleteQuietly(key)) taskFiles.cleaned(envelope.token(), key);
            }
            taskFiles.released(envelope.token());
            return;
        }
        // Tasks submitted before the file registry was introduced used predictable staging keys.
        session.deleteQuietly(stagingSource(envelope.token(), envelope.sourceExtension()));
        if (!"-".equals(envelope.backgroundExtension()))
            session.deleteQuietly(stagingBackground(envelope.token(), envelope.backgroundExtension()));
    }

    private void assertServiceEnabled(Plan plan) {
        String service = plan.capability().isSegment() ? "portrait" : "voice";
        if (!serviceSettings.enabled(service)) throw new ServiceException("腾讯云" + (plan.capability().isSegment() ? "视频人像分割" : "人声分离") + "未启用");
    }

    private static Long mediaTaskId(String requestKey) {
        if (requestKey == null || !requestKey.matches("aid-media-[0-9]{1,19}")) return null;
        try { return Long.parseLong(requestKey.substring("aid-media-".length())); }
        catch (NumberFormatException ex) { return null; }
    }

    private String stagingSource(String token, String extension) {
        return mediaCosConfigManager.current().stagingPrefix() + token + "/source." + extension;
    }

    private String stagingBackground(String token, String extension) {
        return mediaCosConfigManager.current().stagingPrefix() + token + "/background." + extension;
    }

    private String segmentOutput(String token) {
        return mediaCosConfigManager.current().outputPrefix() + token + "/portrait.mp4";
    }

    private String voiceVocalOutput(String token, String codec) {
        return mediaCosConfigManager.current().outputPrefix() + token + "/vocal." + codec;
    }

    private String voiceBackgroundOutput(String token, String codec) {
        return mediaCosConfigManager.current().outputPrefix() + token + "/background." + codec;
    }

    private static String audioContentType(String codec) {
        return switch (codec) {
            case "aac" -> "audio/aac";
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "amr" -> "audio/amr";
            default -> throw new ServiceException("人声分离输出编码不支持");
        };
    }

    private static List<String> videoUrls(Object value) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                urls.add(String.valueOf(item).trim());
            }
        } else if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            urls.add(String.valueOf(value).trim());
        }
        return List.copyOf(urls);
    }

    private static int byteOption(Map<String, Object> options, String key, int fallback) {
        int value = integer(options, key, fallback);
        if (value < 0 || value > 255) throw new ServiceException(key + " 必须为 0 至 255");
        return value;
    }

    private static int integer(Map<String, Object> options, String key, int fallback) {
        Integer value = optionalInteger(options, key);
        return value == null ? fallback : value;
    }

    private static Integer optionalInteger(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value)).intValueExact();
        } catch (RuntimeException ex) {
            throw new ServiceException(key + " 必须为整数");
        }
    }

    private static String text(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value == null ? null : StrUtil.trimToNull(String.valueOf(value));
    }

    private static String canonicalSegmentType(Object value) {
        String normalized = StrUtil.blankToDefault(value == null ? null : String.valueOf(value), "HumanSeg")
                .replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "humanseg" -> "HumanSeg";
            case "greenscreenseg" -> "GreenScreenSeg";
            case "solidcolorseg" -> "SolidColorSeg";
            default -> null;
        };
    }

    private static boolean containsAny(Map<String, Object> options, String... keys) {
        for (String key : keys) if (options.containsKey(key)) return true;
        return false;
    }

    private static int progress(String value) {
        try {
            return Math.max(0, Math.min(99, Integer.parseInt(StrUtil.trim(value))));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String safeFailure(String code, String message) {
        String value = StrUtil.join(": ", StrUtil.trimToEmpty(code), StrUtil.trimToEmpty(message));
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static ProviderTaskResult unknown(String raw, String status, String message) {
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus(status)
                .errorMessage(message).rawResponse(raw).querySuccessful(false)
                .terminalConfirmed(false).build();
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private enum Capability {
        PORTRAIT_MASK("portrait_mask", "Mask", true, false, false),
        PORTRAIT_FOREGROUND("portrait_foreground", "Foreground", true, false, false),
        PORTRAIT_COMBINATION("portrait_combination", "Combination", true, false, false),
        VOICE_ONLY("voice_only", "IsAudio", false, true, false),
        BACKGROUND_ONLY("background_only", "IsBackground", false, false, true),
        VOICE_BACKGROUND("voice_background", "AudioAndBackground", false, true, true);

        private final String code;
        private final String upstreamMode;
        private final boolean segment;
        private final boolean vocalOutput;
        private final boolean backgroundOutput;

        Capability(String code, String upstreamMode, boolean segment,
                   boolean vocalOutput, boolean backgroundOutput) {
            this.code = code;
            this.upstreamMode = upstreamMode;
            this.segment = segment;
            this.vocalOutput = vocalOutput;
            this.backgroundOutput = backgroundOutput;
        }

        static Capability from(String code) {
            if (code == null) return null;
            for (Capability value : values()) if (value.code.equalsIgnoreCase(code)) return value;
            return null;
        }

        boolean isSegment() { return segment; }
        String upstreamMode() { return upstreamMode; }
        boolean hasVocalOutput() { return vocalOutput; }
        boolean hasBackgroundOutput() { return backgroundOutput; }
    }

    private record Plan(Capability capability, String sourceUrl, String backgroundUrl,
                        int jobLevel, String userData, String segmentType, int binaryThreshold,
                        int backgroundRed, int backgroundGreen, int backgroundBlue,
                        int removeRed, int removeGreen, int removeBlue, String audioCodec,
                        Integer audioSampleRate, Integer audioBitrateKbps, Integer audioChannels,
                        int inputDurationSeconds) { }

    private record Submission(String jobId, String rawResponse) { }

    private record QueryDetail(String jobId, String tag, String state, String code,
                               String message, String progress, String rawResponse) { }

    private record Envelope(Capability capability, String jobId, String token,
                            String sourceExtension, String backgroundExtension, String codec,
                            int inputDurationSeconds) {
        private static final String VERSION = "tc2";

        static Envelope of(Plan plan, String jobId, String token,
                           String sourceExtension, String backgroundExtension) {
            return new Envelope(plan.capability(), jobId, token, sourceExtension,
                    backgroundExtension, plan.audioCodec() == null ? "-" : plan.audioCodec(),
                    plan.inputDurationSeconds());
        }

        String serialize() {
            return String.join(":", VERSION, capability.name(), jobId, token,
                    sourceExtension, backgroundExtension, codec, String.valueOf(inputDurationSeconds));
        }

        static Envelope parse(String value) {
            String[] fields = StrUtil.blankToDefault(value, "").split(":", -1);
            if (fields.length != 8 || !VERSION.equals(fields[0])) throw new IllegalArgumentException();
            Capability capability = Capability.valueOf(fields[1]);
            if (!fields[2].matches("[A-Za-z0-9_-]{1,128}")
                    || !fields[3].matches("[a-f0-9]{32}")
                    || !fields[4].matches("[a-z0-9]{2,8}")
                    || !("-".equals(fields[5]) || fields[5].matches("[a-z0-9]{2,8}"))
                    || !("-".equals(fields[6]) || AUDIO_CODECS.contains(fields[6]))) {
                throw new IllegalArgumentException();
            }
            int inputDurationSeconds = Integer.parseInt(fields[7]);
            if (capability.isSegment() != "-".equals(fields[6])
                    || inputDurationSeconds <= 0
                    || !capability.isSegment() && inputDurationSeconds > 2700) {
                throw new IllegalArgumentException();
            }
            return new Envelope(capability, fields[2], fields[3], fields[4], fields[5], fields[6],
                    inputDurationSeconds);
        }
    }
}
