package com.bookvillage.mock.service;

import com.bookvillage.mock.dto.CartRequest;
import com.bookvillage.mock.dto.GuestOrderLookupDto;
import com.bookvillage.mock.dto.OrderDto;
import com.bookvillage.mock.entity.Book;
import com.bookvillage.mock.entity.Order;
import com.bookvillage.mock.entity.OrderItem;
import com.bookvillage.mock.repository.BookRepository;
import com.bookvillage.mock.repository.OrderRepository;
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
        int usedPoints = applyPointsIfPossible(userId, request.getUsePoints(), total.subtract(discount));

        BigDecimal finalAmount = total.subtract(discount).subtract(BigDecimal.valueOf(usedPoints));
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            finalAmount = BigDecimal.ZERO;
        }
        order.setTotalAmount(finalAmount);

        String receiptPath = fileService.generateReceipt(order);
        order.setReceiptFilePath(receiptPath);
        order = orderRepository.save(order);

        jdbcTemplate.update(
                "INSERT INTO payment_transactions (order_id, user_id, payment_method, coupon_code, point_used, amount, status, learning_note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                order.getId(),
                userId,
                paymentMethod,
                request.getCouponCode(),
                usedPoints,
                finalAmount,
                "PAID",
                "Controlled security-lab checkout"
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

    private int applyPointsIfPossible(Long userId, Integer requestedPoints, BigDecimal amountAfterDiscount) {
        if (requestedPoints == null || requestedPoints <= 0) {
            return 0;
        }

        int current = currentPointBalance(userId);
        int maxByAmount = amountAfterDiscount.max(BigDecimal.ZERO).intValue();
        int allowed = Math.min(current, maxByAmount);

        if (requestedPoints > allowed) {
            securityLabService.simulate("REQ-COM-020", userId, "/api/orders/checkout", "requestedPoints=" + requestedPoints);
            return allowed;
        }
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
