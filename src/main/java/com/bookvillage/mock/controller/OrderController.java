package com.bookvillage.mock.controller;

import com.bookvillage.mock.dto.CartRequest;
import com.bookvillage.mock.dto.GuestOrderLookupDto;
import com.bookvillage.mock.dto.OrderDto;
import com.bookvillage.mock.security.UserPrincipal;
import com.bookvillage.mock.service.LearningFeatureService;
import com.bookvillage.mock.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final LearningFeatureService learningFeatureService;

    @PostMapping("/cart")
    public ResponseEntity<OrderDto> addToCart(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CartRequest request) {
        OrderDto order = orderService.checkout(principal.getUserId(), request);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderDto> checkout(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CartRequest request) {
        OrderDto order = orderService.checkout(principal.getUserId(), request);
        return ResponseEntity.ok(order);
    }

    @GetMapping
    public ResponseEntity<List<OrderDto>> getOrders(
            @AuthenticationPrincipal UserPrincipal principal) {
        List<OrderDto> orders = orderService.getOrdersByUserId(principal.getUserId());
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDto> getOrderDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderById(principal.getUserId(), orderId));
    }

    @GetMapping("/lookup")
    public ResponseEntity<GuestOrderLookupDto> lookupOrder(@RequestParam String orderNumber) {
        return ResponseEntity.ok(orderService.getGuestLookupByOrderNumber(orderNumber));
    }

    @GetMapping("/{orderId}/tracking")
    public ResponseEntity<Map<String, Object>> trackOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long orderId,
            @RequestParam String trackingUrl) {
        return ResponseEntity.ok(learningFeatureService.trackOrder(principal.getUserId(), orderId, trackingUrl));
    }
}
