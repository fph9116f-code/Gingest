package com.backend.controller;

import com.backend.dto.FacadeInfo;
import com.backend.dto.GingestResponse;
import com.backend.service.GitLabService;
import com.backend.service.LocalFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class GingestController {

    private final GitLabService gitLabService;
    private final LocalFileService localFileService;

    @GetMapping("")
    public GingestResponse ingest(@RequestParam String projectId, @RequestParam(required = false) String branch) {
        return gitLabService.ingestRepository(projectId, branch);
    }

    @GetMapping("/local")
    public GingestResponse ingestLocal(@RequestParam String localPath) {
        return localFileService.ingestLocalDirectory(localPath);
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadGitLab(@RequestParam String projectId, @RequestParam(required = false) String branch) {
        GingestResponse response = gitLabService.ingestRepository(projectId, branch);
        return buildDownloadResponse(response, projectId);
    }

    @GetMapping("/local/download")
    public ResponseEntity<byte[]> downloadLocal(@RequestParam String localPath) {
        GingestResponse response = localFileService.ingestLocalDirectory(localPath);
        return buildDownloadResponse(response, localPath);
    }

    /**
     * 核心逻辑：组装 TXT 下载文件，包含真正的可视化目录树
     */
    private ResponseEntity<byte[]> buildDownloadResponse(GingestResponse res, String originalName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(res.getProjectName()).append("\n");
        sb.append("Files: ").append(res.getFileCount()).append("\n\n");

        sb.append("================================================\n");
        sb.append("Directory Structure:\n");
        sb.append("================================================\n.\n");
        // 核心：在后端也将 JSON 树转为可视化 ASCII 树
        generateAsciiTree(res.getDirectoryTree(), "", sb);

        sb.append("\n\n================================================\n");
        sb.append("Files Content:\n");
        sb.append("================================================\n\n");
        // 递归提取所有文件内容
        extractAllContent(res.getDirectoryTree(), sb);

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = originalName.replaceAll("[\\\\/:*?\"<>|]", "_") + "_gingest.txt";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", fileName);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    private void generateAsciiTree(List<GitLabService.TreeNode> nodes, String prefix, StringBuilder sb) {
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            GitLabService.TreeNode node = nodes.get(i);
            boolean isLast = (i == nodes.size() - 1);
            sb.append(prefix).append(isLast ? "└── " : "├── ").append(node.label).append(node.isFile ? "" : "/").append("\n");
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                generateAsciiTree(node.getChildren(), prefix + (isLast ? "    " : "│   "), sb);
            }
        }
    }

    private void extractAllContent(List<GitLabService.TreeNode> nodes, StringBuilder sb) {
        if (nodes == null) {
            return;
        }
        for (GitLabService.TreeNode node : nodes) {
            if (node.isFile) {
                sb.append("------------------------------------------------\n");
                sb.append("File: ").append(node.fullPath).append("\n");
                sb.append("------------------------------------------------\n");
                sb.append(node.content).append("\n\n");
            } else {
                extractAllContent(node.children, sb);
            }
        }
    }

    @GetMapping("/branches")
    public List<String> getBranches(@RequestParam String projectId) {
        return gitLabService.getBranches(projectId);
    }

    @GetMapping("/projects")
    public List<String> getProjects() {
        return gitLabService.getAllAccessibleProjects();
    }

    @GetMapping("/facades")
    public List<FacadeInfo> getFacades(@RequestParam String projectId, @RequestParam(required = false) String branch) {
        return gitLabService.extractFacadeMethods(projectId, branch);
    }
}