package com.bookvillage.backend.controller;

import com.bookvillage.backend.common.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * [CTF Lab] 관리자 결제 관리 API
 * - GET  /admin/api/payments         : 결제 목록 조회 (필터/페이징)
 * - GET  /admin/api/payments/{id}    : 결제 상세 조회
 * - POST /admin/api/payments/{id}/cancel : 결제 취소
 */
@RestController
@RequestMapping("/admin/api/payments")
public class AdminPaymentController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public AdminPaymentController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public PageResponse<Map<String, Object>> getPayments(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {

        StringBuilder sql = new StringBuilder(
                "SELECT pt.id, pt.order_id, o.order_number, pt.user_id, u.name AS customer_name, u.email AS customer_email, "
                + "pt.payment_method, pt.card_number_masked, pt.card_holder, pt.card_expiry, "
                + "pt.coupon_code, pt.point_used, pt.amount, pt.status, pt.cancelled_at, pt.created_at "
                + "FROM payment_transactions pt "
                + "LEFT JOIN orders o ON o.id = pt.order_id "
                + "LEFT JOIN users u ON u.id = pt.user_id "
                + "WHERE 1=1");

        List<Object> args = new ArrayList<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (o.order_number LIKE ? OR u.name LIKE ? OR u.email LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.trim().isEmpty()) {
            sql.append(" AND pt.status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (paymentMethod != null && !paymentMethod.trim().isEmpty()) {
            sql.append(" AND pt.payment_method = ?");
            args.add(paymentMethod.trim().toUpperCase(Locale.ROOT));
        }
        if (startDate != null && !startDate.trim().isEmpty()) {
            sql.append(" AND DATE(pt.created_at) >= ?");
            args.add(startDate.trim());
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            sql.append(" AND DATE(pt.created_at) <= ?");
            args.add(endDate.trim());
        }

        sql.append(" ORDER BY pt.created_at DESC, pt.id DESC");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> mapped = rows.stream()
                .map(row -> mapPayment(row, false))
                .collect(Collectors.toList());

        int total = mapped.size();
        int fromIdx = Math.min((page - 1) * pageSize, total);
        int toIdx = Math.min(fromIdx + pageSize, total);

        return new PageResponse<>(mapped.subList(fromIdx, toIdx), total, page, pageSize);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPayment(@PathVariable Long id) {
        String sql = "SELECT pt.id, pt.order_id, o.order_number, pt.user_id, u.name AS customer_name, u.email AS customer_email, "
                + "pt.payment_method, pt.card_number_masked, pt.card_holder, pt.card_expiry, "
                + "pt.coupon_code, pt.point_used, pt.amount, pt.status, pt.cancelled_at, pt.created_at "
                + "FROM payment_transactions pt "
                + "LEFT JOIN orders o ON o.id = pt.order_id "
                + "LEFT JOIN users u ON u.id = pt.user_id "
                + "WHERE pt.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, id);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> payment = mapPayment(rows.get(0), true);

        // 주문 상품 목록 추가
        Object orderIdObj = rows.get(0).get("order_id");
        if (orderIdObj != null) {
            long orderId = ((Number) orderIdObj).longValue();
            String itemSql = "SELECT oi.book_id, oi.quantity, oi.unit_price, b.title "
                    + "FROM order_items oi LEFT JOIN books b ON b.id = oi.book_id "
                    + "WHERE oi.order_id = ? ORDER BY oi.id ASC";
            List<Map<String, Object>> items = jdbcTemplate.queryForList(itemSql, orderId)
                    .stream()
                    .map(row -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("bookId", row.get("book_id"));
                        item.put("title", row.get("title"));
                        item.put("quantity", row.get("quantity"));
                        item.put("unitPrice", row.get("unit_price"));
                        return (Map<String, Object>) item;
                    })
                    .collect(Collectors.toList());
            payment.put("items", items);
        }

        return ResponseEntity.ok(payment);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelPayment(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, order_id, status FROM payment_transactions WHERE id = ?", id);

        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String currentStatus = (String) rows.get(0).get("status");
        if ("CANCELLED".equalsIgnoreCase(currentStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "이미 취소된 결제입니다."));
        }

        jdbcTemplate.update(
                "UPDATE payment_transactions SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = ?", id);

        Object orderIdObj = rows.get(0).get("order_id");
        if (orderIdObj != null) {
            jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", orderIdObj);
        }

        return ResponseEntity.ok(Map.of(
                "message", "결제가 취소되었습니다.",
                "id", id
        ));
    }

    private Map<String, Object> mapPayment(Map<String, Object> row, boolean detail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", row.get("id"));
        p.put("orderId", row.get("order_id"));
        p.put("orderNumber", row.get("order_number"));
        p.put("userId", row.get("user_id"));
        p.put("customerName", row.get("customer_name"));
        if (detail) {
            p.put("customerEmail", row.get("customer_email"));
        }
        p.put("paymentMethod", row.get("payment_method"));
        p.put("cardNumberMasked", row.get("card_number_masked"));
        p.put("cardHolder", row.get("card_holder"));
        p.put("cardExpiry", row.get("card_expiry"));
        p.put("couponCode", row.get("coupon_code"));
        p.put("pointUsed", row.get("point_used"));
        p.put("amount", row.get("amount"));
        p.put("status", row.get("status"));
        p.put("cancelledAt", formatTs(row.get("cancelled_at")));
        p.put("createdAt", formatTs(row.get("created_at")));
        return p;
    }

    private String formatTs(Object ts) {
        if (ts == null) return null;
        if (ts instanceof Timestamp) {
            return ((Timestamp) ts).toLocalDateTime().format(DT_FMT);
        }
        return ts.toString();
    }
}
