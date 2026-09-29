<template>
  <div class="page-container">
    <div class="page-header">
      <h2>文件管理</h2>
      <div class="search-bar">
        <el-input
          v-model="keyword"
          placeholder="搜索文件名"
          style="width: 300px"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #append>
            <el-button @click="handleSearch">
              <el-icon><Search /></el-icon>
            </el-button>
          </template>
        </el-input>
      </div>
    </div>
    
    <!-- 面包屑导航 -->
    <el-breadcrumb separator="/" v-if="showBreadcrumb" class="breadcrumb">
      <!-- 全部文件（根入口） -->
      <el-breadcrumb-item @click="goToAllFiles()" class="breadcrumb-item">
        全部文件
      </el-breadcrumb-item>
      <!-- 当前空间名称（如果从空间管理进入） -->
      <el-breadcrumb-item 
        v-if="currentSpaceId && currentSpaceName" 
        @click="goToSpaceRoot()" 
        class="breadcrumb-item"
      >
        {{ currentSpaceName }}
      </el-breadcrumb-item>
      <!-- 文件夹路径 -->
      <el-breadcrumb-item 
        v-for="item in breadcrumbs" 
        :key="item.id"
        @click="goToFolder(item)"
        class="breadcrumb-item"
      >
        {{ item.name }}
      </el-breadcrumb-item>
    </el-breadcrumb>
    
    <el-table :data="fileList" v-loading="loading" border>
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <div class="file-name" @click="handleFileClick(row)">
            <el-icon v-if="row.type === 'FOLDER'" class="folder-icon">
              <Folder />
            </el-icon>
            <el-icon v-else class="file-icon">
              <Document />
            </el-icon>
            <span class="file-name-text">{{ row.name }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.type === 'FOLDER' ? 'warning' : ''">
            {{ row.type === 'FOLDER' ? '文件夹' : '文件' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ row.type === 'FOLDER' ? '-' : formatSize(row.size) }}
        </template>
      </el-table-column>
      <el-table-column label="创建者" width="150">
        <template #default="{ row }">
          <el-link type="primary" @click.stop="showUserInfo(row.creatorId)">
            {{ row.creatorName }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <template v-if="row.type === 'FOLDER'">
            <el-button type="primary" size="small" @click.stop="goToFolder(row)">
              进入
            </el-button>
            <el-button type="danger" size="small" @click.stop="handleDelete(row)">
              删除
            </el-button>
          </template>
          <template v-else>
            <el-button type="primary" size="small" @click.stop="handleDownload(row)">
              下载
            </el-button>
            <el-button type="danger" size="small" @click.stop="handleDelete(row)">
              删除
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    
    <!-- 分页 -->
    <el-pagination
      v-if="total > 0"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="fetchFiles"
      @size-change="handleSizeChange"
      class="pagination"
    />
    
    <!-- 用户详情弹窗 -->
    <UserInfoDialog v-model="userInfoVisible" :user-info="currentUserInfo" />
  </div>
</template>

<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSpaceFiles, getSpace } from '../../api/space'
import { getAllFiles, searchFiles, deleteFile, getFileDownloadUrl } from '../../api/file'
import { getUserInfo } from '../../api/user'
import UserInfoDialog from '../../components/UserInfoDialog.vue'

const route = useRoute()

const fileList = ref([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

// 当前浏览状态
const currentSpaceId = ref(null)
const currentSpaceName = ref('')  // 当前空间名称
const currentParentId = ref(null)
const breadcrumbs = ref([])

const userInfoVisible = ref(false)
const currentUserInfo = ref(null)

// 是否显示面包屑
const showBreadcrumb = computed(() => {
  return breadcrumbs.value.length > 0 || currentSpaceId.value
})

const formatSize = (bytes) => {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  while (bytes >= 1024 && i < units.length - 1) {
    bytes /= 1024
    i++
  }
  return bytes.toFixed(2) + ' ' + units[i]
}

const formatDateTime = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 加载空间信息
const loadSpaceInfo = async (spaceId) => {
  if (!spaceId) {
    currentSpaceName.value = ''
    return
  }
  try {
    const res = await getSpace(spaceId)
    currentSpaceName.value = res.data.name
  } catch (error) {
    console.error('加载空间信息失败:', error)
  }
}

const fetchFiles = async () => {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: size.value
    }
    
    let res
    if (keyword.value) {
      // 搜索模式 - 搜索所有文件
      params.keyword = keyword.value
      res = await searchFiles(params)
    } else if (currentSpaceId.value) {
      // 在特定空间内浏览
      params.parentId = currentParentId.value
      res = await getSpaceFiles(currentSpaceId.value, params)
    } else {
      // 查看所有文件（根目录）
      res = await getAllFiles(params)
    }
    
    if (res.code === 0) {
      const pageData = res.data
      fileList.value = pageData.content || pageData
      total.value = pageData.totalElements || pageData.length
    }
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (newSize) => {
  size.value = newSize
  page.value = 1
  fetchFiles()
}

const handleSearch = () => {
  page.value = 1
  // 搜索时重置空间浏览状态
  if (keyword.value) {
    currentSpaceId.value = null
    currentSpaceName.value = ''
    currentParentId.value = null
    breadcrumbs.value = []
  }
  fetchFiles()
}

// 返回到全部文件视图
const goToAllFiles = () => {
  currentSpaceId.value = null
  currentSpaceName.value = ''
  currentParentId.value = null
  breadcrumbs.value = []
  page.value = 1
  fetchFiles()
}

// 返回到当前空间的根目录
const goToSpaceRoot = () => {
  currentParentId.value = null
  breadcrumbs.value = []
  page.value = 1
  fetchFiles()
}

const goToFolder = (folder) => {
  if (folder === null) {
    // 返回空间根目录（仅清空文件夹面包屑，保留空间）
    currentParentId.value = null
    breadcrumbs.value = []
  } else {
    // 进入文件夹
    currentSpaceId.value = folder.spaceId
    currentParentId.value = folder.id
    
    // 找到该文件夹在面包屑中的位置，截断后面的
    const index = breadcrumbs.value.findIndex(b => b.id === folder.id)
    if (index === -1) {
      breadcrumbs.value.push({
        id: folder.id,
        name: folder.name,
        spaceId: folder.spaceId
      })
    } else {
      breadcrumbs.value = breadcrumbs.value.slice(0, index + 1)
    }
  }
  page.value = 1
  fetchFiles()
}

const handleFileClick = (row) => {
  if (row.type === 'FOLDER') {
    goToFolder(row)
  } else {
    handleDownload(row)
  }
}

const handleDownload = async (row) => {
  try {
    const res = await getFileDownloadUrl(row.id)
    // 只打开 http(s) 地址，防止 javascript: 等链接在后台页面中执行
    if (/^https?:\/\//i.test(res.data || '')) {
      window.open(res.data, '_blank', 'noopener')
    } else {
      ElMessage.error('获取下载链接失败')
    }
  } catch (error) {
    ElMessage.error('下载失败')
  }
}

const showUserInfo = async (userId) => {
  if (!userId) {
    ElMessage.warning('用户ID为空')
    return
  }
  
  currentUserInfo.value = null
  userInfoVisible.value = true
  
  try {
    const res = await getUserInfo(userId)
    if (res.code === 0) {
      currentUserInfo.value = res.data
    } else {
      ElMessage.error('获取用户信息失败')
    }
  } catch (error) {
    ElMessage.error('获取用户信息失败')
  }
}

const handleDelete = async (row) => {
  try {
    const message = row.type === 'FOLDER' 
      ? `确定要删除文件夹 "${row.name}" 吗？（必须先清空内部文件）`
      : `确定要删除文件 "${row.name}" 吗？`
    
    await ElMessageBox.confirm(message, '提示', {
      type: 'warning'
    })
    
    const res = await deleteFile(row.id)
    if (res.code === 0) {
      ElMessage.success('删除成功')
      fetchFiles()
    }
  } catch {
    // 取消
  }
}

// 从 URL 参数初始化
watch(() => route.query.spaceId, async (newVal) => {
  if (newVal) {
    const spaceId = parseInt(newVal)
    currentSpaceId.value = spaceId
    currentParentId.value = null
    breadcrumbs.value = []
    // 加载空间名称
    await loadSpaceInfo(spaceId)
    fetchFiles()
  }
})

onMounted(async () => {
  if (route.query.spaceId) {
    const spaceId = parseInt(route.query.spaceId)
    currentSpaceId.value = spaceId
    // 加载空间名称
    await loadSpaceInfo(spaceId)
  }
  fetchFiles()
})
</script>

<style scoped>
.page-container {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}

.search-bar {
  display: flex;
  align-items: center;
}

.breadcrumb {
  margin-bottom: 20px;
}

.breadcrumb-item {
  cursor: pointer;
  color: #409EFF;
}

.file-name {
  display: flex;
  align-items: center;
  cursor: pointer;
}

.file-name:hover .file-name-text {
  color: #409EFF;
  text-decoration: underline;
}

.file-name-text {
  transition: color 0.2s;
}

.folder-icon {
  color: #E6A23C;
  margin-right: 8px;
  font-size: 18px;
}

.file-icon {
  color: #909399;
  margin-right: 8px;
  font-size: 18px;
}

.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
