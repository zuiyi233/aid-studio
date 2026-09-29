package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.media.util.ModelCapabilityValidator;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** TokenDance Seedance Generations request contract. */
final class TokenDanceSeedanceVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    private static final Set<String> TASK_TYPES = Set.of("reference", "edit", "extend", "auto");
    private static final Set<String> STRATEGY_OPTION_KEYS = Set.of(
            "omni_reference_task_type", "output_format", "callback_url",
            "service_tier", "execution_expires_after", "priority", "safety_identifier",
            "frames", "draft", "draft_task_id", "tools");
    private static final Set<String> TEXT_OPTION_KEYS = Set.of(
            "omni_reference_task_type", "output_format", "callback_url",
            "service_tier", "safety_identifier", "resolution", "size");
    private static final Pattern FRACTIONAL_TIMESTAMP = Pattern.compile(
            "(?i)(?:\\u7b2c\\s*)?\\d+\\.\\d+\\s*(?:s|\\u79d2)");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.SEEDANCE_GENERATIONS;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.SEEDANCE_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.SEEDANCE_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.SEEDANCE;
    }

    @Override
    public void sanitizePrompt(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        int imageCount = inputs.referenceImages.size()
                + (inputs.firstFrame == null ? 0 : 1)
                + (inputs.lastFrame == null ? 0 : 1);
        ReferencePromptSanitizer.sanitizeInPlaceForSeedance(request, imageCount,
                inputs.referenceVideos.size(), inputs.referenceAudios.size());
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request, STRATEGY_OPTION_KEYS);
        validateRawOptionTypes(request == null ? null : request.getOptions());
        validateRequestOptionContract(request);

        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        sanitizePrompt(request, inputs);
        inputs = TokenDanceVideoInputs.from(modelConfig, request);
        ModelCapabilityValidator.validatePrompt(modelConfig, request.getPrompt());

        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        validateTimestampInstructions(capability, inputs.prompt);
        String scene = resolveScene(modelConfig, capability);
        String taskType = resolveTaskType(modelConfig, inputs.options, scene, inputs, capability);
        boolean referenceMode = StrUtil.isNotBlank(taskType) || isReferenceScene(scene)
                || TokenDanceVideoBodySupport.hasAny(
                        inputs.referenceImages, inputs.referenceVideos, inputs.referenceAudios);
        validateModelContract(modelConfig, request, inputs, capability, scene, taskType, referenceMode);

        List<Map<String, Object>> content = new ArrayList<>();
        if (StrUtil.isNotBlank(inputs.prompt))
        {
            content.add(TokenDanceVideoBodySupport.content("text", inputs.prompt, null, null, null));
        }
        if (referenceMode)
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.firstFrame, "reference_image"));
            }
            addReferenceMedia(content, inputs);
        }
        else
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.firstFrame, "first_frame"));
            }
            if (StrUtil.isNotBlank(inputs.lastFrame))
            {
                TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.firstFrame), "missing first frame");
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.lastFrame, "last_frame"));
            }
        }
        TokenDanceVideoBodySupport.require(!content.isEmpty(), "missing generation content");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inputs.model);
        body.put("content", content);
        TokenDanceVideoBodySupport.putIfPresent(body, "resolution",
                TokenDanceVideoBodySupport.lowerResolution(inputs.resolution));
        TokenDanceVideoBodySupport.putIfPresent(body, "ratio",
                effectiveRatio(inputs, scene, taskType, referenceMode));
        TokenDanceVideoBodySupport.putIfPresent(body, "duration", inputs.duration);
        TokenDanceVideoBodySupport.putIfPresent(body, "generate_audio", inputs.generateAudio);
        TokenDanceVideoBodySupport.putIfPresent(body, "omni_reference_task_type", taskType);
        applyOptionalFields(body, inputs, capability);
        return body;
    }

    private void validateModelContract(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
            TokenDanceVideoInputs inputs, JsonNode capability, String scene, String taskType,
            boolean referenceMode)
    {
        validateOption(capability, "sizeOptions", inputs.resolution, "resolution unsupported");
        validateOption(capability, "aspectRatioOptions",
                effectiveRatio(inputs, scene, taskType, referenceMode), "ratio unsupported");
        validateOption(capability, "durationOptions",
                inputs.duration == null ? null : String.valueOf(inputs.duration), "duration unsupported");

        boolean hasReferenceMedia = TokenDanceVideoBodySupport.hasAny(
                inputs.referenceImages, inputs.referenceVideos, inputs.referenceAudios)
                || referenceMode && StrUtil.isNotBlank(inputs.firstFrame);
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.lastFrame) && referenceMode,
                "first/last frames cannot be mixed with reference media");
        if (referenceMode)
        {
            TokenDanceVideoBodySupport.require(hasReferenceMedia, "missing reference media");
        }

        String operation = operation(scene, taskType);
        if ("edit".equals(operation) || "extend".equals(operation))
        {
            TokenDanceVideoBodySupport.require(!inputs.referenceVideos.isEmpty(),
                    "missing reference video");
        }
        if ("edit".equals(operation))
        {
            TokenDanceVideoBodySupport.require(Integer.valueOf(-1).equals(inputs.duration),
                    "edit duration must be automatic");
            validateEditVideoDuration(request, inputs);
        }

        int imageCount = inputs.referenceImages.size()
                + (inputs.firstFrame == null ? 0 : 1)
                + (inputs.lastFrame == null ? 0 : 1);
        int videoCount = inputs.referenceVideos.size();
        int audioCount = inputs.referenceAudios.size();
        validateMaximum(capability, "maxReferenceImages", imageCount, "reference image count exceeded");
        validateMaximum(capability, "maxReferenceVideos", videoCount, "reference video count exceeded");
        validateMaximum(capability, "maxReferenceAudios", audioCount, "reference audio count exceeded");
        validateMaximum(capability, "maxReferenceMaterials", imageCount + videoCount + audioCount,
                "reference material count exceeded");
        if (capability != null && capability.path("referenceAudioRequiresVisualInput").asBoolean(false))
        {
            TokenDanceVideoBodySupport.require(audioCount == 0 || imageCount + videoCount > 0,
                    "reference audio requires image or video");
        }
        if (Boolean.TRUE.equals(inputs.generateAudio))
        {
            TokenDanceVideoBodySupport.require(capability != null
                    && capability.path("supportsAudio").asBoolean(false), "generated audio unsupported");
        }
    }

    private String resolveTaskType(AiModelConfigVo modelConfig, Map<String, Object> options, String scene,
            TokenDanceVideoInputs inputs, JsonNode capability)
    {
        String requested = strictTextOrNull(options, "omni_reference_task_type");
        if (StrUtil.isNotBlank(requested))
        {
            requested = requested.toLowerCase(Locale.ROOT);
            TokenDanceVideoBodySupport.require(TASK_TYPES.contains(requested), "task type unsupported");
            List<String> allowed = seedanceTaskTypeOptions(modelConfig, capability);
            String matched = ModelCapabilityResolver.matchOption(allowed, requested);
            TokenDanceVideoBodySupport.require(matched != null, "task type unsupported");
            validateTaskTypeScene(matched, scene);
            return matched;
        }

        String inferred = "edit".equals(scene) || "video_to_video".equals(scene) ? "edit"
                : "extend".equals(scene) ? "extend"
                : "reference".equals(scene) || "multi_to_video".equals(scene)
                        || TokenDanceVideoBodySupport.hasAny(inputs.referenceImages,
                                inputs.referenceVideos, inputs.referenceAudios)
                        ? "reference" : null;
        if (inferred == null)
        {
            return null;
        }
        return ModelCapabilityResolver.matchOption(
                seedanceTaskTypeOptions(modelConfig, capability), inferred);
    }

    private void validateTaskTypeScene(String taskType, String scene)
    {
        if ("edit".equals(scene) || "video_to_video".equals(scene))
        {
            TokenDanceVideoBodySupport.require("edit".equals(taskType) || "auto".equals(taskType),
                    "task type conflicts with edit scene");
        }
        else if ("extend".equals(scene))
        {
            TokenDanceVideoBodySupport.require("extend".equals(taskType) || "auto".equals(taskType),
                    "task type conflicts with extend scene");
        }
        else if (StrUtil.isNotBlank(scene) && !isReferenceScene(scene))
        {
            TokenDanceVideoBodySupport.require(false, "task type unsupported for scene");
        }
    }

    private String effectiveRatio(TokenDanceVideoInputs inputs, String scene, String taskType,
            boolean referenceMode)
    {
        String operation = operation(scene, taskType);
        boolean adaptive = (!referenceMode && StrUtil.isNotBlank(inputs.firstFrame))
                || StrUtil.isNotBlank(inputs.lastFrame)
                || "edit".equals(operation) || "extend".equals(operation);
        if (!adaptive)
        {
            return inputs.ratio;
        }
        TokenDanceVideoBodySupport.require(StrUtil.isBlank(inputs.ratio)
                || "adaptive".equalsIgnoreCase(inputs.ratio), "ratio must be adaptive");
        return "adaptive";
    }

    private void applyOptionalFields(Map<String, Object> body, TokenDanceVideoInputs inputs,
            JsonNode capability)
    {
        Map<String, Object> options = inputs.options;
        TokenDanceVideoBodySupport.reject(options.containsKey("watermark_info"),
                "watermark_info unsupported");
        if (options.containsKey("seed"))
        {
            requireCapability(capability, "supportsSeed", false, "seed unsupported");
            body.put("seed", integer(options.get("seed"), "seed"));
        }
        if (options.containsKey("watermark") || options.containsKey("aigc_watermark"))
        {
            Boolean watermark = options.containsKey("watermark")
                    ? bool(options.get("watermark"), "watermark") : null;
            Boolean alias = options.containsKey("aigc_watermark")
                    ? bool(options.get("aigc_watermark"), "aigc_watermark") : null;
            TokenDanceVideoBodySupport.require(watermark == null || alias == null
                    || watermark.equals(alias), "conflicting watermark options");
            body.put("watermark", watermark != null ? watermark : alias);
        }
        if (options.containsKey("camera_fixed"))
        {
            requireCapability(capability, "supportsCameraFixed", false, "camera_fixed unsupported");
            body.put("camera_fixed", bool(options.get("camera_fixed"), "camera_fixed"));
        }
        if (options.containsKey("return_last_frame"))
        {
            requireCapability(capability, "supportsReturnLastFrame", false,
                    "return_last_frame unsupported");
            body.put("return_last_frame", bool(options.get("return_last_frame"), "return_last_frame"));
        }
        if (options.containsKey("output_format"))
        {
            requireCapability(capability, "supportsOutputFormatParameter", true,
                    "output_format unsupported");
            String outputFormat = strictText(options, "output_format").toLowerCase(Locale.ROOT);
            validateOption(capability, "outputFormatOptions", outputFormat, "output format unsupported");
            body.put("output_format", outputFormat);
        }
        if (options.containsKey("callback_url"))
        {
            body.put("callback_url", strictText(options, "callback_url"));
        }
        if (options.containsKey("service_tier"))
        {
            String value = strictText(options, "service_tier");
            validateRequiredOption(capability, "serviceTierOptions", value, "service_tier unsupported");
            body.put("service_tier", value);
        }
        if (options.containsKey("execution_expires_after"))
        {
            long value = integer(options.get("execution_expires_after"), "execution_expires_after");
            TokenDanceVideoBodySupport.require(value > 0, "execution_expires_after must be positive");
            body.put("execution_expires_after", value);
        }
        if (options.containsKey("priority"))
        {
            requireCapability(capability, "supportsPriority", false, "priority unsupported");
            body.put("priority", integer(options.get("priority"), "priority"));
        }
        if (options.containsKey("safety_identifier"))
        {
            body.put("safety_identifier", strictText(options, "safety_identifier"));
        }
        if (options.containsKey("frames"))
        {
            requireCapability(capability, "supportsFrames", true, "frames unsupported");
            long value = integer(options.get("frames"), "frames");
            TokenDanceVideoBodySupport.require(value > 0, "frames must be positive");
            body.put("frames", value);
        }
        if (options.containsKey("draft"))
        {
            requireCapability(capability, "supportsDraft", true, "draft unsupported");
            body.put("draft", bool(options.get("draft"), "draft"));
        }
        if (options.containsKey("draft_task_id"))
        {
            requireCapability(capability, "supportsDraftUpgrade", true, "draft upgrade unsupported");
            TokenDanceVideoBodySupport.require(false,
                    "draft upgrade is not available for this Seedance model");
        }
        if (options.containsKey("tools"))
        {
            requireCapability(capability, "supportsWebSearch", true, "web_search unsupported");
            body.put("tools", tools(options.get("tools")));
        }
    }

    private List<Map<String, Object>> tools(Object raw)
    {
        TokenDanceVideoBodySupport.require(raw instanceof List<?>, "tools must be an array");
        List<?> values = (List<?>) raw;
        TokenDanceVideoBodySupport.require(!values.isEmpty(), "tools must not be empty");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values)
        {
            TokenDanceVideoBodySupport.require(value instanceof Map<?, ?>, "tool must be an object");
            Map<?, ?> tool = (Map<?, ?>) value;
            TokenDanceVideoBodySupport.require(tool.size() == 1
                    && tool.get("type") instanceof CharSequence, "tool fields unsupported");
            String type = tool.get("type").toString().trim().toLowerCase(Locale.ROOT);
            TokenDanceVideoBodySupport.require("web_search".equals(type), "tool type unsupported");
            result.add(Map.of("type", type));
        }
        return result;
    }

    private void validateEditVideoDuration(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        Map<String, ReferenceVideoInput> metadata = new LinkedHashMap<>();
        if (request.getResolvedReferenceVideos() != null)
        {
            for (ReferenceVideoInput input : request.getResolvedReferenceVideos())
            {
                if (input != null && StrUtil.isNotBlank(input.getVideoUrl()))
                {
                    metadata.put(input.getVideoUrl().trim(), input);
                }
            }
        }
        for (String url : inputs.referenceVideos)
        {
            ReferenceVideoInput input = metadata.get(url);
            TokenDanceVideoBodySupport.require(input != null && input.getDurationMs() != null
                    && input.getDurationMs() >= 4_000L && input.getDurationMs() <= 30_000L,
                    input == null || input.getDurationMs() == null
                            ? "video metadata unavailable" : "reference video duration exceeded");
        }
    }

    private void addReferenceMedia(List<Map<String, Object>> content, TokenDanceVideoInputs inputs)
    {
        for (String url : inputs.referenceImages)
        {
            content.add(TokenDanceVideoBodySupport.nestedMedia("image_url", url, "reference_image"));
        }
        for (String url : inputs.referenceVideos)
        {
            content.add(TokenDanceVideoBodySupport.nestedMedia("video_url", url, "reference_video"));
        }
        for (String url : inputs.referenceAudios)
        {
            content.add(TokenDanceVideoBodySupport.nestedMedia("audio_url", url, "reference_audio"));
        }
    }

    private void validateRawOptionTypes(Map<String, Object> options)
    {
        if (options == null)
        {
            return;
        }
        for (String key : TEXT_OPTION_KEYS)
        {
            Object value = options.get(key);
            TokenDanceVideoBodySupport.require(value == null || value instanceof CharSequence,
                    key + " must be text");
        }
    }

    private void validateRequestOptionContract(MediaVideoGenerateRequest request)
    {
        if (request == null || request.getOptions() == null)
        {
            return;
        }
        Map<String, Object> options = request.getOptions();
        TokenDanceVideoBodySupport.reject(options.containsKey("referenceAudios"),
                "referenceAudios must use verified request inputs");
        TokenDanceVideoBodySupport.reject(options.containsKey("watermark_info"),
                "watermark_info unsupported");
        requireMatchingAliases(options, "resolution", "size");
        requireMatchingAliases(options, "ratio", "aspect_ratio");
        String optionRatio = firstText(options, "ratio", "aspect_ratio");
        TokenDanceVideoBodySupport.require(StrUtil.isBlank(request.getAspectRatio())
                || StrUtil.isBlank(optionRatio)
                || request.getAspectRatio().trim().equalsIgnoreCase(optionRatio.trim()),
                "conflicting ratio options");
    }

    private void requireMatchingAliases(Map<String, Object> options, String... keys)
    {
        String selected = null;
        for (String key : keys)
        {
            Object raw = options.get(key);
            if (raw == null)
            {
                continue;
            }
            TokenDanceVideoBodySupport.require(raw instanceof CharSequence,
                    key + " must be text");
            String value = StrUtil.trimToNull(raw.toString());
            if (value == null)
            {
                continue;
            }
            TokenDanceVideoBodySupport.require(selected == null
                    || selected.equalsIgnoreCase(value), "conflicting option aliases");
            selected = value;
        }
    }

    private String firstText(Map<String, Object> options, String... keys)
    {
        for (String key : keys)
        {
            Object value = options.get(key);
            if (value instanceof CharSequence text && StrUtil.isNotBlank(text))
            {
                return text.toString();
            }
        }
        return null;
    }

    private void validateTimestampInstructions(JsonNode capability, String prompt)
    {
        if (capability != null && capability.path("timestampIntegerOnly").asBoolean(false)
                && StrUtil.isNotBlank(prompt) && FRACTIONAL_TIMESTAMP.matcher(prompt).find())
        {
            TokenDanceVideoBodySupport.require(false,
                    "timestamp instructions must use integer seconds");
        }
    }

    private void validateOption(JsonNode capability, String key, String value, String message)
    {
        if (StrUtil.isBlank(value))
        {
            return;
        }
        List<String> allowed = ModelCapabilityResolver.readOptions(capability, key);
        if (!allowed.isEmpty())
        {
            TokenDanceVideoBodySupport.require(
                    ModelCapabilityResolver.matchOption(allowed, value) != null, message);
        }
    }

    private void validateRequiredOption(JsonNode capability, String key, String value, String message)
    {
        List<String> allowed = ModelCapabilityResolver.readOptions(capability, key);
        TokenDanceVideoBodySupport.require(!allowed.isEmpty()
                && ModelCapabilityResolver.matchOption(allowed, value) != null, message);
    }

    private void validateMaximum(JsonNode capability, String key, int actual, String message)
    {
        JsonNode value = capability == null ? null : capability.get(key);
        if (value != null && value.isIntegralNumber() && value.intValue() >= 0)
        {
            TokenDanceVideoBodySupport.require(actual <= value.intValue(), message);
        }
    }

    private void requireCapability(JsonNode capability, String key, boolean requireExplicit,
            String message)
    {
        JsonNode value = capability == null ? null : capability.get(key);
        TokenDanceVideoBodySupport.require(value != null && value.isBoolean() && value.asBoolean()
                || !requireExplicit && (value == null || !value.isBoolean()), message);
    }

    private String strictText(Map<String, Object> options, String key)
    {
        Object value = options.get(key);
        TokenDanceVideoBodySupport.require(value instanceof CharSequence
                && StrUtil.isNotBlank(value.toString()), key + " must be text");
        return value.toString().trim();
    }

    private String strictTextOrNull(Map<String, Object> options, String key)
    {
        return options.containsKey(key) ? strictText(options, key) : null;
    }

    private Boolean bool(Object value, String key)
    {
        if (value instanceof Boolean bool)
        {
            return bool;
        }
        if (value instanceof CharSequence text
                && ("true".equalsIgnoreCase(text.toString().trim())
                        || "false".equalsIgnoreCase(text.toString().trim())))
        {
            return Boolean.valueOf(text.toString().trim());
        }
        TokenDanceVideoBodySupport.require(false, key + " must be boolean");
        return null;
    }

    private long integer(Object value, String key)
    {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)
        {
            return ((Number) value).longValue();
        }
        if (value instanceof CharSequence text)
        {
            try
            {
                return Long.parseLong(text.toString().trim());
            }
            catch (NumberFormatException ignored)
            {
                // Rejected below with the provider contract error.
            }
        }
        TokenDanceVideoBodySupport.require(false, key + " must be an integer");
        return 0L;
    }

    private String resolveScene(AiModelConfigVo modelConfig, JsonNode capability)
    {
        String value = ModelCapabilityResolver.readText(capability, "videoScenario");
        value = StrUtil.blankToDefault(value, modelConfig.getGenerateMode());
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private List<String> seedanceTaskTypeOptions(AiModelConfigVo modelConfig, JsonNode capability)
    {
        List<String> configured = ModelCapabilityResolver.readOptions(capability,
                "seedanceTaskTypeOptions");
        if (!configured.isEmpty())
        {
            return configured;
        }
        String upstream = StrUtil.blankToDefault(modelConfig.getRealModelCode(), modelConfig.getModelCode());
        return StrUtil.containsIgnoreCase(upstream, "seedance-2.5")
                ? List.of("reference", "edit", "extend", "auto") : List.of();
    }

    private boolean isReferenceScene(String scene)
    {
        return Set.of("reference", "edit", "extend", "multi_to_video", "video_to_video")
                .contains(scene);
    }

    private String operation(String scene, String taskType)
    {
        if ("edit".equals(scene) || "video_to_video".equals(scene))
        {
            return "edit";
        }
        if ("extend".equals(scene))
        {
            return "extend";
        }
        return "edit".equals(taskType) || "extend".equals(taskType) ? taskType : "reference";
    }
}
