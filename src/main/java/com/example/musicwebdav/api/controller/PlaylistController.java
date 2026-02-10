package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.AddPlaylistTracksRequest;
import com.example.musicwebdav.api.request.CreatePlaylistRequest;
import com.example.musicwebdav.api.request.RenamePlaylistRequest;
import com.example.musicwebdav.api.response.AddPlaylistTracksResponse;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.PlaylistResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.application.service.PlaylistService;
import java.util.List;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @PostMapping
    public ApiResponse<PlaylistResponse> createPlaylist(@Valid @RequestBody CreatePlaylistRequest request) {
        return ApiResponse.success(playlistService.createPlaylist(request.getName()));
    }

    @GetMapping
    public ApiResponse<List<PlaylistResponse>> listPlaylists() {
        return ApiResponse.success(playlistService.listPlaylists());
    }

    @PatchMapping("/{id}")
    public ApiResponse<PlaylistResponse> renamePlaylist(@PathVariable("id") Long id,
                                                        @Valid @RequestBody RenamePlaylistRequest request) {
        return ApiResponse.success(playlistService.renamePlaylist(id, request.getName()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> deletePlaylist(@PathVariable("id") Long id) {
        playlistService.deletePlaylist(id);
        return ApiResponse.success("DELETED");
    }

    @GetMapping("/{id}/tracks")
    public ApiResponse<PageResponse<TrackResponse>> listPlaylistTracks(
            @PathVariable("id") Long id,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(playlistService.listPlaylistTracks(id, pageNo, pageSize));
    }

    @PostMapping("/{id}/tracks")
    public ApiResponse<AddPlaylistTracksResponse> addTracks(@PathVariable("id") Long id,
                                                            @Valid @RequestBody AddPlaylistTracksRequest request) {
        return ApiResponse.success(playlistService.addTracks(id, request.getTrackIds()));
    }

    @DeleteMapping("/{id}/tracks/{trackId}")
    public ApiResponse<String> removeTrack(@PathVariable("id") Long id,
                                           @PathVariable("trackId") Long trackId) {
        boolean removed = playlistService.removeTrack(id, trackId);
        return ApiResponse.success(removed ? "REMOVED" : "NOT_FOUND");
    }
}
