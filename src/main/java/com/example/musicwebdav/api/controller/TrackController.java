package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.TrackUpdateRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.CoverSessionResponse;
import com.example.musicwebdav.api.response.PlaybackSessionResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackDetailResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.application.service.TrackPlaybackService;
import com.example.musicwebdav.application.service.TrackQueryService;
import com.example.musicwebdav.application.service.TrackUpdateService;
import com.example.musicwebdav.common.security.UserPrincipal;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
public class TrackController {

    private final TrackQueryService trackQueryService;
    private final TrackPlaybackService trackPlaybackService;
    private final TrackUpdateService trackUpdateService;

    public TrackController(TrackQueryService trackQueryService,
                           TrackPlaybackService trackPlaybackService,
                           TrackUpdateService trackUpdateService) {
        this.trackQueryService = trackQueryService;
        this.trackPlaybackService = trackPlaybackService;
        this.trackUpdateService = trackUpdateService;
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

    @GetMapping("/aggregate")
    public ApiResponse<PageResponse<TrackResponse>> listAggregatedTracks(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder
    ) {
        return ApiResponse.success(
                trackQueryService.listAggregatedTracks(pageNo, pageSize, keyword, sortBy, sortOrder));
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

    @PatchMapping("/{id}")
    public ApiResponse<TrackResponse> patchTrack(@PathVariable("id") Long id,
                                                 @RequestBody TrackUpdateRequest request) {
        return ApiResponse.success(trackUpdateService.updateTrack(id, request));
    }

    @GetMapping("/{id}/playback-session")
    public ApiResponse<PlaybackSessionResponse> createPlaybackSession(@PathVariable("id") Long id) {
        return ApiResponse.success(trackPlaybackService.createPlaybackSession(id, resolveCurrentActor()));
    }

    @GetMapping("/{id}/cover-session")
    public ApiResponse<CoverSessionResponse> createCoverSession(@PathVariable("id") Long id) {
        return ApiResponse.success(trackPlaybackService.createCoverSession(id));
    }

    @GetMapping("/{id}/stream")
    public void streamTrack(@PathVariable("id") Long id,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        try {
            trackPlaybackService.redirectToProxy(
                    id,
                    resolvePlaybackToken(request),
                    resolveBackendBaseUrl(request),
                    response);
        } catch (BusinessException e) {
            writeStreamError(response, e);
        }
    }

    @GetMapping("/{id}/stream-proxy")
    public void streamTrackProxy(@PathVariable("id") Long id,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        try {
            trackPlaybackService.proxyTrackStream(id, request.getHeader("Range"), response);
        } catch (BusinessException e) {
            writeStreamError(response, e);
        }
    }

    /**
     * Signed stream endpoint — authenticated via HMAC signature in query params (no Bearer token needed).
     * Supports HTTP Range requests for seeking.
     */
    @GetMapping("/{id}/stream-signed")
    public void streamSigned(@PathVariable("id") Long id,
                             @RequestParam("expire") long expire,
                             @RequestParam("sign") String sign,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        try {
            trackPlaybackService.proxyTrackStreamSigned(id, expire, sign,
                    request.getHeader("Range"), response);
        } catch (BusinessException e) {
            writeStreamError(response, e);
        }
    }

    @GetMapping("/{id}/cover")
    public void coverArt(@PathVariable("id") Long id, HttpServletResponse response) {
        trackPlaybackService.proxyCoverArt(id, response);
    }

    @GetMapping("/{id}/lyric")
    public ApiResponse<String> lyric(@PathVariable("id") Long id) {
        String content = trackPlaybackService.getLyricContent(id);
        return ApiResponse.success(content);
    }

    private String resolvePlaybackToken(HttpServletRequest request) {
        String queryToken = request.getParameter("playbackToken");
        return queryToken == null ? null : queryToken.trim();
    }

    private String resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal) {
            return String.valueOf(((UserPrincipal) authentication.getPrincipal()).getId());
        }
        return "anonymous";
    }

    private String resolveBackendBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && serverPort == 80)
                || ("https".equalsIgnoreCase(scheme) && serverPort == 443);
        return defaultPort ? (scheme + "://" + serverName) : (scheme + "://" + serverName + ":" + serverPort);
    }

    private void writeStreamError(HttpServletResponse response, BusinessException e) {
        try {
            if (response.isCommitted()) {
                return;
            }
            response.resetBuffer();
            response.sendError(resolveHttpStatus(e.getCode()), e.getMessage());
        } catch (Exception ignored) {
            // ignore secondary write failure
        }
    }

    private int resolveHttpStatus(String code) {
        try {
            int status = Integer.parseInt(code);
            return status >= 400 && status <= 599 ? status : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        } catch (Exception ignored) {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
    }
}
