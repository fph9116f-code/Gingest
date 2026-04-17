<script setup lang="ts">
import { ref, watch, computed, markRaw, nextTick } from 'vue'
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
  path?: string
  parentClass?: string
}

interface HelperNode {
  label: string
  isFile: boolean
  fullPath?: string
  content?: string
  childMap: Record<string, HelperNode>
  children?: HelperNode[]
}

const fetchMode = ref<'gitlab' | 'local'>('gitlab')
const localPathInput = ref<string>('')

const searchInput = ref<string>('')
const loading = ref<boolean>(false)
const resultData = ref<GingestResponse | null>(null)

// UI 渲染的终极防线：无论内存里存了多大的数据，界面上的 textarea 最多只渲染 10 万个字符
const MAX_DISPLAY_LENGTH = 100000
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

// 【修复】：为目录树搜索加入 300ms 防抖，彻底告别卡顿
let dirSearchTimeout: ReturnType<typeof setTimeout> | null = null
watch(filterDirText, (val) => {
  if (dirSearchTimeout) clearTimeout(dirSearchTimeout)
  dirSearchTimeout = setTimeout(() => {
    dirTreeRef.value?.filter(val)
  }, 300)
})

// 【修复】：为 Facade 接口树搜索加入 300ms 防抖
let facadeSearchTimeout: ReturnType<typeof setTimeout> | null = null
watch(filterText, (val) => {
  if (facadeSearchTimeout) clearTimeout(facadeSearchTimeout)
  facadeSearchTimeout = setTimeout(() => {
    treeRef.value?.filter(val)
  }, 300)
})

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

// ==========================================
// 【黑科技核心】：双引擎本地文件读取系统 (纯净 TS 版)
// ==========================================
const IGNORE_EXTENSIONS = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.ico', '.pdf', '.zip', '.tar', '.gz',
  '.jar', '.class', '.exe', '.xml', '.node', '.dll', '.so', '.dylib',
  '.woff', '.woff2', '.ttf', '.eot', '.mp4', '.mp3', '.svg', '.properties',
  '.cmd', '.gitignore', '.config', '.iml',
  '.map', '.sql', '.bak', '.log', '.out', '.min.js', '.min.css'
])
const IGNORE_DIRECTORIES = new Set(['node_modules', '.git', 'target', '.idea', 'build', 'dist'])
const IGNORE_FILE_NAMES = new Set(['package-lock.json', 'yarn.lock', 'pnpm-lock.yaml'])
const MAX_FILE_COUNT = 3000
const MAX_TOTAL_SIZE = 50 * 1024 * 1024 // 50MB 内存保护阈值

const buildLocalTree = (paths: string[], contentMap: Record<string, string>): TreeNode[] => {
  if (!paths || paths.length === 0) return []

  const root: HelperNode = { label: 'root', isFile: false, childMap: {} }

  for (const path of paths) {
    const parts = path.split('/')
    let current = root
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i]!
      if (!current.childMap[part]) {
        current.childMap[part] = { label: part, isFile: false, childMap: {} }
      }
      current = current.childMap[part]
      if (i === parts.length - 1) {
        current.isFile = true
        current.fullPath = path
        current.content = contentMap[path]
      }
    }
  }

  const compressTree = (node: HelperNode) => {
    const children = Object.values(node.childMap)
    for (const child of children) compressTree(child)

    if (node.label !== 'root' && !node.isFile && Object.keys(node.childMap).length === 1) {
      const singleChild = Object.values(node.childMap)[0]!
      node.label = node.label + '/' + singleChild.label
      node.childMap = singleChild.childMap
      node.isFile = singleChild.isFile
      node.fullPath = singleChild.fullPath
      node.content = singleChild.content
    }
  }

  const convertMapToList = (node: HelperNode): TreeNode => {
    const childrenList = Object.values(node.childMap).map(convertMapToList)
    return {
      label: node.label,
      isFile: node.isFile,
      fullPath: node.fullPath,
      content: node.content,
      children: childrenList.length > 0 ? childrenList : undefined
    }
  }

  compressTree(root)
  return Object.values(root.childMap).map(convertMapToList)
}

