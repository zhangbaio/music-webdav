package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.ShelfResponse;
import com.example.musicwebdav.application.service.RecommendationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recommendation shelves for the Browse page.
 *
 * <pre>
 *   GET /api/v1/recommendations/shelves
 * </pre>
 *
 * Returns a list of shelves. Each shelf has a type, title, and content
 * (tracks, albums, or artists depending on the shelf type).
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/shelves")
    public ApiResponse<List<ShelfResponse>> getShelves() {
        List<ShelfResponse> shelves = recommendationService.buildAllShelves();
        return ApiResponse.success(shelves);
    }
}
