package com.backend.service;

import com.backend.config.GingestIgnoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FilterConfigService {

    private final GingestIgnoreProperties properties;

    @Getter
    private Set<String> ignoreExtensions;
    @Getter
    private Set<String> ignoreDirectories;
    @Getter
    private Set<String> ignoreFileNames;

    @PostConstruct
    public void init() {
        // 启动时将配置文件的内容加载到内存中
        this.ignoreExtensions = new HashSet<>(properties.getExtensions());
        this.ignoreDirectories = new HashSet<>(properties.getDirectories());
        this.ignoreFileNames = new HashSet<>(properties.getFileNames());
    }

    /**
     * 提供给前端的统一获取接口
     */
    public Map<String, Set<String>> getAllFilters() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("extensions", ignoreExtensions);
        map.put("directories", ignoreDirectories);
        map.put("fileNames", ignoreFileNames);
        return map;
    }

    // ==========================================
    // 预留的方法：供后续临时动态添加限制规则
    // ==========================================
    public void addIgnoreExtension(String ext) {
        this.ignoreExtensions.add(ext.toLowerCase());
    }

    public void addIgnoreDirectory(String dir) {
        this.ignoreDirectories.add(dir.toLowerCase());
    }

    public void addIgnoreFileName(String fileName) {
        this.ignoreFileNames.add(fileName.toLowerCase());
    }
}