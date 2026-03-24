package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.NoticeDto;
import com.bookvillage.backend.service.LearningFeatureService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Paths;

/**
 * [CTF Lab] 관리자 공지사항 API
 * 취약점: Unrestricted File Upload (CWE-434)
 * - 확장자 검증 없이 모든 파일 타입 허용 (JSP, ASP, PHP 웹쉘 포함)
 * - 업로드된 파일이 웹 루트(/uploads/)에 원본 파일명 그대로 저장되어 직접 접근/실행 가능
 */
@RestController("adminNoticeController")
@RequestMapping("/admin/api/notices")
public class NoticeController {

    private final LearningFeatureService learningFeatureService;

    @Value("${file.lab-upload-path:./uploads/lab}")
    private String labUploadPath;

    public NoticeController(LearningFeatureService learningFeatureService) {
        this.learningFeatureService = learningFeatureService;
    }

    /**
     * 공지사항 등록 (파일 첨부 포함)
     * - Content-Type: multipart/form-data
     * - 파라미터: title, content, file(optional)
     * - 업로드된 파일은 확장자 검증 없이 /uploads/{originalFilename} 경로로 서빙됨
     */
    @PostMapping(consumes = {"multipart/form-data", "application/x-www-form-urlencoded", "application/json"})
    public NoticeDto createNotice(
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "linkUrl", required = false) String linkUrl,
            @RequestParam(value = "urgent", required = false, defaultValue = "false") boolean urgent
    ) {
        String attachmentName = null;
        String attachmentUrl = null;

        if (file != null && !file.isEmpty()) {
            // [취약점] 파일 확장자 및 MIME 타입 검증 없이 원본 파일명 그대로 저장
            String originalName = file.getOriginalFilename();
            if (originalName != null) {
                originalName = originalName.replace("\\", "/");
                int idx = originalName.lastIndexOf('/');
                if (idx >= 0) {
                    originalName = originalName.substring(idx + 1);
                }
            } else {
                originalName = "upload.bin";
            }

            // [필터링] 소문자 .jsp / .asp / .php 문자열만 차단
            // 우회 가능: .JSP, .Jsp, .jspx, .ASPX, .PhP 등은 필터링되지 않음
            String ext = originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : "";
            if (ext.equals(".jsp") || ext.equals(".asp") || ext.equals(".php")) {
                throw new RuntimeException("허용되지 않는 파일 형식입니다.");
            }

            try {
                File uploadDir = Paths.get(labUploadPath).toAbsolutePath().toFile();
                uploadDir.mkdirs();
                // [취약점] 원본 파일명 그대로 저장 → .jsp/.asp/.php 파일이 웹에서 직접 실행 가능
                File dest = new File(uploadDir, originalName);
                file.transferTo(dest);
                attachmentName = originalName;
                attachmentUrl = "/uploads/" + originalName;
            } catch (Exception e) {
                throw new RuntimeException("파일 저장 실패: " + e.getMessage(), e);
            }
        }

        return learningFeatureService.createNotice(1L, title, content, attachmentName, attachmentUrl, linkUrl, urgent);
    }
}
