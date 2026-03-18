-- 취약점 시나리오: 세션 + IP 이중 검증
-- 로그인 시 클라이언트 IP를 세션과 함께 저장
-- /cookie-login API에서 토큰 + IP 일치 여부를 검증하지만
-- RequestIpResolver가 X-Forwarded-For 헤더를 무조건 신뢰 →
-- XSS로 SESSION_TOKEN 쿠키 탈취 + 피해자 IP 파악 후
-- X-Forwarded-For 헤더 조작으로 IP 검증 우회 가능

ALTER TABLE user_sessions ADD COLUMN login_ip VARCHAR(45) NULL AFTER active;
