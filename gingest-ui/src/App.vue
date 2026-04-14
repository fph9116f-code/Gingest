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

// 新增：分支相关的状态
const branchList = ref<string[]>([])
const selectedBranch = ref<string>('')
const loadingBranches = ref<boolean>(false)

// --- 新增方法：获取分支列表 ---
const handleFetchBranches = async () => {
  if (!searchInput.value) {
    ElMessage.warning('请先输入 GitLab 项目地址或 ID')
    return
  }

  loadingBranches.value = true
  branchList.value = [] // 清空旧数据
  selectedBranch.value = ''

  try {
    const response = await axios.get<string[]>('/api/ingest/branches', {
      params: {projectId: searchInput.value}
    })

    branchList.value = response.data
    if (branchList.value.length > 0) {
      // 默认选中列表里的第一个分支（通常 GitLab 接口返回的第一个或包含 default 的就是主分支）
      selectedBranch.value = branchList.value.includes('master') ? 'master' :
        branchList.value.includes('main') ? 'main' :
          (branchList.value[0] || '')
      branchList.value.includes('main') ? 'main' : branchList.value[0]
      ElMessage.success(`成功获取 ${branchList.value.length} 个分支，请确认后点击提取`)
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
    ElMessage.warning('请输入 GitLab 项目地址或 ID')
    return
  }
  // 如果拉取了分支但没选，给个提示
  if (branchList.value.length > 0 && !selectedBranch.value) {
    ElMessage.warning('请选择一个分支')
    return
  }

  loading.value = true
  try {
    const response = await axios.get<GingestResponse>('/api/ingest', {
      params: {
        projectId: searchInput.value,
        branch: selectedBranch.value // 将选中的分支传给后端
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
  // 下载时也带上分支参数
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
          <el-input
            v-model="searchInput"
            placeholder="项目地址 (如: zysoft/medical-order)"
            clearable
            class="input-box"
            @keyup.enter="handleFetchBranches"
          >
            <template #append>
              <el-button :icon="Connection" :loading="loadingBranches" @click="handleFetchBranches">
                获取分支
              </el-button>
            </template>
          </el-input>

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

      <el-container v-loading="loading" element-loading-text="正在狂奔向 GitLab 拉取代码...">
        <el-aside width="350px" class="aside-tree">
          <div class="panel-title">项目摘要 (Summary)</div>
          <el-card shadow="never" class="summary-card" v-if="resultData">
            <p><strong>项目:</strong> {{ resultData.projectName }}</p>
            <p><strong>文件数:</strong> {{ resultData.fileCount }} files</p>
            <p><strong>预估 Tokens:</strong> {{ resultData.estimatedTokens }}</p>
            <p><strong>文本大小:</strong> {{ resultData.formattedSize }}</p>
          </el-card>
          <div v-else class="empty-text">暂无摘要信息</div>

          <div class="panel-title" style="margin-top: 20px;">目录结构 (Tree)</div>
          <el-input
            v-if="resultData"
            type="textarea"
            :rows="18"
            readonly
            v-model="resultData.directoryTree"
            class="code-font"
          />
          <div v-else class="empty-text">暂无目录树信息</div>
        </el-aside>

        <el-main class="main-content">
          <div class="action-bar">
            <span class="panel-title">提取结果 (Files Content)</span>
            <div>
              <el-button type="success" :icon="Document" :disabled="!resultData"
                         @click="handleCopy">
                复制全部代码
              </el-button>
              <el-button type="warning" :icon="Download" :disabled="!resultData"
                         @click="handleDownload">
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
          <el-card v-else class="code-card empty-card" shadow="never">
            请输入地址 -> 获取分支 -> 开始提取...
          </el-card>
        </el-main>

      </el-container>
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

/* --- 新增的操作区样式 --- */
.operation-bar {
  display: flex;
  align-items: center;
  gap: 12px; /* 元素之间的间距 */
}

.input-box {
  width: 450px;
}

.branch-select {
  width: 200px;
}

.aside-tree {
  background-color: white;
  border-right: 1px solid #dcdfe6;
  padding: 15px;
  display: flex;
  flex-direction: column;
}

.main-content {
  padding: 20px;
  display: flex;
  flex-direction: column;
}

.panel-title {
  font-weight: bold;
  margin-bottom: 10px;
  color: #303133;
}

.summary-card p {
  margin: 5px 0;
  font-size: 14px;
  color: #606266;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.code-font :deep(.el-textarea__inner) {
  font-family: 'Consolas', 'Courier New', Courier, monospace;
  background-color: #fafafa;
  font-size: 13px;
  line-height: 1.5;
}

.code-textarea :deep(.el-textarea__inner) {
  height: calc(100vh - 160px);
  resize: none;
}

.empty-card {
  height: calc(100vh - 160px);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.empty-text {
  color: #909399;
  font-size: 14px;
  text-align: center;
  padding: 20px 0;
}
</style>
