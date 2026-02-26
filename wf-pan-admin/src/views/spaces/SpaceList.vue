<template>
  <div class="page-container">
    <div class="page-header">
      <h2>空间管理</h2>
      <el-radio-group v-model="filterType" @change="handleFilterChange">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="GLOBAL_PUBLIC">全局空间</el-radio-button>
        <el-radio-button label="USER">用户空间</el-radio-button>
        <el-radio-button label="DEPT">部门空间</el-radio-button>
      </el-radio-group>
    </div>
    
    <el-table :data="spaceList" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="spaceType" label="类型" width="150">
        <template #default="{ row }">
          <el-tag :type="getSpaceTypeTag(row.spaceType)">
            {{ getSpaceTypeText(row.spaceType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ownerId" label="所有者" width="150" />
      <el-table-column label="存储用量" width="200">
        <template #default="{ row }">
          <el-progress 
            :percentage="getUsagePercent(row)" 
            :status="getUsagePercent(row) > 90 ? 'exception' : ''"
          />
          {{ formatSize(row.usedQuota) }} / {{ formatSize(row.totalQuota) }}
        </template>
      </el-table-column>
      <el-table-column label="文件数" width="120">
        <template #default="{ row }">
          文件: {{ row.fileCount }} / 文件夹: {{ row.folderCount }}
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button type="primary" size="small" @click="viewFiles(row)">
            查看文件
          </el-button>
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
      @current-change="fetchSpaces"
      @size-change="handleSizeChange"
      class="pagination"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getSpaces } from '../../api/space'

const router = useRouter()
const spaceList = ref([])
const loading = ref(false)
const filterType = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const spaceTypeMap = {
  'GLOBAL_PUBLIC': { text: '全局公共', tag: 'success' },
  'DEPT_PUBLIC': { text: '部门公共', tag: 'primary' },
  'DEPT_PRIVATE': { text: '部门私有', tag: 'warning' },
  'USER_PUBLIC': { text: '用户公共', tag: 'info' },
  'USER_PRIVATE': { text: '用户私有', tag: '' }
}

const getSpaceTypeText = (type) => {
  return spaceTypeMap[type]?.text || type
}

const getSpaceTypeTag = (type) => {
  return spaceTypeMap[type]?.tag || ''
}

const getUsagePercent = (row) => {
  if (!row.totalQuota) return 0
  return Math.round((row.usedQuota / row.totalQuota) * 100)
}

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

const fetchSpaces = async () => {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: size.value
    }
    // USER 和 DEPT 是特殊过滤值，表示过滤用户相关或部门相关的所有空间类型
    if (filterType.value === 'USER') {
      params.type = 'USER'
    } else if (filterType.value === 'DEPT') {
      params.type = 'DEPT'
    } else if (filterType.value) {
      params.type = filterType.value
    }
    
    const res = await getSpaces(params)
    if (res.code === 0) {
      const pageData = res.data
      spaceList.value = pageData.content || pageData
      total.value = pageData.totalElements || pageData.length
    }
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (newSize) => {
  size.value = newSize
  page.value = 1
  fetchSpaces()
}

const handleFilterChange = () => {
  page.value = 1
  fetchSpaces()
}

const viewFiles = (row) => {
  router.push(`/files?spaceId=${row.id}`)
}

onMounted(() => {
  fetchSpaces()
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

.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
