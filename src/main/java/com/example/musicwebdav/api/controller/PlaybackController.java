package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.PlaybackControlRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.NowPlayingStatusResponse;
import com.example.musicwebdav.application.service.PlaybackControlService;
import javax.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {

    private final PlaybackControlService playbackControlService;

    public PlaybackController(PlaybackControlService playbackControlService) {
        this.playbackControlService = playbackControlService;
    }

    @PostMapping("/control")
    public ApiResponse<NowPlayingStatusResponse> control(@Valid @RequestBody PlaybackControlRequest request) {
        return ApiResponse.success(playbackControlService.handleControl(resolveCurrentActor(), request));
    }

    @GetMapping("/now-playing")
    public ApiResponse<NowPlayingStatusResponse> nowPlaying() {
        return ApiResponse.success(playbackControlService.getNowPlaying(resolveCurrentActor()));
    }

    private String resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return "anonymous";
        }
        String actor = String.valueOf(authentication.getPrincipal());
        if (actor == null || actor.trim().isEmpty()) {
            return "anonymous";
        }
        return actor.trim();
    }
}
