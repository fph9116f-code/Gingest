<script setup lang="ts">
import { ref, watch } from 'vue'
import { Document, Download, Connection, Refresh } from '@element-plus/icons-vue'
import axios from 'axios'
import { ElMessage, ElTree } from 'element-plus'

interface GingestResponse {
  projectName: string
  fileCount: number
  estimatedTokens: number
  formattedSize: string
  directoryTree: TreeNode[] // 现在后端返回的是树形对象数组了
  content: string
  fullContent?: string // 用于前端缓存完整代码
}

interface FacadeInfo {
  className: string
  path: string
  methods: string[]
}

interface TreeNode {
  label: string
  isFile: boolean
  fullPath?: string
  content?: string
  children?: TreeNode[]
}

const searchInput = ref<string>('')
const loading = ref<boolean>(false)
const resultData = ref<GingestResponse | null>(null)

// 动态视图的标题
const currentViewTitle = ref<string>('全部提取结果 (All Files)')

// 中间：目录结构树的响应式数据
const filterDirText = ref('')
const dirTreeRef = ref<InstanceType<typeof ElTree>>()

// 右侧：Facade 树的响应式数据
const facadeTreeData = ref<TreeNode[]>([])
const filterText = ref('')
const treeRef = ref<InstanceType<typeof ElTree>>()

const treeProps = {
  children: 'children',
  label: 'label',
}

// 监听搜索框变化
watch(filterDirText, (val) => { dirTreeRef.value!.filter(val) })
watch(filterText, (val) => { treeRef.value!.filter(val) })

// 统一的树节点过滤逻辑 (忽略大小写)
const filterNode = (value: string, data: any) => {
  if (!value) return true
  // 加上 ? 防止万一哪个节点没有 label 属性导致报错
  return data.label?.toLowerCase().includes(value.toLowerCase())
}

const projectList = ref<string[]>([])
const loadingProjects = ref<boolean>(false)

const branchList = ref<string[]>([])
const selectedBranch = ref<string>('')
const loadingBranches = ref<boolean>(false)

const topHeight = ref<number>(280)
const isDragging = ref<boolean>(false)

const startDrag = () => {
  isDragging.value = true
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
  document.body.style.userSelect = 'none'
}

const onDrag = (e: MouseEvent) => {
  if (!isDragging.value) return
  let newHeight = e.clientY - 80
  if (newHeight < 150) newHeight = 150
  if (newHeight > window.innerHeight - 200) newHeight = window.innerHeight - 200
  topHeight.value = newHeight
}

const stopDrag = () => {
  isDragging.value = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
  document.body.style.userSelect = ''
}

const handleFetchProjects = async (visible: boolean) => {
  if (visible && projectList.value.length === 0) {
    loadingProjects.value = true
    try {
      const response = await axios.get<string[]>('/api/ingest/projects')
      projectList.value = response.data
    } catch (error) {
      ElMessage.error('获取项目列表失败')
    } finally {
      loadingProjects.value = false
    }
  }
}

const handleFetchBranches = async () => {
  if (!searchInput.value) return
  loadingBranches.value = true
  branchList.value = []
  selectedBranch.value = ''
  try {
    const response = await axios.get<string[]>('/api/ingest/branches', { params: { projectId: searchInput.value } })
    branchList.value = response.data
    if (branchList.value.length > 0) {
      selectedBranch.value = branchList.value.includes('master') ? 'master' : branchList.value.includes('main') ? 'main' : (branchList.value[0] || '')
    }
  } catch (error) {
    ElMessage.error('获取分支失败')
  } finally {
    loadingBranches.value = false
  }
}

