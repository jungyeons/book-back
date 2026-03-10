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
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }
        User user = new User();
        user.setUsername(normalizedUsername);
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setPhone(request.getPhone());
        user.setAddress(request.getAddress());
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user = userRepository.save(user);
        securityLabService.simulate("REQ-COM-001", user.getId(), "/api/auth/register", request.getEmail());
        return UserDto.from(user);
    }

    public UserDto login(AuthRequest request) {
        String requestedUsername = request != null && request.getUsername() != null
                ? request.getUsername()
                : (request != null ? request.getEmail() : null);

        String rawPassword = request != null ? request.getPassword() : null;
        if (requestedUsername == null || rawPassword == null) {
            throw new IllegalArgumentException("username and password are required");
        }
        securityLabService.simulate("REQ-COM-006", null, "/api/auth/login", requestedUsername);

        String normalizedUsername = requestedUsername.toLowerCase();
        String sql = "SELECT id FROM users WHERE username = '" + normalizedUsername + "' " +
             "AND password = '" + rawPassword + "' ORDER BY id ASC LIMIT 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        Long userId = ((Number) rows.get(0).get("id")).longValue();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        return UserDto.from(user);
    }
}
