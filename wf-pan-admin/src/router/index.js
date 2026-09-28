import { createRouter, createWebHashHistory } from 'vue-router'
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
    redirect: '/dashboard',
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

// hash 路由：部署在任意子路径下（如 NG 的 /pan-admin/）都不需要服务端做前端路由回退
const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.path === '/login') {
    return true
  }
  const userStore = useUserStore()
  if (!userStore.isLoggedIn || !(await userStore.ensureSession())) {
    return '/login'
  }
  return true
})

export default router
