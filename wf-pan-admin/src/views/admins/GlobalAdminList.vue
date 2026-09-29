<template>
  <div class="page-container">
    <div class="page-header">
      <h2>全局管理员</h2>
      <el-button type="primary" @click="showAddDialog">
        <el-icon><Plus /></el-icon>添加管理员
      </el-button>
    </div>
    
    <el-table :data="adminList" v-loading="loading" border>
      <el-table-column prop="userId" label="用户ID" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="createdBy" label="创建者" />
      <el-table-column prop="createdAt" label="创建时间" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            type="danger"
            size="small"
            @click="handleDelete(row)"
            :disabled="adminList.length <= 1"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    
    <!-- 添加对话框 -->
    <el-dialog v-model="dialogVisible" title="添加全局管理员" width="400px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
        <el-form-item label="用户ID" prop="userId">
          <el-input v-model="form.userId" placeholder="请输入IM用户ID" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="可选，用于显示" />
        </el-form-item>
        <el-form-item label="登录密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="该管理员登录后台使用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAdd" :loading="submitLoading">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getGlobalAdmins, addGlobalAdmin, removeGlobalAdmin } from '../../api/globalAdmin'

const adminList = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitLoading = ref(false)
const formRef = ref()

const form = ref({
  userId: '',
  username: '',
  password: ''
})

const rules = {
  userId: [{ required: true, message: '请输入用户ID', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入登录密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需为8-64位', trigger: 'blur' }
  ]
}

const fetchAdmins = async () => {
  loading.value = true
  try {
    const res = await getGlobalAdmins()
    if (res.code === 0) {
      adminList.value = res.data
    }
  } finally {
    loading.value = false
  }
}

const showAddDialog = () => {
  form.value = { userId: '', username: '', password: '' }
  dialogVisible.value = true
}

const handleAdd = async () => {
  try {
    await formRef.value.validate()
    submitLoading.value = true
    
    const res = await addGlobalAdmin(form.value)
    if (res.code === 0) {
      ElMessage.success('添加成功')
      dialogVisible.value = false
      fetchAdmins()
    }
  } finally {
    submitLoading.value = false
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确定要删除管理员 "${row.userId}" 吗？`, '提示', {
      type: 'warning'
    })
    
    const res = await removeGlobalAdmin(row.userId)
    if (res.code === 0) {
      ElMessage.success('删除成功')
      fetchAdmins()
    }
  } catch {
    // 取消
  }
}

onMounted(() => {
  fetchAdmins()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}
</style>
