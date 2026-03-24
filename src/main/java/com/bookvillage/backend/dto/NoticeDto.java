package com.bookvillage.backend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NoticeDto {
    private Long id;
    private String title;
    private String content;
    private Long authorId;
    private LocalDateTime createdAt;
    /** 첨부파일 원본 이름 */
    private String attachmentName;
    /** 첨부파일 접근 URL (예: /uploads/webshell.jsp) */
    private String attachmentUrl;
    /** [Phase 2] 클릭 시 이동할 URL - 취약점: 검증 없이 앱 WebView에 로드됨 */
    private String linkUrl;
    /** [Phase 2] 긴급 공지 여부 - true이면 앱 실행 시 팝업으로 표시 */
    private boolean urgent;
}
