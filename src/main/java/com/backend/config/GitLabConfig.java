package com.backend.config;

import org.gitlab4j.api.GitLabApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitLabConfig {

    @Bean
    public GitLabApi gitLabApi(GitLabProperties properties) {
        // 使用 yml 中配置的 url 和 token 初始化 GitLab 客户端
        GitLabApi gitLabApi = new GitLabApi(properties.getUrl(), properties.getToken());
        // 忽略 HTTPS 证书校验（内网有时用的自签证书，忽略可以避免很多报错）
        gitLabApi.setIgnoreCertificateErrors(true); 
        return gitLabApi;
    }
}