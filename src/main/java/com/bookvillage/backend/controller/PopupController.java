package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.PopupDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.bookvillage.backend.service.S3StorageService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController("adminPopupController")
@RequestMapping("/admin/api/popups")
public class PopupController {

    private final JdbcTemplate jdbc;
    private final S3StorageService s3StorageService;

    public PopupController(JdbcTemplate jdbc, S3StorageService s3StorageService) {
        this.jdbc = jdbc;
        this.s3StorageService = s3StorageService;
    }

    private final RowMapper<PopupDto> rowMapper = (rs, rn) -> new PopupDto(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("link_url"),
            rs.getDate("start_date")   != null ? rs.getDate("start_date").toLocalDate()       : null,
            rs.getDate("end_date")     != null ? rs.getDate("end_date").toLocalDate()         : null,
            rs.getBoolean("is_active"),
            rs.getString("device_type"),
            rs.getString("popup_type"),
            rs.getString("image_url"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null
    );

    // ── GET /admin/api/popups ───────────────────────────────────────────
    @GetMapping
    public List<PopupDto> listPopups(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) Boolean active
    ) {
        StringBuilder sql = new StringBuilder("SELECT * FROM popups WHERE 1=1");
        if (keyword != null && !keyword.isBlank())
            sql.append(" AND title LIKE '%").append(keyword.replace("'", "''")).append("%'");
        if (deviceType != null && !deviceType.isBlank() && !deviceType.equals("all"))
            sql.append(" AND device_type = '").append(deviceType.replace("'", "''")).append("'");
        if (active != null)
            sql.append(" AND is_active = ").append(active ? 1 : 0);
        sql.append(" ORDER BY id DESC");
        return jdbc.query(sql.toString(), rowMapper);
    }

    // ── GET /admin/api/popups/{id} ──────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<PopupDto> getPopup(@PathVariable Long id) {
        List<PopupDto> list = jdbc.query("SELECT * FROM popups WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(list.get(0));
    }

    // ── POST /admin/api/popups ──────────────────────────────────────────
    @PostMapping
    public ResponseEntity<PopupDto> createPopup(@RequestBody Map<String, Object> body) {
        String title      = (String) body.get("title");
        String content    = (String) body.getOrDefault("content", "");
        String linkUrl    = (String) body.getOrDefault("linkUrl", null);
        String startDate  = (String) body.get("startDate");
        String endDate    = (String) body.get("endDate");
        boolean active    = Boolean.TRUE.equals(body.get("isActive"));
        String deviceType = (String) body.getOrDefault("deviceType", "all");
        String popupType  = (String) body.getOrDefault("popupType", "update");
        String imageUrl   = (String) body.getOrDefault("imageUrl", null);

        if (title == null || title.isBlank() || startDate == null || endDate == null)
            return ResponseEntity.badRequest().build();

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO popups (title, content, link_url, start_date, end_date, is_active, device_type, popup_type, image_url) VALUES (?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, title);
            ps.setString(2, content);
            ps.setString(3, linkUrl);
            ps.setString(4, startDate);
            ps.setString(5, endDate);
            ps.setBoolean(6, active);
            ps.setString(7, deviceType);
            ps.setString(8, popupType);
            ps.setString(9, imageUrl);
            return ps;
        }, keyHolder);

        Long newId = keyHolder.getKey().longValue();
        List<PopupDto> created = jdbc.query("SELECT * FROM popups WHERE id = ?", rowMapper, newId);
        return ResponseEntity.ok(created.get(0));
    }

    // ── PUT /admin/api/popups/{id} ──────────────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<PopupDto> updatePopup(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String title      = (String) body.get("title");
        String content    = (String) body.getOrDefault("content", "");
        String linkUrl    = (String) body.getOrDefault("linkUrl", null);
        String startDate  = (String) body.get("startDate");
        String endDate    = (String) body.get("endDate");
        boolean active    = Boolean.TRUE.equals(body.get("isActive"));
        String deviceType = (String) body.getOrDefault("deviceType", "all");
        String popupType  = (String) body.getOrDefault("popupType", "update");
        String imageUrl   = (String) body.getOrDefault("imageUrl", null);

        if (title == null || title.isBlank() || startDate == null || endDate == null)
            return ResponseEntity.badRequest().build();

        int updated = jdbc.update(
            "UPDATE popups SET title=?, content=?, link_url=?, start_date=?, end_date=?, is_active=?, device_type=?, popup_type=?, image_url=? WHERE id=?",
            title, content, linkUrl, startDate, endDate, active, deviceType, popupType, imageUrl, id
        );
        if (updated == 0) return ResponseEntity.notFound().build();

        List<PopupDto> result = jdbc.query("SELECT * FROM popups WHERE id = ?", rowMapper, id);
        return ResponseEntity.ok(result.get(0));
    }

    // ── DELETE /admin/api/popups/{id} ───────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePopup(@PathVariable Long id) {
        int deleted = jdbc.update("DELETE FROM popups WHERE id = ?", id);
        return deleted > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ── POST /admin/api/popups/upload-image ─────────────────────────────
    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "파일을 선택해주세요."));
        }
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                originalName = "image.jpg";
            }
            originalName = originalName.replace("\\", "/");
            int idx = originalName.lastIndexOf('/');
            if (idx >= 0) originalName = originalName.substring(idx + 1);

            String uuid = UUID.randomUUID().toString().replace("-", "");
            String s3Key = "popups/" + uuid + "_" + originalName;
            String imageUrl;
            try {
                imageUrl = s3StorageService.upload(
                        file.getInputStream(), s3Key, file.getSize(), file.getContentType());
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "S3 업로드 실패: " + e.getMessage()));
            }
            return ResponseEntity.ok(Map.of("imageUrl", imageUrl));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "이미지 업로드 실패: " + e.getMessage()));
        }
    }
}
