package com.bookvillage.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link preview generation endpoint.
 * Fetches the target URL from the server side without URL validation.
 * Internal addresses (127.0.0.1, 169.254.169.254, etc.) are also accessible.
 */
@RestController
@RequestMapping("/api/link-preview")
public class LinkPreviewController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, String> request) {
        String url = request != null ? request.get("url") : null;
        if (url == null || url.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }

        // [필터링] "127.0.0.1" 및 "169.254.169.254" 문자열만 차단
        // 우회 가능: 2130706433(십진수), 0x7f000001(16진수), 0177.0.0.1(8진수),
        //           localhost, ::1, http://[::ffff:7f00:1], http://①②⑦.0.0.1 등
        String trimmedUrl = url.trim();
        if (trimmedUrl.contains("127.0.0.1") || trimmedUrl.contains("169.254.169.254")) {
            return ResponseEntity.badRequest().body(Map.of("error", "접근이 차단된 주소입니다."));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);

        HttpURLConnection conn = null;
        try {
            URL target = new URL(url.trim());
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "BookVillage-LinkPreview/1.0");

            int status = conn.getResponseCode();
            body.put("statusCode", status);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int read;
                while ((read = reader.read(buf)) != -1 && sb.length() < 8192) {
                    sb.append(buf, 0, read);
                }
            }

            String responseBody = sb.toString();
            body.put("bodyPreview", responseBody.length() > 2000 ? responseBody.substring(0, 2000) : responseBody);

            Matcher titleMatcher = Pattern.compile("(?i)<title>(.*?)</title>").matcher(responseBody);
            body.put("title", titleMatcher.find() ? titleMatcher.group(1).trim() : "(no title)");

        } catch (Exception e) {
            body.put("error", e.getMessage());
            body.put("title", "(fetch failed)");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return ResponseEntity.ok(body);
    }
}
