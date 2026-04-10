package com.backend.dto;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class GingestResponse {
    // 基础信息 (对应 Summary)
    private String projectName;
    private int fileCount;
    private long estimatedTokens;
    
    // 目录结构树 (对应 Directory Structure)
    private String directoryTree;
    
    // 纯文本代码内容 (对应 Files Content)
    private String content;
}