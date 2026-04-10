package com.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gitlab")
public class GitLabProperties {
    /**
     * GitLab 服务地址
     */
    private String url;
    
    /**
     * 个人访问令牌 (PAT)
     */
    private String token;
}