<script setup lang="ts">
import { ref } from 'vue'
import { Document, Download, Connection } from '@element-plus/icons-vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

// --- 1. 定义后端返回的数据结构接口 ---
interface GingestResponse {
  projectName: string
  fileCount: number
  estimatedTokens: number
  formattedSize: string
  directoryTree: string
  content: string
}

interface FacadeInfo {
  className: string
  path: string
  methods: string[]
}

interface TreeNode {
  label: string
  children?: TreeNode[]
}

// --- 2. 响应式状态 ---
const searchInput = ref<string>('')
const loading = ref<boolean>(false)
const resultData = ref<GingestResponse | null>(null)

// Facade 树状图数据
const facadeTreeData = ref<TreeNode[]>([])
const treeProps = {
  children: 'children',
  label: 'label',
}

const projectList = ref<string[]>([])
const loadingProjects = ref<boolean>(false)

const branchList = ref<string[]>([])
const selectedBranch = ref<string>('')
const loadingBranches = ref<boolean>(false)

// --- 新增：拖拽调整上下比例的状态与逻辑 ---
const topHeight = ref<number>(280) // 上半部分默认高度 280px
const isDragging = ref<boolean>(false)

const startDrag = () => {
  isDragging.value = true
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
  document.body.style.userSelect = 'none' // 防止拖拽时误选中文本
}

const onDrag = (e: MouseEvent) => {
  if (!isDragging.value) return
  // 鼠标Y轴坐标 - 顶部导航栏高度(60px) - 主体内边距(20px)
  let newHeight = e.clientY - 80
  // 限制拖拽的最小和最大高度，防止把窗口挤没
  if (newHeight < 150) newHeight = 150
  if (newHeight > window.innerHeight - 200) newHeight = window.innerHeight - 200
  topHeight.value = newHeight
}

const stopDrag = () => {
  isDragging.value = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
  document.body.style.userSelect = '' // 恢复文本选中能力
}

