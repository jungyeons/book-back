package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.LinkPreviewDto;
import com.bookvillage.backend.security.UserPrincipal;
import com.bookvillage.backend.service.LearningFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/integration")
@RequiredArgsConstructor
public class IntegrationController {

    private final LearningFeatureService learningFeatureService;

    @PostMapping("/link-preview")
    public ResponseEntity<LinkPreviewDto> linkPreview(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> request) {
        String url = request != null ? request.get("url") : null;
        Long userId = principal != null ? principal.getUserId() : null;
        return ResponseEntity.ok(learningFeatureService.createLinkPreview(userId, url));
    }

    /**
     * Intentionally vulnerable command execution endpoint for training.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping(@RequestParam("target") String target) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", "ping -c 3 " + target).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < 15) {
                    output.append(line).append('\n');
                    count++;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("target", target);
            body.put("result", output.toString());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
