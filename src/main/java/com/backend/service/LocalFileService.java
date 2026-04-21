package com.backend.service;

import com.backend.dto.GingestResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileService {
    private final FilterConfigService filterConfigService;

    // 安全熔断阈值
    private static final int MAX_FILE_COUNT = 3000;
    private static final long MAX_TOTAL_SIZE = 50 * 1024 * 1024L;

    public GingestResponse ingestLocalDirectory(String localPath) {
        Path rootPath = Paths.get(localPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new RuntimeException("本地目录不存在或不是有效的文件夹路径: " + localPath);
        }

        log.info("开始极速扫描本地目录: {}", localPath);
        long startTime = System.currentTimeMillis();

        List<String> processedFiles = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>();
        long[] sizeAndLength = new long[2];

        // ========================================================
        // 【新增黑科技】：动态解析当前目录的 .gitignore 规则
        // ========================================================
        IgnoreNode ignoreNode = new IgnoreNode();
        Path gitignorePath = rootPath.resolve(".gitignore");
        if (Files.exists(gitignorePath)) {
            try (InputStream in = Files.newInputStream(gitignorePath)) {
                ignoreNode.parse(in);
                log.info("成功加载并解析本地 .gitignore 动态过滤规则");
            } catch (Exception e) {
                log.warn("解析 .gitignore 失败，将仅使用全局 YML 过滤规则", e);
            }
        }

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();

                    // 1. 全局配置拦截 (如 .git, .idea)
                    if (dirName.startsWith(".") || filterConfigService.getIgnoreDirectories().contains(dirName.toLowerCase())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // 2. 原生 .gitignore 拦截 (文件夹级别)
                    if (!dir.equals(rootPath) && !ignoreNode.getRules().isEmpty()) {
                        String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");
                        // 判定是否被忽略 (入参 true 表示这是一个目录)
                        if (ignoreNode.isIgnored(relativePath, true) == IgnoreNode.MatchResult.IGNORED) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NonNull Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();

                    // 1. 全局后缀和特定文件名拦截
                    if (isTextFile(fileName)) {
                        String relativePath = rootPath.relativize(file).toString().replace("\\", "/");

                        // 2. 原生 .gitignore 拦截 (文件级别)
                        if (!ignoreNode.getRules().isEmpty() &&
                                ignoreNode.isIgnored(relativePath, false) == IgnoreNode.MatchResult.IGNORED) {
                            return FileVisitResult.CONTINUE; // 跳过此文件
                        }

                        // 【安全熔断检查】
                        if (processedFiles.size() >= MAX_FILE_COUNT) {
                            throw new RuntimeException("【安全熔断】该目录过大！有效代码文件已超过 " + MAX_FILE_COUNT + " 个...");
                        }
                        if (sizeAndLength[0] >= MAX_TOTAL_SIZE) {
                            throw new RuntimeException("【安全熔断】该目录过大！累计读取源码已超过 50MB...");
                        }

                        try {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            processedFiles.add(relativePath);
                            fileContents.put(relativePath, content);

                            sizeAndLength[0] += file.toFile().length();
                            sizeAndLength[1] += content.length();
                        } catch (java.nio.charset.MalformedInputException e) {
                            log.debug("跳过非 UTF-8 文本文件: {}", fileName);
                        } catch (Exception e) {
                            log.warn("无法读取本地文件: {} ({})", fileName, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("读取本地文件失败: " + e.getMessage());
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
                .content("")
                .build();
    }

    private boolean isTextFile(String fileName) {
        String lowerCaseName = fileName.toLowerCase();
        for (String ext : filterConfigService.getIgnoreExtensions()) {
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