// --- 方法：获取用户有权限的所有项目列表 ---
const handleFetchProjects = async (visible: boolean) => {
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
  if (!searchInput.value) return

  loadingBranches.value = true
  branchList.value = []
  selectedBranch.value = ''

  try {
    const response = await axios.get<string[]>('/api/ingest/branches', {
      params: { projectId: searchInput.value }
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

// --- 核心方法：并发拉取代码和 Facade 树 ---
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
  facadeTreeData.value = []

  try {
    const [ingestRes, facadeRes] = await Promise.all([
      axios.get<GingestResponse>('/api/ingest', {
        params: { projectId: searchInput.value, branch: selectedBranch.value }
      }),
      axios.get<FacadeInfo[]>('/api/ingest/facades', {
        params: { projectId: searchInput.value, branch: selectedBranch.value }
      }).catch(err => {
        console.warn('获取 Facade 接口数据失败', err);
        return { data: [] };
      })
    ])

    resultData.value = ingestRes.data

    facadeTreeData.value = facadeRes.data.map(item => ({
      label: item.className,
      children: item.methods.map(method => ({ label: method }))
    }))

    ElMessage.success(`提取成功！共包含 ${ingestRes.data.fileCount} 个文件`)
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
            <el-option v-for="proj in projectList" :key="proj" :label="proj" :value="proj" />
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
            <el-option v-for="branch in branchList" :key="branch" :label="branch" :value="branch" />
          </el-select>

          <el-button type="primary" :loading="loading" @click="handleIngest">
            开始提取
          </el-button>
        </div>
      </el-header>

      <el-main class="main-content" v-loading="loading" element-loading-text="正在狂奔向 GitLab 拉取代码...">

        <div class="top-section" v-if="resultData" :style="{ height: topHeight + 'px' }">
          <el-row :gutter="15" class="h-100">

            <el-col :span="5" class="h-100">
              <div class="panel-title">项目摘要 (Summary)</div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <p><strong>项目:</strong><br/> <span class="summary-text">{{ resultData.projectName }}</span></p>
                <p><strong>文件数:</strong> {{ resultData.fileCount }} files</p>
                <p><strong>Tokens:</strong> {{ resultData.estimatedTokens }}</p>
                <p><strong>大小:</strong> {{ resultData.formattedSize }}</p>
              </el-card>
            </el-col>

            <el-col :span="10" class="h-100">
              <div class="panel-title">目录结构 (Tree)</div>
              <div class="textarea-wrapper">
                <el-input
                  type="textarea"
                  readonly
                  v-model="resultData.directoryTree"
                  class="code-font tree-textarea"
                />
              </div>
            </el-col>

            <el-col :span="9" class="h-100">
              <div class="panel-title">Facade 接口 (Interfaces)</div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <el-tree
                  :data="facadeTreeData"
                  :props="treeProps"
                  empty-text="未扫描到 Facade 接口数据"
                  class="facade-tree"
                />
              </el-card>
            </el-col>

          </el-row>
        </div>

        <div class="drag-divider" v-if="resultData" @mousedown="startDrag">
          <div class="drag-line"></div>
        </div>

        <div class="bottom-section" :class="{ 'flex-center': !resultData }">
          <template v-if="resultData">
            <div class="action-bar">
              <span class="panel-title">提取结果 (Files Content)</span>
              <div>
                <el-button type="success" :icon="Document" @click="handleCopy">
                  复制全部代码
                </el-button>
                <el-button type="warning" :icon="Download" @click="handleDownload">
                  下载 TXT
                </el-button>
              </div>
            </div>
            <div class="textarea-wrapper">
              <el-input
                type="textarea"
                readonly
                v-model="resultData.content"
                class="code-font bottom-textarea"
              />
            </div>
          </template>

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

.project-select {
  width: 400px;
}

.branch-select {
  width: 180px;
}

/* 主体容器需要 overflow:hidden 以防止整个页面被意外撑开 */
.main-content {
  padding: 20px;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px);
  box-sizing: border-box;
  overflow: hidden;
}

.panel-title {
  font-weight: bold;
  margin-bottom: 10px;
  color: #303133;
}

/* --- 顶部区域 --- */
.top-section {
  flex: none; /* 高度完全由 style 动态控制，不参与弹性伸缩 */
}

.h-100 {
  height: 100%;
}

/* --- 修复溢出问题的核心：可内滚动的卡片 --- */
.panel-card {
  height: calc(100% - 30px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

.scrollable-card :deep(.el-card__body) {
  flex: 1;
  overflow-y: auto; /* 子元素过多时，只在卡片内部出滚动条！绝不向外撑破 */
  padding: 10px 15px;
  min-height: 0;
}

.facade-tree {
  font-family: 'Consolas', 'Courier New', Courier, monospace;
  font-size: 13px;
}

.panel-card p {
  margin: 5px 0;
  font-size: 13px;
  color: #606266;
}

.summary-text {
  word-break: break-all;
  color: #409EFF;
}

/* --- 动态高度适配的文本域包装器 --- */
.textarea-wrapper {
  height: calc(100% - 30px);
}
.tree-textarea {
  height: 100%;
}
.tree-textarea :deep(.el-textarea__inner) {
  height: 100%;
  resize: none;
}

/* --- 神级拖拽分割线 --- */
.drag-divider {
  height: 18px;
  cursor: row-resize; /* 鼠标悬浮时变成上下拉伸图标 */
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 2px 0;
  flex-shrink: 0;
  transition: background-color 0.2s;
}

.drag-line {
  width: 60px;
  height: 4px;
  background-color: #dcdfe6;
  border-radius: 2px;
  transition: background-color 0.2s;
}

.drag-divider:hover .drag-line,
.drag-divider:active .drag-line {
  background-color: #409EFF; /* 拖拽时变为品牌蓝 */
}

/* --- 底部区域自动铺满 --- */
.bottom-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flex-center {
  justify-content: center;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  flex-shrink: 0;
}

.bottom-textarea {
  height: 100%;
}
.bottom-textarea :deep(.el-textarea__inner) {
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
