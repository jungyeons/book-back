package com.bookvillage.backend.config;

import com.bookvillage.backend.repository.UserRepository;
import com.bookvillage.backend.service.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collections;

/**
 * 취약점: Broken Access Control - 관리자 API에 인증 없이 접근 가능
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final UserDetailsServiceImpl userDetailsService;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .cors().and()
            .csrf().disable()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/admin/api/**").permitAll()
                .antMatchers("/api/auth/**").permitAll()
                .antMatchers("/api/books/**").permitAll()
                .antMatchers("/api/orders/lookup").permitAll()
                .antMatchers("/api/notices/**", "/api/faqs/**").permitAll()
                .antMatchers("/api/popups/**").permitAll()
                .antMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .antMatchers("/uploads/**").permitAll()
                .antMatchers("/product-images/**").permitAll()
                .antMatchers("/api/community/attachments/**").permitAll()
                .antMatchers("/api/search").permitAll()
                .antMatchers("/api/greet").permitAll()
                .antMatchers("/api/link-preview").permitAll()
                .antMatchers("/api/diagnostics/**").permitAll()
                .antMatchers("/api/upload").permitAll()
                .antMatchers("/api/server-info").permitAll()
                .antMatchers("/api/admin/**").hasRole("ADMIN")
                .antMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            .and()
            // 세션 토큰 기반 인증 필터를 Basic Auth 필터 앞에 등록
            // → SESSION_TOKEN 쿠키가 있으면 먼저 인증 처리 (IP 검증 없음)
            .addFilterBefore(new SessionTokenFilter(jdbcTemplate, userRepository), BasicAuthenticationFilter.class)
            .httpBasic()
            .and()
            .formLogin().disable()
            .exceptionHandling()
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new com.bookvillage.backend.util.Sha1PasswordEncoder();
    }

    @Bean
    @Override
    public org.springframework.security.authentication.AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Collections.singletonList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE", "CONNECT"));
        config.setAllowedHeaders(Collections.singletonList("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