const handleIngest = async () => {
  if (!searchInput.value) return ElMessage.warning('请选择项目')
  loading.value = true
  facadeTreeData.value = []
  filterText.value = ''
  filterDirText.value = ''
  currentViewTitle.value = '全部提取结果 (All Files)'

  try {
    const [ingestRes, facadeRes] = await Promise.all([
      axios.get<GingestResponse>('/api/ingest', { params: { projectId: searchInput.value, branch: selectedBranch.value } }),
      axios.get<FacadeInfo[]>('/api/ingest/facades', { params: { projectId: searchInput.value, branch: selectedBranch.value } }).catch(() => ({ data: [] }))
    ])

    resultData.value = ingestRes.data
    resultData.value.fullContent = ingestRes.data.content // 缓存一份全局全量代码

    facadeTreeData.value = facadeRes.data.map(item => ({
      label: item.className,
      isFile: false,
      children: item.methods.map(method => ({ label: method, isFile: true }))
    }))

    ElMessage.success(`提取成功！共 ${ingestRes.data.fileCount} 个文件`)
  } catch (error) {
    ElMessage.error('提取失败')
  } finally {
    loading.value = false
  }
}

// === 核心互动：点击目录树触发内容刷新 ===
const handleDirTreeClick = (node: TreeNode) => {
  if (!resultData.value) return
  const gathered = gatherContent(node)
  resultData.value.content = gathered || '该文件夹下为空或无有效代码文件'
  currentViewTitle.value = `查看: ${node.label}`
}

// 递归拼接选中节点及子节点的所有代码
const gatherContent = (node: TreeNode): string => {
  if (node.isFile) {
    return `================================================\nFile: ${node.fullPath || node.label}\n================================================\n${node.content || ''}\n\n`
  }
  let res = ''
  if (node.children) {
    for (const child of node.children) {
      res += gatherContent(child)
    }
  }
  return res
}

// 恢复查看全库代码
const resetView = () => {
  if (resultData.value && resultData.value.fullContent) {
    resultData.value.content = resultData.value.fullContent
    currentViewTitle.value = '全部提取结果 (All Files)'
  }
}

const handleDownload = () => {
  if (!searchInput.value) return
  let downloadUrl = `/api/ingest/download?projectId=${encodeURIComponent(searchInput.value)}`
  if (selectedBranch.value) downloadUrl += `&branch=${encodeURIComponent(selectedBranch.value)}`
  window.open(downloadUrl, '_blank')
}

const handleCopy = async () => {
  if (!resultData.value || !resultData.value.content) return
  try {
    await navigator.clipboard.writeText(resultData.value.content)
    ElMessage.success('当前视图的代码已复制！')
  } catch (err) {
    ElMessage.error('复制失败')
  }
}
</script>

