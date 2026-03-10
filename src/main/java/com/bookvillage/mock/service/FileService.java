package com.bookvillage.mock.service;

import com.bookvillage.mock.entity.Order;
import com.bookvillage.mock.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class FileService {
    private static final DateTimeFormatter RECEIPT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${file.storage-path:./uploads/receipts}")
    private String storagePath;

    private Path basePath;

    @PostConstruct
    public void init() {
        basePath = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    public String generateReceipt(Order order) {
        String filename = "order_" + order.getOrderNumber() + ".pdf";
        Path filePath = basePath.resolve(filename).normalize();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                final float left = 50f;
                final float right = 545f;
                float y = 755f;

                // Header
                y = drawText(contentStream, PDType1Font.HELVETICA_BOLD, 20, left, y, "BOOK VILLAGE RECEIPT", 26);
                y = drawText(contentStream, PDType1Font.HELVETICA, 10, left, y, "Official payment receipt", 16);
                y = drawLine(contentStream, left, right, y - 4f);

                // Order metadata
                y -= 18f;
                y = drawLabelValue(contentStream, left, right, y, "Order Number", safeText(order.getOrderNumber()));
                y = drawLabelValue(contentStream, left, right, y, "Issued At", formatDate(order.getCreatedAt()));
                y = drawLabelValue(contentStream, left, right, y, "Payment Method", safeText(order.getPaymentMethod()));
                y = drawLabelValue(contentStream, left, right, y, "Order Status", safeText(order.getStatus()));
                y = drawLabelValue(contentStream, left, right, y, "Ship To", safeText(order.getShippingAddress()));

                // Items header
                y -= 8f;
                y = drawLine(contentStream, left, right, y);
                y -= 16f;
                drawTextAt(contentStream, PDType1Font.HELVETICA_BOLD, 10, left, y, "#");
                drawTextAt(contentStream, PDType1Font.HELVETICA_BOLD, 10, left + 25f, y, "Book");
                drawTextAt(contentStream, PDType1Font.HELVETICA_BOLD, 10, left + 220f, y, "Qty");
                drawTextAt(contentStream, PDType1Font.HELVETICA_BOLD, 10, left + 275f, y, "Unit Price");
                drawTextAt(contentStream, PDType1Font.HELVETICA_BOLD, 10, left + 415f, y, "Amount");
                y -= 8f;
                y = drawLine(contentStream, left, right, y);

                // Items rows
                List<OrderItem> items = order.getItems() == null ? new ArrayList<>() : order.getItems();
                BigDecimal subtotal = BigDecimal.ZERO;
                if (items.isEmpty()) {
                    y -= 18f;
                    drawTextAt(contentStream, PDType1Font.HELVETICA_OBLIQUE, 10, left + 25f, y, "No item details available");
                } else {
                    int idx = 1;
                    for (OrderItem item : items) {
                        y -= 18f;
                        BigDecimal unit = item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
                        int qty = item.getQuantity() == null ? 0 : item.getQuantity();
                        BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(qty));
                        subtotal = subtotal.add(lineTotal);

                        drawTextAt(contentStream, PDType1Font.HELVETICA, 10, left, y, String.valueOf(idx++));
                        drawTextAt(contentStream, PDType1Font.HELVETICA, 10, left + 25f, y, "Book #" + item.getBookId());
                        drawTextAt(contentStream, PDType1Font.HELVETICA, 10, left + 220f, y, String.valueOf(qty));
                        drawRightText(contentStream, PDType1Font.HELVETICA, 10, left + 390f, y, formatCurrency(unit));
                        drawRightText(contentStream, PDType1Font.HELVETICA_BOLD, 10, right, y, formatCurrency(lineTotal));
                    }
                }

                // Summary
                y -= 14f;
                y = drawLine(contentStream, left, right, y);
                y -= 18f;
                BigDecimal totalAmount = order.getTotalAmount() == null ? subtotal : order.getTotalAmount();
                y = drawRightLabelValue(contentStream, left + 230f, right, y, "Subtotal", formatCurrency(subtotal));
                y = drawRightLabelValue(contentStream, left + 230f, right, y, "Shipping", formatCurrency(BigDecimal.ZERO));
                y = drawRightLabelValue(contentStream, left + 230f, right, y, "Total", formatCurrency(totalAmount), true);

                // Footer
                y -= 8f;
                y = drawLine(contentStream, left, right, y);
                y -= 18f;
                drawTextAt(contentStream, PDType1Font.HELVETICA_OBLIQUE, 9, left, y,
                        "Thank you for your purchase. Keep this receipt for exchanges/refunds.");
            }
            document.save(filePath.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate receipt", e);
        }
        return filename;
    }

    private float drawLabelValue(
            PDPageContentStream contentStream,
            float left,
            float right,
            float y,
            String label,
            String value
    ) throws IOException {
        drawTextAt(contentStream, PDType1Font.HELVETICA_BOLD, 10, left, y, safeText(label));
        drawRightText(contentStream, PDType1Font.HELVETICA, 10, right, y, safeText(value));
        return y - 16f;
    }

    private float drawRightLabelValue(
            PDPageContentStream contentStream,
            float left,
            float right,
            float y,
            String label,
            String value
    ) throws IOException {
        return drawRightLabelValue(contentStream, left, right, y, label, value, false);
    }

    private float drawRightLabelValue(
            PDPageContentStream contentStream,
            float left,
            float right,
            float y,
            String label,
            String value,
            boolean bold
    ) throws IOException {
        PDFont valueFont = bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
        float valueSize = bold ? 12f : 10f;
        drawTextAt(contentStream, PDType1Font.HELVETICA, 10, left, y, safeText(label));
        drawRightText(contentStream, valueFont, valueSize, right, y, safeText(value));
        return y - 16f;
    }

    private float drawText(
            PDPageContentStream contentStream,
            PDFont font,
            float fontSize,
            float x,
            float y,
            String text,
            float nextLineGap
    ) throws IOException {
        drawTextAt(contentStream, font, fontSize, x, y, text);
        return y - nextLineGap;
    }

    private void drawTextAt(
            PDPageContentStream contentStream,
            PDFont font,
            float fontSize,
            float x,
            float y,
            String text
    ) throws IOException {
        contentStream.beginText();
        contentStream.setFont(font, fontSize);
        contentStream.newLineAtOffset(x, y);
        contentStream.showText(safeText(text));
        contentStream.endText();
    }

    private void drawRightText(
            PDPageContentStream contentStream,
            PDFont font,
            float fontSize,
            float rightX,
            float y,
            String text
    ) throws IOException {
        String safe = safeText(text);
        float textWidth = font.getStringWidth(safe) / 1000f * fontSize;
        float x = rightX - textWidth;
        drawTextAt(contentStream, font, fontSize, x, y, safe);
    }

    private float drawLine(PDPageContentStream contentStream, float leftX, float rightX, float y) throws IOException {
        contentStream.moveTo(leftX, y);
        contentStream.lineTo(rightX, y);
        contentStream.stroke();
        return y;
    }

    private String formatCurrency(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
        return String.format(Locale.US, "KRW %,d", normalized.longValue());
    }

    private String formatDate(LocalDateTime value) {
        LocalDateTime dateTime = value == null ? LocalDateTime.now() : value;
        return dateTime.format(RECEIPT_DATE_FORMAT);
    }

    private String safeText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch >= 32 && ch <= 126) {
                sb.append(ch);
            } else if (ch == '\n' || ch == '\r' || ch == '\t') {
                sb.append(' ');
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    public Resource loadFileAsResource(String filename) throws IOException {
        // Intentionally vulnerable: user-controlled path is resolved directly.
        Path filePath = basePath.resolve(filename).normalize();

        Resource resource = new UrlResource(filePath.toUri());
        if (resource.exists() && resource.isReadable()) {
            return resource;
        }
        throw new IOException("File not found: " + filename);
    }
}
