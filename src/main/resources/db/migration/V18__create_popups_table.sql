CREATE TABLE IF NOT EXISTS popups (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200)  NOT NULL,
    content     TEXT          NULL,
    link_url    VARCHAR(500)  NULL,
    start_date  DATE          NOT NULL,
    end_date    DATE          NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    device_type VARCHAR(20)   NOT NULL DEFAULT 'all',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO popups (title, content, link_url, start_date, end_date, is_active, device_type)
VALUES ('샘플 팝업', '북촌 도서 서비스에 오신 것을 환영합니다.', NULL, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 0, 'all');