<template>
  <div class="common-layout">
    <el-container class="main-container">

      <el-header class="header">
        <h2>Gingest 代码提取器</h2>
        <div class="operation-bar">
          <el-select v-model="searchInput" placeholder="搜索或选择项目" class="project-select" filterable allow-create clearable :loading="loadingProjects" @visible-change="handleFetchProjects" @change="handleFetchBranches">
            <el-option v-for="proj in projectList" :key="proj" :label="proj" :value="proj" />
          </el-select>
          <el-button :icon="Connection" :loading="loadingBranches" @click="handleFetchBranches" title="手动刷新分支">获取分支</el-button>
          <el-select v-model="selectedBranch" placeholder="请选择分支" class="branch-select" :disabled="branchList.length === 0" filterable>
            <el-option v-for="branch in branchList" :key="branch" :label="branch" :value="branch" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="handleIngest">开始提取</el-button>
        </div>
      </el-header>

      <el-main class="main-content" v-loading="loading" element-loading-text="正在狂奔向 GitLab 拉取代码...">
        <div class="top-section" v-if="resultData" :style="{ height: topHeight + 'px' }">
          <el-row :gutter="15" class="h-100">

            <el-col :span="5" class="h-100">
              <div class="panel-header-with-search">
                <div class="panel-title">项目摘要 (Summary)</div>
              </div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <p><strong>项目:</strong><br/> <span class="summary-text">{{ resultData.projectName }}</span></p>
                <p><strong>文件数:</strong> {{ resultData.fileCount }} files</p>
                <p><strong>Tokens:</strong> {{ resultData.estimatedTokens }}</p>
                <p><strong>大小:</strong> {{ resultData.formattedSize }}</p>
              </el-card>
            </el-col>

            <el-col :span="10" class="h-100">
              <div class="panel-header-with-search">
                <div class="panel-title">目录结构 (Files Tree)</div>
                <el-input v-model="filterDirText" placeholder="搜索文件或目录..." size="small" clearable class="facade-search" />
              </div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <el-tree
                  ref="dirTreeRef"
                  :data="resultData.directoryTree"
                  :props="treeProps"
                  :filter-node-method="filterNode"
                  @node-click="handleDirTreeClick"
                  empty-text="暂无有效代码文件"
                  class="facade-tree"
                />
              </el-card>
            </el-col>

            <el-col :span="9" class="h-100">
              <div class="panel-header-with-search">
                <div class="panel-title">Facade 接口 (Interfaces)</div>
                <el-input v-model="filterText" placeholder="搜索方法、注释或类名..." size="small" clearable class="facade-search" />
              </div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <el-tree ref="treeRef" :data="facadeTreeData" :props="treeProps" :filter-node-method="filterNode" empty-text="未扫描到 Facade 接口" class="facade-tree" />
              </el-card>
            </el-col>

          </el-row>
        </div>

        <div class="drag-divider" v-if="resultData" @mousedown="startDrag"><div class="drag-line"></div></div>

        <div class="bottom-section" :class="{ 'flex-center': !resultData }">
          <template v-if="resultData">
            <div class="action-bar">
              <span class="panel-title" style="color: #409EFF;">{{ currentViewTitle }}</span>
              <div>
                <el-button type="info" plain :icon="Refresh" @click="resetView" v-if="currentViewTitle !== '全部提取结果 (All Files)'">
                  恢复查看全库
                </el-button>
                <el-button type="success" :icon="Document" @click="handleCopy">
                  复制当前视图代码
                </el-button>
                <el-button type="warning" :icon="Download" @click="handleDownload">
                  下载完整 TXT
                </el-button>
              </div>
            </div>
            <div class="textarea-wrapper">
              <el-input type="textarea" readonly v-model="resultData.content" class="code-font bottom-textarea" />
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
.common-layout { height: 100vh; background-color: #f5f7fa; }
.main-container { height: 100%; }
.header { background-color: #24292f; color: white; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; }
.header h2 { margin: 0; font-size: 20px; }
.operation-bar { display: flex; align-items: center; gap: 12px; }
.project-select { width: 400px; }
.branch-select { width: 180px; }
.main-content { padding: 20px; display: flex; flex-direction: column; height: calc(100vh - 60px); box-sizing: border-box; overflow: hidden; }

.panel-header-with-search { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; height: 24px; }
.panel-header-with-search .panel-title { margin-bottom: 0; }
.facade-search { width: 160px; }

.panel-title { font-weight: bold; margin-bottom: 10px; color: #303133; }
.top-section { flex: none; }
.h-100 { height: 100%; }

.panel-card { height: calc(100% - 34px); box-sizing: border-box; display: flex; flex-direction: column; }
.scrollable-card :deep(.el-card__body) { flex: 1; overflow-y: auto; padding: 10px 15px; min-height: 0; }
.facade-tree { font-family: 'Consolas', 'Courier New', Courier, monospace; font-size: 13px; }
.panel-card p { margin: 5px 0; font-size: 13px; color: #606266; }
.summary-text { word-break: break-all; color: #409EFF; }

.textarea-wrapper { height: calc(100% - 30px); }
.drag-divider { height: 18px; cursor: row-resize; display: flex; align-items: center; justify-content: center; margin: 2px 0; flex-shrink: 0; transition: background-color 0.2s; }
.drag-line { width: 60px; height: 4px; background-color: #dcdfe6; border-radius: 2px; transition: background-color 0.2s; }
.drag-divider:hover .drag-line, .drag-divider:active .drag-line { background-color: #409EFF; }

.bottom-section { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.flex-center { justify-content: center; }
.action-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; flex-shrink: 0; }
.bottom-textarea { height: 100%; }
.bottom-textarea :deep(.el-textarea__inner) { height: 100%; resize: none; }
.code-font :deep(.el-textarea__inner) { font-family: 'Consolas', 'Courier New', Courier, monospace; background-color: #fafafa; font-size: 13px; line-height: 1.5; }
.empty-card { flex: 1; display: flex; align-items: center; justify-content: center; }
.empty-text { color: #909399; font-size: 14px; text-align: center; }
</style>
