package com.aid.media.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.media.dto.MinimaxMusicGenerateRequest;
import com.aid.media.service.impl.MinimaxMusicGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C-side entry for the default-disabled legacy MiniMax Music catalog. */
@RestController
@RequestMapping("/api/user/music")
@RequiredArgsConstructor
public class MinimaxMusicController extends BaseController {

    private final MinimaxMusicGenerationService musicGenerationService;

    @PostMapping("/generate")
    public AjaxResult generate(@RequestBody MinimaxMusicGenerateRequest request) {
        return success(musicGenerationService.generate(request, SecurityUtils.getUserId()));
    }
}
