-- FCM 토큰 저장 테이블
-- 앱 설치 기기의 FCM 토큰을 수집 → 전체 발송에 사용
-- 취약점: 인증 없이 토큰 등록 가능 (MyFirebaseMessagingService.registerTokenToBackend)
CREATE TABLE IF NOT EXISTS device_fcm_tokens (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    token       VARCHAR(512)  NOT NULL,
    device_id   VARCHAR(255)  NULL,
    registered_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_token (token(255))
);
