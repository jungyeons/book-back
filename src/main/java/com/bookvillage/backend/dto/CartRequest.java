package com.bookvillage.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartRequest {
    private List<CartItem> items;
    private String paymentMethod;
    private String couponCode;
    private Integer usePoints;
    private String shippingAddress;
    private Boolean skipVerification;
    /**
     * [CTF Lab] 파라미터 변조: 클라이언트가 최종 결제 금액을 직접 지정
     * 서버는 이 값이 있으면 서버 계산 금액을 무시하고 그대로 사용
     */
    private BigDecimal totalAmount;
    /**
     * [CTF Lab] 파라미터 변조: 클라이언트가 할인 금액을 직접 지정
     * 쿠폰 코드 없이도 임의 할인 적용 가능
     */
    private BigDecimal discountAmount;

    /** 카드번호 (CARD 결제 시) - 서버에서 마스킹 후 저장 */
    private String cardNumber;
    /** 유효기간 MM/YY */
    private String cardExpiry;
    /** CVC (저장하지 않음, 모의 검증용) */
    private String cardCvc;
    /** 카드 소유자명 */
    private String cardHolder;

    @Data
    public static class CartItem {
        private Long bookId;
        private Integer quantity;
    }
}
