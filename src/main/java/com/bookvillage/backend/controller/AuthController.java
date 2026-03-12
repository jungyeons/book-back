package com.bookvillage.backend.controller;

import com.bookvillage.backend.dto.AuthRequest;
import com.bookvillage.backend.dto.RegisterRequest;
import com.bookvillage.backend.dto.UserDto;
import com.bookvillage.backend.security.UserPrincipal;
import com.bookvillage.backend.service.AuthService;
import com.bookvillage.backend.service.LearningFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LearningFeatureService learningFeatureService;
    private final JdbcTemplate jdbcTemplate;

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody RegisterRequest request) {
        UserDto user = authService.register(request);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/login")
    public ResponseEntity<UserDto> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String clientIp = resolveClientIp(httpRequest);
        try {
            UserDto user = authService.login(request);
            writeAccessLog(user.getId(), "/api/auth/login", "LOGIN", clientIp);

            // 취약점: 세션 토큰 쿠키 설정
            // - HttpOnly 미설정 → XSS로 document.cookie 탈취 가능
            // - IP 바인딩 없음 → 타 단말에서 세션 토큰만으로 권한 우회 가능
            String sessionCookie = "SESSION_TOKEN=" + user.getSessionToken()
                    + "; Path=/; Max-Age=86400; SameSite=Lax";

            return ResponseEntity.ok()
                    .header("Set-Cookie", sessionCookie)
                    .body(user);
        } catch (RuntimeException ex) {
            writeAccessLog(null, "/api/auth/login", "LOGIN_FAIL", clientIp);
            throw ex;
        }
    }

    @PostMapping("/find-id")
    public ResponseEntity<Map<String, Object>> findId(@RequestBody Map<String, String> request) {
        String name = request != null ? request.get("name") : null;
        String email = request != null ? request.get("email") : null;
        return ResponseEntity.ok(learningFeatureService.findId(name, email));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, Object>> requestPasswordReset(@RequestBody Map<String, String> request) {
        String email = request != null ? request.get("email") : null;
        return ResponseEntity.ok(learningFeatureService.requestPasswordReset(email));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@RequestBody Map<String, String> request) {
        String email = request != null ? request.get("email") : null;
        Long userId = null;
        if (request != null && request.get("userId") != null && !request.get("userId").trim().isEmpty()) {
            userId = Long.valueOf(request.get("userId").trim());
        }
        String token = request != null ? request.get("token") : null;
        String newPassword = request != null ? request.get("newPassword") : null;
        learningFeatureService.confirmPasswordReset(userId, email, token, newPassword);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/address-search")
    public ResponseEntity<List<Map<String, Object>>> searchAddress(@RequestParam("q") String query) {
        return ResponseEntity.ok(learningFeatureService.searchAddress(null, query));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Long actorUserId = principal != null ? principal.getUserId() : null;
        if (actorUserId == null && session != null && session.getAttribute("AUTH_USER_ID") != null) {
            Object raw = session.getAttribute("AUTH_USER_ID");
            if (raw instanceof Number) {
                actorUserId = ((Number) raw).longValue();
            } else {
                actorUserId = Long.valueOf(String.valueOf(raw));
            }
        }
        if (actorUserId != null) {
            learningFeatureService.logout(actorUserId);
        }
        if (session != null) {
            // Intentionally vulnerable: do not invalidate or rotate session on logout.
            // Session remains valid and can be reused after logout.
            session.setAttribute("LAST_LOGOUT_AT", System.currentTimeMillis());
        }
        // 취약점: SecurityContext만 클리어하고 DB의 user_sessions는 비활성화하지 않음
        // → 로그아웃 후에도 세션 토큰이 여전히 유효 (세션 미파기 취약점)
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok()
                .header("Set-Cookie", "SESSION_TOKEN=; Max-Age=0; Path=/")
                .body(Map.of("message", "로그아웃 되었습니다"));
    }

    /**
     * Cookie-based access endpoint.
     * Reads the 'role' cookie value directly and grants access accordingly.
     * Setting role=admin in the cookie allows access to privileged operations.
     */
    @GetMapping("/cookie-login")
    public ResponseEntity<Map<String, Object>> cookieLogin(HttpServletRequest request) {
        String role = "guest";
        String userId = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("role".equals(cookie.getName())) {
                    role = cookie.getValue();
                }
                if ("userId".equals(cookie.getName())) {
                    userId = cookie.getValue();
                }
            }
        }

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("role", role);
        response.put("userId", userId);

        if ("admin".equalsIgnoreCase(role)) {
            response.put("access", "granted");
            response.put("message", "관리자 권한으로 접근되었습니다.");
            response.put("adminData", Map.of(
                    "totalUsers", 1024,
                    "serverInfo", "BookVillage v2.1.0 / MySQL 8.0",
                    "secretKey", "bv-internal-2024-secret"
            ));
        } else {
            response.put("access", "limited");
            response.put("message", "일반 사용자로 접근되었습니다. role=admin 쿠키를 설정하면 관리자 기능을 이용할 수 있습니다.");
        }
        return ResponseEntity.ok(response);
    }

    private void writeAccessLog(Long userId, String endpoint, String method, String ipAddress) {
        String safeEndpoint = endpoint == null ? "/" : endpoint.trim();
        if (safeEndpoint.isEmpty()) {
            safeEndpoint = "/";
        }
        if (safeEndpoint.length() > 255) {
            safeEndpoint = safeEndpoint.substring(0, 255);
        }

        String safeMethod = method == null ? "GET" : method.trim().toUpperCase();
        if (safeMethod.isEmpty()) {
            safeMethod = "GET";
        }
        if (safeMethod.length() > 10) {
            safeMethod = safeMethod.substring(0, 10);
        }

        String safeIp = ipAddress == null ? "" : ipAddress.trim();
        if (safeIp.isEmpty()) {
            safeIp = "unknown";
        }
        if (safeIp.length() > 45) {
            safeIp = safeIp.substring(0, 45);
        }

        jdbcTemplate.update(
                "INSERT INTO access_logs (user_id, endpoint, method, ip_address) VALUES (?, ?, ?, ?)",
                userId,
                safeEndpoint,
                safeMethod,
                safeIp
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }

        String xForwardedFor = trim(request.getHeader("X-Forwarded-For"));
        if (!xForwardedFor.isEmpty()) {
            String[] parts = xForwardedFor.split(",");
            if (parts.length > 0) {
                String first = trim(parts[0]);
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }

        String xRealIp = trim(request.getHeader("X-Real-IP"));
        if (!xRealIp.isEmpty()) {
            return xRealIp;
        }

        return trim(request.getRemoteAddr());
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