const processLocalDirectoryModern = async (): Promise<GingestResponse> => {
  const dirHandle = await (window as any).showDirectoryPicker()
  localPathInput.value = dirHandle.name

  // 【核心修复 1】：用户在系统弹窗点击"允许"后，立即开启加载状态
  loading.value = true
  // 【核心修复 2】：利用 Promise 强行休眠 50 毫秒，把主线程让给浏览器的渲染引擎，确保 Loading 动画成功画在屏幕上！
  await new Promise(resolve => setTimeout(resolve, 50))

  let fileCount = 0; let totalTextLength = 0; let byteSize = 0;
  const processedFiles: string[] = []; const fileContents: Record<string, string> = {}

  const traverse = async (handle: any, currentPath: string) => {
    for await (const entry of handle.values()) {
      if (entry.kind === 'directory') {
        if (entry.name.startsWith('.') || IGNORE_DIRECTORIES.has(entry.name.toLowerCase())) continue
        await traverse(entry, currentPath + entry.name + '/')
      } else if (entry.kind === 'file') {
        const fileName = entry.name; const lowerName = fileName.toLowerCase()
        if (IGNORE_FILE_NAMES.has(lowerName)) continue
        let isIgnoredExt = false
        for (const ext of IGNORE_EXTENSIONS) { if (lowerName.endsWith(ext)) { isIgnoredExt = true; break } }
        if (isIgnoredExt) continue

        if (fileCount >= MAX_FILE_COUNT) throw new Error(`【安全熔断】该目录过大！代码文件已超过 ${MAX_FILE_COUNT} 个。`)
        if (byteSize >= MAX_TOTAL_SIZE) throw new Error(`【安全熔断】该目录过大！源码体积已超过 50MB。`)

        const file = await entry.getFile()
        const relativePath = currentPath + fileName
        processedFiles.push(relativePath)

        const content = await file.text()

        fileContents[relativePath] = content
        totalTextLength += content.length; byteSize += file.size; fileCount++
      }
    }
  }

  await traverse(dirHandle, '')
  return {
    projectName: 'Local: ' + dirHandle.name,
    fileCount, estimatedTokens: Math.floor(totalTextLength / 4),
    formattedSize: formatSize(byteSize),
    directoryTree: buildLocalTree(processedFiles, fileContents), content: ''
  }
}

