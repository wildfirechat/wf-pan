import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login, logout as logoutApi, getUserInfo } from '../api/auth'

const STORAGE_KEY = 'admin_user'

export const useUserStore = defineStore('user', () => {
  // 登录态由服务端会话（HttpOnly cookie）决定，这里只记住用户名用于界面显示
  const username = ref(localStorage.getItem(STORAGE_KEY) || '')
  const isLoggedIn = computed(() => !!username.value)
  // 页面加载后是否已向服务端确认过会话有效
  let verified = false

  function setUser(name) {
    username.value = name
    verified = true
    localStorage.setItem(STORAGE_KEY, name)
  }

  function clearUser() {
    username.value = ''
    verified = false
    localStorage.removeItem(STORAGE_KEY)
  }

  async function loginAction(credentials) {
    const res = await login(credentials)
    setUser(res.data.username)
    return true
  }

  async function ensureSession() {
    if (verified) {
      return true
    }
    try {
      const res = await getUserInfo()
      setUser(res.data.username)
      return true
    } catch {
      clearUser()
      return false
    }
  }

  async function logout() {
    try {
      await logoutApi()
    } catch {
      // 会话可能已经失效，忽略
    } finally {
      clearUser()
    }
  }

  return {
    username,
    isLoggedIn,
    loginAction,
    ensureSession,
    logout,
    clearUser
  }
})
