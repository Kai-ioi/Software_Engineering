import { createRouter, createWebHistory } from 'vue-router'
import { auth, loadUser } from '../stores/auth'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/', component: () => import('../layouts/AppLayout.vue'), children: [
    { path: '', redirect: '/courses' },
    { path: 'courses', component: () => import('../views/CoursesView.vue'), meta: { title: '我的课程' } },
    { path: 'courses/:id', component: () => import('../views/CourseDetailView.vue'), meta: { title: '课程空间' } },
    { path: 'courses/:courseId/projects', component: () => import('../views/ProjectsView.vue'), meta: { title: '课程项目' } },
    { path: 'courses/:courseId/projects/:projectId', component: () => import('../views/ProjectDetailView.vue'), meta: { title: '项目详情' } },
    { path: 'courses/:courseId/projects/:projectId/teams', component: () => import('../views/TeamsView.vue'), meta: { title: '团队与成员' } },
    { path: 'courses/:courseId/projects/:projectId/teams/:teamId', component: () => import('../views/TeamDetailView.vue'), meta: { title: '团队详情' } },
    { path: 'modules/tasks', component: () => import('../views/CollaborationView.vue'), meta: { title: '迭代与任务' } },
    { path: 'modules/submissions', component: () => import('../views/SubmissionsView.vue'), meta: { title: '成果与版本' } },
{ path: 'modules/reviews', component: () => import('../views/ReviewView.vue'), meta: { title: '检查与互评' } },
    { path: 'modules/grades', component: () => import('../views/GradesView.vue'), meta: { title: '评分与成绩' } },
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
