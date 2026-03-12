package com.bookvillage.backend.controller;

import com.bookvillage.backend.common.PageResponse;
import com.bookvillage.backend.common.SuccessResponse;
import com.bookvillage.backend.model.Product;
import com.bookvillage.backend.request.DeleteIdsRequest;
import com.bookvillage.backend.service.InMemoryDataStore;
import com.bookvillage.backend.service.S3StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/products")
public class ProductController {
    private final InMemoryDataStore store;
    private final S3StorageService s3StorageService;

    @Value("${file.product-image-path:./uploads/admin-products}")
    private String productImagePath;

    public ProductController(InMemoryDataStore store, S3StorageService s3StorageService) {
        this.store = store;
        this.s3StorageService = s3StorageService;
    }

    /**
     * 상품 이미지 업로드
     */
    @PostMapping("/upload-image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "파일을 선택해주세요."));
        }
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                originalName = "image.bin";
            }
            // 파일명에서 경로 구분자 제거
            originalName = originalName.replace("\\", "/");
            int idx = originalName.lastIndexOf('/');
            if (idx >= 0) originalName = originalName.substring(idx + 1);

            // S3 업로드 시도 (key: products/{uuid}_{filename})
            String uuid = UUID.randomUUID().toString().replace("-", "");
            String s3Key = "products/" + uuid + "_" + originalName;
            String imageUrl;
            try {
                String uploadedUrl = s3StorageService.upload(
                        file.getInputStream(), s3Key, file.getSize(), file.getContentType());
                if (uploadedUrl != null && uploadedUrl.startsWith("https://")) {
                    // S3 업로드 성공
                    imageUrl = uploadedUrl;
                } else {
                    // 로컬 폴백
                    imageUrl = saveLocally(file, originalName);
                }
            } catch (Exception e) {
                // S3 업로드 실패 시 로컬 저장
                imageUrl = saveLocally(file, originalName);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("url", imageUrl);
            body.put("fileName", originalName);
            body.put("size", file.getSize());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "이미지 업로드에 실패했습니다."));
        }
    }

    private String saveLocally(MultipartFile file, String originalName) throws Exception {
        File uploadDir = Paths.get(productImagePath).toAbsolutePath().toFile();
        uploadDir.mkdirs();
        File dest = new File(uploadDir, originalName);
        file.transferTo(dest);
        return "/product-images/" + originalName;
    }

    @GetMapping
    public PageResponse<Product> getProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return store.getProducts(keyword, status, category, page, pageSize);
    }

    @GetMapping("/{id}")
    public Product getProduct(@PathVariable String id) {
        return store.getProduct(id);
    }

    @PostMapping
    public Product createProduct(@RequestBody Product request) {
        return store.createProduct(request);
    }

    @PutMapping("/{id}")
    public Product updateProduct(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return store.updateProduct(id, patch);
    }

    @DeleteMapping
    public SuccessResponse deleteProducts(@RequestBody(required = false) DeleteIdsRequest request) {
        store.deleteProducts(request == null ? null : request.ids);
        return new SuccessResponse(true);
    }
}
