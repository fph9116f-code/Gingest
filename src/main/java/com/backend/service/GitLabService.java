package com.backend.service;

import com.backend.dto.FacadeInfo;
import com.backend.dto.GingestResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.ProjectFilter;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    private final GitLabApi gitLabApi;
    private final FilterConfigService filterConfigService;

    /**
     * 拉取代码并生成包含动态树形结构的响应
     */
    public GingestResponse ingestRepository(String input, String branch) {
        String projectIdOrPath = parseProjectIdentifier(input);
        String targetBranch = (branch == null || branch.trim().isEmpty()) ? null : branch.trim();

        log.info("开始极速拉取并解析 GitLab 仓库: {}, 目标分支: {}", projectIdOrPath, targetBranch != null ? targetBranch : "默认主分支");
        long startTime = System.currentTimeMillis();

        List<String> processedFiles = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>();
        int fileCount = 0;

        // 【极致优化】：彻底干掉 contentBuilder，不再拼装 10MB+ 的冗余长文本
        long totalTextLength = 0;
        long byteSize = 0;

        try {
            InputStream archiveStream = gitLabApi.getRepositoryApi()
                    .getRepositoryArchive(projectIdOrPath, targetBranch, org.gitlab4j.api.Constants.ArchiveFormat.ZIP);

            try (ZipInputStream zipIn = new ZipInputStream(archiveStream)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    String fileName = entry.getName();

                    if (!entry.isDirectory() && isTextFile(fileName)) {
                        String cleanPath = cleanRootPath(fileName);
                        processedFiles.add(cleanPath);

                        byte[] fileBytes = zipIn.readAllBytes();
                        // 取消所有限制，无脑转成字符串，交给前端强大的防卡死机制去处理
                        String fileContent = new String(fileBytes, StandardCharsets.UTF_8);


                        fileContents.put(cleanPath, fileContent);

                        totalTextLength += fileContent.length();
                        byteSize += fileBytes.length;

                        fileCount++;
                    }
                    zipIn.closeEntry();
                }
            }

            long estimatedTokens = totalTextLength / 4;

            log.info("解析完成！共处理 {} 个有效文件，预估 {} tokens，耗时 {} ms",
                    fileCount, estimatedTokens, (System.currentTimeMillis() - startTime));

            return GingestResponse.builder()
                    .projectName(projectIdOrPath + (targetBranch != null ? " (" + targetBranch + ")" : ""))
                    .fileCount(fileCount)
                    .estimatedTokens(estimatedTokens)
                    .formattedSize(formatSize(byteSize))
                    .directoryTree(buildDirectoryTree(processedFiles, fileContents))
                    // 【极致优化】：强行置空，绝不传输双份数据！体积直接缩减 50%
                    .content("")
                    .build();

        } catch (Exception e) {
            log.error("处理代码流发生异常", e);
            throw new RuntimeException("解析仓库失败，请检查项目权限或分支名是否正确: " + e.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TreeNode {
        public String label;
        public boolean isFile;
        public String fullPath;
        public String content;
        public List<TreeNode> children = new ArrayList<>();

        private transient Map<String, TreeNode> childMap = new TreeMap<>();

        public TreeNode(String label) {
            this.label = label;
        }
    }

    private List<TreeNode> buildDirectoryTree(List<String> paths, Map<String, String> contentMap) {
        if (paths == null || paths.isEmpty()) {
            return new ArrayList<>();
        }

        TreeNode root = new TreeNode("root");
        for (String path : paths) {
            String[] parts = path.split("/");
            TreeNode current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                current.childMap.putIfAbsent(part, new TreeNode(part));
                current = current.childMap.get(part);
                if (i == parts.length - 1) {
                    current.isFile = true;
                    current.fullPath = path;
                    current.content = contentMap.get(path);
                }
            }
        }
        compressTree(root);
        convertMapToList(root);
        return root.children;
    }

    private void compressTree(TreeNode node) {
        for (TreeNode child : new ArrayList<>(node.childMap.values())) {
            compressTree(child);
        }
        if (!"root".equals(node.label) && !node.isFile && node.childMap.size() == 1) {
            TreeNode singleChild = node.childMap.values().iterator().next();
            node.label = node.label + "/" + singleChild.label;
            node.childMap = singleChild.childMap;
            node.isFile = singleChild.isFile;
            node.fullPath = singleChild.fullPath;
            node.content = singleChild.content;
        }
    }

    private void convertMapToList(TreeNode node) {
        node.children = new ArrayList<>(node.childMap.values());
        for (TreeNode child : node.children) {
            convertMapToList(child);
        }
    }

    private String cleanRootPath(String originalPath) {
        int firstSlash = originalPath.indexOf("/");
        return firstSlash != -1 ? originalPath.substring(firstSlash + 1) : originalPath;
    }

    private boolean isTextFile(String fileName) {
        String lowerCaseName = fileName.toLowerCase();
        String[] pathParts = lowerCaseName.split("/");
        for (String part : pathParts) {
            if (part.startsWith(".") || filterConfigService.getIgnoreDirectories().contains(part)) {
                return false;
            }
        }
        for (String ext : filterConfigService.getIgnoreExtensions()) {
            if (lowerCaseName.endsWith(ext)) {
                return false;
            }
        }
        String pureFileName = pathParts[pathParts.length - 1];
        return !filterConfigService.getIgnoreFileNames().contains(pureFileName);
    }

    public List<String> getBranches(String input) {
        String projectIdOrPath = parseProjectIdentifier(input);
        Long actualProjectId = resolveProjectId(projectIdOrPath);
        try {
            return gitLabApi.getRepositoryApi().getBranches(actualProjectId).stream()
                    .map(org.gitlab4j.api.models.Branch::getName)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("获取分支列表失败: " + e.getMessage());
        }
    }

    private String parseProjectIdentifier(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("输入不能为空");
        }
        input = input.trim();
        if (input.matches("\\d+")) {
            return input;
        }
        if (input.endsWith(".git")) {
            input = input.substring(0, input.length() - 4);
        }
        try {
            if (input.startsWith("http")) {
                java.net.URL url = new java.net.URL(input);
                String path = url.getPath();
                return path.startsWith("/") ? path.substring(1) : path;
            } else if (input.startsWith("git@")) {
                int colonIndex = input.indexOf(':');
                if (colonIndex != -1) {
                    return input.substring(colonIndex + 1);
                }
            }
        } catch (Exception e) {

        }
        return input;
    }

    private Long resolveProjectId(String projectIdOrPath) {
        if (projectIdOrPath.matches("\\d+")) {
            return Long.parseLong(projectIdOrPath);
        }
        try {
            return gitLabApi.getProjectApi().getProject(projectIdOrPath).getId();
        } catch (Exception e) {
            try {
                String encodedPath = URLEncoder.encode(projectIdOrPath, StandardCharsets.UTF_8);
                return gitLabApi.getProjectApi().getProject(encodedPath).getId();
            } catch (Exception ex) {
                throw new RuntimeException("无法找到该项目，请确认权限与地址！");
            }
        }
    }

    public List<String> getAllAccessibleProjects() {
        try {
            ProjectFilter filter = new ProjectFilter()
                    .withMembership(true)
                    .withSimple(true)
                    .withOrderBy(org.gitlab4j.api.Constants.ProjectOrderBy.UPDATED_AT);

            return gitLabApi.getProjectApi().getProjects(filter, 1, 200).stream()
                    .map(org.gitlab4j.api.models.Project::getPathWithNamespace)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("获取项目列表失败: " + e.getMessage());
        }
    }

    public List<FacadeInfo> extractFacadeMethods(String input, String branch) {
        String projectIdOrPath = parseProjectIdentifier(input);
        Long actualProjectId = resolveProjectId(projectIdOrPath);
        String targetBranch = (branch == null || branch.trim().isEmpty()) ? null : branch.trim();

        List<FacadeInfo> facadeList = new ArrayList<>();
        Pattern methodPattern = Pattern.compile("(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+|final\\s+|abstract\\s+|default\\s+)*(?!class\\b|interface\\b|enum\\b|@interface\\b)(?:[\\w<>\\[\\]?.,]+\\s+)+(\\w+)\\s*\\(");
        Pattern operationPattern = Pattern.compile("@(?:Operation|ApiOperation)\\s*\\([\\s\\S]*?(?:summary|value)\\s*=\\s*\"([^\"]+)\"");
        Set<String> ignoreWords = Set.of("new", "return", "throw", "if", "else", "for", "while", "catch", "switch", "try", "super", "this");

        try {
            InputStream archiveStream = gitLabApi.getRepositoryApi().getRepositoryArchive(actualProjectId, targetBranch, org.gitlab4j.api.Constants.ArchiveFormat.ZIP);
            try (ZipInputStream zipIn = new ZipInputStream(archiveStream)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    String fileName = entry.getName();
                    String lowerName = fileName.toLowerCase();
                    if (!entry.isDirectory() && lowerName.endsWith(".java") && lowerName.matches(".*(?:^|/)facade/.*")) {
                        String cleanPath = cleanRootPath(fileName);
                        String fileContent = new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                        String className = fileName.substring(fileName.lastIndexOf("/") + 1, fileName.lastIndexOf("."));
                        List<String> methods = new ArrayList<>();
                        Matcher matcher = methodPattern.matcher(fileContent);
                        int lastEnd = 0;

                        while (matcher.find()) {
                            String methodName = matcher.group(1).trim();
                            int currentStart = matcher.start();
                            String precedingText = fileContent.substring(lastEnd, currentStart);
                            lastEnd = matcher.end();

                            if (!methodName.equals(className) && !ignoreWords.contains(methodName)) {
                                boolean exists = methods.stream().anyMatch(m -> m.startsWith(methodName + " ("));
                                if (!exists) {
                                    Matcher opMatcher = operationPattern.matcher(precedingText);
                                    String summary = "-";
                                    while (opMatcher.find()) {
                                        summary = opMatcher.group(1).trim();
                                    }
                                    methods.add(methodName + " (" + summary + ")");
                                }
                            }
                        }
                        if (!methods.isEmpty()) {
                            facadeList.add(FacadeInfo.builder().className(className).path(cleanPath).methods(methods).build());
                        }
                    }
                    zipIn.closeEntry();
                }
            }
            return facadeList;
        } catch (Exception e) {
            throw new RuntimeException("提取 Facade 方法失败: " + e.getMessage());
        }
    }

    private String formatSize(long size) {
        if (size <= 0) {
            return "0 B";
        }
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new java.text.DecimalFormat("#,##0.##").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}