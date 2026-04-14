<script setup lang="ts">
import {ref} from 'vue'
import {Document, Download, Connection} from '@element-plus/icons-vue'
import axios from 'axios'
import {ElMessage} from 'element-plus'

// --- 1. 定义后端返回的数据结构接口 ---
interface GingestResponse {
  projectName: string
  fileCount: number
  estimatedTokens: number
  formattedSize: string
  directoryTree: string
  content: string
}

// --- 2. 响应式状态 ---
const searchInput = ref<string>('')
const loading = ref<boolean>(false)
const resultData = ref<GingestResponse | null>(null)

// 新增：项目列表相关的状态
const projectList = ref<string[]>([])
const loadingProjects = ref<boolean>(false)

// 分支相关的状态
const branchList = ref<string[]>([])
const selectedBranch = ref<string>('')
const loadingBranches = ref<boolean>(false)

// --- 新增方法：获取用户有权限的所有项目列表 ---
const handleFetchProjects = async (visible: boolean) => {
  // 只有当下拉框展开，并且列表为空时才去请求，避免每次点击都发请求
  if (visible && projectList.value.length === 0) {
    loadingProjects.value = true
    try {
      const response = await axios.get<string[]>('/api/ingest/projects')
      projectList.value = response.data
      if (projectList.value.length === 0) {
        ElMessage.warning('未获取到任何有权限的项目，请检查 Token 配置')
      }
    } catch (error) {
      console.error(error)
      ElMessage.error('获取项目列表失败')
    } finally {
      loadingProjects.value = false
    }
  }
}

