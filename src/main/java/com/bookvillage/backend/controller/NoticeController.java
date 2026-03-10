package com.bookvillage.backend.controller;

import com.bookvillage.mock.dto.NoticeDto;
import com.bookvillage.mock.service.LearningFeatureService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController("adminNoticeController")
@RequestMapping("/admin/api/notices")
public class NoticeController {
    private final LearningFeatureService learningFeatureService;

    public NoticeController(LearningFeatureService learningFeatureService) {
        this.learningFeatureService = learningFeatureService;
    }

    @PostMapping
    public NoticeDto createNotice(@RequestBody(required = false) Map<String, String> request) {
        String title = request != null ? request.get("title") : null;
        String content = request != null ? request.get("content") : null;
        return learningFeatureService.createNotice(1L, title, content);
    }
}
