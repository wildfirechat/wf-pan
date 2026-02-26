import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/Login.vue')
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('../components/MainLayout.vue'),
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/dashboard/Dashboard.vue'),
        meta: { title: '仪表盘' }
      },
      {
        path: 'global-admins',
        name: 'GlobalAdmins',
        component: () => import('../views/admins/GlobalAdminList.vue'),
        meta: { title: '全局管理员' }
      },
      {
        path: 'spaces',
        name: 'Spaces',
        component: () => import('../views/spaces/SpaceList.vue'),
        meta: { title: '空间管理' }
      },
      {
        path: 'files',
        name: 'Files',
        component: () => import('../views/files/FileList.vue'),
        meta: { title: '文件管理' }
      },
      {
        path: 'change-password',
        name: 'ChangePassword',
        component: () => import('../views/user/ChangePassword.vue'),
        meta: { title: '修改密码' }
      },
      {
        path: 'logs',
        name: 'Logs',
        component: () => import('../views/logs/LogList.vue'),
        meta: { title: '操作日志' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory('/'),
  routes
})

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()
  
  // 未登录时可以访问的页面
  if (to.path === '/login' || to.path === '/') {
    next()
    return
  }
  
  if (!userStore.isLoggedIn) {
    next('/')
    return
  }
  
  // 验证 session 是否有效
  const valid = await userStore.fetchUserInfo()
  if (!valid) {
    userStore.logout()
    next('/login')
    return
  }
  
  next()
})

export default router
