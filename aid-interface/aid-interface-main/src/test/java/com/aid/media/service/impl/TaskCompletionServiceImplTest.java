package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.billing.service.BillingFacadeService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.model.definition.ModelTaskConfigurationResolver;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.common.exception.ServiceException;
import com.aid.modelhealth.service.ModelHealthRecorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

class TaskCompletionServiceImplTest {

    @BeforeAll
    static void initMybatisMetadata() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "task-completion-test");
        assistant.setCurrentNamespace("com.aid.media.service.impl.TaskCompletionServiceImplTest");
        TableInfoHelper.initTableInfo(assistant, AidMediaTask.class);
        TableInfoHelper.initTableInfo(assistant, AidMediaResult.class);
    }

    @Test
    void casWinnerRecordsKlingRawFailureExactlyOnce() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(any(), any())).thenReturn(1);
        when(fixture.billingFacadeService.refundBilling(any())).thenReturn(true);

        assertTrue(fixture.service.completeTask(1L, failedResult()));

        verify(fixture.failureRecorder, times(1)).record(
            eq(1L), eq("kling-3.0-omni-i2v"), eq("raw provider failure"));
    }

    @Test
    void casLoserDoesNotRecordKlingRawFailure() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(any(), any())).thenReturn(0);

        assertFalse(fixture.service.completeTask(1L, failedResult()));

        verify(fixture.failureRecorder, never()).record(any(), any(), any());
    }

    @Test
    void successfulVideoPassesCompletionTokensToBillingSettlement() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(any(), any())).thenReturn(1);
        when(fixture.billingFacadeService.settleBilling(any(), any())).thenReturn(true);
        ProviderTaskResult result = ProviderTaskResult.builder()
                .status(MediaTaskStatus.SUCCEEDED.name())
                .resultUrl("https://cdn.test/video.mp4")
                .completionTokens(194400)
                .totalTokens(194400)
                .build();

        assertTrue(fixture.service.completeTask(1L, result));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> usage = ArgumentCaptor.forClass(Map.class);
        verify(fixture.billingFacadeService).settleBilling(any(), usage.capture());
        assertEquals(194400, usage.getValue().get("completion_tokens"));
        assertEquals(194400, usage.getValue().get("output_tokens"));
        assertEquals(194400, usage.getValue().get("total_tokens"));
    }

    @Test
    void tencentVoiceOnlyMayReturnOneResultWhenModelAllowsTwo() {
        Fixture fixture = new Fixture();
        AidMediaTask task = tencentVoiceTask();
        configureTencentVoiceModel(fixture.service);
        assertNotNull(ReflectionTestUtils.invokeMethod(fixture.service,
                "resolveTencentStoredAudioResults", task, tencentVoiceResult(1)));
        assertNotNull(ReflectionTestUtils.invokeMethod(fixture.service,
                "resolveTencentStoredAudioResults", task, tencentVoiceResult(2)));
    }

    @Test
    void tencentVoiceRejectsIncompleteOrExcessResults() {
        Fixture fixture = new Fixture();
        AidMediaTask task = tencentVoiceTask();
        configureTencentVoiceModel(fixture.service);
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(fixture.service,
                "resolveTencentStoredAudioResults", task, tencentVoiceResult(0)));
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(fixture.service,
                "resolveTencentStoredAudioResults", task, tencentVoiceResult(3)));
    }

    @Test
    void tencentVoiceOutputCompletesVideoInputTaskWithoutVideoMimeValidation() {
        Fixture fixture = new Fixture();
        AidMediaTask task = tencentVoiceTask();
        when(fixture.mapper.selectById(1L)).thenReturn(task);
        when(fixture.mapper.update(any(), any())).thenReturn(1);
        when(fixture.billingFacadeService.settleBilling(any(), any())).thenReturn(true);
        AidMediaResultMapper results = mock(AidMediaResultMapper.class);
        when(results.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(fixture.service, "aidMediaResultMapper", results);
        configureTencentVoiceModel(fixture.service);
        ProviderTaskResult output = tencentVoiceResult(1);
        output.setPersistedResultMimeTypes(List.of("audio/mp4"));
        output.setQuerySuccessful(true);
        output.setTerminalConfirmed(true);

        assertTrue(fixture.service.completeTask(1L, output));
        verify(results).upsertTaskResult(eq(1L), eq(0), eq(MediaType.AUDIO.name()), any(), any());
        verify(fixture.billingFacadeService).settleBilling(any(), any());
    }

    private static AidMediaTask tencentVoiceTask() {
        AidMediaTask task = activeTask();
        task.setProtocol("tencent-ci-async-media");
        task.setModelName("tencent-ci-voice-separation");
        return task;
    }

    private static void configureTencentVoiceModel(TaskCompletionServiceImpl service) {
        ModelTaskConfigurationResolver resolver = mock(ModelTaskConfigurationResolver.class);
        AiModelConfigVo model = new AiModelConfigVo();
        model.setProtocol("tencent-ci-async-media");
        model.setProviderCode("tencent_ci_media");
        model.setCapabilityJson("{\"outputModalities\":[\"AUDIO\"],\"maxOutputCount\":2}");
        when(resolver.resolve(any())).thenReturn(model);
        ReflectionTestUtils.setField(service, "taskConfigurationResolver", resolver);
    }

    private static ProviderTaskResult tencentVoiceResult(int count) {
        List<String> origins = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "https://provider.test/voice-" + index + ".aac").toList();
        List<String> stored = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "/media/voice-" + index + ".aac").toList();
        return ProviderTaskResult.builder().status("SUCCEEDED").resultUrls(origins)
                .resultCount(count).persistedResultUrls(stored)
                .persistedResultMimeTypes(java.util.Collections.nCopies(count, "audio/aac"))
                .persistedResultFileSizes(java.util.Collections.nCopies(count, 100L)).build();
    }

    private static ProviderTaskResult failedResult() {
        return ProviderTaskResult.builder()
            .status(MediaTaskStatus.FAILED.name())
            .errorMessage("上游任务执行失败")
            .rawErrorMessage("raw provider failure")
            .build();
    }

    private static AidMediaTask activeTask() {
        AidMediaTask task = new AidMediaTask();
        task.setId(1L);
        task.setUserId(2L);
        task.setStatus(MediaTaskStatus.WAIT_POLL.name());
        task.setModelName("kling-3.0-omni-i2v");
        task.setMediaType(MediaType.VIDEO.name());
        task.setRequestJson("{\"payloadCompacted\":true}");
        return task;
    }

    private static final class Fixture {
        private final AidMediaTaskMapper mapper = mock(AidMediaTaskMapper.class);
        private final BillingFacadeService billingFacadeService = mock(BillingFacadeService.class);
        private final MediaConcurrencyLimiter concurrencyLimiter = mock(MediaConcurrencyLimiter.class);
        private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        private final ComposeCompletionService composeCompletionService = mock(ComposeCompletionService.class);
        private final OssConfigManager ossConfigManager = mock(OssConfigManager.class);
        private final MediaTaskArchiveService archiveService = mock(MediaTaskArchiveService.class);
        private final ModelHealthRecorder modelHealthRecorder = mock(ModelHealthRecorder.class);
        private final KlingTerminalFailureRecorder failureRecorder = mock(KlingTerminalFailureRecorder.class);
        private final TaskCompletionServiceImpl service;

        private Fixture() {
            MediaTaskArchiveService.PreparedTerminalPayload payload =
                mock(MediaTaskArchiveService.PreparedTerminalPayload.class);
            when(payload.getRequestJson()).thenReturn("{\"payloadCompacted\":true}");
            when(payload.getResponseJson()).thenReturn("");
            when(archiveService.prepareTerminalPayload(any(), any(), any())).thenReturn(payload);
            when(mapper.selectById(1L)).thenReturn(activeTask());
            service = new TaskCompletionServiceImpl(mapper, billingFacadeService, concurrencyLimiter,
                eventPublisher, composeCompletionService, ossConfigManager, archiveService,
                modelHealthRecorder, failureRecorder);
        }
    }
}