const processLocalDirectoryLegacy = (): Promise<GingestResponse> => {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    ;(input as any).webkitdirectory = true
    input.multiple = true
    input.style.display = 'none'

    input.onchange = async (e: Event) => {
      const target = e.target as HTMLInputElement
      const files = target.files
      if (!files || files.length === 0) return reject(new Error('AbortError'))

      if (files.length > 4000) {
        return reject(new Error(`【拦截】包含文件过多 (${files.length} 个)！为防止卡死，请直接选中 src 目录，不要选包含 node_modules 的根目录。`))
      }

      loading.value = true
      try {
        let fileCount = 0; let totalTextLength = 0; let byteSize = 0;
        const processedFiles: string[] = []; const fileContents: Record<string, string> = {}

        const firstFile = files[0]! as any
        const baseDirName = firstFile.webkitRelativePath ? firstFile.webkitRelativePath.split('/')[0] : 'Local_Project'
        localPathInput.value = baseDirName

        for (let i = 0; i < files.length; i++) {
          const file = files[i]!
          const webkitRelativePath = (file as any).webkitRelativePath || file.name
          const pathParts = webkitRelativePath.split('/')

          let isIgnoredDir = false
          for (const part of pathParts.slice(0, -1)) {
            if (part.startsWith('.') || IGNORE_DIRECTORIES.has(part.toLowerCase())) {
              isIgnoredDir = true; break
            }
          }
          if (isIgnoredDir) continue

          const fileName = file.name; const lowerName = fileName.toLowerCase()
          if (IGNORE_FILE_NAMES.has(lowerName)) continue
          let isIgnoredExt = false
          for (const ext of IGNORE_EXTENSIONS) { if (lowerName.endsWith(ext)) { isIgnoredExt = true; break } }
          if (isIgnoredExt) continue

          if (fileCount >= MAX_FILE_COUNT) throw new Error(`【安全熔断】该目录过大！代码文件已超过 ${MAX_FILE_COUNT} 个。`)
          if (byteSize >= MAX_TOTAL_SIZE) throw new Error(`【安全熔断】该目录过大！源码体积已超过 50MB。`)

          const relativePath = pathParts.slice(1).join('/')
          if (!relativePath) continue

          processedFiles.push(relativePath)

          const content = await file.text()

          fileContents[relativePath] = content
          totalTextLength += content.length; byteSize += file.size; fileCount++
        }

        resolve({
          projectName: 'Local: ' + baseDirName,
          fileCount, estimatedTokens: Math.floor(totalTextLength / 4),
          formattedSize: formatSize(byteSize),
          directoryTree: buildLocalTree(processedFiles, fileContents), content: ''
        })
      } catch (err) {
        reject(err)
      }
    }

    document.body.appendChild(input)
    input.click()
    document.body.removeChild(input)
  })
}