// --- 方法：获取分支列表 ---
const handleFetchBranches = async () => {
  if (!searchInput.value) {
    return
  }

  loadingBranches.value = true
  branchList.value = []
  selectedBranch.value = ''

  try {
    const response = await axios.get<string[]>('/api/ingest/branches', {
      params: {projectId: searchInput.value}
    })

    branchList.value = response.data
    if (branchList.value.length > 0) {
      selectedBranch.value = branchList.value.includes('master') ? 'master' :
        branchList.value.includes('main') ? 'main' :
          (branchList.value[0] || '')
      ElMessage.success(`成功获取分支，已自动选中: ${selectedBranch.value}`)
    } else {
      ElMessage.warning('该项目未找到任何分支')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('获取分支失败，请检查项目地址或网络状态')
  } finally {
    loadingBranches.value = false
  }
}

// --- 核心方法：调用后端提取代码 ---
const handleIngest = async () => {
  if (!searchInput.value) {
    ElMessage.warning('请选择或输入 GitLab 项目')
    return
  }
  if (branchList.value.length > 0 && !selectedBranch.value) {
    ElMessage.warning('请选择一个分支')
    return
  }

  loading.value = true
  try {
    const response = await axios.get<GingestResponse>('/api/ingest', {
      params: {
        projectId: searchInput.value,
        branch: selectedBranch.value
      }
    })

    resultData.value = response.data
    ElMessage.success(`提取成功！共包含 ${response.data.fileCount} 个文件`)
  } catch (error) {
    console.error(error)
    ElMessage.error('提取失败，请检查项目权限或后端服务状态')
  } finally {
    loading.value = false
  }
}

// --- 辅助方法：调用下载接口 ---
const handleDownload = () => {
  if (!searchInput.value) return
  let downloadUrl = `/api/ingest/download?projectId=${encodeURIComponent(searchInput.value)}`
  if (selectedBranch.value) {
    downloadUrl += `&branch=${encodeURIComponent(selectedBranch.value)}`
  }
  window.open(downloadUrl, '_blank')
}

// --- 辅助方法：一键复制文本 ---
const handleCopy = async () => {
  if (!resultData.value || !resultData.value.content) return
  try {
    await navigator.clipboard.writeText(resultData.value.content)
    ElMessage.success('代码已成功复制到剪贴板！')
  } catch (err) {
    ElMessage.error('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="common-layout">
    <el-container class="main-container">

      <el-header class="header">
        <h2>Gingest 代码提取器</h2>
        <div class="operation-bar">

          <el-select
            v-model="searchInput"
            placeholder="搜索或选择项目 (支持直接粘贴纯数字ID)"
            class="project-select"
            filterable
            allow-create
            default-first-option
            clearable
            :loading="loadingProjects"
            @visible-change="handleFetchProjects"
            @change="handleFetchBranches"
          >
            <el-option
              v-for="proj in projectList"
              :key="proj"
              :label="proj"
              :value="proj"
            />
          </el-select>

          <el-button :icon="Connection" :loading="loadingBranches" @click="handleFetchBranches" title="手动刷新分支">
            获取分支
          </el-button>

          <el-select
            v-model="selectedBranch"
            placeholder="请选择分支"
            class="branch-select"
            :disabled="branchList.length === 0"
            filterable
          >
            <el-option
              v-for="branch in branchList"
              :key="branch"
              :label="branch"
              :value="branch"
            />
          </el-select>

          <el-button type="primary" :loading="loading" @click="handleIngest">
            开始提取
          </el-button>
        </div>
      </el-header>

      <el-main class="main-content" v-loading="loading" element-loading-text="正在狂奔向 GitLab 拉取代码...">

        <div class="top-section" v-if="resultData">
          <el-row :gutter="20" style="height: 100%;">
            <el-col :span="8">
              <div class="panel-title">项目摘要 (Summary)</div>
              <el-card shadow="never" class="summary-card">
                <p><strong>项目:</strong> {{ resultData.projectName }}</p>
                <p><strong>文件数:</strong> {{ resultData.fileCount }} files</p>
                <p><strong>预估 Tokens:</strong> {{ resultData.estimatedTokens }}</p>
                <p><strong>文本大小:</strong> {{ resultData.formattedSize }}</p>
              </el-card>
            </el-col>

            <el-col :span="16">
              <div class="panel-title">目录结构 (Tree)</div>
              <el-input
                type="textarea"
                readonly
                v-model="resultData.directoryTree"
                class="code-font tree-textarea"
              />
            </el-col>
          </el-row>
        </div>

        <div class="bottom-section">
          <div class="action-bar">
            <span class="panel-title">提取结果 (Files Content)</span>
            <div>
              <el-button type="success" :icon="Document" :disabled="!resultData" @click="handleCopy">
                复制全部代码
              </el-button>
              <el-button type="warning" :icon="Download" :disabled="!resultData" @click="handleDownload">
                下载 TXT
              </el-button>
            </div>
          </div>

          <el-input
            v-if="resultData"
            type="textarea"
            readonly
            v-model="resultData.content"
            class="code-textarea code-font"
          />
          <el-card v-else class="empty-card" shadow="never">
            <div class="empty-text">请选择项目 -> 确认分支 -> 开始提取...</div>
          </el-card>
        </div>

      </el-main>
    </el-container>
  </div>
</template>

<style scoped>
.common-layout {
  height: 100vh;
  background-color: #f5f7fa;
}

.main-container {
  height: 100%;
}

.header {
  background-color: #24292f;
  color: white;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header h2 {
  margin: 0;
  font-size: 20px;
}

.operation-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 调整后的项目选择框宽度 */
.project-select {
  width: 400px;
}

.branch-select {
  width: 180px;
}

.main-content {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  height: calc(100vh - 60px);
  box-sizing: border-box;
}

.panel-title {
  font-weight: bold;
  margin-bottom: 10px;
  color: #303133;
}

.top-section {
  flex: 0 0 240px;
}

.summary-card {
  height: calc(100% - 30px);
  box-sizing: border-box;
}

.summary-card p {
  margin: 8px 0;
  font-size: 14px;
  color: #606266;
}

.tree-textarea :deep(.el-textarea__inner) {
  height: 208px;
  resize: none;
}

.bottom-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.code-textarea {
  flex: 1;
}

.code-textarea :deep(.el-textarea__inner) {
  height: 100%;
  resize: none;
}

.code-font :deep(.el-textarea__inner) {
  font-family: 'Consolas', 'Courier New', Courier, monospace;
  background-color: #fafafa;
  font-size: 13px;
  line-height: 1.5;
}

.empty-card {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-text {
  color: #909399;
  font-size: 14px;
  text-align: center;
}
</style>
