package com.bookvillage.mock.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Service
public class S3StorageService {

    private final AmazonS3 amazonS3;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Value("${cloud.aws.region.static:ap-northeast-2}")
    private String region;

    @Value("${cloud.aws.credentials.access-key:local-dummy-key}")
    private String accessKey;

    @Value("${file.board-storage-path:./uploads/board}")
    private String localBoardStoragePath;

    public S3StorageService(AmazonS3 amazonS3) {
        this.amazonS3 = amazonS3;
    }

    public String upload(InputStream inputStream, String key, long size, String contentType) {
        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read attachment stream", e);
        }

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType != null ? contentType : "application/octet-stream");
        if (!useLocalOnly()) {
            try {
                amazonS3.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(bytes), metadata));
                return String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
            } catch (Exception ignored) {
                // Fallback to local storage in dev/test when S3 is unavailable.
            }
        }

        Path localPath = toLocalPath(key);
        try {
            Files.createDirectories(localPath.getParent());
            Files.write(localPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return localPath.toUri().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store attachment", e);
        }
    }

    public InputStream download(String key) {
        if (!useLocalOnly()) {
            try {
                return amazonS3.getObject(bucket, key).getObjectContent();
            } catch (Exception ignored) {
                // Fallback to local storage in dev/test when S3 is unavailable.
            }
        }
        Path localPath = toLocalPath(key);
        try {
            return Files.newInputStream(localPath, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load attachment", e);
        }
    }

    public void deleteQuietly(String key) {
        Path localPath = toLocalPath(key);
        try {
            if (!useLocalOnly()) {
                amazonS3.deleteObject(bucket, key);
            }
        } catch (Exception ignored) {
        }
        try {
            Files.deleteIfExists(localPath);
        } catch (Exception ignored) {
        }
    }

    private boolean useLocalOnly() {
        String normalizedBucket = bucket == null ? "" : bucket.trim().toLowerCase();
        String normalizedAccessKey = accessKey == null ? "" : accessKey.trim().toLowerCase();
        return normalizedBucket.startsWith("local-dummy") || normalizedAccessKey.startsWith("local-dummy");
    }

    private Path toLocalPath(String key) {
        String safeKey = key == null ? "" : key.replace("\\", "/");
        if (safeKey.startsWith("/")) {
            safeKey = safeKey.substring(1);
        }
        return Paths.get(localBoardStoragePath).resolve(safeKey).normalize();
    }
}
