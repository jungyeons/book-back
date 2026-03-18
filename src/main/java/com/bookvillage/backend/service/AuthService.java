package com.bookvillage.backend.service;

import com.bookvillage.backend.dto.AuthRequest;
import com.bookvillage.backend.dto.RegisterRequest;
import com.bookvillage.backend.dto.UserDto;
import com.bookvillage.backend.entity.User;
import com.bookvillage.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 취약점: SHA1 해시를 사용한 약한 패스워드 저장
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SecurityLabService securityLabService;

    /**
     * 취약점: SQL Injection - 회원가입 폼 입력값을 문자열 결합으로 SQL에 삽입
     * 예시 공격: username 필드에 admin','admin@hack.com','pwdhash','Hacker','','','ADMIN','ACTIVE'); -- 입력
     */
    public UserDto register(RegisterRequest request) {
        if (request == null || request.getUsername() == null || request.getEmail() == null || request.getPassword() == null || request.getName() == null) {
            throw new IllegalArgumentException("username, email, password, and name are required");
        }
        String normalizedUsername = request.getUsername().toLowerCase();
        if (normalizedUsername.isEmpty()) {
            throw new IllegalArgumentException("username is required");
        }
        if (request.getPassword().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        // 취약점: 중복 검사도 문자열 결합으로 SQL Injection 가능
        String checkSql = "SELECT COUNT(*) FROM users WHERE username = '" + normalizedUsername + "'";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("Username already exists");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        String email = request.getEmail();
        String name = request.getName();
        String phone = request.getPhone() != null ? request.getPhone() : "";
        String address = request.getAddress() != null ? request.getAddress() : "";

        // 취약점: 문자열 결합으로 INSERT → SQL Injection 가능
        String insertSql = "INSERT INTO users (username, email, password, name, phone, address, role, status) "
                + "VALUES ('" + normalizedUsername + "', '" + email + "', '" + hashedPassword + "', '"
                + name + "', '" + phone + "', '" + address + "', 'USER', 'ACTIVE')";
        jdbcTemplate.execute(insertSql);

        // 방금 삽입한 유저 조회
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE username = '" + normalizedUsername + "' ORDER BY id DESC LIMIT 1");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Registration failed");
        }
        Long userId = ((Number) rows.get(0).get("id")).longValue();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Registration failed"));

        securityLabService.simulate("REQ-COM-001", user.getId(), "/api/auth/register", request.getEmail());
        return UserDto.from(user);
    }

    public UserDto login(AuthRequest request, String clientIp) {
        String requestedUsername = request != null && request.getUsername() != null
                ? request.getUsername()
                : (request != null ? request.getEmail() : null);

        String rawPassword = request != null ? request.getPassword() : null;
        if (requestedUsername == null || rawPassword == null) {
            throw new IllegalArgumentException("username and password are required");
        }
        securityLabService.simulate("REQ-COM-006", null, "/api/auth/login", requestedUsername);

        String normalizedUsername = requestedUsername.toLowerCase();
        String hashedPassword = passwordEncoder.encode(rawPassword);
        String sql = "SELECT id FROM users WHERE username = '" + normalizedUsername + "' " +
             "AND password = '" + hashedPassword + "' ORDER BY id ASC LIMIT 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        Long userId = ((Number) rows.get(0).get("id")).longValue();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        // 취약점 시나리오: 로그인 IP를 세션과 함께 저장 (이중 검증용)
        // /cookie-login API에서 이 IP와 요청 IP를 비교하지만
        // RequestIpResolver가 X-Forwarded-For 헤더를 신뢰 → XSS 탈취 후 헤더 조작으로 우회 가능
        String sessionToken = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO user_sessions (user_id, session_key, active, login_ip) VALUES (?, ?, true, ?)",
                userId, sessionToken, clientIp);

        return UserDto.from(user, sessionToken);
    }
}
