package com.backend.service;

import com.backend.dto.GingestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    private final GitLabApi gitLabApi;

    // 常见需要过滤的二进制文件或无意义文件的后缀/目录
    private static final Set<String> IGNORE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".zip", ".tar", ".gz", ".jar", ".class", ".exe", ".xml");
    private static final Set<String> IGNORE_DIRECTORIES = Set.of("node_modules/", ".git/", "target/", ".idea/", "build/");

    /**
     * 拉取代码并生成 Markdown 格式的纯文本
     *
     * @param input 项目 ID (如 123) 或者项目路径 (如 "zysoft/medical-order")
     */
    public GingestResponse ingestRepository(String input) {
        String projectIdOrPath = parseProjectIdentifier(input);

        // 【新增日志】开始解析的提示
        log.info("开始拉取并解析 GitLab 仓库: {}", projectIdOrPath);
        // 【新增时间统计】记录开始时间
        long startTime = System.currentTimeMillis();

        StringBuilder contentBuilder = new StringBuilder();
        List<String> processedFiles = new ArrayList<>(); // 用于收集有效文件路径
        int fileCount = 0;

        try {
            InputStream archiveStream = gitLabApi.getRepositoryApi()
                    .getRepositoryArchive(projectIdOrPath, (String) null, org.gitlab4j.api.Constants.ArchiveFormat.ZIP);

            try (ZipInputStream zipIn = new ZipInputStream(archiveStream)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    String fileName = entry.getName();

                    if (!entry.isDirectory() && isTextFile(fileName)) {
                        // 1. 收集路径
                        String cleanPath = cleanRootPath(fileName);
                        processedFiles.add(cleanPath);

                        // 2. 拼接代码内容
                        String fileContent = new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                        contentBuilder.append("================================================\n");
                        contentBuilder.append("File: ").append(cleanPath).append("\n");
                        contentBuilder.append("================================================\n");
                        contentBuilder.append(fileContent).append("\n\n");

                        fileCount++;
                    }
                    zipIn.closeEntry();
                }
            }

            String finalContent = contentBuilder.toString();
            long estimatedTokens = finalContent.length() / 4;

            // 【新增日志】打印处理结果和耗时
            log.info("解析完成！共处理 {} 个有效文件，预估 {} tokens，耗时 {} ms",
                    fileCount, estimatedTokens, (System.currentTimeMillis() - startTime));

            // 3. 构建返回对象
            return GingestResponse.builder()
                    .projectName(projectIdOrPath)
                    .fileCount(fileCount)
                    .estimatedTokens(estimatedTokens)
                    .directoryTree(buildDirectoryTree(processedFiles)) // 生成树状结构
                    .content(finalContent)
                    .build();

        } catch (Exception e) {
            log.error("处理代码流发生异常", e);
            throw new RuntimeException("解析仓库失败: " + e.getMessage());
        }
    }

    // 辅助方法：生成简单的缩进目录树
    private String buildDirectoryTree(List<String> paths) {
        Collections.sort(paths); // 排序让目录结构规整
        StringBuilder tree = new StringBuilder();
        for (String path : paths) {
            // 根据斜杠数量决定缩进层级
            int depth = path.length() - path.replace("/", "").length();
            String indent = "  ".repeat(depth);
            String fileName = path.substring(path.lastIndexOf("/") + 1);
            tree.append(indent).append("├── ").append(fileName).append("\n");
        }
        return tree.toString();
    }

    // 辅助方法：去掉 GitLab zip 包自带的第一层带 hash 的长目录名
    private String cleanRootPath(String originalPath) {
        int firstSlash = originalPath.indexOf("/");
        return firstSlash != -1 ? originalPath.substring(firstSlash + 1) : originalPath;
    }

    /**
     * 简单的文件过滤逻辑
     */
    private boolean isTextFile(String fileName) {
        String lowerCaseName = fileName.toLowerCase();

        // 检查是否包含忽略的目录
        for (String dir : IGNORE_DIRECTORIES) {
            if (lowerCaseName.contains(dir)) {
                return false;
            }
        }

        // 检查后缀
        for (String ext : IGNORE_EXTENSIONS) {
            if (lowerCaseName.endsWith(ext)) {
                return false;
            }
        }

        return true; // 剩下的暂且都当做文本文件处理
    }

    /**
     * 智能解析输入内容，支持纯数字ID、HTTP地址、SSH地址、命名空间路径
     */
    private String parseProjectIdentifier(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("输入不能为空");
        }
        input = input.trim();

        // 1. 如果是纯数字，说明用户直接输入了 Project ID
        if (input.matches("\\d+")) {
            return input;
        }

        // 2. 去除可能携带的 .git 后缀 (比如从 clone 按钮复制的地址)
        if (input.endsWith(".git")) {
            input = input.substring(0, input.length() - 4);
        }

        try {
            // 3. 处理 HTTP/HTTPS 例如: https://gitlab.zysoft.com.cn/backend/medical-order
            if (input.startsWith("http")) {
                java.net.URL url = new java.net.URL(input);
                String path = url.getPath(); // 返回 "/backend/medical-order"
                if (path.startsWith("/")) {
                    path = path.substring(1); // 去掉开头的斜杠
                }
                return path;
            } else if (input.startsWith("git@")) {
                // 4. 处理 SSH 地址 例如: git@gitlab.zysoft.com.cn:backend/medical-order
                int colonIndex = input.indexOf(':');
                if (colonIndex != -1) {
                    return input.substring(colonIndex + 1);
                }
            }
        } catch (Exception e) {
            log.warn("URL 解析异常，将尝试把输入作为直接路径处理: {}", input);
        }

        // 5. 如果都不是，假设用户输入的是形如 "zysoft/medical-order" 的直接路径
        return input;
    }
}