package com.bookvillage.backend.service;

import com.bookvillage.backend.common.ApiException;
import com.bookvillage.backend.common.PageResponse;
import com.bookvillage.backend.model.AccessLogEntry;
import com.bookvillage.backend.model.Coupon;
import com.bookvillage.backend.model.CustomerServiceInquiry;
import com.bookvillage.backend.model.Customer;
import com.bookvillage.backend.model.DeliveryInfo;
import com.bookvillage.backend.model.InventoryLog;
import com.bookvillage.backend.model.InventoryProduct;
import com.bookvillage.backend.model.Order;
import com.bookvillage.backend.model.OrderItem;
import com.bookvillage.backend.model.Product;
import com.bookvillage.backend.model.Review;
import com.bookvillage.backend.request.InventoryAdjustRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class InMemoryDataStore {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<List<String>>() {};
    private static final Pattern DATA_URL_IMAGE_PATTERN = Pattern.compile("^data:image/([a-zA-Z0-9.+-]+);base64,(.+)$", Pattern.DOTALL);

    private static final String STATUS_ON_SALE = "판매중";
    private static final String STATUS_OUT_OF_STOCK = "품절";

    private static final String PAYMENT_PENDING = "결제대기";
    private static final String PAYMENT_DONE = "결제완료";
    private static final String PAYMENT_CANCELED = "결제취소";

    private static final String FULFILLMENT_RECEIVED = "주문접수";
    private static final String FULFILLMENT_PREPARING = "상품준비중";
    private static final String FULFILLMENT_SHIPPING = "배송중";
    private static final String FULFILLMENT_DELIVERED = "배송완료";
    private static final String FULFILLMENT_CANCELED = "주문취소";

    private static final String INVENTORY_IN = "입고";
    private static final String INVENTORY_OUT = "출고";
    private static final String INVENTORY_ADJUST = "조정";

    private static final String REVIEW_PUBLIC = "공개";
    private static final String REVIEW_HIDDEN = "숨김";

    private static final String COUPON_ACTIVE = "활성";
    private static final String COUPON_INACTIVE = "비활성";
    private static final String COUPON_EXPIRED = "만료";

    private static final String GRADE_NORMAL = "일반";
    private static final String GRADE_VIP = "VIP";
    private static final String GRADE_VVIP = "VVIP";

    private static final String CUSTOMER_STATUS_ACTIVE = "활성";
    private static final String CUSTOMER_STATUS_SUSPENDED = "정지";

    private static final String CUSTOMER_ROLE_GENERAL = "일반회원";
    private static final String CUSTOMER_ROLE_VIP = "VIP회원";
    private static final String CUSTOMER_ROLE_VVIP = "VVIP회원";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final com.bookvillage.mock.service.S3StorageService s3StorageService;
    private final Path adminImageDir = Paths.get("uploads", "admin-products").toAbsolutePath().normalize();

    public InMemoryDataStore(JdbcTemplate jdbcTemplate, com.bookvillage.mock.service.S3StorageService s3StorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        this.s3StorageService = s3StorageService;
    }

    @PostConstruct
    public synchronized void init() {
        ensureSupportTables();
    }

    public synchronized PageResponse<Product> getProducts(
            String keyword,
            String status,
            String category,
            int page,
            int pageSize
    ) {
        List<Product> rows = loadProducts();

        String keywordNorm = lower(keyword);
        String statusNorm = trim(status);
        String categoryNorm = trim(category);

        List<Product> filtered = rows.stream()
                .filter(product -> keywordNorm.isEmpty()
                        || containsLower(product.title, keywordNorm)
                        || containsLower(product.author, keywordNorm)
                        || containsLower(product.isbn13, keywordNorm))
                .filter(product -> statusNorm.isEmpty() || statusNorm.equals(product.status))
                .filter(product -> categoryNorm.isEmpty() || categoryNorm.equals(product.category))
                .sorted(Comparator.comparingLong((Product p) -> parseLong(p.id, 0L)).reversed())
                .collect(Collectors.toList());

        return paginate(filtered, page, pageSize);
    }

    public synchronized Product getProduct(String id) {
        long productId = requireNumericId(id, "상품을 찾을 수 없습니다.");
        String sql = "SELECT b.id, b.isbn, b.title, b.author, b.publisher, b.category, b.price, b.stock, "
                + "b.description, b.cover_image_url, b.created_at, "
                + "m.subtitle, m.published_date, m.sale_price, m.status AS meta_status, m.tags_json, m.images_json "
                + "FROM books b LEFT JOIN admin_book_meta m ON m.book_id = b.id WHERE b.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, productId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        return mapProduct(rows.get(0));
    }

    public synchronized Product createProduct(Product payload) {
        if (payload == null || isBlank(payload.title)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "상품명을 입력해주세요.");
        }

        if (payload.price <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "가격은 1 이상이어야 합니다.");
        }

        int stock = Math.max(0, payload.stock);
        String isbn = trimToNull(payload.isbn13);
        List<String> normalizedImages = normalizeProductImages(payload.images);
        String coverImageUrl = firstImage(normalizedImages);

        KeyHolder keyHolder = new GeneratedKeyHolder();

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO books (isbn, title, author, publisher, category, price, stock, description, cover_image_url) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, isbn);
                ps.setString(2, trim(payload.title));
                ps.setString(3, trimToNull(payload.author));
                ps.setString(4, trimToNull(payload.publisher));
                ps.setString(5, trimToNull(payload.category));
                ps.setBigDecimal(6, BigDecimal.valueOf(payload.price));
                ps.setInt(7, stock);
                ps.setString(8, trimToNull(payload.description));
                ps.setString(9, coverImageUrl);
                return ps;
            }, keyHolder);
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "상품 생성에 실패했습니다.");
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "상품 생성 후 ID를 확인할 수 없습니다.");
        }

        long bookId = key.longValue();
        upsertBookMeta(
                bookId,
                trimToNull(payload.subtitle),
                trimToNull(payload.publishedDate),
                payload.salePrice,
                normalizeProductStatus(payload.status, stock),
                sanitizeStringList(payload.tags),
                normalizedImages
        );

        return getProduct(String.valueOf(bookId));
    }

    public synchronized Product updateProduct(String id, Map<String, Object> patch) {
        Product existing = getProduct(id);
        long bookId = requireNumericId(existing.id, "상품을 찾을 수 없습니다.");

        String title = asString(patch, "title", existing.title);
        if (isBlank(title)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "상품명을 입력해주세요.");
        }

        int price = Math.max(0, asInt(patch, "price", existing.price));
        if (price <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "가격은 1 이상이어야 합니다.");
        }

        int stock = Math.max(0, asInt(patch, "stock", existing.stock));

        String isbn13 = asNullableString(patch, "isbn13", existing.isbn13);
        String author = asNullableString(patch, "author", existing.author);
        String publisher = asNullableString(patch, "publisher", existing.publisher);
        String category = asNullableString(patch, "category", existing.category);
        String description = asNullableString(patch, "description", existing.description);

        List<String> images = patch != null && patch.containsKey("images")
                ? asStringList(patch.get("images"), existing.images)
                : sanitizeStringList(existing.images);
        List<String> normalizedImages = normalizeProductImages(images);
        String coverImageUrl = firstImage(normalizedImages);

        jdbcTemplate.update(
                "UPDATE books SET isbn = ?, title = ?, author = ?, publisher = ?, category = ?, price = ?, stock = ?, "
                        + "description = ?, cover_image_url = ? WHERE id = ?",
                trimToNull(isbn13),
                trim(title),
                trimToNull(author),
                trimToNull(publisher),
                trimToNull(category),
                BigDecimal.valueOf(price),
                stock,
                trimToNull(description),
                trimToNull(coverImageUrl),
                bookId
        );

        String subtitle = asNullableString(patch, "subtitle", existing.subtitle);
        String publishedDate = asNullableString(patch, "publishedDate", existing.publishedDate);
        Integer salePrice = patch != null && patch.containsKey("salePrice")
                ? asNullableInt(patch.get("salePrice"))
                : existing.salePrice;
        String status = asNullableString(patch, "status", existing.status);
        status = normalizeProductStatus(status, stock);

        List<String> tags = patch != null && patch.containsKey("tags")
                ? asStringList(patch.get("tags"), existing.tags)
                : sanitizeStringList(existing.tags);

        upsertBookMeta(bookId, subtitle, publishedDate, salePrice, status, tags, normalizedImages);
        return getProduct(String.valueOf(bookId));
    }

    public synchronized void deleteProducts(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        List<Long> numericIds = ids.stream()
                .map(id -> parseLong(id, -1L))
                .filter(value -> value > 0)
                .collect(Collectors.toList());

        if (numericIds.isEmpty()) {
            return;
        }

        String placeholders = numericIds.stream().map(v -> "?").collect(Collectors.joining(","));

        try {
            jdbcTemplate.update("DELETE FROM admin_book_meta WHERE book_id IN (" + placeholders + ")", numericIds.toArray());
            jdbcTemplate.update("DELETE FROM books WHERE id IN (" + placeholders + ")", numericIds.toArray());
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "주문 이력이 있는 상품은 삭제할 수 없습니다.");
        }
    }

    public synchronized PageResponse<Order> getOrders(
            String keyword,
            String paymentStatus,
            String fulfillmentStatus,
            String startDate,
            String endDate,
            int page,
            int pageSize
    ) {
        List<Order> all = loadOrders(false);

        String keywordNorm = lower(keyword);
        String paymentNorm = trim(paymentStatus);
        String fulfillmentNorm = trim(fulfillmentStatus);
        String startNorm = trim(startDate);
        String endNorm = trim(endDate);

        List<Order> filtered = all.stream()
                .filter(order -> keywordNorm.isEmpty()
                        || containsLower(order.orderNumber, keywordNorm)
                        || containsLower(order.customerName, keywordNorm))
                .filter(order -> paymentNorm.isEmpty() || paymentNorm.equals(order.paymentStatus))
                .filter(order -> fulfillmentNorm.isEmpty() || fulfillmentNorm.equals(order.fulfillmentStatus))
                .filter(order -> startNorm.isEmpty() || order.createdAt.compareTo(startNorm) >= 0)
                .filter(order -> endNorm.isEmpty() || order.createdAt.compareTo(endNorm) <= 0)
                .collect(Collectors.toList());

        return paginate(filtered, page, pageSize);
    }

    public synchronized Order getOrder(String id) {
        long orderId = requireNumericId(id, "주문을 찾을 수 없습니다.");

        String sql = "SELECT o.id, o.order_number, o.user_id, o.total_amount, o.status, o.shipping_address, o.created_at, "
                + "u.name AS customer_name, u.phone AS customer_phone, u.address AS customer_address "
                + "FROM orders o LEFT JOIN users u ON u.id = o.user_id WHERE o.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, orderId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }

        return mapOrder(rows.get(0), true);
    }

    public synchronized Order updateOrderStatus(String id, String paymentStatus, String fulfillmentStatus) {
        long orderId = requireNumericId(id, "주문을 찾을 수 없습니다.");
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM orders WHERE id = ?", Integer.class, orderId);
        if (exists == null || exists == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }

        String targetDbStatus = toDbOrderStatus(paymentStatus, fulfillmentStatus);
        jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", targetDbStatus, orderId);

        return getOrder(String.valueOf(orderId));
    }

    public synchronized PageResponse<Customer> getCustomers(
            String keyword,
            String grade,
            String status,
            String role,
            int page,
            int pageSize
    ) {
        List<Customer> all = loadCustomers();

        String keywordNorm = lower(keyword);
        String gradeNorm = trim(grade);
        String statusNorm = trim(status);
        String roleNorm = trim(role);

        List<Customer> filtered = all.stream()
                .filter(customer -> keywordNorm.isEmpty()
                        || containsLower(customer.name, keywordNorm)
                        || containsLower(customer.email, keywordNorm))
                .filter(customer -> gradeNorm.isEmpty() || gradeNorm.equals(customer.grade))
                .filter(customer -> statusNorm.isEmpty() || statusNorm.equals(customer.status))
                .filter(customer -> roleNorm.isEmpty() || roleNorm.equals(customer.memberRole))
                .collect(Collectors.toList());

        return paginate(filtered, page, pageSize);
    }

    public synchronized Customer getCustomer(String id) {
        long userId = requireNumericId(id, "고객을 찾을 수 없습니다.");

        String sql = "SELECT u.id, u.name, u.email, u.phone, u.created_at, "
                + "m.status AS member_status, m.member_role AS member_role, "
                + "COUNT(o.id) AS total_orders, "
                + "COALESCE(SUM(CASE WHEN o.status NOT IN ('CANCELLED', 'FAILED') THEN o.total_amount ELSE 0 END), 0) AS total_spent, "
                + "MAX(o.created_at) AS last_order_at "
                + "FROM users u "
                + "LEFT JOIN orders o ON o.user_id = u.id "
                + "LEFT JOIN admin_customer_meta m ON m.user_id = u.id "
                + "WHERE u.id = ? AND u.role <> 'ADMIN' "
                + "GROUP BY u.id, u.name, u.email, u.phone, u.created_at, m.status, m.member_role";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "고객을 찾을 수 없습니다.");
        }

        return mapCustomer(rows.get(0));
    }

    public synchronized List<Order> getCustomerOrders(String customerId) {
        long userId = requireNumericId(customerId, "고객을 찾을 수 없습니다.");
        return loadOrders(false).stream()
                .filter(order -> parseLong(order.customerId, -1L) == userId)
                .collect(Collectors.toList());
    }

    public synchronized Customer updateCustomerMemberAccess(String id, String status, String memberRole) {
        long userId = requireNumericId(id, "고객을 찾을 수 없습니다.");

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM users WHERE id = ? AND role <> 'ADMIN'",
                Integer.class,
                userId
        );
        if (exists == null || exists == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "고객을 찾을 수 없습니다.");
        }

        Customer existing = getCustomer(String.valueOf(userId));
        String nextStatus = isBlank(status) ? existing.status : normalizeCustomerStatus(status);
        String nextRole = isBlank(memberRole) ? existing.memberRole : normalizeCustomerRole(memberRole);

        jdbcTemplate.update(
                "INSERT INTO admin_customer_meta (user_id, status, member_role) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), member_role = VALUES(member_role), updated_at = CURRENT_TIMESTAMP",
                userId,
                trimToNull(nextStatus),
                trimToNull(nextRole)
        );

        return getCustomer(String.valueOf(userId));
    }

    public synchronized PageResponse<AccessLogEntry> getAccessLogs(
            String keyword,
            String endpoint,
            String method,
            String ipAddress,
            int page,
            int pageSize
    ) {
        String sql = "SELECT a.id, a.user_id, a.endpoint, a.method, a.ip_address, a.created_at, "
                + "u.name AS user_name, u.email AS user_email "
                + "FROM access_logs a "
                + "LEFT JOIN users u ON u.id = a.user_id "
                + "ORDER BY a.created_at DESC, a.id DESC";

        String keywordNorm = lower(keyword);
        String endpointNorm = lower(endpoint);
        String methodNorm = trim(method).toUpperCase(Locale.ROOT);
        String ipNorm = lower(ipAddress);

        List<AccessLogEntry> rows = jdbcTemplate.queryForList(sql).stream()
                .map(this::mapAccessLog)
                .filter(log -> keywordNorm.isEmpty()
                        || containsLower(log.userId, keywordNorm)
                        || containsLower(log.userName, keywordNorm)
                        || containsLower(log.userEmail, keywordNorm)
                        || containsLower(log.endpoint, keywordNorm)
                        || containsLower(log.method, keywordNorm)
                        || containsLower(log.ipAddress, keywordNorm))
                .filter(log -> endpointNorm.isEmpty() || containsLower(log.endpoint, endpointNorm))
                .filter(log -> methodNorm.isEmpty() || methodNorm.equalsIgnoreCase(log.method))
                .filter(log -> ipNorm.isEmpty() || containsLower(log.ipAddress, ipNorm))
                .collect(Collectors.toList());

        return paginate(rows, page, pageSize);
    }

    public synchronized void recordAccessLog(Long userId, String endpoint, String method, String ipAddress) {
        String safeEndpoint = trim(endpoint);
        if (safeEndpoint.isEmpty()) {
            safeEndpoint = "/";
        }
        if (safeEndpoint.length() > 255) {
            safeEndpoint = safeEndpoint.substring(0, 255);
        }

        String safeMethod = trim(method).toUpperCase(Locale.ROOT);
        if (safeMethod.isEmpty()) {
            safeMethod = "GET";
        }
        if (safeMethod.length() > 10) {
            safeMethod = safeMethod.substring(0, 10);
        }

        String safeIp = trim(ipAddress);
        if (safeIp.isEmpty()) {
            safeIp = "unknown";
        }
        if (safeIp.length() > 45) {
            safeIp = safeIp.substring(0, 45);
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO access_logs (user_id, endpoint, method, ip_address) VALUES (?, ?, ?, ?)",
                    userId,
                    safeEndpoint,
                    safeMethod,
                    safeIp
            );
        } catch (DataAccessException ignored) {
            // Access logging must not break business APIs.
        }
    }

    public synchronized PageResponse<CustomerServiceInquiry> getCustomerServiceInquiries(
            String keyword,
            String status,
            int page,
            int pageSize
    ) {
        String sql = "SELECT cs.id, cs.user_id, cs.subject, cs.content, cs.status, cs.admin_answer, cs.created_at, "
                + "u.name AS user_name, u.email AS user_email "
                + "FROM customer_service cs "
                + "LEFT JOIN users u ON u.id = cs.user_id "
                + "ORDER BY cs.created_at DESC, cs.id DESC";

        String keywordNorm = lower(keyword);
        String statusNorm = trim(status).toUpperCase(Locale.ROOT);

        List<CustomerServiceInquiry> rows = jdbcTemplate.queryForList(sql).stream()
                .map(this::mapCustomerServiceInquiry)
                .filter(inquiry -> keywordNorm.isEmpty()
                        || containsLower(inquiry.id, keywordNorm)
                        || containsLower(inquiry.subject, keywordNorm)
                        || containsLower(inquiry.content, keywordNorm)
                        || containsLower(inquiry.userName, keywordNorm)
                        || containsLower(inquiry.userEmail, keywordNorm))
                .filter(inquiry -> statusNorm.isEmpty()
                        || statusNorm.equals(trim(inquiry.status).toUpperCase(Locale.ROOT)))
                .collect(Collectors.toList());

        return paginate(rows, page, pageSize);
    }

    public synchronized CustomerServiceInquiry replyCustomerServiceInquiry(String id, String answer) {
        long inquiryId = requireNumericId(id, "문의를 찾을 수 없습니다.");
        String adminAnswer = trim(answer);
        if (adminAnswer.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "답변 내용을 입력해주세요.");
        }

        int updated = jdbcTemplate.update(
                "UPDATE customer_service SET admin_answer = ?, status = 'ANSWERED' WHERE id = ?",
                adminAnswer,
                inquiryId
        );
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
        }

        return mapCustomerServiceInquiry(queryCustomerServiceInquiryRow(inquiryId));
    }

    public synchronized PageResponse<InventoryProduct> getInventoryProducts(
            String keyword,
            String author,
            String isbn13,
            int page,
            int pageSize
    ) {
        String keywordNorm = lower(keyword);
        String authorNorm = lower(author);
        String isbnNorm = lower(isbn13);

        List<InventoryProduct> rows = loadProducts().stream()
                .filter(product -> keywordNorm.isEmpty()
                        || containsLower(product.title, keywordNorm)
                        || containsLower(product.author, keywordNorm)
                        || containsLower(product.isbn13, keywordNorm))
                .filter(product -> authorNorm.isEmpty() || containsLower(product.author, authorNorm))
                .filter(product -> isbnNorm.isEmpty() || containsLower(product.isbn13, isbnNorm))
                .map(product -> {
                    InventoryProduct item = new InventoryProduct();
                    item.id = product.id;
                    item.title = product.title;
                    item.author = product.author;
                    item.isbn13 = product.isbn13;
                    item.stock = product.stock;
                    item.status = product.status;
                    return item;
                })
                .collect(Collectors.toList());

        return paginate(rows, page, pageSize);
    }

    public synchronized PageResponse<InventoryLog> getInventoryLogs(String keyword, String type, int page, int pageSize) {
        String sql = "SELECT id, product_id, product_title, type, quantity, reason, actor, created_at "
                + "FROM admin_inventory_logs ORDER BY created_at DESC, id DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        String keywordNorm = lower(keyword);
        String typeNorm = trim(type);

        List<InventoryLog> filtered = rows.stream()
                .map(this::mapInventoryLog)
                .filter(log -> keywordNorm.isEmpty() || containsLower(log.productTitle, keywordNorm))
                .filter(log -> typeNorm.isEmpty() || typeNorm.equals(log.type))
                .collect(Collectors.toList());

        return paginate(filtered, page, pageSize);
    }

    public synchronized InventoryLog adjustInventory(InventoryAdjustRequest request) {
        if (request == null || (isBlank(request.productId) && isBlank(request.isbn13))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "상품 ID 또는 ISBN을 입력해주세요.");
        }

        Map<String, Object> product;
        long productId;
        if (!isBlank(request.productId)) {
            productId = requireNumericId(request.productId, "상품을 찾을 수 없습니다.");
            List<Map<String, Object>> productRows = jdbcTemplate.queryForList(
                    "SELECT id, title, stock FROM books WHERE id = ?",
                    productId
            );
            if (productRows.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
            }
            product = productRows.get(0);
            productId = asLong(product.get("id"), productId);
        } else {
            String isbn13 = trim(request.isbn13);
            List<Map<String, Object>> productRows = jdbcTemplate.queryForList(
                    "SELECT id, title, stock FROM books "
                            + "WHERE REPLACE(isbn, '-', '') = REPLACE(?, '-', '') "
                            + "ORDER BY id DESC",
                    isbn13
            );
            if (productRows.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ISBN에 해당하는 상품을 찾을 수 없습니다.");
            }
            product = productRows.get(0);
            productId = asLong(product.get("id"), 0L);
            if (productId <= 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
            }
        }

        String type = normalizeInventoryType(request.type);
        int quantity = Math.abs(request.quantity);

        if (quantity <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "수량은 1 이상이어야 합니다.");
        }
        int currentStock = asInt(product.get("stock"), 0);
        int delta;
        if (INVENTORY_OUT.equals(type)) {
            delta = -quantity;
        } else if (INVENTORY_ADJUST.equals(type)) {
            delta = request.quantity;
        } else {
            delta = quantity;
        }

        int nextStock = Math.max(0, currentStock + delta);
        int actualDelta = nextStock - currentStock;

        jdbcTemplate.update("UPDATE books SET stock = ? WHERE id = ?", nextStock, productId);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        String reason = trim(request.reason);
        if (reason.isEmpty()) {
            reason = "재고 조정";
        }
        final long finalProductId = productId;
        final String finalProductTitle = asString(product.get("title"));
        final String finalReason = reason;

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO admin_inventory_logs (product_id, product_title, type, quantity, reason, actor) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, finalProductId);
            ps.setString(2, finalProductTitle);
            ps.setString(3, type);
            ps.setInt(4, actualDelta);
            ps.setString(5, finalReason);
            ps.setString(6, "관리자");
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "재고 로그를 저장하지 못했습니다.");
        }

        List<Map<String, Object>> logRows = jdbcTemplate.queryForList(
                "SELECT id, product_id, product_title, type, quantity, reason, actor, created_at "
                        + "FROM admin_inventory_logs WHERE id = ?",
                key.longValue()
        );

        return mapInventoryLog(logRows.get(0));
    }

    public synchronized PageResponse<Coupon> getCoupons(String keyword, String status, int page, int pageSize) {
        List<Coupon> all = loadCoupons();

        String keywordNorm = lower(keyword);
        String statusNorm = trim(status);

        List<Coupon> filtered = all.stream()
                .filter(coupon -> keywordNorm.isEmpty()
                        || containsLower(coupon.code, keywordNorm)
                        || containsLower(coupon.name, keywordNorm))
                .filter(coupon -> statusNorm.isEmpty() || statusNorm.equals(coupon.status))
                .collect(Collectors.toList());

        return paginate(filtered, page, pageSize);
    }

    public synchronized Coupon createCoupon(Coupon payload) {
        if (payload == null || isBlank(payload.code) || isBlank(payload.name)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "쿠폰 코드와 이름은 필수입니다.");
        }

        String code = trim(payload.code).toUpperCase(Locale.ROOT);
        String discountTypeDb = toDbDiscountType(payload.discountType);
        int discountValue = Math.max(0, payload.discountValue);
        Timestamp validFrom = toStartOfDay(payload.startAt);
        Timestamp validUntil = toEndOfDay(payload.endAt);
        int remaining = COUPON_INACTIVE.equals(trim(payload.status)) ? 0 : 9999;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO coupons (code, discount_type, discount_value, remaining_count, valid_from, valid_until) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, code);
                ps.setString(2, discountTypeDb);
                ps.setBigDecimal(3, BigDecimal.valueOf(discountValue));
                ps.setInt(4, remaining);
                ps.setTimestamp(5, validFrom);
                ps.setTimestamp(6, validUntil);
                return ps;
            }, keyHolder);
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "쿠폰 생성에 실패했습니다.");
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "쿠폰 생성 후 ID를 확인할 수 없습니다.");
        }

        long couponId = key.longValue();
        upsertCouponMeta(
                couponId,
                trim(payload.name),
                payload.minOrderAmount,
                payload.maxDiscountAmount,
                normalizeCouponStatus(payload.status)
        );

        return getCouponById(couponId);
    }

    public synchronized Coupon updateCoupon(String id, Map<String, Object> patch) {
        long couponId = requireNumericId(id, "쿠폰을 찾을 수 없습니다.");
        Coupon existing = getCouponById(couponId);

        String code = asString(patch, "code", existing.code).toUpperCase(Locale.ROOT);
        String name = asString(patch, "name", existing.name);
        String discountTypeUi = asString(patch, "discountType", existing.discountType);
        int discountValue = Math.max(0, asInt(patch, "discountValue", existing.discountValue));
        Integer minOrderAmount = asNullableInt(patch != null && patch.containsKey("minOrderAmount")
                ? patch.get("minOrderAmount")
                : existing.minOrderAmount);
        Integer maxDiscountAmount = asNullableInt(patch != null && patch.containsKey("maxDiscountAmount")
                ? patch.get("maxDiscountAmount")
                : existing.maxDiscountAmount);
        String startAt = asNullableString(patch, "startAt", existing.startAt);
        String endAt = asNullableString(patch, "endAt", existing.endAt);
        String status = normalizeCouponStatus(asString(patch, "status", existing.status));

        String discountTypeDb = toDbDiscountType(discountTypeUi);
        Timestamp validFrom = toStartOfDay(startAt);
        Timestamp validUntil = toEndOfDay(endAt);
        int remaining = COUPON_INACTIVE.equals(status) ? 0 : 9999;

        int updated;
        try {
            updated = jdbcTemplate.update(
                    "UPDATE coupons SET code = ?, discount_type = ?, discount_value = ?, remaining_count = ?, valid_from = ?, valid_until = ? WHERE id = ?",
                    code,
                    discountTypeDb,
                    BigDecimal.valueOf(discountValue),
                    remaining,
                    validFrom,
                    validUntil,
                    couponId
            );
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "쿠폰 수정에 실패했습니다.");
        }

        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
        }

        upsertCouponMeta(couponId, name, minOrderAmount, maxDiscountAmount, status);
        return getCouponById(couponId);
    }

    public synchronized void deleteCoupon(String id) {
        long couponId = requireNumericId(id, "쿠폰을 찾을 수 없습니다.");
        jdbcTemplate.update("DELETE FROM admin_coupon_meta WHERE coupon_id = ?", couponId);
        int deleted = jdbcTemplate.update("DELETE FROM coupons WHERE id = ?", couponId);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
        }
    }

    public synchronized PageResponse<Review> getReviews(String keyword, String status, int page, int pageSize) {
        List<Review> all = loadReviews();

        String keywordNorm = lower(keyword);
        String statusNorm = trim(status);

        List<Review> filtered = all.stream()
                .filter(review -> keywordNorm.isEmpty()
                        || containsLower(review.productTitle, keywordNorm)
                        || containsLower(review.customerName, keywordNorm)
                        || containsLower(review.content, keywordNorm))
                .filter(review -> statusNorm.isEmpty() || statusNorm.equals(review.status))
                .collect(Collectors.toList());

        return paginate(filtered, page, pageSize);
    }

    public synchronized Review toggleReviewStatus(String id) {
        long reviewId = requireNumericId(id, "리뷰를 찾을 수 없습니다.");

        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM reviews WHERE id = ?", Integer.class, reviewId);
        if (exists == null || exists == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다.");
        }

        String current = jdbcTemplate.query(
                "SELECT status FROM admin_review_states WHERE review_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                reviewId
        );

        if (isBlank(current)) {
            current = REVIEW_PUBLIC;
        }

        String next = REVIEW_PUBLIC.equals(current) ? REVIEW_HIDDEN : REVIEW_PUBLIC;

        jdbcTemplate.update(
                "INSERT INTO admin_review_states (review_id, status) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), updated_at = CURRENT_TIMESTAMP",
                reviewId,
                next
        );

        return getReviewById(reviewId);
    }

    private List<Product> loadProducts() {
        String sql = "SELECT b.id, b.isbn, b.title, b.author, b.publisher, b.category, b.price, b.stock, "
                + "b.description, b.cover_image_url, b.created_at, "
                + "m.subtitle, m.published_date, m.sale_price, m.status AS meta_status, m.tags_json, m.images_json "
                + "FROM books b LEFT JOIN admin_book_meta m ON m.book_id = b.id";

        return jdbcTemplate.queryForList(sql).stream()
                .map(this::mapProduct)
                .collect(Collectors.toList());
    }

    private Product mapProduct(Map<String, Object> row) {
        Product product = new Product();
        product.id = String.valueOf(asLong(row.get("id"), 0L));
        product.isbn13 = asString(row.get("isbn"));
        product.title = asString(row.get("title"));
        product.author = asString(row.get("author"));
        product.publisher = asString(row.get("publisher"));
        product.category = asString(row.get("category"));
        product.price = toInt(row.get("price"));
        product.stock = asInt(row.get("stock"), 0);
        product.description = asString(row.get("description"));

        product.subtitle = asString(row.get("subtitle"));
        product.salePrice = asNullableInt(row.get("sale_price"));

        String published = toDateString(row.get("published_date"));
        if (published.isEmpty()) {
            published = extractPublishedDate(product.description);
        }
        product.publishedDate = published;

        product.tags = parseStringList(asString(row.get("tags_json")));
        if (product.tags.isEmpty() && !isBlank(product.category)) {
            product.tags.add(product.category);
        }

        product.images = parseStringList(asString(row.get("images_json")));
        String coverImageUrl = asString(row.get("cover_image_url"));
        if (product.images.isEmpty() && !isBlank(coverImageUrl)) {
            product.images.add(coverImageUrl);
        }

        product.status = normalizeProductStatus(asString(row.get("meta_status")), product.stock);
        product.createdAt = toDateString(row.get("created_at"));
        product.updatedAt = product.createdAt;

        return product;
    }

    private void upsertBookMeta(
            long bookId,
            String subtitle,
            String publishedDate,
            Integer salePrice,
            String status,
            List<String> tags,
            List<String> images
    ) {
        jdbcTemplate.update(
                "INSERT INTO admin_book_meta (book_id, subtitle, published_date, sale_price, status, tags_json, images_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE subtitle = VALUES(subtitle), published_date = VALUES(published_date), "
                        + "sale_price = VALUES(sale_price), status = VALUES(status), tags_json = VALUES(tags_json), "
                        + "images_json = VALUES(images_json), updated_at = CURRENT_TIMESTAMP",
                bookId,
                trimToNull(subtitle),
                toSqlDate(publishedDate),
                salePrice,
                trimToNull(status),
                toJson(tags),
                toJson(images)
        );
    }

    private List<Order> loadOrders(boolean includeItems) {
        String sql = "SELECT o.id, o.order_number, o.user_id, o.total_amount, o.status, o.shipping_address, o.created_at, "
                + "u.name AS customer_name, u.phone AS customer_phone, u.address AS customer_address "
                + "FROM orders o LEFT JOIN users u ON u.id = o.user_id "
                + "ORDER BY o.created_at DESC, o.id DESC";

        return jdbcTemplate.queryForList(sql).stream()
                .map(row -> mapOrder(row, includeItems))
                .collect(Collectors.toList());
    }

    private Order mapOrder(Map<String, Object> row, boolean includeItems) {
        Order order = new Order();

        long orderId = asLong(row.get("id"), 0L);
        String dbStatus = asString(row.get("status")).toUpperCase(Locale.ROOT);

        order.id = String.valueOf(orderId);
        order.orderNumber = asString(row.get("order_number"));
        order.customerId = String.valueOf(asLong(row.get("user_id"), 0L));
        order.customerName = asString(row.get("customer_name"));
        order.totalAmount = toInt(row.get("total_amount"));
        order.paymentStatus = toPaymentStatus(dbStatus);
        order.fulfillmentStatus = toFulfillmentStatus(dbStatus);
        order.createdAt = toDateString(row.get("created_at"));
        order.paidAt = PAYMENT_DONE.equals(order.paymentStatus) ? order.createdAt : null;

        DeliveryInfo delivery = new DeliveryInfo();
        delivery.receiverName = order.customerName;
        delivery.phone = asString(row.get("customer_phone"));
        String shippingAddress = asString(row.get("shipping_address"));
        if (shippingAddress.isEmpty()) {
            shippingAddress = asString(row.get("customer_address"));
        }
        delivery.address1 = shippingAddress;
        delivery.address2 = "";
        delivery.memo = null;
        order.delivery = delivery;

        if (includeItems) {
            order.items = loadOrderItems(orderId);
        }

        return order;
    }

    private List<OrderItem> loadOrderItems(long orderId) {
        String sql = "SELECT oi.book_id, oi.quantity, oi.unit_price, b.title "
                + "FROM order_items oi LEFT JOIN books b ON b.id = oi.book_id "
                + "WHERE oi.order_id = ? ORDER BY oi.id ASC";

        return jdbcTemplate.queryForList(sql, orderId).stream()
                .map(row -> {
                    OrderItem item = new OrderItem();
                    item.productId = String.valueOf(asLong(row.get("book_id"), 0L));
                    item.title = asString(row.get("title"));
                    item.quantity = asInt(row.get("quantity"), 0);
                    item.unitPrice = toInt(row.get("unit_price"));
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Customer> loadCustomers() {
        String sql = "SELECT u.id, u.name, u.email, u.phone, u.created_at, "
                + "m.status AS member_status, m.member_role AS member_role, "
                + "COUNT(o.id) AS total_orders, "
                + "COALESCE(SUM(CASE WHEN o.status NOT IN ('CANCELLED', 'FAILED') THEN o.total_amount ELSE 0 END), 0) AS total_spent, "
                + "MAX(o.created_at) AS last_order_at "
                + "FROM users u "
                + "LEFT JOIN orders o ON o.user_id = u.id "
                + "LEFT JOIN admin_customer_meta m ON m.user_id = u.id "
                + "WHERE u.role <> 'ADMIN' "
                + "GROUP BY u.id, u.name, u.email, u.phone, u.created_at, m.status, m.member_role "
                + "ORDER BY u.created_at DESC, u.id DESC";

        return jdbcTemplate.queryForList(sql).stream()
                .map(this::mapCustomer)
                .collect(Collectors.toList());
    }

    private Customer mapCustomer(Map<String, Object> row) {
        Customer customer = new Customer();
        customer.id = String.valueOf(asLong(row.get("id"), 0L));
        customer.name = asString(row.get("name"));
        customer.email = asString(row.get("email"));
        customer.phone = asString(row.get("phone"));
        customer.status = normalizeCustomerStatus(asString(row.get("member_status")));
        customer.memberRole = normalizeCustomerRole(asString(row.get("member_role")));
        customer.totalOrders = asInt(row.get("total_orders"), 0);
        customer.totalSpent = toInt(row.get("total_spent"));
        customer.grade = resolveGrade(customer.totalSpent);
        customer.lastOrderAt = toDateString(row.get("last_order_at"));
        customer.createdAt = toDateString(row.get("created_at"));
        return customer;
    }

    private List<Coupon> loadCoupons() {
        String sql = "SELECT c.id, c.code, c.discount_type, c.discount_value, c.remaining_count, c.valid_from, c.valid_until, c.created_at, "
                + "m.name AS meta_name, m.min_order_amount, m.max_discount_amount, m.status AS meta_status "
                + "FROM coupons c LEFT JOIN admin_coupon_meta m ON m.coupon_id = c.id "
                + "ORDER BY c.created_at DESC, c.id DESC";

        return jdbcTemplate.queryForList(sql).stream()
                .map(this::mapCoupon)
                .collect(Collectors.toList());
    }

    private Coupon getCouponById(long couponId) {
        String sql = "SELECT c.id, c.code, c.discount_type, c.discount_value, c.remaining_count, c.valid_from, c.valid_until, c.created_at, "
                + "m.name AS meta_name, m.min_order_amount, m.max_discount_amount, m.status AS meta_status "
                + "FROM coupons c LEFT JOIN admin_coupon_meta m ON m.coupon_id = c.id WHERE c.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, couponId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
        }
        return mapCoupon(rows.get(0));
    }

    private Coupon mapCoupon(Map<String, Object> row) {
        Coupon coupon = new Coupon();
        coupon.id = String.valueOf(asLong(row.get("id"), 0L));
        coupon.code = asString(row.get("code"));
        coupon.discountType = toUiDiscountType(asString(row.get("discount_type")));
        coupon.discountValue = toInt(row.get("discount_value"));
        coupon.minOrderAmount = asNullableInt(row.get("min_order_amount"));
        coupon.maxDiscountAmount = asNullableInt(row.get("max_discount_amount"));
        coupon.startAt = toDateString(row.get("valid_from"));
        coupon.endAt = toDateString(row.get("valid_until"));

        String name = asString(row.get("meta_name"));
        coupon.name = name.isEmpty() ? coupon.code : name;

        String status = trim(asString(row.get("meta_status")));
        if (status.isEmpty()) {
            LocalDate endDate = parseLocalDate(coupon.endAt);
            int remaining = asInt(row.get("remaining_count"), 0);
            if (endDate != null && endDate.isBefore(LocalDate.now())) {
                status = COUPON_EXPIRED;
            } else if (remaining <= 0) {
                status = COUPON_INACTIVE;
            } else {
                status = COUPON_ACTIVE;
            }
        }
        coupon.status = status;

        return coupon;
    }

    private void upsertCouponMeta(
            long couponId,
            String name,
            Integer minOrderAmount,
            Integer maxDiscountAmount,
            String status
    ) {
        jdbcTemplate.update(
                "INSERT INTO admin_coupon_meta (coupon_id, name, min_order_amount, max_discount_amount, status) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), min_order_amount = VALUES(min_order_amount), "
                        + "max_discount_amount = VALUES(max_discount_amount), status = VALUES(status), updated_at = CURRENT_TIMESTAMP",
                couponId,
                trimToNull(name),
                minOrderAmount,
                maxDiscountAmount,
                trimToNull(status)
        );
    }

    private List<Review> loadReviews() {
        String sql = "SELECT r.id, r.book_id, b.title AS product_title, u.name AS customer_name, r.rating, r.content, r.created_at, "
                + "COALESCE(s.status, ?) AS review_status "
                + "FROM reviews r "
                + "LEFT JOIN books b ON b.id = r.book_id "
                + "LEFT JOIN users u ON u.id = r.user_id "
                + "LEFT JOIN admin_review_states s ON s.review_id = r.id "
                + "ORDER BY r.created_at DESC, r.id DESC";

        return jdbcTemplate.queryForList(sql, REVIEW_PUBLIC).stream()
                .map(this::mapReview)
                .collect(Collectors.toList());
    }

    private Review getReviewById(long reviewId) {
        String sql = "SELECT r.id, r.book_id, b.title AS product_title, u.name AS customer_name, r.rating, r.content, r.created_at, "
                + "COALESCE(s.status, ?) AS review_status "
                + "FROM reviews r "
                + "LEFT JOIN books b ON b.id = r.book_id "
                + "LEFT JOIN users u ON u.id = r.user_id "
                + "LEFT JOIN admin_review_states s ON s.review_id = r.id "
                + "WHERE r.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, REVIEW_PUBLIC, reviewId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다.");
        }
        return mapReview(rows.get(0));
    }

    private Review mapReview(Map<String, Object> row) {
        Review review = new Review();
        review.id = String.valueOf(asLong(row.get("id"), 0L));
        review.productId = String.valueOf(asLong(row.get("book_id"), 0L));
        review.productTitle = asString(row.get("product_title"));
        review.customerName = asString(row.get("customer_name"));
        review.rating = asInt(row.get("rating"), 0);
        review.content = asString(row.get("content"));
        review.status = asString(row.get("review_status"));
        review.createdAt = toDateString(row.get("created_at"));
        return review;
    }

    private InventoryLog mapInventoryLog(Map<String, Object> row) {
        InventoryLog log = new InventoryLog();
        log.id = String.valueOf(asLong(row.get("id"), 0L));
        log.productId = String.valueOf(asLong(row.get("product_id"), 0L));
        log.productTitle = asString(row.get("product_title"));
        log.type = asString(row.get("type"));
        log.quantity = asInt(row.get("quantity"), 0);
        log.reason = asString(row.get("reason"));
        log.actor = asString(row.get("actor"));
        log.createdAt = toDateString(row.get("created_at"));
        return log;
    }

    private AccessLogEntry mapAccessLog(Map<String, Object> row) {
        AccessLogEntry log = new AccessLogEntry();
        log.id = String.valueOf(asLong(row.get("id"), 0L));

        long userId = asLong(row.get("user_id"), 0L);
        log.userId = userId > 0 ? String.valueOf(userId) : "";
        log.userName = asString(row.get("user_name"));
        log.userEmail = asString(row.get("user_email"));

        log.endpoint = asString(row.get("endpoint"));
        log.method = asString(row.get("method")).toUpperCase(Locale.ROOT);
        log.ipAddress = asString(row.get("ip_address"));
        log.createdAt = toDateTimeString(row.get("created_at"));
        return log;
    }

    private CustomerServiceInquiry mapCustomerServiceInquiry(Map<String, Object> row) {
        CustomerServiceInquiry inquiry = new CustomerServiceInquiry();
        inquiry.id = String.valueOf(asLong(row.get("id"), 0L));

        long userId = asLong(row.get("user_id"), 0L);
        inquiry.userId = userId > 0 ? String.valueOf(userId) : "";
        inquiry.userName = asString(row.get("user_name"));
        inquiry.userEmail = asString(row.get("user_email"));

        inquiry.subject = asString(row.get("subject"));
        inquiry.content = asString(row.get("content"));
        inquiry.adminAnswer = asString(row.get("admin_answer"));

        String status = trim(asString(row.get("status"))).toUpperCase(Locale.ROOT);
        if (status.isEmpty()) {
            status = "OPEN";
        }
        if (!isBlank(inquiry.adminAnswer)) {
            status = "ANSWERED";
        }
        inquiry.status = status;

        inquiry.createdAt = toDateString(row.get("created_at"));
        return inquiry;
    }

    private Map<String, Object> queryCustomerServiceInquiryRow(long inquiryId) {
        String sql = "SELECT cs.id, cs.user_id, cs.subject, cs.content, cs.status, cs.admin_answer, cs.created_at, "
                + "u.name AS user_name, u.email AS user_email "
                + "FROM customer_service cs "
                + "LEFT JOIN users u ON u.id = cs.user_id "
                + "WHERE cs.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, inquiryId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private String toDbOrderStatus(String paymentStatus, String fulfillmentStatus) {
        String payment = trim(paymentStatus);
        String fulfillment = trim(fulfillmentStatus);

        if (PAYMENT_CANCELED.equals(payment) || FULFILLMENT_CANCELED.equals(fulfillment)) {
            return "CANCELLED";
        }

        if (FULFILLMENT_DELIVERED.equals(fulfillment)) {
            return "DELIVERED";
        }
        if (FULFILLMENT_SHIPPING.equals(fulfillment)) {
            return "SHIPPED";
        }
        if (FULFILLMENT_PREPARING.equals(fulfillment)) {
            return "PAID";
        }
        if (FULFILLMENT_RECEIVED.equals(fulfillment)) {
            return "PENDING";
        }

        if (PAYMENT_PENDING.equals(payment)) {
            return "PENDING";
        }
        if (PAYMENT_DONE.equals(payment)) {
            return "PAID";
        }

        return "PENDING";
    }

    private String toPaymentStatus(String dbStatus) {
        switch (dbStatus) {
            case "PENDING":
                return PAYMENT_PENDING;
            case "CANCELLED":
            case "FAILED":
            case "REFUNDED":
                return PAYMENT_CANCELED;
            default:
                return PAYMENT_DONE;
        }
    }

    private String toFulfillmentStatus(String dbStatus) {
        switch (dbStatus) {
            case "PENDING":
                return FULFILLMENT_RECEIVED;
            case "PAID":
            case "PROCESSING":
                return FULFILLMENT_PREPARING;
            case "SHIPPED":
                return FULFILLMENT_SHIPPING;
            case "DELIVERED":
                return FULFILLMENT_DELIVERED;
            case "CANCELLED":
            case "FAILED":
            case "REFUNDED":
                return FULFILLMENT_CANCELED;
            default:
                return FULFILLMENT_PREPARING;
        }
    }

    private String resolveGrade(int totalSpent) {
        if (totalSpent >= 500_000) {
            return GRADE_VVIP;
        }
        if (totalSpent >= 200_000) {
            return GRADE_VIP;
        }
        return GRADE_NORMAL;
    }

    private String toDbDiscountType(String uiType) {
        String value = trim(uiType).toUpperCase(Locale.ROOT);
        if ("정률".equals(uiType) || "PERCENT".equals(value)) {
            return "PERCENT";
        }
        return "AMOUNT";
    }

    private String toUiDiscountType(String dbType) {
        String value = trim(dbType).toUpperCase(Locale.ROOT);
        if ("PERCENT".equals(value)) {
            return "정률";
        }
        return "정액";
    }

    private String normalizeProductStatus(String status, int stock) {
        String value = trim(status);
        if (value.isEmpty()) {
            return stock <= 0 ? STATUS_OUT_OF_STOCK : STATUS_ON_SALE;
        }
        if (STATUS_OUT_OF_STOCK.equals(value)) {
            return STATUS_OUT_OF_STOCK;
        }
        if (stock <= 0) {
            return STATUS_OUT_OF_STOCK;
        }
        return STATUS_ON_SALE;
    }

    private String normalizeCouponStatus(String status) {
        String value = trim(status);
        if (value.isEmpty()) {
            return COUPON_ACTIVE;
        }
        if (COUPON_EXPIRED.equals(value)) {
            return COUPON_EXPIRED;
        }
        if (COUPON_INACTIVE.equals(value)) {
            return COUPON_INACTIVE;
        }
        return COUPON_ACTIVE;
    }

    private String normalizeCustomerStatus(String status) {
        String value = trim(status);
        if (CUSTOMER_STATUS_SUSPENDED.equals(value)) {
            return CUSTOMER_STATUS_SUSPENDED;
        }
        return CUSTOMER_STATUS_ACTIVE;
    }

    private String normalizeCustomerRole(String role) {
        String value = trim(role);
        if (CUSTOMER_ROLE_VIP.equals(value)) {
            return CUSTOMER_ROLE_VIP;
        }
        if (CUSTOMER_ROLE_VVIP.equals(value)) {
            return CUSTOMER_ROLE_VVIP;
        }
        return CUSTOMER_ROLE_GENERAL;
    }

    private String normalizeInventoryType(String type) {
        String value = trim(type);
        if (INVENTORY_OUT.equals(value)) {
            return INVENTORY_OUT;
        }
        if (INVENTORY_ADJUST.equals(value)) {
            return INVENTORY_ADJUST;
        }
        return INVENTORY_IN;
    }

    private String extractPublishedDate(String description) {
        if (isBlank(description)) {
            return "";
        }
        String marker = "PublishDate=";
        int start = description.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int from = start + marker.length();
        int end = description.indexOf(';', from);
        String raw = end >= 0 ? description.substring(from, end) : description.substring(from);
        raw = raw.trim();

        if (raw.matches("\\d{4}[-.]\\d{2}[-.]\\d{2}")) {
            return raw.replace('.', '-');
        }

        if (raw.matches("\\d{4}년\\s*\\d{2}월\\s*\\d{2}일")) {
            return raw.replace("년", "-").replace("월", "-").replace("일", "").replace(" ", "").trim();
        }

        return "";
    }

    private void ensureSupportTables() {
        try {
            Files.createDirectories(adminImageDir);
        } catch (IOException ignored) {
            // ignore
        }

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS access_logs ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "user_id BIGINT NULL,"
                        + "endpoint VARCHAR(255),"
                        + "method VARCHAR(10),"
                        + "ip_address VARCHAR(45),"
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "INDEX idx_access_logs_created_at (created_at),"
                        + "INDEX idx_access_logs_user_id (user_id),"
                        + "INDEX idx_access_logs_ip (ip_address)"
                        + ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS customer_service ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "user_id BIGINT,"
                        + "subject VARCHAR(200),"
                        + "content TEXT,"
                        + "status VARCHAR(20) DEFAULT 'OPEN',"
                        + "admin_answer TEXT,"
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")"
        );

        Integer adminAnswerColumnExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'customer_service' "
                        + "AND column_name = 'admin_answer'",
                Integer.class
        );
        if (adminAnswerColumnExists != null && adminAnswerColumnExists == 0) {
            jdbcTemplate.execute("ALTER TABLE customer_service ADD COLUMN admin_answer TEXT");
        }

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_book_meta ("
                        + "book_id BIGINT PRIMARY KEY,"
                        + "subtitle VARCHAR(255),"
                        + "published_date DATE NULL,"
                        + "sale_price INT NULL,"
                        + "status VARCHAR(20),"
                        + "tags_json TEXT,"
                        + "images_json TEXT,"
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                        + ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_inventory_logs ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "product_id BIGINT NOT NULL,"
                        + "product_title VARCHAR(500) NOT NULL,"
                        + "type VARCHAR(20) NOT NULL,"
                        + "quantity INT NOT NULL,"
                        + "reason VARCHAR(255),"
                        + "actor VARCHAR(100) DEFAULT '관리자',"
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_review_states ("
                        + "review_id BIGINT PRIMARY KEY,"
                        + "status VARCHAR(20) NOT NULL,"
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                        + ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_coupon_meta ("
                        + "coupon_id BIGINT PRIMARY KEY,"
                        + "name VARCHAR(255),"
                        + "min_order_amount INT NULL,"
                        + "max_discount_amount INT NULL,"
                        + "status VARCHAR(20),"
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                        + ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_customer_meta ("
                        + "user_id BIGINT PRIMARY KEY,"
                        + "status VARCHAR(20),"
                        + "member_role VARCHAR(30),"
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                        + ")"
        );
    }

    private <T> PageResponse<T> paginate(List<T> items, int page, int pageSize) {
        int safePage = page < 1 ? 1 : page;
        int safePageSize = pageSize < 1 ? 10 : pageSize;
        int total = items.size();
        int from = (safePage - 1) * safePageSize;
        int to = Math.min(from + safePageSize, total);

        if (from >= total) {
            return new PageResponse<>(new ArrayList<>(), total, safePage, safePageSize);
        }

        return new PageResponse<>(new ArrayList<>(items.subList(from, to)), total, safePage, safePageSize);
    }

    private long requireNumericId(String rawId, String notFoundMessage) {
        long id = parseLong(rawId, -1L);
        if (id <= 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundMessage);
        }
        return id;
    }

    private List<String> sanitizeStringList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> asStringList(Object value, List<String> fallback) {
        if (!(value instanceof List<?>)) {
            return sanitizeStringList(fallback);
        }
        List<?> raw = (List<?>) value;
        List<String> parsed = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    parsed.add(text);
                }
            }
        }
        return parsed;
    }

    private List<String> parseStringList(String json) {
        if (isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST_TYPE);
            return sanitizeStringList(values);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String toJson(List<String> values) {
        List<String> sanitized = sanitizeStringList(values);
        if (sanitized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstImage(List<String> images) {
        List<String> sanitized = sanitizeStringList(images);
        return sanitized.isEmpty() ? null : sanitized.get(0);
    }

    private List<String> normalizeProductImages(List<String> images) {
        List<String> sanitized = sanitizeStringList(images);
        List<String> normalized = new ArrayList<>();
        for (String image : sanitized) {
            String stored = storeDataUrlImage(image);
            if (!isBlank(stored)) {
                normalized.add(stored);
            }
        }
        return normalized;
    }

    private String storeDataUrlImage(String image) {
        if (isBlank(image)) {
            return null;
        }
        String trimmed = image.trim();
        if (!trimmed.startsWith("data:image/")) {
            return trimmed;
        }

        Matcher matcher = DATA_URL_IMAGE_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }

        String ext = matcher.group(1).toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
        String payload = matcher.group(2).replaceAll("\\s+", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        if (bytes.length == 0) {
            return null;
        }

        String fileName = "book_" + UUID.randomUUID() + "." + ext;
        String key = "admin/books/" + fileName;
        String contentType = "image/" + ext.replace("jpg", "jpeg");
        try {
            return s3StorageService.upload(
                    new java.io.ByteArrayInputStream(bytes), key, bytes.length, contentType);
        } catch (Exception ex) {
            return null;
        }
    }

    private String asString(Map<String, Object> patch, String key, String fallback) {
        if (patch == null || !patch.containsKey(key)) {
            return fallback;
        }
        Object value = patch.get(key);
        return value == null ? "" : trim(String.valueOf(value));
    }

    private String asNullableString(Map<String, Object> patch, String key, String fallback) {
        if (patch == null || !patch.containsKey(key)) {
            return trimToNull(fallback);
        }
        return trimToNull(patch.get(key));
    }

    private int asInt(Map<String, Object> patch, String key, int fallback) {
        if (patch == null || !patch.containsKey(key)) {
            return fallback;
        }
        return asInt(patch.get(key), fallback);
    }

    private int asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Integer asNullableInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private int toInt(Object value) {
        return asBigDecimal(value).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private long asLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String lower(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private boolean containsLower(String source, String lowerKeyword) {
        if (lowerKeyword == null || lowerKeyword.isEmpty()) {
            return true;
        }
        if (source == null) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private java.sql.Date toSqlDate(String date) {
        LocalDate parsed = parseLocalDate(date);
        return parsed == null ? null : java.sql.Date.valueOf(parsed);
    }

    private Timestamp toStartOfDay(String date) {
        LocalDate parsed = parseLocalDate(date);
        if (parsed == null) {
            return null;
        }
        return Timestamp.valueOf(parsed.atStartOfDay());
    }

    private Timestamp toEndOfDay(String date) {
        LocalDate parsed = parseLocalDate(date);
        if (parsed == null) {
            return null;
        }
        LocalDateTime atEnd = parsed.plusDays(1).atStartOfDay().minusNanos(1);
        return Timestamp.valueOf(atEnd);
    }

    private LocalDate parseLocalDate(String date) {
        String text = trim(date);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text, DATE_FORMAT);
        } catch (Exception ex) {
            return null;
        }
    }

    private String toDateString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().format(DATE_FORMAT);
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().toLocalDate().format(DATE_FORMAT);
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).format(DATE_FORMAT);
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate().format(DATE_FORMAT);
        }
        if (value instanceof java.util.Date) {
            return ((java.util.Date) value).toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT);
        }

        String text = String.valueOf(value).trim();
        if (text.length() >= 10) {
            return text.substring(0, 10);
        }
        return text;
    }

    private String toDateTimeString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().format(DATE_TIME_FORMAT);
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DATE_TIME_FORMAT);
        }
        if (value instanceof java.util.Date) {
            return ((java.util.Date) value).toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(DATE_TIME_FORMAT);
        }

        String text = String.valueOf(value).trim().replace('T', ' ');
        if (text.length() >= 19) {
            return text.substring(0, 19);
        }
        return text;
    }
}
