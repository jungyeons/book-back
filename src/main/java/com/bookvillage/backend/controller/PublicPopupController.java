package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.PopupDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController("publicPopupController")
@RequestMapping("/api/popups")
public class PublicPopupController {

    private final JdbcTemplate jdbc;

    public PublicPopupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    // GET /api/popups/active?deviceType=pc|mobile|all
    @GetMapping("/active")
    public List<PopupDto> getActivePopups(
            @RequestParam(required = false, defaultValue = "all") String deviceType
    ) {
        String today = LocalDate.now().toString();
        if ("all".equals(deviceType)) {
            return jdbc.query(
                "SELECT * FROM popups WHERE is_active = 1 AND start_date <= ? AND end_date >= ? ORDER BY id DESC",
                rowMapper, today, today
            );
        }
        return jdbc.query(
            "SELECT * FROM popups WHERE is_active = 1 AND start_date <= ? AND end_date >= ? AND (device_type = 'all' OR device_type = ?) ORDER BY id DESC",
            rowMapper, today, today, deviceType
        );
    }
}
