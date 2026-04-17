package com.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "gingest.ignore")
public class GingestIgnoreProperties {
    private Set<String> extensions = new HashSet<>();
    private Set<String> directories = new HashSet<>();
    private Set<String> fileNames = new HashSet<>();
}