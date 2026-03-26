package com.bookvillage.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * React SPA 설정: frontend 빌드 결과물을 src/main/resources/static에서 서빙
 * API 경로가 아닌 요청에 대해 index.html로 폴백 (SPA 라우팅 지원)
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 업로드 파일 서빙 (팝업 이미지 등)
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:./uploads/");

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new ResourceResolver() {
                    @Override
                    public Resource resolveResource(HttpServletRequest request, String requestPath,
                            List<? extends Resource> locations, ResourceResolverChain chain) {
                        Resource resolved = chain.resolveResource(request, requestPath, locations);
                        if (resolved != null) return resolved;
                        if (!requestPath.startsWith("api")) {
                            return new ClassPathResource("/static/index.html");
                        }
                        return null;
                    }
                    @Override
                    public String resolveUrlPath(String resourcePath, List<? extends Resource> locations,
                            ResourceResolverChain chain) {
                        return chain.resolveUrlPath(resourcePath, locations);
                    }
                });
    }
}
