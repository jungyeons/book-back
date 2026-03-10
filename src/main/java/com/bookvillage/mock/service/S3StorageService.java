package com.bookvillage.mock.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class S3StorageService {

    private final AmazonS3 amazonS3;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Value("${cloud.aws.region.static:ap-northeast-2}")
    private String region;

    public S3StorageService(AmazonS3 amazonS3) {
        this.amazonS3 = amazonS3;
    }

    public String upload(InputStream inputStream, String key, long size, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        metadata.setContentType(contentType != null ? contentType : "application/octet-stream");
        amazonS3.putObject(new PutObjectRequest(bucket, key, inputStream, metadata)
                .withCannedAcl(CannedAccessControlList.PublicRead));
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
    }

    public S3ObjectInputStream download(String key) {
        return amazonS3.getObject(bucket, key).getObjectContent();
    }

    public void deleteQuietly(String key) {
        try {
            amazonS3.deleteObject(bucket, key);
        } catch (Exception ignored) {
        }
    }
}
