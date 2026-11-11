import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册账号', public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      {
        path: '',
        redirect: { name: 'dashboard' }
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '概览', icon: 'DataLine' }
      },
      {
        path: 'workflows',
        name: 'workflows',
        component: () => import('@/views/WorkflowListView.vue'),
        meta: { title: '工作流', icon: 'Share' }
      },
      {
        path: 'workflows/:id(\\d+)',
        name: 'workflow-detail',
        component: () => import('@/views/WorkflowDetailView.vue'),
        meta: { title: '工作流详情', parent: 'workflows' }
      },
      {
        path: 'executions',
        name: 'executions',
        component: () => import('@/views/ExecutionListView.vue'),
        meta: { title: '执行记录', icon: 'VideoPlay' }
      },
      {
        path: 'executions/:id(\\d+)',
        name: 'execution-detail',
        component: () => import('@/views/ExecutionDetailView.vue'),
        meta: { title: '执行详情', parent: 'executions' }
      },
      {
        path: 'approvals',
        name: 'approvals',
        component: () => import('@/views/ApprovalsView.vue'),
        meta: { title: '审批中心', icon: 'Stamp' }
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/ReportsView.vue'),
        meta: { title: '报告', icon: 'Document' }
      },
      {
        path: 'reports/:id(\\d+)',
        name: 'report-detail',
        component: () => import('@/views/ReportDetailView.vue'),
        meta: { title: '报告详情', parent: 'reports' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true }
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    // 已登录用户不应停留在登录/注册页
    if (auth.isAuthenticated && (to.name === 'login' || to.name === 'register')) {
      return { name: 'dashboard' }
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · FlowPilot 管理控制台` : 'FlowPilot 管理控制台'
})

export default router
