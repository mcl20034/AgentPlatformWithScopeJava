import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/change-password', name: 'change-password', component: () => import('../views/ChangePasswordView.vue') },
    {
      path: '/', component: () => import('../layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/chat' },
        { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue') },
        { path: 'admin/models', name: 'models', component: () => import('../views/ConfigurationCatalogView.vue'), meta: { admin: true, catalog: 'models', title: '模型管理' } },
        { path: 'admin/embedding-models', name: 'embedding-models', component: () => import('../views/ConfigurationCatalogView.vue'), meta: { admin: true, catalog: 'embedding-models', title: 'Embedding 模型' } },
        { path: 'admin/data-sources', name: 'data-sources', component: () => import('../views/ConfigurationCatalogView.vue'), meta: { admin: true, catalog: 'data-sources', title: '数据源管理' } },
        { path: 'admin/knowledge-bases', name: 'knowledge-bases', component: () => import('../views/KnowledgeBaseView.vue'), meta: { admin: true } },
        { path: 'admin/accounts', name: 'accounts', component: () => import('../views/AccountsView.vue'), meta: { admin: true } },
        { path: 'forbidden', name: 'forbidden', component: () => import('../views/ForbiddenView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { public: true } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.load()
  if (to.meta.public) return auth.user && to.name === 'login' ? '/chat' : true
  if (!auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (auth.user.mustChangePassword && to.name !== 'change-password') return { name: 'change-password' }
  if (!auth.user.mustChangePassword && to.name === 'change-password') return '/chat'
  if (to.meta.admin && auth.user.role !== 'ADMIN') return { name: 'forbidden' }
  return true
})

export default router
