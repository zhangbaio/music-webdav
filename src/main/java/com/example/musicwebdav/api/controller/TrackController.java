package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackDetailResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.application.service.TrackPlaybackService;
import com.example.musicwebdav.application.service.TrackQueryService;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
public class TrackController {

    private final TrackQueryService trackQueryService;
    private final TrackPlaybackService trackPlaybackService;

    public TrackController(TrackQueryService trackQueryService, TrackPlaybackService trackPlaybackService) {
        this.trackQueryService = trackQueryService;
        this.trackPlaybackService = trackPlaybackService;
    }

    @GetMapping
    public ApiResponse<PageResponse<TrackResponse>> listTracks(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "artist", required = false) String artist,
            @RequestParam(value = "album", required = false) String album,
            @RequestParam(value = "genre", required = false) String genre,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder
    ) {
        return ApiResponse.success(
                trackQueryService.listTracks(pageNo, pageSize, keyword, artist, album, genre, year, sortBy, sortOrder));
    }

    @GetMapping("/search")
    public ApiResponse<List<TrackResponse>> search(@RequestParam("q") String keyword,
                                                   @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(trackQueryService.searchTracks(keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<TrackDetailResponse> getTrack(@PathVariable("id") Long id) {
        TrackDetailResponse response = trackQueryService.getTrack(id);
        if (response == null) {
            return ApiResponse.fail("404", "歌曲不存在");
        }
        return ApiResponse.success(response);
    }

    @GetMapping("/{id}/stream")
    public void streamTrack(@PathVariable("id") Long id, HttpServletResponse response) {
        trackPlaybackService.redirectToWebDav(id, response);
    }
}
