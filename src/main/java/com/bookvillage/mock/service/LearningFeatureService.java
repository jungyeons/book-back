package com.bookvillage.mock.service;

import com.bookvillage.mock.dto.*;
import com.bookvillage.mock.entity.Book;
import com.bookvillage.mock.entity.Order;
import com.bookvillage.mock.entity.User;
import com.bookvillage.mock.repository.BookRepository;
import com.bookvillage.mock.repository.OrderRepository;
import com.bookvillage.mock.repository.ReviewRepository;
import com.bookvillage.mock.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LearningFeatureService {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityLabService securityLabService;

    @Transactional
    public Map<String, Object> requestPasswordReset(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalized).orElse(null);
        // Intentionally weak token entropy for brute-force training.
        String token = String.format(Locale.ROOT, "%04d", new Random().nextInt(10_000));

        jdbcTemplate.update(
                "INSERT INTO password_reset_tokens (user_id, email, token, attempt_count, expires_at) VALUES (?, ?, ?, 0, ?)",
                user != null ? user.getId() : null,
                normalized,
                token,
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(10))
        );

        String masked = maskEmail(normalized);
        securityLabService.simulate("REQ-COM-004", user != null ? user.getId() : null, "/api/auth/password-reset/request", normalized);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Password reset requested. In this lab flow, mailbox ownership is not verified.");
        response.put("email", masked);
        return response;
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void confirmPasswordReset(Long userId, String email, String token, String newPassword) {
        if (token == null || newPassword == null) {
            throw new IllegalArgumentException("token and newPassword are required");
        }
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("newPassword must be at least 8 characters");
        }

        Long targetUserId = userId;
        if (targetUserId == null && email != null && !email.trim().isEmpty()) {
            User byEmail = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            targetUserId = byEmail.getId();
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("userId or email is required");
        }

        // Intentionally vulnerable logic:
        // only token comparison is used, no mailbox ownership verification.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, user_id, token, attempt_count, expires_at, used_at " +
                        "FROM password_reset_tokens WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                targetUserId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No reset request found");
        }

        Map<String, Object> latest = rows.get(0);
        String storedToken = (String) latest.get("token");
        Integer attemptCount = ((Number) latest.get("attempt_count")).intValue();
        Long tokenId = ((Number) latest.get("id")).longValue();

        if (!storedToken.equals(token)) {
            jdbcTemplate.update("UPDATE password_reset_tokens SET attempt_count = ? WHERE id = ?", attemptCount + 1, tokenId);
            securityLabService.simulate("REQ-COM-004", targetUserId, "/api/auth/password-reset/confirm", "user_id=" + targetUserId + ", token=" + token);
            throw new IllegalArgumentException("Invalid reset token");
        }

        User user = userRepository.findById(targetUserId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        jdbcTemplate.update("UPDATE password_reset_tokens SET used_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now()), tokenId);
    }

    public Map<String, Object> findId(String name, String email) {
        if (name == null || name.trim().isEmpty() || email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("name and email are required");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalizedEmail)
                .filter(u -> name.trim().equals(u.getName()))
                .orElseThrow(() -> new IllegalArgumentException("No matching user"));

        // Intentionally vulnerable for Info Disclosure training:
        // returns full account identifier without ownership verification.
        String exposedId = user.getUsername();
        jdbcTemplate.update(
                "INSERT INTO id_lookup_logs (name, email, masked_result) VALUES (?, ?, ?)",
                name.trim(),
                normalizedEmail,
                exposedId
        );
        securityLabService.simulate("REQ-COM-005", user.getId(), "/api/auth/find-id", normalizedEmail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("foundId", exposedId);
        result.put("message", "ID lookup completed. In this lab flow, account IDs are disclosed directly.");
        return result;
    }

    public void logout(Long userId) {
        if (userId == null) {
            return;
        }
        String sessionKey = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO user_sessions (user_id, session_key, active, invalidated_at) VALUES (?, ?, FALSE, ?)",
                userId,
                sessionKey,
                Timestamp.valueOf(LocalDateTime.now())
        );
        securityLabService.simulate("REQ-COM-007", userId, "/api/auth/logout", sessionKey);
    }

    public List<Map<String, Object>> searchAddress(Long userId, String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        LabSimulationResponse simulation = securityLabService.simulate("REQ-COM-008", userId, "/api/users/me/address-search", query);
        jdbcTemplate.update(
                "INSERT INTO address_search_logs (user_id, query_text, simulated_warning) VALUES (?, ?, ?)",
                userId,
                query,
                simulation.isTriggered() ? simulation.getSimulatedResult() : null
        );

        List<Map<String, Object>> results = new ArrayList<>();
        results.add(addressRow("06234", "Seoul Gangnam-daero", "101"));
        results.add(addressRow("04159", "Seoul Mapo Worldcup-ro", "55"));
        return results;
    }

    private Map<String, Object> addressRow(String zip, String street, String number) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("zipcode", zip);
        row.put("street", street);
        row.put("number", number);
        return row;
    }

    public void trackRecentView(Long userId, Long bookId) {
        if (userId == null || bookId == null) {
            return;
        }
        jdbcTemplate.update("INSERT INTO recent_views (user_id, book_id) VALUES (?, ?)", userId, bookId);
        securityLabService.logEvent("REQ-COM-028", userId, "/api/books/" + bookId, "TRACK", String.valueOf(bookId), "recent view saved");
    }

    public List<Map<String, Object>> getRecentViews(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT rv.id, rv.book_id AS bookId, b.title, b.author, rv.viewed_at AS viewedAt " +
                        "FROM recent_views rv JOIN books b ON rv.book_id = b.id WHERE rv.user_id = ? ORDER BY rv.viewed_at DESC LIMIT 20",
                userId
        );
    }

    public List<Map<String, Object>> getWishlist(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT w.id, w.book_id AS bookId, b.title, b.author, b.price, w.created_at AS createdAt " +
                        "FROM wishlist_items w JOIN books b ON b.id = w.book_id WHERE w.user_id = ? ORDER BY w.created_at DESC",
                userId
        );
    }

    @Transactional
    public void addWishlist(Long userId, Long bookId) {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId is required");
        }
        if (!bookRepository.existsById(bookId)) {
            throw new IllegalArgumentException("Book not found");
        }
        jdbcTemplate.update("INSERT IGNORE INTO wishlist_items (user_id, book_id) VALUES (?, ?)", userId, bookId);
        securityLabService.logEvent("REQ-COM-029", userId, "/api/mypage/wishlist", "CREATE", String.valueOf(bookId), "wishlist item added");
    }

    @Transactional
    public void deleteWishlist(Long userId, Long wishlistId) {
        List<Map<String, Object>> row = jdbcTemplate.queryForList("SELECT user_id FROM wishlist_items WHERE id = ?", wishlistId);
        if (row.isEmpty()) {
            throw new IllegalArgumentException("Wishlist item not found");
        }
        Long ownerId = ((Number) row.get(0).get("user_id")).longValue();
        if (!ownerId.equals(userId)) {
            securityLabService.simulate("REQ-COM-029", userId, "/api/mypage/wishlist/" + wishlistId, String.valueOf(wishlistId));
            throw new IllegalArgumentException("Cannot delete another user's wishlist item");
        }
        jdbcTemplate.update("DELETE FROM wishlist_items WHERE id = ?", wishlistId);
    }

    public Map<String, Object> getWallet(Long userId) {
        Integer points = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM point_histories WHERE user_id = ?",
                Integer.class,
                userId
        );
        List<Map<String, Object>> histories = jdbcTemplate.queryForList(
                "SELECT change_type AS changeType, amount, balance_after AS balanceAfter, description, created_at AS createdAt " +
                        "FROM point_histories WHERE user_id = ? ORDER BY created_at DESC",
                userId
        );
        List<Map<String, Object>> coupons = jdbcTemplate.queryForList(
                "SELECT code, discount_type AS discountType, discount_value AS discountValue, remaining_count AS remainingCount " +
                        "FROM coupons ORDER BY id ASC"
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentPoints", points != null ? points : 0);
        data.put("pointHistories", histories);
        data.put("coupons", coupons);
        return data;
    }

    public Map<String, Object> getMypageSummary(Long userId) {
        Integer recentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recent_views WHERE user_id = ?",
                Integer.class,
                userId
        );
        Integer wishlistCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishlist_items WHERE user_id = ?",
                Integer.class,
                userId
        );
        Integer currentPoints = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM point_histories WHERE user_id = ?",
                Integer.class,
                userId
        );
        Integer pointHistoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_histories WHERE user_id = ?",
                Integer.class,
                userId
        );
        Integer couponCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE remaining_count > 0",
                Integer.class
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recentCount", recentCount != null ? recentCount : 0);
        summary.put("wishlistCount", wishlistCount != null ? wishlistCount : 0);
        summary.put("currentPoints", currentPoints != null ? currentPoints : 0);
        summary.put("pointHistoryCount", pointHistoryCount != null ? pointHistoryCount : 0);
        summary.put("couponCount", couponCount != null ? couponCount : 0);
        return summary;
    }

    @Transactional
    public void requestOrderCancel(Long userId, Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Cannot cancel another user's order");
        }

        if (!"PENDING".equalsIgnoreCase(order.getStatus()) && !"PAID".equalsIgnoreCase(order.getStatus())) {
            securityLabService.simulate("REQ-COM-032", userId, "/api/mypage/orders/" + orderId + "/cancel", order.getStatus());
            throw new IllegalArgumentException("Only paid or pending orders can be canceled");
        }

        jdbcTemplate.update(
                "INSERT INTO order_action_requests (order_id, user_id, action_type, reason, status) VALUES (?, ?, 'CANCEL', ?, 'REQUESTED')",
                orderId,
                userId,
                reason
        );
        order.setStatus("CANCEL_REQUESTED");
        orderRepository.save(order);
    }

    @Transactional
    public void requestOrderReturn(Long userId, Long orderId, String reason, String proofFileName) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Cannot create return request for another user");
        }
        if (!"SHIPPED".equalsIgnoreCase(order.getStatus()) && !"DELIVERED".equalsIgnoreCase(order.getStatus())) {
            securityLabService.simulate("REQ-COM-033", userId, "/api/mypage/orders/" + orderId + "/return", order.getStatus());
            throw new IllegalArgumentException("Only shipped or delivered orders can request return");
        }
        jdbcTemplate.update(
                "INSERT INTO order_action_requests (order_id, user_id, action_type, reason, proof_file_name, status) VALUES (?, ?, 'RETURN', ?, ?, 'REQUESTED')",
                orderId,
                userId,
                reason,
                proofFileName
        );
        order.setStatus("RETURN_REQUESTED");
        orderRepository.save(order);
        securityLabService.simulate("REQ-COM-033", userId, "/api/mypage/orders/" + orderId + "/return", proofFileName);
    }

    @Transactional
    public void requestOrderExchange(Long userId, Long orderId, String reason, String proofFileName) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Cannot create exchange request for another user");
        }
        if (!"SHIPPED".equalsIgnoreCase(order.getStatus()) && !"DELIVERED".equalsIgnoreCase(order.getStatus())) {
            securityLabService.simulate("REQ-COM-033", userId, "/api/mypage/orders/" + orderId + "/exchange", order.getStatus());
            throw new IllegalArgumentException("Only shipped or delivered orders can request exchange");
        }
        jdbcTemplate.update(
                "INSERT INTO order_action_requests (order_id, user_id, action_type, reason, proof_file_name, status) VALUES (?, ?, 'EXCHANGE', ?, ?, 'REQUESTED')",
                orderId,
                userId,
                reason,
                proofFileName
        );
        order.setStatus("EXCHANGE_REQUESTED");
        orderRepository.save(order);
        securityLabService.simulate("REQ-COM-033", userId, "/api/mypage/orders/" + orderId + "/exchange", proofFileName);
    }

    public List<Map<String, Object>> getFavoritePosts(Long userId, boolean includePrivate) {
        if (includePrivate) {
            return jdbcTemplate.queryForList(
                    "SELECT id, post_title AS postTitle, is_private AS isPrivate, created_at AS createdAt FROM favorite_posts WHERE user_id = ? ORDER BY created_at DESC",
                    userId
            );
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, post_title AS postTitle, is_private AS isPrivate, created_at AS createdAt FROM favorite_posts WHERE user_id = ? ORDER BY created_at DESC",
                userId
        );
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("isPrivate"))) {
                row.put("postTitle", "[PRIVATE] Hidden post title");
            }
        }
        securityLabService.simulate("REQ-COM-035", userId, "/api/mypage/favorite-posts", "includePrivate=" + includePrivate);
        return rows;
    }

    public void deleteFavoritePost(Long userId, Long postId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT user_id FROM favorite_posts WHERE id = ?", postId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Favorite post not found");
        }
        Long ownerId = ((Number) rows.get(0).get("user_id")).longValue();
        if (!ownerId.equals(userId)) {
            securityLabService.simulate("REQ-COM-034", userId, "/api/mypage/favorite-posts/" + postId, String.valueOf(postId));
            throw new IllegalArgumentException("Cannot delete another user's favorite post");
        }
        jdbcTemplate.update("DELETE FROM favorite_posts WHERE id = ?", postId);
    }

    public List<NoticeDto> getNotices(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        List<NoticeDto> notices = jdbcTemplate.query(
                "SELECT id, title, content, author_id, created_at FROM notices WHERE (? = '' OR title LIKE CONCAT('%', ?, '%') OR content LIKE CONCAT('%', ?, '%')) ORDER BY created_at DESC",
                (rs, rowNum) -> {
                    NoticeDto dto = new NoticeDto();
                    dto.setId(rs.getLong("id"));
                    dto.setTitle(rs.getString("title"));
                    dto.setContent(rs.getString("content"));
                    dto.setAuthorId(rs.getObject("author_id") != null ? rs.getLong("author_id") : null);
                    Timestamp created = rs.getTimestamp("created_at");
                    dto.setCreatedAt(created != null ? created.toLocalDateTime() : null);
                    return dto;
                },
                kw,
                kw,
                kw
        );
        securityLabService.simulate("REQ-COM-023", null, "/api/notices", kw);
        return notices;
    }

    public NoticeDto getNotice(Long noticeId) {
        NoticeDto dto = jdbcTemplate.queryForObject(
                "SELECT id, title, content, author_id, created_at FROM notices WHERE id = ?",
                (rs, rowNum) -> {
                    NoticeDto row = new NoticeDto();
                    row.setId(rs.getLong("id"));
                    row.setTitle(rs.getString("title"));
                    row.setContent(rs.getString("content"));
                    row.setAuthorId(rs.getObject("author_id") != null ? rs.getLong("author_id") : null);
                    Timestamp created = rs.getTimestamp("created_at");
                    row.setCreatedAt(created != null ? created.toLocalDateTime() : null);
                    return row;
                },
                noticeId
        );
        securityLabService.simulate("REQ-COM-024", null, "/api/notices/" + noticeId, dto != null ? dto.getContent() : null);
        return dto;
    }

    public NoticeDto createNotice(Long adminUserId, String title, String content) {
        if (title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("title and content are required");
        }
        jdbcTemplate.update(
                "INSERT INTO notices (title, content, author_id) VALUES (?, ?, ?)",
                title.trim(),
                content.trim(),
                adminUserId
        );
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM notices", Long.class);
        return getNotice(id);
    }

    public List<FaqDto> getFaqs(String category) {
        String value = category == null ? "" : category.trim();
        List<FaqDto> faqs = jdbcTemplate.query(
                "SELECT id, category, question, answer, display_order FROM faqs WHERE (? = '' OR category = ?) ORDER BY display_order ASC, id ASC",
                (rs, rowNum) -> {
                    FaqDto dto = new FaqDto();
                    dto.setId(rs.getLong("id"));
                    dto.setCategory(rs.getString("category"));
                    dto.setQuestion(rs.getString("question"));
                    dto.setAnswer(rs.getString("answer"));
                    dto.setDisplayOrder(rs.getInt("display_order"));
                    return dto;
                },
                value,
                value
        );
        securityLabService.simulate("REQ-COM-027", null, "/api/faqs", value);
        return faqs;
    }

    public Map<String, Object> saveInquiryAttachment(Long userId, Long inquiryId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        List<Map<String, Object>> ownerRows = jdbcTemplate.queryForList("SELECT user_id FROM customer_service WHERE id = ?", inquiryId);
        if (ownerRows.isEmpty()) {
            throw new IllegalArgumentException("Inquiry not found");
        }
        Long ownerId = ownerRows.get(0).get("user_id") != null ? ((Number) ownerRows.get(0).get("user_id")).longValue() : null;
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new IllegalArgumentException("Cannot attach files to another user's inquiry");
        }

        boolean safe = isSafeAttachment(file.getOriginalFilename(), file.getContentType());
        jdbcTemplate.update(
                "INSERT INTO customer_service_attachments (inquiry_id, file_name, file_type, file_size, safe_stored) VALUES (?, ?, ?, ?, ?)",
                inquiryId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                safe
        );
        securityLabService.simulate("REQ-COM-026", userId, "/api/customer-service/" + inquiryId + "/attachments", file.getOriginalFilename());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("safeStored", safe);
        result.put("message", safe
                ? "Attachment metadata stored for training."
                : "Suspicious extension detected. File was not executed and only training log was recorded.");
        return result;
    }

    private boolean isSafeAttachment(String filename, String contentType) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean safeExt = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp");
        boolean safeMime = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
        return safeExt && safeMime;
    }

    public List<CartItemDto> getCart(Long userId) {
        return jdbcTemplate.query(
                "SELECT c.id, c.book_id, b.title, c.quantity, c.unit_price FROM cart_items c JOIN books b ON c.book_id = b.id WHERE c.user_id = ? ORDER BY c.id DESC",
                (rs, rowNum) -> {
                    CartItemDto dto = new CartItemDto();
                    dto.setId(rs.getLong("id"));
                    dto.setBookId(rs.getLong("book_id"));
                    dto.setTitle(rs.getString("title"));
                    dto.setQuantity(rs.getInt("quantity"));
                    dto.setUnitPrice(rs.getBigDecimal("unit_price"));
                    dto.setLineTotal(dto.getUnitPrice().multiply(BigDecimal.valueOf(dto.getQuantity())));
                    return dto;
                },
                userId
        );
    }

    @Transactional
    public void addCartItem(Long userId, Long bookId, Integer quantity, BigDecimal providedPrice) {
        if (bookId == null || quantity == null) {
            throw new IllegalArgumentException("bookId and quantity are required");
        }
        if (quantity <= 0) {
            securityLabService.simulate("REQ-COM-017", userId, "/api/cart", String.valueOf(quantity));
            throw new IllegalArgumentException("Quantity must be positive");
        }
        Book book = bookRepository.findById(bookId).orElseThrow(() -> new IllegalArgumentException("Book not found"));

        if (providedPrice != null && providedPrice.compareTo(book.getPrice()) != 0) {
            securityLabService.simulate("REQ-COM-016", userId, "/api/cart", String.valueOf(providedPrice));
        }

        jdbcTemplate.update(
                "INSERT INTO cart_items (user_id, book_id, quantity, unit_price) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity), unit_price = VALUES(unit_price)",
                userId,
                bookId,
                quantity,
                book.getPrice()
        );
    }

    @Transactional
    public void updateCartItem(Long userId, Long cartItemId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            securityLabService.simulate("REQ-COM-017", userId, "/api/cart/" + cartItemId, String.valueOf(quantity));
            throw new IllegalArgumentException("Quantity must be positive");
        }
        int updated = jdbcTemplate.update("UPDATE cart_items SET quantity = ? WHERE id = ? AND user_id = ?", quantity, cartItemId, userId);
        if (updated == 0) {
            throw new IllegalArgumentException("Cart item not found");
        }
    }

    public void deleteCartItem(Long userId, Long cartItemId) {
        jdbcTemplate.update("DELETE FROM cart_items WHERE id = ? AND user_id = ?", cartItemId, userId);
    }

    public void clearCart(Long userId) {
        jdbcTemplate.update("DELETE FROM cart_items WHERE user_id = ?", userId);
    }

    public Map<String, Object> trackOrder(Long userId, Long orderId, String trackingUrl) {
        if (orderId == null || trackingUrl == null || trackingUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("orderId and trackingUrl are required");
        }
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Cannot track another user's order");
        }

        LabSimulationResponse simulation = securityLabService.simulate("REQ-COM-022", userId, "/api/orders/" + orderId + "/tracking", trackingUrl);

        String status = simulation.isTriggered() ? "BLOCKED" : "IN_TRANSIT";
        jdbcTemplate.update(
                "INSERT INTO shipping_tracking_logs (order_id, user_id, tracking_url, simulated_status) VALUES (?, ?, ?, ?)",
                orderId,
                userId,
                trackingUrl,
                status
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("status", status);
        response.put("currentLocation", simulation.isTriggered() ? "N/A (blocked)" : "Seoul Hub");
        response.put("eta", simulation.isTriggered() ? "unknown" : "1 business day");
        response.put("simulation", simulation);
        return response;
    }

    public LinkPreviewDto createLinkPreview(Long userId, String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        LabSimulationResponse simulation = securityLabService.simulate("REQ-COM-048", userId, "/api/integration/link-preview", url);

        boolean blocked = simulation.isTriggered();
        LinkPreviewDto dto = new LinkPreviewDto();
        dto.setUrl(url);
        dto.setStatus(blocked ? "BLOCKED" : "OK");

        if (blocked) {
            dto.setTitle("Blocked by SSRF training policy");
            dto.setThumbnailUrl(null);
        } else {
            String host = extractHost(url);
            dto.setTitle("Preview: " + host);
            dto.setThumbnailUrl("https://dummyimage.com/320x180/efefef/555555.png&text=" + host.replace(".", "_"));
        }

        jdbcTemplate.update(
                "INSERT INTO link_previews (user_id, url, title, thumbnail_url, status) VALUES (?, ?, ?, ?, ?)",
                userId,
                url,
                dto.getTitle(),
                dto.getThumbnailUrl(),
                dto.getStatus()
        );
        return dto;
    }

    private String extractHost(String url) {
        String trimmed = url.trim();
        String noProtocol = trimmed.replaceFirst("^https?://", "");
        int slash = noProtocol.indexOf('/');
        return slash >= 0 ? noProtocol.substring(0, slash) : noProtocol;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(0, at));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
