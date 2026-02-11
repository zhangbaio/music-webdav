package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.response.AlbumSearchResponse;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.ArtistSearchResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.SearchClassifyResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.application.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/classify")
    public ApiResponse<SearchClassifyResponse> classify(@RequestParam("q") String keyword) {
        return ApiResponse.success(searchService.classify(keyword));
    }

    @GetMapping("/songs")
    public ApiResponse<PageResponse<TrackResponse>> searchSongs(
            @RequestParam("q") String keyword,
            @RequestParam(value = "scope", defaultValue = "all") String scope,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(searchService.searchSongs(keyword, scope, pageNo, pageSize));
    }

    @GetMapping("/artists")
    public ApiResponse<PageResponse<ArtistSearchResponse>> searchArtists(
            @RequestParam("q") String keyword,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(searchService.searchArtists(keyword, pageNo, pageSize));
    }

    @GetMapping("/albums")
    public ApiResponse<PageResponse<AlbumSearchResponse>> searchAlbums(
            @RequestParam(value = "artist", required = false) String artist,
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(searchService.searchAlbums(artist, keyword, pageNo, pageSize));
    }

    @GetMapping("/artist-tracks")
    public ApiResponse<PageResponse<TrackResponse>> searchArtistTracks(
            @RequestParam("artist") String artist,
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return ApiResponse.success(searchService.searchArtistTracks(artist, keyword, pageNo, pageSize));
    }
}
