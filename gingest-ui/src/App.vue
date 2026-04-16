<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { Document, Download, Connection, Refresh, CircleCheck, Folder } from '@element-plus/icons-vue'
import axios from 'axios'
import { ElMessage, ElTree } from 'element-plus'

interface GingestResponse {
  projectName: string
  fileCount: number
  estimatedTokens: number
  formattedSize: string
  directoryTree: TreeNode[]
  content: string
  // 新增：用于缓存全库的原始统计数据
  fullContent?: string
  fullFileCount?: number
  fullEstimatedTokens?: number
  fullFormattedSize?: string
}

interface FacadeInfo {
  className: string
  path: string
  methods: string[]
}

interface TreeNode {
  id?: number
  label: string
  isFile: boolean
  fullPath?: string
  content?: string
  children?: TreeNode[]
}

const searchInput = ref<string>('')
const loading = ref<boolean>(false)
const resultData = ref<GingestResponse | null>(null)

// ==========================================
// 【新增】：防卡死预览截断逻辑
// ==========================================
const MAX_DISPLAY_LENGTH = 100000 // 10 万字符安全阈值，随便拖拽都不卡
const isContentTruncated = computed(() => {
  return resultData.value && resultData.value.content && resultData.value.content.length > MAX_DISPLAY_LENGTH
})

const previewContent = computed(() => {
  if (!resultData.value || !resultData.value.content) return ''
  if (resultData.value.content.length > MAX_DISPLAY_LENGTH) {
    return resultData.value.content.substring(0, MAX_DISPLAY_LENGTH) +
      '\n\n\n================================================\n' +
      '【⚠️ 内容过长，已开启防卡死截断保护】\n' +
      ' 为保证浏览器流畅，此处仅展示前 10 万字符预览。\n' +
      ' 您提取的完整代码已在后台就绪，请点击上方【复制】或【下载】获取全量内容！\n' +
      '================================================'
  }
  return resultData.value.content
})

const currentViewTitle = ref<string>('全部提取结果 (All Files)')

const filterDirText = ref('')
const dirTreeRef = ref<InstanceType<typeof ElTree>>()

const facadeTreeData = ref<TreeNode[]>([])
const filterText = ref('')
const treeRef = ref<InstanceType<typeof ElTree>>()

const treeProps = {
  children: 'children',
  label: 'label',
}

watch(filterDirText, (val) => { dirTreeRef.value!.filter(val) })
watch(filterText, (val) => { treeRef.value!.filter(val) })

