package com.aid.media.service;

import org.springframework.stereotype.Service;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

import lombok.RequiredArgsConstructor;

/**
 * Provider 提交前的统一资源准备入口。任务快照保持原地址，重试时重新签名并按模型配置改写图片。
 */
@Service
@RequiredArgsConstructor
public class ModelOutboundResourcePreparer
{
    private final ModelResourceUrlSigner signer;
    private final ModelImageUrlProxyProcessor imageProxyProcessor;

    public void prepare(AiModelConfigVo config, MediaImageGenerateRequest request)
    {
        imageProxyProcessor.process(config, request);
        signer.sign(request);
    }

    public void prepare(AiModelConfigVo config, MediaVideoGenerateRequest request)
    {
        imageProxyProcessor.process(config, request);
        // Tencent CI stages registered media directly inside the configured COS bucket.
        // Replacing the registered CDN URL with a signed COS URL here would hide the
        // trusted bucket object path from that staging step and force an external download.
        if (config == null || !"tencent_ci_media".equalsIgnoreCase(config.getProviderCode()))
        {
            signer.sign(request);
        }
    }

    public void prepare(AiModelConfigVo config, MediaAudioGenerateRequest request)
    {
        signer.sign(request);
        imageProxyProcessor.validate(config);
    }

    public void prepare(AiModelConfigVo config, MediaTextGenerateRequest request)
    {
        imageProxyProcessor.process(config, request);
        signer.sign(request);
    }
}
