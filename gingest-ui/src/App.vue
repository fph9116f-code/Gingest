<script setup lang="ts">
import { ref } from 'vue'
import { Document, Download } from '@element-plus/icons-vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

// --- 1. 定义后端返回的数据结构接口 (完美对应后端的 GingestResponse DTO) ---
interface GingestResponse {
  projectName: string
  fileCount: number
  estimatedTokens: number
  formattedSize: string
  directoryTree: string
  content: string
}

// --- 2. 响应式状态 (加上泛型约束) ---
const searchInput = ref<string>('')
const loading = ref<boolean>(false)
// 告诉 TS：这个值要么是 null，要么是咱们定义的 GingestResponse 对象
const resultData = ref<GingestResponse | null>(null)

// --- 核心方法：调用后端提取代码 ---
const handleIngest = async () => {
  if (!searchInput.value) {
    ElMessage.warning('请输入 GitLab 项目地址或 ID')
    return
  }

  loading.value = true
  try {
    // 告诉 axios：返回的 data 类型是 GingestResponse
    const response = await axios.get<GingestResponse>('/api/ingest', {
      params: { projectId: searchInput.value }
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
  window.open(`/api/ingest/download?projectId=${encodeURIComponent(searchInput.value)}`, '_blank')
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
