import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login, getUserInfo } from '../api/auth'

export const useUserStore = defineStore('user', () => {
  // State
  const token = ref(localStorage.getItem('admin_token') || '')
  const username = ref('')
  
  // Getters
  const isLoggedIn = computed(() => !!token.value)
  
  // Actions
  async function loginAction(credentials) {
    const res = await login(credentials)
    if (res.code === 0) {
      token.value = res.data.sessionId
      username.value = res.data.username
      localStorage.setItem('admin_token', res.data.sessionId)
      return true
    }
    return false
  }
  
  async function fetchUserInfo() {
    try {
      const res = await getUserInfo()
      if (res.code === 0) {
        username.value = res.data.username
        return true
      }
    } catch (error) {
      return false
    }
    return false
  }
  
  function logout() {
    token.value = ''
    username.value = ''
    localStorage.removeItem('admin_token')
  }
  
  return {
    token,
    username,
    isLoggedIn,
    loginAction,
    fetchUserInfo,
    logout
  }
})
