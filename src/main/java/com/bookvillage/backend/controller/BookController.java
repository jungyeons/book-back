package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.BookDto;
import com.bookvillage.backend.security.UserPrincipal;
import com.bookvillage.backend.service.BookService;
import com.bookvillage.backend.service.LearningFeatureService;
import com.bookvillage.backend.service.SecurityLabService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;
    private final SecurityLabService securityLabService;
    private final LearningFeatureService learningFeatureService;

    @GetMapping("/search")
    public ResponseEntity<List<BookDto>> search(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category) {
        if (q != null && !q.trim().isEmpty()) {
            securityLabService.simulate("REQ-COM-010", principal != null ? principal.getUserId() : null, "/api/books/search", q);
        }
        if (category != null && !category.trim().isEmpty()) {
            securityLabService.simulate("REQ-COM-011", principal != null ? principal.getUserId() : null, "/api/books/search", category);
        }
        List<BookDto> books = bookService.search(q, category);
        return ResponseEntity.ok(books);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        List<String> categories = bookService.getCategories();
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<BookDto> getBook(
            @PathVariable Long bookId,
            @AuthenticationPrincipal UserPrincipal principal) {
        BookDto book = bookService.getBookById(bookId);
        if (principal != null) {
            learningFeatureService.trackRecentView(principal.getUserId(), bookId);
        }
        securityLabService.simulate("REQ-COM-012", principal != null ? principal.getUserId() : null, "/api/books/" + bookId, book.getDescription());
        return ResponseEntity.ok(book);
    }

    @GetMapping("/{bookId}/shipping-info")
    public ResponseEntity<Map<String, Object>> shippingInfo(
            @PathVariable Long bookId,
            @RequestParam(required = false) String zipcode,
            @AuthenticationPrincipal UserPrincipal principal) {
        String input = zipcode == null ? "" : zipcode;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookId", bookId);
        body.put("zipcode", zipcode);
        body.put("etaDays", 1 + (bookId.intValue() % 3));
        body.put("carrier", "BOOKVILLAGE Logistics");
        securityLabService.simulate("REQ-COM-013", principal != null ? principal.getUserId() : null, "/api/books/" + bookId + "/shipping-info", input);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{bookId}/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @PathVariable Long bookId,
            @RequestParam(required = false) String filePath,
            @AuthenticationPrincipal UserPrincipal principal) {
        BookDto book = bookService.getBookById(bookId);
        String source = filePath == null ? "preview-" + bookId + ".txt" : filePath;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookId", bookId);
        body.put("source", source);
        body.put("previewText", (book.getDescription() == null ? "" : book.getDescription()).substring(0,
                Math.min(120, book.getDescription() == null ? 0 : book.getDescription().length())));
        securityLabService.simulate("REQ-COM-014", principal != null ? principal.getUserId() : null, "/api/books/" + bookId + "/preview", source);
        return ResponseEntity.ok(body);
    }

    /**
     * [SSRF 취약점] 서버가 클라이언트가 전달한 URL을 직접 fetch하여 반환합니다.
     * url 파라미터에 대한 검증이 없으므로 내부 메타데이터 서버(169.254.169.254 등) 접근이 가능합니다.
     * [필터링] "127.0.0.1" 및 "169.254.169.254" 문자열만 차단
     * 우회 가능: 2130706433(십진수), 0x7f000001(16진수), 0177.0.0.1(8진수),
     *           localhost, ::1, http://[::ffff:7f00:1] 등
     */
    @GetMapping("/{bookId}/image-proxy")
    public ResponseEntity<byte[]> imageProxy(
            @PathVariable Long bookId,
            @RequestParam String url) {
        String trimmedUrl = url.trim();
        if (trimmedUrl.contains("127.0.0.1") || trimmedUrl.contains("169.254.169.254")) {
            byte[] errBody = "접근이 차단된 주소입니다.".getBytes();
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(errBody);
        }
        try {
            URL targetUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "BookVillage-ImageProxy/1.0");
            conn.connect();

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();

            InputStream is = (status >= 400 || conn.getErrorStream() != null)
                    ? conn.getErrorStream()
                    : conn.getInputStream();
            byte[] data = (is != null) ? is.readAllBytes() : new byte[0];

            MediaType mediaType;
            try {
                String rawType = contentType != null ? contentType.split(";")[0].trim() : "application/octet-stream";
                mediaType = MediaType.parseMediaType(rawType);
            } catch (Exception e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(data);
        } catch (Exception e) {
            byte[] errBody = ("Error fetching URL: " + e.getMessage()).getBytes();
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(errBody);
        }
    }
}
