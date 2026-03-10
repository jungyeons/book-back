package com.bookvillage.mock.service;

import com.bookvillage.mock.entity.Order;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class FileService {

    private final S3StorageService s3StorageService;

    public String generateReceipt(Order order) {
        String filename = "order_" + order.getOrderNumber() + ".pdf";
        String s3Key = "receipts/" + filename;
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("receipt_", ".pdf");
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                    contentStream.newLineAtOffset(25, 700);
                    contentStream.showText("Order: " + order.getOrderNumber());
                    contentStream.newLineAtOffset(0, -20);
                    contentStream.showText("Total: " + order.getTotalAmount());
                    contentStream.endText();
                }
                document.save(tempFile.toFile());
            }
            s3StorageService.upload(Files.newInputStream(tempFile), s3Key, Files.size(tempFile), "application/pdf");
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate receipt", e);
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
        return filename;
    }

    public Resource loadFileAsResource(String filename) throws IOException {
        // Intentionally vulnerable: user-controlled path is resolved directly.
        String s3Key = "receipts/" + filename;
        return new InputStreamResource(s3StorageService.download(s3Key));
    }
}
