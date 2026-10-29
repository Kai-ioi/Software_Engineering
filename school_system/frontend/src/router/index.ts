import { createRouter, createWebHistory } from 'vue-router'
import { auth, loadUser } from '../stores/auth'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/', component: () => import('../layouts/AppLayout.vue'), children: [
    { path: '', redirect: '/courses' },
    { path: 'courses', component: () => import('../views/CoursesView.vue'), meta: { title: '我的课程' } },
    { path: 'courses/:id', component: () => import('../views/CourseDetailView.vue'), meta: { title: '课程空间' } },
    { path: 'modules/:module', component: () => import('../views/ModuleView.vue'), meta: { title: '业务模块' } }
  ] },
  { path: '/:pathMatch(.*)*', component: () => import('../views/NotFoundView.vue'), meta: { public: true } }
] })
router.beforeEach(async (to) => {
  await loadUser()
  if (!to.meta.public && !auth.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && auth.user) return '/courses'
})
window.addEventListener('session-expired', () => {
  if (router.currentRoute.value.path !== '/login') void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})
export default router
