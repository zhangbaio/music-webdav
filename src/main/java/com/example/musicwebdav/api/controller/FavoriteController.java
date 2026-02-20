package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.FavoriteSyncRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.FavoriteStatusResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.application.service.FavoriteService;
import com.example.musicwebdav.common.security.UserPrincipal;
import javax.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ApiResponse<FavoriteStatusResponse> favorite(@PathVariable("id") Long trackId,
                                                        @RequestHeader(value = "x-idempotency-key", required = false)
                                                        String idempotencyKey) {
        return ApiResponse.success(favoriteService.favorite(resolveCurrentActor(), trackId, idempotencyKey));
    }

    @DeleteMapping("/tracks/{id}/favorite")
    public ApiResponse<FavoriteStatusResponse> unfavorite(@PathVariable("id") Long trackId,
                                                          @RequestHeader(value = "x-idempotency-key", required = false)
                                                          String idempotencyKey) {
        return ApiResponse.success(favoriteService.unfavorite(resolveCurrentActor(), trackId, idempotencyKey));
    }

    @PostMapping("/tracks/{id}/favorite/sync")
    public ApiResponse<FavoriteStatusResponse> syncFavorite(
            @PathVariable("id") Long trackId,
            @Valid @RequestBody FavoriteSyncRequest request,
            @RequestHeader(value = "x-idempotency-key", required = false) String idempotencyKey) {
        return ApiResponse.success(favoriteService.syncFavorite(resolveCurrentActor(), trackId, request, idempotencyKey));
    }

    @GetMapping("/tracks/{id}/favorite")
    public ApiResponse<FavoriteStatusResponse> favoriteStatus(@PathVariable("id") Long trackId) {
        return ApiResponse.success(favoriteService.status(resolveCurrentActor(), trackId));
    }

    @GetMapping("/favorites/tracks")
    public ApiResponse<PageResponse<TrackResponse>> favoriteTracks(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(favoriteService.listFavoriteTracks(pageNo, pageSize));
    }

    private String resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal) {
            return String.valueOf(((UserPrincipal) authentication.getPrincipal()).getId());
        }
        return "anonymous";
    }
}