const filterNode = (value: string, data: any) => {
  if (!value) return true
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

// --- 新增：前端纯 JS 版的大小格式化与 Tokens 估算算法 ---
const formatSize = (sizeInBytes: number): string => {
  if (sizeInBytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const digitGroups = Math.floor(Math.log10(sizeInBytes) / Math.log10(1024))
  return (sizeInBytes / Math.pow(1024, digitGroups)).toFixed(2) + ' ' + units[digitGroups]
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

    let idCounter = 1
    const assignIds = (nodes: TreeNode[]) => {
      nodes.forEach(node => {
        node.id = idCounter++
        if (node.children) assignIds(node.children)
      })
    }
    assignIds(ingestRes.data.directoryTree)

    resultData.value = ingestRes.data

    // 【核心改动】：缓存全库的完整统计数据，方便后续“恢复”
    resultData.value.fullContent = ingestRes.data.content
    resultData.value.fullFileCount = ingestRes.data.fileCount
    resultData.value.fullEstimatedTokens = ingestRes.data.estimatedTokens
    resultData.value.fullFormattedSize = ingestRes.data.formattedSize

    facadeTreeData.value = facadeRes.data.map(item => ({
      label: item.className,
      path: item.path,
      isFile: false,
      children: item.methods.map(method => ({ label: method, path: item.path, parentClass: item.className, isFile: true }))
    }))

    ElMessage.success(`提取成功！共 ${ingestRes.data.fileCount} 个文件`)
  } catch (error) {
    ElMessage.error('提取失败')
  } finally {
    loading.value = false
  }
}

const generateTreeText = (nodes: TreeNode[], prefix = ''): string => {
  let text = ''
  nodes.forEach((node, index) => {
    const isLast = index === nodes.length - 1
    const connector = isLast ? '└── ' : '├── '
    text += prefix + connector + node.label + (node.isFile ? '' : '/') + '\n'
    if (node.children && node.children.length > 0) {
      const childPrefix = prefix + (isLast ? '    ' : '│   ')
      text += generateTreeText(node.children, childPrefix)
    }
  })
  return text
}

const handleAssembleSelected = () => {
  if (!resultData.value || !dirTreeRef.value) return

  const checkedNodes = dirTreeRef.value.getCheckedNodes()
  const selectedFiles = checkedNodes.filter(node => node.isFile)

  if (selectedFiles.length === 0) {
    ElMessage.warning('请先在目录树中勾选需要包含的文件或文件夹')
    return
  }

  const treeText = "================================================\nDirectory Structure (Tree):\n================================================\n.\n"
    + generateTreeText(resultData.value.directoryTree)

  let contentText = "\n\n================================================\nSelected Files Content:\n================================================\n\n"
  selectedFiles.forEach(file => {
    contentText += `================================================\nFile: ${file.fullPath || file.label}\n================================================\n${file.content || ''}\n\n`
  })

  const finalString = treeText + contentText

  // 【核心改动】：动态计算选中后的 Token、大小、文件数并刷新面板
  resultData.value.content = finalString
  resultData.value.fileCount = selectedFiles.length
  resultData.value.estimatedTokens = Math.floor(finalString.length / 4) // 大致估算：4个字符1个Token
  resultData.value.formattedSize = formatSize(new Blob([finalString]).size) // 精准计算UTF-8字节大小

  currentViewTitle.value = `组装完毕: 完整大纲 + ${selectedFiles.length} 个文件代码`
  ElMessage.success(`成功组装！共抽取了 ${selectedFiles.length} 个核心文件`)
}


const findContentByPath = (nodes: TreeNode[], targetPath: string): string | null => {
  for (const n of nodes) {
    if (n.isFile && n.fullPath === targetPath) return n.content || null
    if (n.children && n.children.length > 0) {
      const found = findContentByPath(n.children, targetPath)
      if (found) return found
    }
  }
  return null
}

const handleFacadeTreeClick = (node: any) => {
  if (!resultData.value || !node.path) return
  const content = findContentByPath(resultData.value.directoryTree, node.path)

  if (content) {
    const finalString = `================================================\nFile: ${node.path}\n================================================\n${content}\n`

    // 【核心改动】：单点文件时，也将统计面板更新为单文件的属性
    resultData.value.content = finalString
    resultData.value.fileCount = 1
    resultData.value.estimatedTokens = Math.floor(finalString.length / 4)
    resultData.value.formattedSize = formatSize(new Blob([finalString]).size)

    const displayName = node.parentClass ? node.parentClass : node.label
    currentViewTitle.value = `查看接口源码: ${displayName}`
  } else {
    ElMessage.warning('未在目录树中提取到该源码 (可能已被过滤)')
  }
}

const resetView = () => {
  if (resultData.value && resultData.value.fullContent) {
    // 【核心改动】：把之前缓存的“全库属性”完璧归赵
    resultData.value.content = resultData.value.fullContent
    resultData.value.fileCount = resultData.value.fullFileCount!
    resultData.value.estimatedTokens = resultData.value.fullEstimatedTokens!
    resultData.value.formattedSize = resultData.value.fullFormattedSize!

    currentViewTitle.value = '全部提取结果 (All Files)'
    if (dirTreeRef.value) {
      dirTreeRef.value.setCheckedKeys([])
    }
  }
}

const handleDownload = () => {
  if (!resultData.value) return
  let downloadContent = ''
  const checkedNodes = dirTreeRef.value ? dirTreeRef.value.getCheckedNodes().filter(n => n.isFile) : []

  if (checkedNodes.length > 0) {
    const treeText = "================================================\nDirectory Structure (Tree):\n================================================\n.\n"
      + generateTreeText(resultData.value.directoryTree)

    let contentText = "\n\n================================================\nSelected Files Content:\n================================================\n\n"
    checkedNodes.forEach(file => {
      contentText += `================================================\nFile: ${file.fullPath || file.label}\n================================================\n${file.content || ''}\n\n`
    })

    downloadContent = `Project: ${resultData.value.projectName}\n` +
      `Export Type: Selected Files (${checkedNodes.length} files)\n\n` +
      treeText + contentText

    ElMessage.success(`正在下载选中的 ${checkedNodes.length} 个核心文件...`)
  } else {
    const treeText = "================================================\nDirectory Structure (Tree):\n================================================\n.\n"
      + generateTreeText(resultData.value.directoryTree)

    downloadContent = `Project: ${resultData.value.projectName}\n` +
      `Export Type: Full Repository (${resultData.value.fullFileCount} files)\n\n` +
      treeText + "\n\nFiles Content:\n------------------------------------------------\n" +
      resultData.value.fullContent

    ElMessage.success('正在下载全库完整代码...')
  }

  const blob = new Blob([downloadContent], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url

  const safeProjectName = searchInput.value.replace(/[\\/:*?"<>|]/g, '_')
  link.download = `${safeProjectName}_gingest${checkedNodes.length > 0 ? '_selected' : '_full'}.txt`

  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const handleCopy = async () => {
  if (!resultData.value || !resultData.value.content) return

  // 【新增强制限制】：如果内容因为过大被截断展示，严禁复制到剪贴板
  if (isContentTruncated.value) {
    ElMessage({
      message: '内容过大！为确保浏览器稳定和数据完整性，暂不允许直接复制。请点击右侧【下载完整 TXT】获取全量内容。',
      type: 'error',
      duration: 5000, // 提示停留久一点，确保用户看清
      showClose: true
    })
    return
  }

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
                <p><strong>文件数:</strong> <span style="color: #67C23A; font-weight: bold;">{{ resultData.fileCount }}</span> files</p>
                <p><strong>Tokens:</strong> <span style="color: #E6A23C; font-weight: bold;">{{ resultData.estimatedTokens }}</span></p>
                <p><strong>大小:</strong> <span style="color: #F56C6C; font-weight: bold;">{{ resultData.formattedSize }}</span></p>
              </el-card>
            </el-col>

            <el-col :span="10" class="h-100">
              <div class="panel-header-with-search">
                <div class="panel-title">目录结构 (Files Tree)</div>
                <div style="display: flex; gap: 8px;">
                  <el-input v-model="filterDirText" placeholder="搜索..." size="small" clearable class="facade-search" />
                  <el-button type="primary" size="small" :icon="CircleCheck" @click="handleAssembleSelected">组装勾选</el-button>
                </div>
              </div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <el-tree
                  ref="dirTreeRef"
                  :data="resultData.directoryTree"
                  :props="treeProps"
                  :filter-node-method="filterNode"
                  empty-text="暂无有效代码文件"
                  class="facade-tree"
                  show-checkbox
                  node-key="id"
                  check-on-click-node
                >
                  <template #default="{ node, data }">
                    <span class="custom-tree-node" :class="data.isFile ? 'is-file' : 'is-folder'">
                      <el-icon class="node-icon">
                        <Document v-if="data.isFile" />
                        <Folder v-else />
                      </el-icon>
                      <span class="node-label">{{ node.label }}</span>
                    </span>
                  </template>
                </el-tree>
              </el-card>
            </el-col>

            <el-col :span="9" class="h-100">
              <div class="panel-header-with-search">
                <div class="panel-title">Facade 接口 (Interfaces)</div>
                <el-input v-model="filterText" placeholder="搜索方法、注释或类名..." size="small" clearable class="facade-search" />
              </div>
              <el-card shadow="never" class="panel-card scrollable-card">
                <el-tree
                  ref="treeRef"
                  :data="facadeTreeData"
                  :props="treeProps"
                  :filter-node-method="filterNode"
                  @node-click="handleFacadeTreeClick"
                  empty-text="未扫描到 Facade 接口"
                  class="facade-tree"
                />
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
                <el-button
                  :type="isContentTruncated ? 'info' : 'success'"
                  :icon="Document"
                  @click="handleCopy"
                >
                  复制当前视图代码
                </el-button>
                <el-button type="warning" :icon="Download" @click="handleDownload">
                  下载完整 TXT
                </el-button>
              </div>
            </div>

            <el-alert
              v-if="isContentTruncated"
              title="⚠️ 代码量过大！为防止浏览器卡死，下方仅展示预览。请放心点击上方【复制】或【下载】，获取的是 100% 完整代码。"
              type="warning"
              show-icon
              :closable="false"
              style="margin-bottom: 10px; flex-shrink: 0;"
            />

            <div class="textarea-wrapper">
              <el-input type="textarea" readonly :model-value="previewContent" class="code-font bottom-textarea" />
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

.custom-tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
}
.node-icon {
  font-size: 14px;
}
.is-folder {
  color: #79bbff;
}
.is-file {
  color: #b1b3b8;
}

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
