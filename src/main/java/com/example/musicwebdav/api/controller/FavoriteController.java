package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.FavoriteStatusResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.application.service.FavoriteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PutMapping("/tracks/{id}/favorite")
    public ApiResponse<FavoriteStatusResponse> favorite(@PathVariable("id") Long trackId) {
        return ApiResponse.success(favoriteService.favorite(trackId));
    }

    @DeleteMapping("/tracks/{id}/favorite")
    public ApiResponse<FavoriteStatusResponse> unfavorite(@PathVariable("id") Long trackId) {
        return ApiResponse.success(favoriteService.unfavorite(trackId));
    }

    @GetMapping("/tracks/{id}/favorite")
    public ApiResponse<FavoriteStatusResponse> favoriteStatus(@PathVariable("id") Long trackId) {
        return ApiResponse.success(favoriteService.status(trackId));
    }

    @GetMapping("/favorites/tracks")
    public ApiResponse<PageResponse<TrackResponse>> favoriteTracks(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(favoriteService.listFavoriteTracks(pageNo, pageSize));
    }
}
