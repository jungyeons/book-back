-- CTF Lab: 카드 결제 정보 모의 저장을 위한 컬럼 추가
ALTER TABLE payment_transactions
    ADD COLUMN card_number_masked VARCHAR(25) NULL COMMENT '마스킹된 카드번호 (앞 4자리-****-****-뒤 4자리)',
    ADD COLUMN card_holder        VARCHAR(100) NULL COMMENT '카드 소유자명',
    ADD COLUMN card_expiry        VARCHAR(7)   NULL COMMENT '유효기간 (MM/YY)',
    ADD COLUMN cancelled_at       TIMESTAMP    NULL COMMENT '결제 취소 시각';
