package com.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 用于展示 Facade 接口及方法树形结构的 DTO
 */
@Data
@Builder
public class FacadeInfo {
    // 类名 (例如: OutpatientOrderInfoFacade)
    private String className;
    // 相对路径
    private String path;
    // 该类下的所有方法名集合
    private List<String> methods;
}