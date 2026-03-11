package com.bookvillage.backend.config;

import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Paths;

@Configuration
public class TomcatConfig {

    @Value("${file.lab-upload-path:./uploads/lab}")
    private String labUploadPath;

    @Value("${file.product-image-path:./uploads/admin-products}")
    private String productImagePath;

    /**
     * 업로드 디렉토리를 Tomcat 웹 컨텍스트에 마운트하여 정적 리소스로 서빙한다.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadDirCustomizer() {
        return factory -> factory.addContextCustomizers(context -> {
            File labDir = Paths.get(labUploadPath).toAbsolutePath().toFile();
            labDir.mkdirs();

            File productDir = Paths.get(productImagePath).toAbsolutePath().toFile();
            productDir.mkdirs();

            WebResourceRoot resources = new StandardRoot(context);

            resources.addPreResources(
                    new DirResourceSet(resources, "/uploads", labDir.getAbsolutePath(), "/")
            );

            // 상품 이미지 서빙
            resources.addPreResources(
                    new DirResourceSet(resources, "/product-images", productDir.getAbsolutePath(), "/")
            );

            context.setResources(resources);
        });
    }
}
