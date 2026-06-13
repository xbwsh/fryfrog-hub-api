package com.fryfrog.hub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fryfrogHubOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fryfrog Hub API")
                        .description("统一媒体后端 API - 支持音乐、漫画、电子书、视频的元数据管理和流媒体播放")
                        .version("0.1.0"));
    }
}