// ==========================================
// 主提交流程 (XML 格式组装)
// ==========================================
const handleIngest = async () => {
  if (fetchMode.value === 'gitlab' && !searchInput.value) return ElMessage.warning('请选择项目')

  if (fetchMode.value === 'gitlab') {
    loading.value = true
  }

  facadeTreeData.value = []
  filterText.value = ''
  filterDirText.value = ''
  currentViewTitle.value = '全部提取结果 (All Files)'
  resultData.value = null

  try {
    let ingestRes: { data: GingestResponse } = { data: {} as GingestResponse }
    let facadeRes: { data: FacadeInfo[] } = { data: [] }

    if (fetchMode.value === 'gitlab') {
      const axiosConfig = { timeout: 120000 }
      const [res1, res2] = await Promise.all([
        axios.get<GingestResponse>('/api/ingest', { params: { projectId: searchInput.value, branch: selectedBranch.value }, ...axiosConfig }),
        axios.get<FacadeInfo[]>('/api/ingest/facades', { params: { projectId: searchInput.value, branch: selectedBranch.value }, ...axiosConfig }).catch(() => ({ data: [] as FacadeInfo[] }))
      ])
      ingestRes = res1
      facadeRes = res2
    } else {
      try {
        if ('showDirectoryPicker' in window) {
          const localData = await processLocalDirectoryModern()
          ingestRes.data = localData
        } else {
          const localData = await processLocalDirectoryLegacy()
          ingestRes.data = localData
        }
      } catch (err: any) {
        if (err.name === 'AbortError' || err.message === 'AbortError') {
          loading.value = false
          return
        }
        throw err
      }
    }

    let idCounter = 1
    const assignIds = (nodes: TreeNode[]) => {
      nodes.forEach(node => {
        node.id = idCounter++
        if (node.children) assignIds(node.children)
      })
    }
    assignIds(ingestRes.data.directoryTree)

    const summaryXml = `<project_summary>\nProject: ${ingestRes.data.projectName}\nTotal Files: ${ingestRes.data.fileCount}\nEstimated Tokens: ${ingestRes.data.estimatedTokens}\n</project_summary>\n\n`
    const treeXml = `<directory_tree>\n.\n${generateTreeText(ingestRes.data.directoryTree)}</directory_tree>\n`
    const fullTreeText = summaryXml + treeXml

    let contentArray: string[] = []
    const gatherAll = (nodes: TreeNode[]) => {
      nodes.forEach(n => {
        if (n.isFile) {
          contentArray.push(`<file path="${n.fullPath || n.label}">\n${n.content || ''}\n</file>\n\n`)
        } else if (n.children) {
          gatherAll(n.children)
        }
      })
    }
    gatherAll(ingestRes.data.directoryTree)

    const finalFullContent = fullTreeText + "\n<files>\n" + contentArray.join('') + "</files>"

    if (ingestRes.data.estimatedTokens > 500000) {
      const warningText = `${fullTreeText}\n` +
        `【⚠️ 系统保护机制：当前仓库极其庞大 (${ingestRes.data.estimatedTokens} Tokens)】\n` +
        `为防止浏览器内存崩溃，已自动关闭全库代码的合并预览。\n\n` +
        `👉 您的操作指南：\n` +
        `1. 请在左侧【目录结构】中，精准勾选您本次需要分析的核心业务文件。\n` +
        `2. 勾选完成后，点击右上角的【组装勾选】按钮。\n` +
        `3. 您依然可以直接点击右上角【下载完整 TXT】获取真正的全库代码！\n`
      ingestRes.data.content = warningText
      ingestRes.data.fullContent = finalFullContent
    } else {
      ingestRes.data.content = finalFullContent
      ingestRes.data.fullContent = finalFullContent
    }

    ingestRes.data.fullFileCount = ingestRes.data.fileCount
    ingestRes.data.fullEstimatedTokens = ingestRes.data.estimatedTokens
    ingestRes.data.fullFormattedSize = ingestRes.data.formattedSize

    resultData.value = markRaw(ingestRes.data)

    facadeTreeData.value = markRaw(facadeRes.data.map((item: FacadeInfo) => ({
      label: item.className,
      path: item.path,
      isFile: false,
      children: item.methods.map(method => ({ label: method, path: item.path, parentClass: item.className, isFile: true }))
    })))

    ElMessage.success(`提取成功！共 ${ingestRes.data.fileCount} 个文件`)
  } catch (error: any) {
    let errorMsg = '提取失败，请检查路径或网络连接'
    if (error.response?.data?.message || error.response?.data?.error) {
      errorMsg = error.response.data.message || error.response.data.error
    } else if (error.message) {
      errorMsg = error.message
    }
    ElMessage.error({ message: errorMsg, duration: 6000, showClose: true })
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

// ==========================================
// 勾选合并 (XML 格式组装)
// ==========================================
const handleAssembleSelected = () => {
  if (!resultData.value || !dirTreeRef.value) return

  const checkedNodes = dirTreeRef.value.getCheckedNodes() as TreeNode[]
  const selectedFiles = checkedNodes.filter(node => node.isFile)

  if (selectedFiles.length === 0) {
    ElMessage.warning('请先在目录树中勾选需要包含的文件或文件夹')
    return
  }

  const summaryXml = `<project_summary>\nProject: ${resultData.value.projectName}\nExport Type: Selected Files (${selectedFiles.length} files)\n</project_summary>\n\n`
  const treeXml = `<directory_tree>\n.\n${generateTreeText(resultData.value.directoryTree)}</directory_tree>\n`

  let contentText = "\n<files>\n"
  selectedFiles.forEach(file => {
    contentText += `<file path="${file.fullPath || file.label}">\n${file.content || ''}\n</file>\n\n`
  })
  contentText += "</files>"

  const finalString = summaryXml + treeXml + contentText

  resultData.value = markRaw({
    ...resultData.value,
    content: finalString,
    fileCount: selectedFiles.length,
    estimatedTokens: Math.floor(finalString.length / 4),
    formattedSize: formatSize(new Blob([finalString]).size)
  } as GingestResponse)

  currentViewTitle.value = `组装完毕: 完整大纲 + ${selectedFiles.length} 个文件代码`
  ElMessage.success(`成功组装！共抽取了 ${selectedFiles.length} 个核心文件`)
}

const findNodeIdByPath = (nodes: TreeNode[], targetPath: string): number | null => {
  for (const n of nodes) {
    if (n.isFile && n.fullPath === targetPath) return n.id || null
    if (n.children && n.children.length > 0) {
      const found = findNodeIdByPath(n.children, targetPath)
      if (found !== null) return found
    }
  }
  return null
}

// ==========================================
// Facade 点击联动 (不渲染代码，仅打钩并定位)
// ==========================================
const handleFacadeTreeClick = (data: any) => {
  const node = data as TreeNode;
  if (!resultData.value || !node.path || !dirTreeRef.value) return

  const targetId = findNodeIdByPath(resultData.value.directoryTree, node.path)

  if (targetId !== null) {
    // 1. 在中间的目录树中打钩（保留已选的其他项）
    dirTreeRef.value.setChecked(targetId, true, false)

    const treeNode = dirTreeRef.value.getNode(targetId)
    if (treeNode) {
      // 2. 逐级向上展开父文件夹
      let parent = treeNode.parent
      while (parent && parent.level > 0) {
        parent.expanded = true
        parent = parent.parent
      }

      // 3. 高亮背景色
      dirTreeRef.value.setCurrentKey(targetId)

      // 4. 精准平滑滚动（加入 nextTick 避免找不到渲染中的 DOM）
      nextTick(() => {
        setTimeout(() => {
          // 只在中间目录树内部搜索高亮元素，绝对不会误搜到右侧！
          const treeContainer = (dirTreeRef.value as any).$el
          if (treeContainer) {
            const el = treeContainer.querySelector('.el-tree-node.is-current')
            if (el) {
              el.scrollIntoView({ behavior: 'smooth', block: 'center' })
            }
          }
        }, 150) // 延迟确保 Element Plus 的折叠展开动画播放完毕
      })
    }

    const displayName = node.parentClass ? node.parentClass : node.label
    ElMessage.success(`已定位并勾选: ${displayName} (请挑选完毕后点击"组装勾选")`)
  } else {
    ElMessage.warning('未在目录树中找到该文件 (可能已被过滤或未提取)')
  }
}

const resetView = () => {
  if (resultData.value && resultData.value.fullContent) {

    let displayContent = resultData.value.fullContent
    if (resultData.value.fullEstimatedTokens! > 500000) {
      const summaryXml = `<project_summary>\nProject: ${resultData.value.projectName}\nTotal Files: ${resultData.value.fullFileCount}\nEstimated Tokens: ${resultData.value.fullEstimatedTokens}\n</project_summary>\n\n`
      const treeXml = `<directory_tree>\n.\n${generateTreeText(resultData.value.directoryTree)}</directory_tree>\n`
      displayContent = `${summaryXml}${treeXml}\n` +
        `【⚠️ 系统保护机制：当前仓库极其庞大 (${resultData.value.fullEstimatedTokens} Tokens)】\n` +
        `为防止浏览器内存崩溃，已自动关闭全库代码的合并预览。\n\n` +
        `👉 您的操作指南：\n` +
        `1. 请在左侧【目录结构】中，精准勾选您本次需要分析的核心业务文件。\n` +
        `2. 勾选完成后，点击右上角的【组装勾选】按钮。\n` +
        `3. 您依然可以直接点击右上角【下载完整 TXT】获取真正的全库代码！\n`
    }

    resultData.value = markRaw({
      ...resultData.value,
      content: displayContent,
      fileCount: resultData.value.fullFileCount!,
      estimatedTokens: resultData.value.fullEstimatedTokens!,
      formattedSize: resultData.value.fullFormattedSize!
    } as GingestResponse)

    currentViewTitle.value = '全部提取结果 (All Files)'
    if (dirTreeRef.value) {
      dirTreeRef.value.setCheckedKeys([])
    }
  }
}

const handleDownload = () => {
  if (!resultData.value) return
  let downloadContent = ''

  const checkedNodes = dirTreeRef.value ? (dirTreeRef.value.getCheckedNodes() as TreeNode[]).filter(n => n.isFile) : []

  if (checkedNodes.length > 0) {
    const summaryXml = `<project_summary>\nProject: ${resultData.value.projectName}\nExport Type: Selected Files (${checkedNodes.length} files)\n</project_summary>\n\n`
    const treeXml = `<directory_tree>\n.\n${generateTreeText(resultData.value.directoryTree)}</directory_tree>\n`

    let contentText = "\n<files>\n"
    checkedNodes.forEach(file => {
      contentText += `<file path="${file.fullPath || file.label}">\n${file.content || ''}\n</file>\n\n`
    })
    contentText += "</files>"

    downloadContent = summaryXml + treeXml + contentText

    ElMessage.success(`正在下载选中的 ${checkedNodes.length} 个核心文件...`)
  } else {
    downloadContent = resultData.value.fullContent || ''
    ElMessage.success('正在下载全库完整代码...')
  }

  const blob = new Blob([downloadContent], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url

  const baseName = fetchMode.value === 'gitlab' ? searchInput.value : (localPathInput.value || 'local_project')
  const safeProjectName = baseName.replace(/[\\/:*?"<>|]/g, '_')

  link.download = `${safeProjectName}_gingest${checkedNodes.length > 0 ? '_selected' : '_full'}.txt`

  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const handleCopy = async () => {
  if (!resultData.value || !resultData.value.content) return

  if (isContentTruncated.value) {
    ElMessage({
      message: '内容过大！为确保浏览器稳定和数据完整性，暂不允许直接复制。请点击右侧【下载完整 TXT】获取全量内容。',
      type: 'error',
      duration: 5000,
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

          <el-radio-group v-model="fetchMode" size="default" class="mode-switch">
            <el-radio-button label="gitlab">GitLab</el-radio-button>
            <el-radio-button label="local">本地磁盘</el-radio-button>
          </el-radio-group>

          <template v-if="fetchMode === 'gitlab'">
            <el-select v-model="searchInput" placeholder="搜索或选择项目" class="project-select" filterable allow-create clearable :loading="loadingProjects" @visible-change="handleFetchProjects" @change="handleFetchBranches">
              <el-option v-for="proj in projectList" :key="proj" :label="proj" :value="proj" />
            </el-select>
            <el-button :icon="Connection" :loading="loadingBranches" @click="handleFetchBranches" title="手动刷新分支">获取分支</el-button>
            <el-select v-model="selectedBranch" placeholder="请选择分支" class="branch-select" :disabled="branchList.length === 0" filterable>
              <el-option v-for="branch in branchList" :key="branch" :label="branch" :value="branch" />
            </el-select>
          </template>

          <template v-else>
            <div style="display: flex; align-items: center; color: #a8abb2; font-size: 14px; margin-right: 15px;">
              <el-icon style="margin-right: 5px;"><Folder /></el-icon>
              点击右侧【开始提取】，浏览器将安全地直读您的本地目录
            </div>
          </template>

          <el-button type="primary" :loading="loading" @click="handleIngest">开始提取</el-button>
        </div>
      </el-header>

      <el-main class="main-content" v-loading="loading" :element-loading-text="fetchMode === 'gitlab' ? '正在狂奔向 GitLab 拉取代码...' : '正在极速处理本地文件...' ">
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
                  :empty-text="fetchMode === 'local' ? '本地模式暂不解析 Facade 接口' : '未扫描到 Facade 接口'"
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
            <div class="empty-text">请在上方配置提取源 -> 开始提取...</div>
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

/* 控件宽度调整 */
.mode-switch { margin-right: 8px; }
.project-select { width: 350px; }
.branch-select { width: 140px; }
.local-input { width: 500px; }

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
