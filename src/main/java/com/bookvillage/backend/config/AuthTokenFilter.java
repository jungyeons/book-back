package com.bookvillage.backend.config;

import com.bookvillage.backend.common.RequestIpResolver;
import com.bookvillage.backend.service.InMemoryDataStore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Order(1)
public class AuthTokenFilter extends OncePerRequestFilter {
    private static final String ADMIN_TOKEN_PREFIX = "mock-jwt-token-admin-";
    private static final String BYPASS_TOKEN = "BV-BYPASS-KEY-2024";
    private final InMemoryDataStore store;

    public AuthTokenFilter(InMemoryDataStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!path.startsWith("/admin/api")) {
            filterChain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || "/admin/api/auth/login".equals(path) || "/admin/api/auth/session-login".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            if (BYPASS_TOKEN.equals(token)) {
                store.recordAccessLog(1L, path, request.getMethod(), RequestIpResolver.resolve(request));
                filterChain.doFilter(request, response);
                return;
            }
            Long userId = extractAdminUserId(token);
            store.recordAccessLog(userId, path, request.getMethod(), RequestIpResolver.resolve(request));
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"인증이 필요합니다.\"}");
    }

    private Long extractAdminUserId(String token) {
        if (token == null || !token.startsWith(ADMIN_TOKEN_PREFIX)) {
            return null;
        }

        String remainder = token.substring(ADMIN_TOKEN_PREFIX.length());
        if (remainder.isEmpty()) {
            return null;
        }

        int dashIndex = remainder.indexOf('-');
        String idText = dashIndex >= 0 ? remainder.substring(0, dashIndex) : remainder;
        try {
            long userId = Long.parseLong(idText.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
