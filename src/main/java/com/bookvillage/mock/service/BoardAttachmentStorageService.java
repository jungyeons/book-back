package com.bookvillage.mock.service;

import lombok.Getter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class BoardAttachmentStorageService {

    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip"
    ));

    private final S3StorageService s3StorageService;

    public BoardAttachmentStorageService(S3StorageService s3StorageService) {
        this.s3StorageService = s3StorageService;
    }

    public StoredFile store(MultipartFile file, Long postId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Attachment size must be 10MB or less.");
        }

        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported file extension.");
        }

        String storedName = "post_" + postId + "_" + UUID.randomUUID() + "." + ext;
        String s3Key = "board/" + storedName;
        String contentType = normalizeContentType(file.getContentType());

        try {
            s3StorageService.upload(file.getInputStream(), s3Key, file.getSize(), contentType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store attachment", e);
        }

        return new StoredFile(originalName, storedName, contentType, file.getSize());
    }

    public Resource loadAsResource(String storedName) {
        String safeName = sanitizeStoredName(storedName);
        String s3Key = "board/" + safeName;
        return new InputStreamResource(s3StorageService.download(s3Key));
    }

    public void deleteQuietly(String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) {
            return;
        }
        String safeName = sanitizeStoredName(storedName);
        s3StorageService.deleteQuietly("board/" + safeName);
    }

    private String sanitizeOriginalName(String originalName) {
        String normalized = originalName == null ? "" : originalName.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("original filename is required");
        }

        normalized = normalized.replace("\\", "/");
        int idx = normalized.lastIndexOf('/');
        if (idx >= 0) {
            normalized = normalized.substring(idx + 1);
        }
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException("Invalid original filename.");
        }
        return normalized;
    }

    private String sanitizeStoredName(String storedName) {
        String normalized = storedName == null ? "" : storedName.trim();
        if (normalized.isEmpty() || normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Invalid stored filename.");
        }
        return normalized;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("File extension is required.");
        }
        return filename.substring(dot + 1);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return "application/octet-stream";
        }
        return contentType.trim();
    }

    @Getter
    public static class StoredFile {
        private final String originalName;
        private final String storedName;
        private final String contentType;
        private final long size;

        public StoredFile(String originalName, String storedName, String contentType, long size) {
            this.originalName = originalName;
            this.storedName = storedName;
            this.contentType = contentType;
            this.size = size;
        }
    }
}
