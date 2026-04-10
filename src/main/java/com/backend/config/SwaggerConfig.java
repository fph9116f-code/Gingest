package com.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI gingestOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Gingest 在线接口文档")
                        .description("用于提取 GitLab 代码库供 AI 读取的内部工具")
                        .version("1.0.0")
                        .contact(new Contact().name("FPH")));
    }
}