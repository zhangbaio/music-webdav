package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.request.WebDavTestRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
import com.example.musicwebdav.api.response.WebDavTestResponse;
import com.example.musicwebdav.application.service.WebDavConnectionService;
import java.util.List;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webdav")
public class WebDavController {

    private final WebDavConnectionService webDavConnectionService;

    public WebDavController(WebDavConnectionService webDavConnectionService) {
        this.webDavConnectionService = webDavConnectionService;
    }

    @PostMapping("/test")
    public ApiResponse<WebDavTestResponse> testConnection(@Valid @RequestBody WebDavTestRequest request) {
        return ApiResponse.success(webDavConnectionService.testConnection(request));
    }

    @PostMapping("/configs")
    public ApiResponse<WebDavConfigResponse> createConfig(@Valid @RequestBody CreateWebDavConfigRequest request) {
        return ApiResponse.success(webDavConnectionService.createConfig(request));
    }

    @GetMapping("/configs/{id}")
    public ApiResponse<WebDavConfigResponse> getConfig(@PathVariable("id") Long id) {
        return ApiResponse.success(webDavConnectionService.getConfig(id));
    }

    @GetMapping("/configs")
    public ApiResponse<List<WebDavConfigResponse>> listConfigs() {
        return ApiResponse.success(webDavConnectionService.listConfigs());
    }
}
