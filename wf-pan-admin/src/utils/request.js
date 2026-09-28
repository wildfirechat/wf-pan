import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'

const request = axios.create({
  // 相对路径：前端资源嵌入后端，页面在哪个路径下（根路径或 NG 子路径 /pan-admin/），接口就在它下面的 api/
  baseURL: 'api',
  timeout: 10000
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 0) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  (error) => {
    // 未登录或会话过期：服务端返回 HTTP 401
    if (error.response?.status === 401) {
      useUserStore().clearUser()
      if (window.location.hash !== '#/login') {
        ElMessage.warning('登录已过期，请重新登录')
        window.location.hash = '#/login'
      }
      return Promise.reject(error)
    }
    ElMessage.error(error.response?.data?.message || error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default request
