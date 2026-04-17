init
这是一份基于您提供的代码为您编写的详细 `README.md` 文档。

***

# Gingest 代码提取器

[cite_start]Gingest 是一个专为将完整代码库提取并转化为 AI（大模型）友好格式而设计的内部工具 [cite: 125, 126][cite_start]。它可以将 GitLab 仓库或本地目录中的代码结构和文件内容，一键打包为清晰的带有目录树和 XML 风格标签的纯文本上下文 [cite: 43]，完美解决 AI 代码审查、架构分析或业务逻辑梳理时手动复制粘贴的痛点。

## ✨ 核心特性

* **双重提取引擎**：
    * [cite_start]**GitLab 模式**：通过 GitLab API 直接检索有权限的项目和分支 [cite: 143, 144][cite_start]，并在后端内存中极速解析 ZIP 流，不落盘直接提取源码 [cite: 162, 166]。
    * [cite_start]**本地模式**：采用现代化的双引擎机制（现代 `showDirectoryPicker` API 与传统 `<input>` 后备方案）直读本地文件 [cite: 19, 26][cite_start]，或通过后端 NIO 高效遍历磁盘 [cite: 243]。
* [cite_start]**AI 友好格式组装**：自动将代码打包为结构化文本，包含 `<project_summary>`（项目摘要）、`<directory_tree>`（目录拓扑）以及包含具体代码的 `<files>` 节点 [cite: 43, 51]。
* [cite_start]**Java Facade / Controller 接口透视**：通过正则引擎自动扫描 `.java` 文件中的接口方法及 `@Operation` 注解 [cite: 216, 223][cite_start]，在前端单独生成接口树，点击后可一键在目录树中联动定位并高亮核心业务文件 [cite: 53, 54]。
* **强大的安全防卡死机制**：
    * [cite_start]**大文本截断**：前端采用 10 万字符预览限制，防止超长代码卡死浏览器渲染进程 [cite: 6]。
    * [cite_start]**全库熔断保护**：当代码预估超过 50 万 Tokens 时，自动关闭全量合并预览，引导用户按需勾选或直接下载完整文件 [cite: 44, 57]。
    * [cite_start]**本地规模限制**：严格控制单次本地提取不超过 3000 个文件或 50MB 体积，保障系统内存安全 [cite: 14, 238]。
* **极致性能**：
    * [cite_start]后端默认开启 Spring Boot 虚拟线程（Virtual Threads） [cite: 282]。
    * [cite_start]开启针对大文本输出的 GZIP 全局响应压缩 [cite: 282]。
    * [cite_start]前端树形组件与接口搜索全面接入 300ms 防抖 [cite: 8, 9]。

## 🛠️ 技术栈

### 前端 (`gingest-ui`)
* [cite_start]**核心框架**：Vue 3 + Vite [cite: 3, 111]
* [cite_start]**开发语言**：TypeScript [cite: 111]
* [cite_start]**UI 组件库**：Element Plus [cite: 3]
* [cite_start]**工程化规范**：ESLint + Prettier + Oxlint [cite: 111]

### 后端
* [cite_start]**核心框架**：Spring Boot 3 [cite: 280]
* **开发语言**：Java
* [cite_start]**第三方集成**：GitLab4J-API [cite: 120]
* [cite_start]**API 文档**：Swagger (OpenAPI 3) [cite: 125]

## 🚀 快速开始

### 1. 后端服务启动

[cite_start]后端项目为标准的 Spring Boot 工程，默认运行在 `31020` 端口 [cite: 282]。

**修改配置**：
[cite_start]在 `src/main/resources/application.yml` 中配置您的 GitLab 实例地址与个人访问令牌（PAT）[cite: 121, 282]：
```yaml
gitlab:
  url: "http://您的-gitlab-地址/"
  token: "您的-glpat-token"
```

**启动服务**：
[cite_start]运行 `GingestApplication.java` 的 `main` 方法 [cite: 280]。
[cite_start]启动成功后，可在浏览器访问接口文档测试功能：`http://127.0.0.1:31020/doc.html` [cite: 281]。

### 2. 前端服务启动

[cite_start]前端项目位于 `gingest-ui` 目录下 [cite: 1][cite_start]。Vite 代理已自动配置，开发环境会把 `/api` 流量转发至 `http://localhost:31020` [cite: 110]。

```bash
# 进入前端目录
cd gingest-ui

# 安装依赖包
npm install

# 启动开发服务器
npm run dev
```

启动完成后，打开终端提示的 `localhost` 地址即可开始使用。

## 📖 使用指南

1.  [cite_start]**选择数据源**：在顶部控制栏切换【GitLab】或【本地磁盘】模式 [cite: 63]。
2.  **获取代码**：
    * [cite_start]如果是 GitLab，可模糊搜索项目名，下拉选择对应的分支，点击【开始提取】[cite: 64, 66]。
    * [cite_start]如果是本地磁盘，直接点击【开始提取】，并在弹出的浏览器授权窗口中选择您的源码文件夹（建议直接选择 `src` 等有效代码目录，避开根目录）[cite: 26, 66]。
3.  **精细化操作**：
    * [cite_start]**左侧看板**：查看项目的基础文件数、Tokens 预估量与整体大小 [cite: 68, 69, 70, 71]。
    * [cite_start]**中间代码树**：浏览完整的目录结构。您可以勾选想要深入分析的具体文件，点击右上角的【组装勾选】，剔除无效代码 [cite: 50, 72]。
    * [cite_start]**右侧接口树**：专门针对 Java 后端的 Facade 层，可以搜索业务接口方法。点击具体的方法，会自动在中间的目录树中定位到其对应的物理文件并高亮 [cite: 53, 54, 79]。
4.  **复制/下载投喂给大模型**：
    * [cite_start]使用【复制当前视图代码】一键将规整好的代码送入剪贴板 [cite: 84]。
    * [cite_start]如遇超大型项目，建议点击【下载完整 TXT】，将生成的 `.txt` 文本作为附件上传给具有文件分析能力的 AI 助手 [cite: 85, 86]。

## ⚠️ 注意事项与过滤规则

为了保证生成的 AI 上下文高度纯净并防止性能崩塌，系统在前后端均内置了强制过滤黑名单：
* [cite_start]**拦截无用目录**：`node_modules`, `.git`, `target`, `.idea`, `build` 等 [cite: 13, 155, 236]。
* [cite_start]**拦截二进制与非代码文件**：包括但不限于 `.jar`, `.exe`, `.png`, `.pdf`, `.mp4` 以及常规构建产物 `.min.js`, `.map` 等 [cite: 13, 153, 234]。
* [cite_start]**拦截重型锁文件**：`package-lock.json`, `yarn.lock`, `pnpm-lock.yaml` [cite: 13, 156]。