package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.CreateScanTaskRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.CreateScanTaskResponse;
import com.example.musicwebdav.api.response.ScanTaskDetailResponse;
import com.example.musicwebdav.application.service.ScanTaskService;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scan/tasks")
public class ScanTaskController {

    private final ScanTaskService scanTaskService;

    public ScanTaskController(ScanTaskService scanTaskService) {
        this.scanTaskService = scanTaskService;
    }

    @PostMapping
    public ApiResponse<CreateScanTaskResponse> createTask(@Valid @RequestBody CreateScanTaskRequest request) {
        return ApiResponse.success(scanTaskService.createTask(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<ScanTaskDetailResponse> getTask(@PathVariable("id") Long id) {
        ScanTaskDetailResponse response = scanTaskService.getTask(id);
        if (response == null) {
            return ApiResponse.fail("404", "任务不存在");
        }
        return ApiResponse.success(response);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<String> cancelTask(@PathVariable("id") Long id) {
        boolean canceled = scanTaskService.cancelTask(id);
        if (!canceled) {
            return ApiResponse.fail("404", "任务不存在");
        }
        return ApiResponse.success("CANCELED");
    }
}
