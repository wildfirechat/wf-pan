<template>
  <el-dialog
    v-model="visible"
    title="用户信息"
    width="500px"
    :close-on-click-modal="true"
  >
    <div v-if="userInfo" class="user-info-container">
      <!-- 头像区域 -->
      <div class="avatar-container">
        <el-avatar 
          :size="80" 
          :src="userInfo.portrait" 
          :icon="UserFilled"
          fit="cover"
        />
      </div>
      
      <el-form :model="userInfo" label-width="100px">
        <el-form-item label="用户ID">
          <el-input v-model="userInfo.userId" disabled />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="userInfo.name" disabled />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="userInfo.displayName" disabled />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="userInfo.mobile" disabled />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userInfo.email" disabled />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="userInfo.address" disabled />
        </el-form-item>
        <el-form-item label="公司">
          <el-input v-model="userInfo.company" disabled />
        </el-form-item>
      </el-form>
    </div>
    <el-empty v-else description="加载中..." />
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="visible = false">关闭</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { UserFilled } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  userInfo: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:modelValue'])

const visible = ref(false)

watch(() => props.modelValue, (val) => {
  visible.value = val
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})
</script>

<style scoped>
.user-info-container {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.avatar-container {
  margin-bottom: 20px;
}

:deep(.el-form) {
  width: 100%;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
}
</style>
