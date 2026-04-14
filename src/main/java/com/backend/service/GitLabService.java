package com.backend.service;

import com.backend.dto.GingestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.ProjectFilter;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
     * @param input  项目 ID (如 123) 或者项目路径 (如 "zysoft/medical-order")
     * @param branch 分支名 (如 "develop", "feature/abc")。如果传 null 或空字符串，则默认拉取主分支
     */
    public GingestResponse ingestRepository(String input, String branch) {
        String projectIdOrPath = parseProjectIdentifier(input);

        // 处理分支参数：为空则传入 null，GitLab4J 内部会默认拉取 default branch
        String targetBranch = (branch == null || branch.trim().isEmpty()) ? null : branch.trim();

        // 【新增日志】开始解析的提示，包含分支信息
        log.info("开始拉取并解析 GitLab 仓库: {}, 目标分支: {}", projectIdOrPath, targetBranch != null ? targetBranch : "默认主分支");
        // 【新增时间统计】记录开始时间
        long startTime = System.currentTimeMillis();

        StringBuilder contentBuilder = new StringBuilder();
        List<String> processedFiles = new ArrayList<>(); // 用于收集有效文件路径
        int fileCount = 0;

        try {
            // 将 targetBranch 传入 getRepositoryArchive
            InputStream archiveStream = gitLabApi.getRepositoryApi()
                    .getRepositoryArchive(projectIdOrPath, targetBranch, org.gitlab4j.api.Constants.ArchiveFormat.ZIP);

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
                    .projectName(projectIdOrPath + (targetBranch != null ? " (" + targetBranch + ")" : "")) // 在响应中也带上分支名
                    .fileCount(fileCount)
                    .estimatedTokens(estimatedTokens)
                    .directoryTree(buildDirectoryTree(processedFiles)) // 生成树状结构
                    .content(finalContent)
                    .build();

        } catch (Exception e) {
            log.error("处理代码流发生异常", e);
            throw new RuntimeException("解析仓库失败，请检查项目权限或分支名是否正确: " + e.getMessage());
        }
    }

    // 辅助方法：生成层级分明且自动折叠单子节点的目录树
    private String buildDirectoryTree(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "暂无有效文件\n";
        }

        // 1. 构建完整的初始树结构
        TreeNode root = new TreeNode("root");
        for (String path : paths) {
            String[] parts = path.split("/");
            TreeNode current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                current.children.putIfAbsent(part, new TreeNode(part));
                current = current.children.get(part);
                if (i == parts.length - 1) {
                    current.isFile = true;
                }
            }
        }

        // 2. 核心魔法：递归压缩树结构（把单传代代合并）
        compressTree(root);

        // 3. 遍历打印树状文本
        StringBuilder treeStr = new StringBuilder();
        for (TreeNode child : root.children.values()) {
            printTree(child, "", treeStr);
        }
        return treeStr.toString();
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
     * 获取指定仓库的所有分支名列表
     */
    public List<String> getBranches(String input) {
        String projectIdOrPath = parseProjectIdentifier(input);

        // 【核心修改】将路径转换为万无一失的数字 ID
        Long actualProjectId = resolveProjectId(projectIdOrPath);
        log.info("获取仓库分支列表, 真实项目 ID: {}", actualProjectId);

        try {
            // 调用 GitLab4J API (这里传入的 identifier 变成了 Long 类型的 actualProjectId)
            return gitLabApi.getRepositoryApi().getBranches(actualProjectId).stream()
                    .map(org.gitlab4j.api.models.Branch::getName)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.error("获取分支列表失败", e);
            throw new RuntimeException("获取分支列表失败: " + e.getMessage());
        }
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

    /**
     * 将解析出的路径或字符串，安全地转换为绝对正确的纯数字 Project ID
     */
    private Long resolveProjectId(String projectIdOrPath) {
        // 1. 如果已经是纯数字，直接转成长整型返回
        if (projectIdOrPath.matches("\\d+")) {
            return Long.parseLong(projectIdOrPath);
        }

        log.info("尝试将项目路径转换为真实数字 ID: {}", projectIdOrPath);
        try {
            // 2. 调用 ProjectApi 获取项目详情，抽取最底层的数字 ID
            return gitLabApi.getProjectApi().getProject(projectIdOrPath).getId();
        } catch (Exception e) {
            log.warn("直接使用路径查询项目失败，尝试 URL 编码后再次查询...");
            try {
                // 3. 终极兜底：对深层路径进行严格的 URL 编码 (将 a/b 转为 a%2Fb)
                String encodedPath = URLEncoder.encode(projectIdOrPath, StandardCharsets.UTF_8);
                return gitLabApi.getProjectApi().getProject(encodedPath).getId();
            } catch (Exception ex) {
                log.error("路径转换 ID 彻底失败: {}", projectIdOrPath, ex);
                throw new RuntimeException("无法找到该项目，请确认地址拼写正确，且配置的 Token 具有该项目的访问权限！");
            }
        }
    }

    // 内部类：用于构建真实的树状结构
    private static class TreeNode {
        String name;
        // TreeMap 保证文件按字母顺序自动排序
        java.util.Map<String, TreeNode> children = new java.util.TreeMap<>();
        boolean isFile;

        TreeNode(String name) {
            this.name = name;
        }
    }

    // 递归压缩逻辑：如果文件夹只有一个孩子，就和孩子合并成一行
    private void compressTree(TreeNode node) {
        // 先深入到最底层，把子孙节点压缩好
        for (TreeNode child : new java.util.ArrayList<>(node.children.values())) {
            compressTree(child);
        }

        // 如果当前节点不是根节点，且不是文件，且【只有一个孩子】
        if (!"root".equals(node.name) && !node.isFile && node.children.size() == 1) {
            TreeNode singleChild = node.children.values().iterator().next();
            // 将当前节点与唯一子节点合并 (例如 src + main 变成 src/main)
            node.name = node.name + "/" + singleChild.name;
            node.children = singleChild.children;
            node.isFile = singleChild.isFile; // 如果子节点是文件，合并后当前节点也变成了文件末端
        }
    }

    // 递归打印逻辑
    private void printTree(TreeNode node, String indent, StringBuilder sb) {
        sb.append(indent).append("├── ").append(node.name);
        // 如果压缩到最后依然是个目录，加个后缀斜杠区分
        if (!node.isFile) {
            sb.append("/");
        }
        sb.append("\n");

        String childIndent = indent + "    ";
        for (TreeNode child : node.children.values()) {
            printTree(child, childIndent, sb);
        }
    }

    /**
     * 获取当前 Token 拥有访问权限的所有项目列表 (带命名空间路径)
     */
    public List<String> getAllAccessibleProjects() {
        log.info("开始拉取当前 Token 可访问的项目列表...");
        try {
            // 【核心防坑】：只拉取当前用户真正加入的、有实际权限的项目
            ProjectFilter filter = new ProjectFilter().withMembership(true);

            // 调用接口获取项目列表
            return gitLabApi.getProjectApi().getProjects(filter).stream()
                    .map(org.gitlab4j.api.models.Project::getPathWithNamespace)
                    // 如果你想让列表按字母顺序排个序，可以直接在 Java 里加上 .sorted()
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());

        } catch (Exception e) {
            log.error("获取项目列表失败", e);
            throw new RuntimeException("获取项目列表失败: " + e.getMessage());
        }
    }
}