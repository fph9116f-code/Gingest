package com.backend.controller;

import com.backend.dto.GingestResponse;
import com.backend.service.GitLabService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "代码提取", description = "GitLab 代码拉取相关接口")
@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class GingestController {

    private final GitLabService gitLabService;

    @Operation(summary = "提取指定项目的代码并转为Markdown文本")
    @GetMapping("/api/ingest")
    public GingestResponse ingest(
            @RequestParam String projectId,
            @RequestParam(required = false) String branch) {
        return gitLabService.ingestRepository(projectId, branch);
    }

    // 👇 改造后的下载接口
    @Operation(summary = "提取代码并作为 TXT 文件下载")
    @GetMapping(value = "/download")
    public ResponseEntity<byte[]> downloadAsTxt(
            @RequestParam String projectId,
            @RequestParam(required = false) String branch) {

        // 1. 获取结构化的响应数据
        GingestResponse response = gitLabService.ingestRepository(projectId, branch);

        // 2. 像 GitIngest 那样，优雅地把数据拼装成一个包含摘要、目录树和正文的纯文本
        StringBuilder txtBuilder = new StringBuilder();

        // -- 拼接头部摘要 --
        txtBuilder.append("Project: ").append(response.getProjectName()).append("\n");
        txtBuilder.append("Files analyzed: ").append(response.getFileCount()).append("\n");
        txtBuilder.append("Estimated tokens: ").append(response.getEstimatedTokens()).append("\n");
        txtBuilder.append("================================================\n\n");

        // -- 拼接目录树 --
        txtBuilder.append("Directory Structure:\n");
        txtBuilder.append("------------------------------------------------\n");
        txtBuilder.append(response.getDirectoryTree()).append("\n\n");

        // -- 拼接详细代码内容 --
        txtBuilder.append("Files Content:\n");
        txtBuilder.append("------------------------------------------------\n");
        txtBuilder.append(response.getContent());

        // 3. 将拼接好的优美文本转为字节数组
        byte[] contentBytes = txtBuilder.toString().getBytes(StandardCharsets.UTF_8);

        // 4. 构造一个安全的文件名 (处理一下路径里的斜杠，防止破坏文件名)
        String safeFileName = projectId.replaceAll("[\\\\/:*?\"<>|]", "_") + "_gingest.txt";

        // 5. 设置 HTTP 响应头，触发浏览器下载
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); // 告诉浏览器这是二进制流
        headers.setContentDispositionFormData("attachment", safeFileName); // 告诉浏览器作为附件下载，并指定文件名

        // 6. 返回包含响应头和字节流的 ResponseEntity
        return new ResponseEntity<>(contentBytes, headers, HttpStatus.OK);
    }
}