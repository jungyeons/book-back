package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.PopupDto;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@RestController("adminPopupController")
@RequestMapping("/admin/api/popups")
public class PopupController {

    private final JdbcTemplate jdbc;

    public PopupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<PopupDto> rowMapper = (rs, rn) -> new PopupDto(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("link_url"),
            rs.getDate("start_date") != null ? rs.getDate("start_date").toLocalDate() : null,
            rs.getDate("end_date") != null ? rs.getDate("end_date").toLocalDate() : null,
            rs.getBoolean("is_active"),
            rs.getString("device_type"),
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

        if (title == null || title.isBlank() || startDate == null || endDate == null)
            return ResponseEntity.badRequest().build();

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO popups (title, content, link_url, start_date, end_date, is_active, device_type) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, title);
            ps.setString(2, content);
            ps.setString(3, linkUrl);
            ps.setString(4, startDate);
            ps.setString(5, endDate);
            ps.setBoolean(6, active);
            ps.setString(7, deviceType);
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

        if (title == null || title.isBlank() || startDate == null || endDate == null)
            return ResponseEntity.badRequest().build();

        int updated = jdbc.update(
            "UPDATE popups SET title=?, content=?, link_url=?, start_date=?, end_date=?, is_active=?, device_type=? WHERE id=?",
            title, content, linkUrl, startDate, endDate, active, deviceType, id
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
}
