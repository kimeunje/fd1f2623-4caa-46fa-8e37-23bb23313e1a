package com.secuhub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA (Single Page Application) 지원 설정
 *
 * Vue Router의 History 모드를 사용할 때, /dashboard, /login 등의 경로로
 * 직접 접속하거나 새로고침하면 백엔드에서 해당 경로의 리소스를 찾지 못해 404가 발생합니다.
 *
 * 이 설정은 API 경로(/api/**)가 아닌 모든 요청에 대해
 * static/index.html을 반환하여 Vue Router가 클라이언트 측에서 라우팅을 처리하도록 합니다.
 */
@Configuration
public class SpaWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);

                        // 실제 파일이 존재하면 그 파일을 반환 (JS, CSS, 이미지 등)
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // API 요청은 여기서 처리하지 않음 (Controller가 처리)
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }

                        // 그 외 모든 경로 → index.html 반환 (SPA 라우팅)
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}