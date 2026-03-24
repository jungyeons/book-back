package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.FaqDto;
import com.bookvillage.backend.dto.NoticeDto;
import com.bookvillage.backend.security.UserPrincipal;
import com.bookvillage.backend.service.LearningFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SupportController {

    private final LearningFeatureService learningFeatureService;

    @GetMapping("/api/notices")
    public ResponseEntity<List<NoticeDto>> notices(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(learningFeatureService.getNotices(q));
    }

    @GetMapping("/api/notices/{noticeId}")
    public ResponseEntity<NoticeDto> noticeDetail(@PathVariable Long noticeId) {
        // 첨부파일(attachmentName, attachmentUrl) 포함하여 반환
        return ResponseEntity.ok(learningFeatureService.getNoticeWithAttachment(noticeId));
    }

    /**
     * [Phase 2] 최신 긴급 공지사항 조회 - 모바일 앱 팝업용
     *
     * 취약점: urgent=true 공지의 linkUrl을 앱이 검증 없이 WebView에 로드
     * 공격 흐름:
     *   1. 공격자가 관리자 권한 탈취 후 POST /admin/api/notices 로 긴급 공지 등록
     *      { "title": "긴급 보안 업데이트", "linkUrl": "http://attacker.com/fake-store", "urgent": true }
     *   2. 사용자 앱 실행 → GET /api/notices/latest-urgent 호출 → 팝업 표시
     *   3. 사용자 클릭 → WebView가 피싱 페이지로 이동 (Phase 3)
     */
    @GetMapping("/api/notices/latest-urgent")
    public ResponseEntity<?> latestUrgentNotice() {
        NoticeDto notice = learningFeatureService.getLatestUrgentNotice();
        if (notice == null) {
            return ResponseEntity.noContent().build();  // 204: 긴급 공지 없음
        }
        return ResponseEntity.ok(notice);
    }

    @GetMapping("/api/faqs")
    public ResponseEntity<List<FaqDto>> faqs(@RequestParam(required = false) String category) {
        return ResponseEntity.ok(learningFeatureService.getFaqs(category));
    }

    @PostMapping("/api/customer-service/{inquiryId}/attachments")
    public ResponseEntity<Map<String, Object>> uploadInquiryAttachment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long inquiryId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(learningFeatureService.saveInquiryAttachment(principal.getUserId(), inquiryId, file));
    }
}
