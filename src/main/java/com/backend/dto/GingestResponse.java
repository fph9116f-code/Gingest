package com.backend.dto;

import com.backend.service.GitLabService;
import lombok.Data;
import lombok.Builder;

import java.util.List;

@Data
@Builder
public class GingestResponse {
    // 基础信息 (对应 Summary)
    private String projectName;
    private int fileCount;
    private long estimatedTokens;
    private String formattedSize;

    // 目录结构树 (对应 Directory Structure)
    private List<GitLabService.TreeNode> directoryTree;
    
    // 纯文本代码内容 (对应 Files Content)
    private String content;
}