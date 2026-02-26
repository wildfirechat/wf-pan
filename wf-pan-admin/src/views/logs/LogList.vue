<template>
  <div class="page-container">
    <div class="page-header">
      <h2>操作日志</h2>
      <div class="header-actions">
        <el-input
          v-model="searchUserId"
          placeholder="搜索用户ID"
          style="width: 200px; margin-right: 10px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button @click="handleSearch">
          <el-icon><Search /></el-icon>
          搜索
        </el-button>
        <el-button type="danger" @click="handleClear">
          <el-icon><Delete /></el-icon>
          清空日志
        </el-button>
      </div>
    </div>
    
    <el-table :data="logList" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="userId" label="用户ID" width="150" />
      <el-table-column prop="operation" label="操作类型" width="150">
        <template #default="{ row }">
          <el-tag :type="getOperationTypeTag(row.operation)">
            {{ getOperationTypeText(row.operation) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetType" label="目标类型" width="100">
        <template #default="{ row }">
          {{ row.targetType || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="targetId" label="目标ID" width="100">
        <template #default="{ row }">
          {{ row.targetId || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="details" label="详情" min-width="200">
        <template #default="{ row }">
          <div class="details-text" :title="formatDetails(row.details)">
            {{ formatDetails(row.details) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="ipAddress" label="IP地址" width="130">
        <template #default="{ row }">
          {{ row.ipAddress || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="操作时间" width="180">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
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
      @current-change="fetchLogs"
      @size-change="handleSizeChange"
      class="pagination"
    />
    
    <!-- 清空日志确认弹窗 -->
    <el-dialog v-model="clearDialogVisible" title="清空日志" width="500px">
      <el-form :model="clearForm" label-width="120px">
        <el-form-item label="清空方式">
          <el-radio-group v-model="clearForm.type">
            <el-radio label="all">全部清空</el-radio>
            <el-radio label="before">清空指定天数前</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="天数" v-if="clearForm.type === 'before'">
          <el-input-number v-model="clearForm.days" :min="1" :max="365" />
          <span class="tip">天前的日志将被删除</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="clearDialogVisible = false">取消</el-button>
          <el-button type="danger" @click="confirmClear" :loading="clearLoading">
            确认清空
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLogs, clearAllLogs, clearLogsBefore } from '../../api/log'

const logList = ref([])
const loading = ref(false)
const searchUserId = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const clearDialogVisible = ref(false)
const clearLoading = ref(false)
const clearForm = ref({
  type: 'before',
  days: 30
})

const operationTypeMap = {
  'CREATE_FOLDER': { text: '创建文件夹', tag: 'success' },
  'UPLOAD_FILE': { text: '上传文件', tag: 'success' },
  'DELETE_FILE': { text: '删除文件', tag: 'danger' },
  'ADMIN_DELETE_FILE': { text: '管理员删除', tag: 'danger' },
  'RENAME_FILE': { text: '重命名', tag: 'warning' },
  'MOVE_FILE': { text: '移动', tag: 'warning' },
  'CHANGE_PASSWORD': { text: '修改密码', tag: 'warning' },
  'CLEAR_ALL_LOGS': { text: '清空全部日志', tag: 'danger' },
  'CLEAR_OLD_LOGS': { text: '清空旧日志', tag: 'danger' },
  'ADD_GLOBAL_ADMIN': { text: '添加全局管理员', tag: 'success' },
  'REMOVE_GLOBAL_ADMIN': { text: '移除全局管理员', tag: 'danger' }
}

const getOperationTypeText = (type) => {
  return operationTypeMap[type]?.text || type
}

const getOperationTypeTag = (type) => {
  return operationTypeMap[type]?.tag || ''
}

const formatDetails = (details) => {
  if (!details) return '-'
  try {
    const obj = JSON.parse(details)
    return Object.entries(obj)
      .map(([key, value]) => `${key}: ${value}`)
      .join(', ')
  } catch {
    return details
  }
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

const fetchLogs = async () => {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: size.value
    }
    if (searchUserId.value) {
      params.userId = searchUserId.value
    }
    
    const res = await getLogs(params)
    if (res.code === 0) {
      const pageData = res.data
      logList.value = pageData.content || pageData
      total.value = pageData.totalElements || pageData.length
    }
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (newSize) => {
  size.value = newSize
  page.value = 1
  fetchLogs()
}

const handleSearch = () => {
  page.value = 1
  fetchLogs()
}

const handleClear = () => {
  clearForm.value.type = 'before'
  clearForm.value.days = 30
  clearDialogVisible.value = true
}

const confirmClear = async () => {
  try {
    await ElMessageBox.confirm(
      clearForm.value.type === 'all' 
        ? '确定要清空所有日志吗？此操作不可恢复！'
        : `确定要清空${clearForm.value.days}天前的日志吗？此操作不可恢复！`,
      '警告',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    
    clearLoading.value = true
    let res
    if (clearForm.value.type === 'all') {
      res = await clearAllLogs()
    } else {
      res = await clearLogsBefore(clearForm.value.days)
    }
    
    if (res.code === 0) {
      ElMessage.success('日志清空成功')
      clearDialogVisible.value = false
      fetchLogs()
    }
  } catch (error) {
    if (error !== 'cancel') {
      console.error('清空日志失败:', error)
    }
  } finally {
    clearLoading.value = false
  }
}

onMounted(() => {
  fetchLogs()
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

.header-actions {
  display: flex;
  align-items: center;
}

.details-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 300px;
}

.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}

.tip {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
}
</style>
