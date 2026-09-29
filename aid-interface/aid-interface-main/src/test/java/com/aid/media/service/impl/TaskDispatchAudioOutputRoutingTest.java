package com.aid.media.service.impl;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.enums.MediaType;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.service.TaskCompletionService;
import com.aid.model.definition.ModelTaskConfigurationResolver;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.aid.service.IAiModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class TaskDispatchAudioOutputRoutingTest {

    @Test
    void videoInputProtocolCanPollAnAudioOutputTask() {
        VideoProviderClient videoClient = mock(VideoProviderClient.class);
        when(videoClient.supportsProtocol("tencent-ci-async-media")).thenReturn(true);
        ProviderTaskResult completed = ProviderTaskResult.builder().status("SUCCEEDED").build();
        AiModelConfigVo model = new AiModelConfigVo();
        when(videoClient.query(model, "upstream-job")).thenReturn(completed);

        TaskDispatchServiceImpl service = service(List.of(videoClient), List.of(), model);
        assertSame(completed, ReflectionTestUtils.invokeMethod(service, "queryUpstreamCaptured", audioTask()));
        verify(videoClient).query(model, "upstream-job");
    }

    @Test
    void nativeAudioProtocolKeepsItsOwnClient() {
        AudioProviderClient audioClient = mock(AudioProviderClient.class);
        VideoProviderClient videoClient = mock(VideoProviderClient.class);
        when(audioClient.supportsProtocol("tencent-ci-async-media")).thenReturn(true);
        when(videoClient.supportsProtocol("tencent-ci-async-media")).thenReturn(true);
        ProviderTaskResult completed = ProviderTaskResult.builder().status("SUCCEEDED").build();
        AiModelConfigVo model = new AiModelConfigVo();
        when(audioClient.query(model, "upstream-job")).thenReturn(completed);

        TaskDispatchServiceImpl service = service(List.of(videoClient), List.of(audioClient), model);
        assertSame(completed, ReflectionTestUtils.invokeMethod(service, "queryUpstreamCaptured", audioTask()));
        verifyNoInteractions(videoClient);
    }

    private static AidMediaTask audioTask() {
        AidMediaTask task = new AidMediaTask();
        task.setId(123L);
        task.setMediaType(MediaType.AUDIO.name());
        task.setProtocol("tencent-ci-async-media");
        task.setProviderTaskId("upstream-job");
        return task;
    }

    private static TaskDispatchServiceImpl service(List<VideoProviderClient> videoClients,
                                                   List<AudioProviderClient> audioClients,
                                                   AiModelConfigVo model) {
        TaskDispatchServiceImpl service = new TaskDispatchServiceImpl(
                mock(AidMediaTaskMapper.class), mock(IAiModelConfigService.class),
                mock(TaskCompletionService.class), List.of(), videoClients, List.of(), audioClients,
                mock(MediaGenFanInSupport.class), mock(ComposeCompletionService.class));
        ModelTaskConfigurationResolver resolver = mock(ModelTaskConfigurationResolver.class);
        when(resolver.resolve(org.mockito.ArgumentMatchers.any(AidMediaTask.class))).thenReturn(model);
        ReflectionTestUtils.setField(service, "taskConfigurationResolver", resolver);
        return service;
    }
}
