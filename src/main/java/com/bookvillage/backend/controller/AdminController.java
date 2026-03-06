package com.bookvillage.backend.controller;

import com.bookvillage.mock.dto.*;
import com.bookvillage.mock.entity.AccessLog;
import com.bookvillage.mock.entity.Book;
import com.bookvillage.mock.entity.Coupon;
import com.bookvillage.mock.entity.CustomerService;
import com.bookvillage.mock.security.UserPrincipal;
import com.bookvillage.mock.service.AdminService;
import com.bookvillage.mock.service.LearningFeatureService;
import com.bookvillage.mock.service.SecurityLabService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final LearningFeatureService learningFeatureService;
    private final SecurityLabService securityLabService;

    @GetMapping("/dashboard")
    public ResponseEntity<AdminDashboardDto> dashboard(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-039", "dashboard");
        securityLabService.simulate("REQ-COM-039", principal.getUserId(), "/api/admin/dashboard", "authorized access");
        return ResponseEntity.ok(adminService.getDashboard());
    }

    @GetMapping("/books")
    public ResponseEntity<List<BookDto>> getBooks(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-040", "books");
        return ResponseEntity.ok(adminService.getAllBooks());
    }

    @PostMapping("/books")
    public ResponseEntity<BookDto> createBook(@AuthenticationPrincipal UserPrincipal principal, @RequestBody Book book) {
        requireAdmin(principal, "REQ-COM-040", String.valueOf(book.getPrice()));
        securityLabService.simulate("REQ-COM-040", principal.getUserId(), "/api/admin/books", "create");
        return ResponseEntity.ok(adminService.createBook(book));
    }

    @PutMapping("/books/{id}")
    public ResponseEntity<BookDto> updateBook(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody Book book) {
        requireAdmin(principal, "REQ-COM-040", String.valueOf(book.getPrice()));
        securityLabService.simulate("REQ-COM-040", principal.getUserId(), "/api/admin/books/" + id, "update");
        return ResponseEntity.ok(adminService.updateBook(id, book));
    }

    @DeleteMapping("/books/{id}")
    public ResponseEntity<Void> deleteBook(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        requireAdmin(principal, "REQ-COM-041", String.valueOf(id));
        securityLabService.simulate("REQ-COM-041", principal.getUserId(), "/api/admin/books/" + id, "delete");
        adminService.deleteBook(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/books/stock")
    public ResponseEntity<List<BookDto>> stock(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String isbn) {
        requireAdmin(principal, "REQ-COM-045", (author == null ? "" : author) + "|" + (isbn == null ? "" : isbn));
        securityLabService.simulate("REQ-COM-045", principal.getUserId(), "/api/admin/books/stock", (author == null ? "" : author) + (isbn == null ? "" : isbn));
        return ResponseEntity.ok(adminService.getStockByFilter(author, isbn));
    }

    @PostMapping("/books/inbound")
    public ResponseEntity<BookDto> inbound(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> request) {
        requireAdmin(principal, "REQ-COM-046", String.valueOf(request));
        String isbn = request.get("isbn") == null ? null : String.valueOf(request.get("isbn"));
        Integer quantity = request.get("quantity") == null ? null : Integer.valueOf(String.valueOf(request.get("quantity")));
        securityLabService.simulate("REQ-COM-046", principal.getUserId(), "/api/admin/books/inbound", isbn);
        return ResponseEntity.ok(adminService.inboundBook(isbn, quantity));
    }

    @PutMapping("/books/{id}/stock")
    public ResponseEntity<BookDto> updateStock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        requireAdmin(principal, "REQ-COM-047", String.valueOf(request));
        Integer stock = request.get("stock") == null ? null : Integer.valueOf(String.valueOf(request.get("stock")));
        securityLabService.simulate("REQ-COM-047", principal.getUserId(), "/api/admin/books/" + id + "/stock", String.valueOf(stock));
        return ResponseEntity.ok(adminService.updateBookStock(id, stock));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<OrderDto>> getOrders(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-042", "list");
        return ResponseEntity.ok(adminService.getAllOrders());
    }

    @PutMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderDto> updateOrderStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long orderId,
            @RequestBody Map<String, String> request) {
        requireAdmin(principal, "REQ-COM-042", String.valueOf(request));
        String status = request != null ? request.get("status") : null;
        securityLabService.simulate("REQ-COM-042", principal.getUserId(), "/api/admin/orders/" + orderId + "/status", status);
        return ResponseEntity.ok(adminService.updateOrderStatus(orderId, status));
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> getUsers(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-043", "list");
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @PutMapping("/users/{userId}/status")
    public ResponseEntity<UserDto> updateUserStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        requireAdmin(principal, "REQ-COM-043", String.valueOf(request));
        String status = request != null ? request.get("status") : null;
        String role = request != null ? request.get("role") : null;
        securityLabService.simulate("REQ-COM-043", principal.getUserId(), "/api/admin/users/" + userId + "/status", String.valueOf(request));
        return ResponseEntity.ok(adminService.updateUserStatus(userId, status, role));
    }

    @GetMapping("/coupons")
    public ResponseEntity<List<Coupon>> getCoupons(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-019", "coupons");
        return ResponseEntity.ok(adminService.getAllCoupons());
    }

    @PostMapping("/coupons")
    public ResponseEntity<Coupon> createCoupon(@AuthenticationPrincipal UserPrincipal principal, @RequestBody Coupon coupon) {
        requireAdmin(principal, "REQ-COM-019", coupon.getCode());
        return ResponseEntity.ok(adminService.createCoupon(coupon));
    }

    @GetMapping("/customer-service")
    public ResponseEntity<List<CustomerService>> getCustomerService(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-044", "inquiries");
        return ResponseEntity.ok(adminService.getAllCustomerService());
    }

    @PostMapping("/customer-service/{inquiryId}/reply")
    public ResponseEntity<CustomerService> reply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long inquiryId,
            @RequestBody Map<String, String> request) {
        requireAdmin(principal, "REQ-COM-044", String.valueOf(request));
        String answer = request != null ? request.get("answer") : null;
        securityLabService.simulate("REQ-COM-044", principal.getUserId(), "/api/admin/customer-service/" + inquiryId + "/reply", answer);
        return ResponseEntity.ok(adminService.replyInquiry(inquiryId, answer));
    }

    @PostMapping("/notices")
    public ResponseEntity<NoticeDto> createNotice(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> request) {
        requireAdmin(principal, "REQ-COM-024", String.valueOf(request));
        String title = request != null ? request.get("title") : null;
        String content = request != null ? request.get("content") : null;
        return ResponseEntity.ok(learningFeatureService.createNotice(principal.getUserId(), title, content));
    }

    @GetMapping("/logs")
    public ResponseEntity<List<AccessLog>> getLogs(@AuthenticationPrincipal UserPrincipal principal) {
        requireAdmin(principal, "REQ-COM-039", "logs");
        return ResponseEntity.ok(adminService.getAllLogs());
    }

    private void requireAdmin(UserPrincipal principal, String reqId, String input) {
        if (principal == null || !"ADMIN".equalsIgnoreCase(principal.getRole())) {
            securityLabService.simulate(reqId, principal != null ? principal.getUserId() : null, "/api/admin", input);
            throw new AccessDeniedException("Admin role required");
        }
    }
}
