-- [CTF Lab] Phase 2: 긴급 공지사항 피싱 시나리오 지원
-- 공격자가 관리자 권한 탈취 후 악성 링크를 공지사항에 삽입하여
-- 모바일 앱 메인 화면에 팝업으로 표시하는 시나리오에 사용
ALTER TABLE notices
    ADD COLUMN link_url VARCHAR(500) NULL COMMENT '클릭 시 이동할 URL (취약점: 검증 없이 앱 WebView에 로드됨)',
    ADD COLUMN urgent   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '긴급 공지 여부 (1이면 앱 실행 시 팝업 표시)';
