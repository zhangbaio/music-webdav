package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.api.request.PlayEventRequest;
import com.example.musicwebdav.application.service.PlayEventService;
import com.example.musicwebdav.domain.PlayEventType;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
public class PlayEventController {

    private final PlayEventService playEventService;

    public PlayEventController(PlayEventService playEventService) {
        this.playEventService = playEventService;
    }

    @PostMapping("/{id}/play-event")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordPlayEvent(
            @PathVariable("id") Long trackId,
            @Valid @RequestBody PlayEventRequest request) {
        PlayEventType eventType = PlayEventType.valueOf(request.getEventType());
        playEventService.recordEvent(trackId, eventType, request.getDurationSec());
    }

    @GetMapping("/recently-played")
    public ApiResponse<PageResponse<TrackResponse>> recentlyPlayed(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(playEventService.listRecentlyPlayedTracks(pageNo, pageSize));
    }
}
