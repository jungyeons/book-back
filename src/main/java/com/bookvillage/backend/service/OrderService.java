package com.bookvillage.backend.service;

import com.bookvillage.backend.dto.CartRequest;
import com.bookvillage.backend.dto.GuestOrderLookupDto;
import com.bookvillage.backend.dto.OrderDto;
import com.bookvillage.backend.entity.Book;
import com.bookvillage.backend.entity.Order;
import com.bookvillage.backend.entity.OrderItem;
import com.bookvillage.backend.repository.BookRepository;
import com.bookvillage.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final BookRepository bookRepository;
    private final FileService fileService;
    private final JdbcTemplate jdbcTemplate;
    private final SecurityLabService securityLabService;

    @Transactional
    public OrderDto checkout(Long userId, CartRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Cart items are required");
        }

        String paymentMethod = request.getPaymentMethod() == null ? "CARD" : request.getPaymentMethod().trim().toUpperCase(Locale.ROOT);
        String shippingAddress = request.getShippingAddress() == null ? "Seoul" : request.getShippingAddress().trim();

        if (Boolean.TRUE.equals(request.getSkipVerification())) {
            securityLabService.simulate("REQ-COM-018", userId, "/api/orders/checkout", "skipVerification=true");
        }

        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        order.setStatus("PAID");
        order.setPaymentMethod(paymentMethod);
        order.setShippingAddress(shippingAddress);
        order.setTotalAmount(BigDecimal.ZERO);

        BigDecimal total = BigDecimal.ZERO;
        for (CartRequest.CartItem item : request.getItems()) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                securityLabService.simulate("REQ-COM-017", userId, "/api/orders/checkout", String.valueOf(item.getQuantity()));
                throw new IllegalArgumentException("Quantity must be positive");
            }

            Book book = bookRepository.findById(item.getBookId())
                    .orElseThrow(() -> new IllegalArgumentException("Book not found: " + item.getBookId()));

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setBookId(book.getId());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setUnitPrice(book.getPrice());
            order.getItems().add(orderItem);

            total = total.add(book.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        BigDecimal discount = applyCouponIfPossible(userId, request.getCouponCode(), total);

        // [CTF Lab] 파라미터 변조: discountAmount가 제공되면 쿠폰 검증 없이 해당 할인 금액 직접 적용
        if (request.getDiscountAmount() != null) {
            securityLabService.simulate("REQ-COM-016", userId, "/api/orders/checkout", "discountAmount=" + request.getDiscountAmount());
            discount = request.getDiscountAmount();
        }

        int usedPoints = applyPointsIfPossible(userId, request.getUsePoints(), total.subtract(discount));

        BigDecimal finalAmount = total.subtract(discount).subtract(BigDecimal.valueOf(usedPoints));
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            finalAmount = BigDecimal.ZERO;
        }

        // [CTF Lab] 파라미터 변조: totalAmount가 제공되면 서버 계산 금액을 완전히 무시하고 클라이언트 값 사용
        if (request.getTotalAmount() != null) {
            securityLabService.simulate("REQ-COM-016", userId, "/api/orders/checkout", "totalAmount=" + request.getTotalAmount());
            finalAmount = request.getTotalAmount().max(BigDecimal.ZERO);
        }

        order.setTotalAmount(finalAmount);

        String receiptPath = fileService.generateReceipt(order);
        order.setReceiptFilePath(receiptPath);
        order = orderRepository.save(order);

        String maskedCard = maskCardNumber(request.getCardNumber());
        jdbcTemplate.update(
                "INSERT INTO payment_transactions (order_id, user_id, payment_method, coupon_code, point_used, amount, status, learning_note, card_number_masked, card_holder, card_expiry) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.getId(),
                userId,
                paymentMethod,
                request.getCouponCode(),
                usedPoints,
                finalAmount,
                "PAID",
                "Controlled security-lab checkout",
                maskedCard,
                request.getCardHolder(),
                request.getCardExpiry()
        );

        if (usedPoints > 0) {
            int balance = currentPointBalance(userId);
            jdbcTemplate.update(
                    "INSERT INTO point_histories (user_id, change_type, amount, balance_after, description) VALUES (?, 'USE', ?, ?, ?)",
                    userId,
                    -usedPoints,
                    balance,
                    "checkout point use"
            );
        }

        int reward = Math.max(1, finalAmount.divide(BigDecimal.valueOf(100), BigDecimal.ROUND_DOWN).intValue());
        int rewardBalance = currentPointBalance(userId) + reward;
        jdbcTemplate.update(
                "INSERT INTO point_histories (user_id, change_type, amount, balance_after, description) VALUES (?, 'EARN', ?, ?, ?)",
                userId,
                reward,
                rewardBalance,
                "checkout reward"
        );

        jdbcTemplate.update("DELETE FROM cart_items WHERE user_id = ?", userId);
        return OrderDto.from(order);
    }

    private BigDecimal applyCouponIfPossible(Long userId, String couponCode, BigDecimal total) {
        if (couponCode == null || couponCode.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, discount_type, discount_value, remaining_count FROM coupons WHERE code = ?",
                couponCode.trim()
        );
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Map<String, Object> coupon = rows.get(0);
        int remaining = ((Number) coupon.get("remaining_count")).intValue();
        if (remaining <= 0) {
            securityLabService.simulate("REQ-COM-019", userId, "/api/orders/checkout", couponCode);
            return BigDecimal.ZERO;
        }

        BigDecimal discountValue = (BigDecimal) coupon.get("discount_value");
        String type = (String) coupon.get("discount_type");
        BigDecimal discount;
        if ("PERCENT".equalsIgnoreCase(type)) {
            discount = total.multiply(discountValue).divide(BigDecimal.valueOf(100));
        } else {
            discount = discountValue;
        }

        if (discount.compareTo(total) > 0) {
            discount = total;
        }

        jdbcTemplate.update("UPDATE coupons SET remaining_count = remaining_count - 1 WHERE id = ?", ((Number) coupon.get("id")).longValue());
        return discount;
    }

    /**
     * [CTF Lab] 파라미터 변조: usePoints 검증 없음
     * - 서버가 클라이언트 제공 포인트 값을 실제 잔액과 비교하지 않음
     * - Burp Suite로 usePoints 값을 보유 포인트보다 크게 변조하면 그대로 적용됨
     * - 결과: 포인트 잔액이 음수가 되어 사실상 무료 또는 초저가 결제 가능
     */
    private int applyPointsIfPossible(Long userId, Integer requestedPoints, BigDecimal amountAfterDiscount) {
        if (requestedPoints == null || requestedPoints <= 0) {
            return 0;
        }

        // [취약점] 잔액 초과 여부를 검증하지 않고 요청된 포인트를 그대로 사용
        securityLabService.simulate("REQ-COM-020", userId, "/api/orders/checkout", "requestedPoints=" + requestedPoints);
        return requestedPoints;
    }

    private int currentPointBalance(Long userId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM point_histories WHERE user_id = ?",
                Integer.class,
                userId
        );
        return value == null ? 0 : value;
    }

    public List<OrderDto> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserId(userId).stream()
                .map(OrderDto::from)
                .collect(Collectors.toList());
    }

    public OrderDto getOrderById(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderDto::from)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    public OrderDto getOrderByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(OrderDto::from)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    public GuestOrderLookupDto getGuestLookupByOrderNumber(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        securityLabService.simulate("REQ-COM-021", null, "/api/orders/lookup", orderNumber);

        GuestOrderLookupDto dto = new GuestOrderLookupDto();
        dto.setOrderNumber(order.getOrderNumber());
        dto.setStatus(order.getStatus());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setMaskedShippingAddress(maskAddress(order.getShippingAddress()));
        return dto;
    }

    /**
     * 카드번호를 마스킹합니다.
     * 예) 1234-5678-9012-3456 → 1234-****-****-3456
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.trim().isEmpty()) return null;
        String digits = cardNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 8) return null;
        String first4 = digits.substring(0, 4);
        String last4 = digits.substring(digits.length() - 4);
        return first4 + "-****-****-" + last4;
    }

    private String maskAddress(String address) {
        if (address == null || address.isEmpty()) {
            return "N/A";
        }
        if (address.length() < 6) {
            return "***";
        }
        return address.substring(0, Math.min(6, address.length())) + "***";
    }
}
