package com.backend.service;

import com.backend.dto.GingestResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Slf4j
@Service
public class LocalFileService {

    // 常见需要过滤的垃圾目录和二进制文件
    private static final Set<String> IGNORE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".zip", ".tar", ".gz",
            ".jar", ".class", ".exe", ".xml", ".node", ".dll", ".so", ".dylib",
            ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".mp3", ".svg", ".properties", ".cmd", ".gitignore", ".config",".iml"
    );
    private static final Set<String> IGNORE_DIRECTORIES = Set.of("node_modules", ".git", "target", ".idea", "build");

    // 【新增】：安全熔断阈值，防止扫描超大目录撑爆内存
    private static final int MAX_FILE_COUNT = 3000; // 最多允许扫描 3000 个有效代码文件
    private static final long MAX_TOTAL_SIZE = 50 * 1024 * 1024L; // 最多允许累计读取 50MB 文本
    /**
     * 扫描本地目录，提取代码并生成与 GitLab 一致的树形结构
     */
    public GingestResponse ingestLocalDirectory(String localPath) {
        Path rootPath = Paths.get(localPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new RuntimeException("本地目录不存在或不是有效的文件夹路径: " + localPath);
        }

        log.info("开始极速扫描本地目录: {}", localPath);
        long startTime = System.currentTimeMillis();

        List<String> processedFiles = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>();

        // 数组用于在匿名内部类中累加统计信息：[0] 为字节大小，[1] 为总字符长度
        long[] sizeAndLength = new long[2];

        try {
            // 使用 NIO 的 walkFileTree，性能远超普通递归或 Files.walk，因为它可以直接 SKIP_SUBTREE 砍掉无用分支
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    // 遇到隐藏目录 (如 .vscode) 或者黑名单目录，直接跳过整棵树，极大幅度提升性能！
                    if (dirName.startsWith(".") || IGNORE_DIRECTORIES.contains(dirName.toLowerCase())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NonNull Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (isTextFile(fileName)) {
                        // 【新增核心防线】：熔断拦截！
                        if (processedFiles.size() >= MAX_FILE_COUNT) {
                            throw new RuntimeException("【安全熔断】该目录过大！有效代码文件已超过 " + MAX_FILE_COUNT + " 个，为保护系统内存已强制拦截。请指定更精确的子目录！");
                        }
                        if (sizeAndLength[0] >= MAX_TOTAL_SIZE) {
                            throw new RuntimeException("【安全熔断】该目录过大！累计读取源码已超过 50MB，为保护系统内存已强制拦截。请指定更精确的子目录！");
                        }
                        // 将 Windows 的反斜杠转换为统一的正斜杠，兼容前端的树形解析
                        String relativePath = rootPath.relativize(file).toString().replace("\\", "/");
                        try {
                            // 尝试以 UTF-8 读取
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            processedFiles.add(relativePath);
                            fileContents.put(relativePath, content);

                            sizeAndLength[0] += file.toFile().length();
                            sizeAndLength[1] += content.length();
                        } catch (java.nio.charset.MalformedInputException e) {
                            // 【核心防线】：如果强读抛出乱码异常，说明它绝对是个二进制/非文本文件，直接静默跳过！
                            log.debug("跳过非 UTF-8 文本/二进制文件: {}", file.getFileName());
                        } catch (Exception e) {
                            // 其他真正的权限错误等，只打印一行简短警告，不打印长堆栈刷屏
                            log.warn("无法读取本地文件: {} ({})", file.getFileName(), e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("由于权限等原因无法访问该文件/目录: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("扫描本地目录发生全局异常", e);
            throw new RuntimeException("读取本地文件失败，请检查磁盘权限: " + e.getMessage());
        }

        int fileCount = processedFiles.size();
        long estimatedTokens = sizeAndLength[1] / 4;
        String formattedSize = formatSize(sizeAndLength[0]);

        log.info("本地扫描完成！共处理 {} 个有效文件，耗时 {} ms", fileCount, (System.currentTimeMillis() - startTime));

        return GingestResponse.builder()
                .projectName("Local: " + rootPath.getFileName().toString())
                .fileCount(fileCount)
                .estimatedTokens(estimatedTokens)
                .formattedSize(formattedSize)
                .directoryTree(buildDirectoryTree(processedFiles, fileContents))
                .content("") // 遵循上一步的前端极速组装优化，后端不传几十 MB 的重复字符串
                .build();
    }

    private boolean isTextFile(String fileName) {
        String lowerCaseName = fileName.toLowerCase();
        for (String ext : IGNORE_EXTENSIONS) {
            if (lowerCaseName.endsWith(ext)) {
                return false;
            }
        }
        return true;
    }

    private String formatSize(long size) {
        if (size <= 0) {
            return "0 B";
        }
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new java.text.DecimalFormat("#,##0.##").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    // ==========================================
    // 独立实现的纯粹树形构建逻辑
    // ==========================================
    private List<GitLabService.TreeNode> buildDirectoryTree(List<String> paths, Map<String, String> contentMap) {
        if (paths == null || paths.isEmpty()) {
            return new ArrayList<>();
        }

        GitLabService.TreeNode root = new GitLabService.TreeNode("root");
        Map<String, GitLabService.TreeNode> dirNodes = new HashMap<>();
        dirNodes.put("", root);

        for (String path : paths) {
            String[] parts = path.split("/");
            String currentPath = "";
            GitLabService.TreeNode parent = root;

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean isFile = (i == parts.length - 1);
                currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;

                GitLabService.TreeNode node = dirNodes.get(currentPath);
                if (node == null) {
                    node = new GitLabService.TreeNode(part);
                    node.isFile = isFile;
                    if (isFile) {
                        node.fullPath = path;
                        node.content = contentMap.get(path);
                    }
                    parent.children.add(node);
                    dirNodes.put(currentPath, node);
                }
                parent = node;
            }
        }
        compressTree(root);
        return root.children;
    }

    private void compressTree(GitLabService.TreeNode node) {
        // 先向下递归处理所有的子节点
        for (GitLabService.TreeNode child : new ArrayList<>(node.children)) {
            compressTree(child);
        }
        // 如果当前节点是只有一个子节点的文件夹（非文件），进行目录折叠（例如合并 com/zoe 为 com.zoe）
        if (!"root".equals(node.label) && !node.isFile && node.children.size() == 1) {
            GitLabService.TreeNode singleChild = node.children.get(0);
            node.label = node.label + "/" + singleChild.label;
            node.children = singleChild.children;
            node.isFile = singleChild.isFile;
            node.fullPath = singleChild.fullPath;
            node.content = singleChild.content;
        }
    }
}