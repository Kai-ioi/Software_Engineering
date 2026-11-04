<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Reading, FolderOpened, User, List, Document, ChatDotRound, Medal, Bell, SwitchButton, ArrowRight } from '@element-plus/icons-vue'
import { auth, logout } from '../stores/auth'
import { request } from '../api/http'
import { getCourses } from '../api/courses'
import { getProjects } from '../api/projects'

const route = useRoute(); const router = useRouter()
const retentionAccess = ref(false)
const mode = ref(''); const busy = ref(false); const mobileMenu = ref(false)

type NavItem = { label: string; icon: Component; active: (p: string) => boolean; path?: string; go?: () => void }

// 「课程项目」「团队成员」已接入真实页面：点击时定位到当前用户的第一个课程（及第一个项目）。
async function goProjects() {
  try {
    const courses = await getCourses()
    if (courses.length) { await router.push(`/courses/${courses[0].id}/projects`); return }
  } catch {}
  await router.push('/courses')
}

async function goTeams() {
  try {
    const courses = await getCourses()
    if (!courses.length) { await router.push('/courses'); return }
    const courseId = courses[0].id
    const projects = await getProjects(courseId)
    if (projects.length) { await router.push(`/courses/${courseId}/projects/${projects[0].id}/teams`); return }
    await router.push(`/courses/${courseId}/projects`)
  } catch {
    await router.push('/courses')
  }
}

const nav: NavItem[] = [
  { path: '/courses', label: '我的课程', icon: Reading, active: (p) => p === '/courses' || /^\/courses\/\d+$/.test(p) },
  { label: '课程项目', icon: FolderOpened, active: (p) => /^\/courses\/\d+\/projects/.test(p), go: goProjects },
  { label: '团队成员', icon: User, active: (p) => /^\/courses\/\d+\/projects\/\d+\/teams/.test(p), go: goTeams },
  { path: '/modules/tasks', label: '迭代与任务', icon: List, active: (p) => p === '/modules/tasks' || /^\/courses\/\d+\/tasks/.test(p) },
  { path: '/modules/submissions', label: '成果与版本', icon: Document, active: (p) => p === '/modules/submissions' || /^\/courses\/\d+\/submissions/.test(p) },
  { path: '/modules/reviews', label: '检查与互评', icon: ChatDotRound, active: (p) => p === '/modules/reviews' || /^\/courses\/\d+\/reviews/.test(p) },
  { path: '/modules/grades', label: '评分与成绩', icon: Medal, active: (p) => p === '/modules/grades' || /^\/courses\/\d+\/grades/.test(p) },
  { path: '/modules/history', label: '过程记录', icon: Document, active: (p) => p === '/modules/history' },
  { path: '/modules/notifications', label: '站内通知', icon: Bell, active: (p) => p === '/modules/notifications' || /^\/courses\/\d+\/notifications/.test(p) }
]

function onNav(item: NavItem) {
  mobileMenu.value = false
  if (item.go) { item.go(); return }
  if (item.path) router.push(item.path)
}

const title = computed(() => String(route.meta.title || '课程空间'))
onMounted(async () => {
  try { const result = await request<{admin:boolean;approver:boolean}>('/platform/retention/access', {}, true); retentionAccess.value = result.admin || result.approver } catch {}
  try { mode.value = (await request<{ mode: string }>('/health', {}, true)).mode } catch {} })
async function signOut() { busy.value = true; try { await logout(); await router.replace('/login') } catch {} finally { busy.value = false } }
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ expanded: mobileMenu }">
      <RouterLink to="/courses" class="brand"><span class="brand-symbol">协</span><span>课协<span class="brand-en">COURSE COLLAB</span></span></RouterLink>
      <div class="nav-caption">课程工作空间</div>
      <nav aria-label="主导航"><RouterLink v-if="retentionAccess" class="nav-item" to="/platform/retention"><Document/><span>课程数据留存</span></RouterLink><RouterLink v-if="auth.user?.platformAdmin" class="nav-item" to="/platform/directory"><User/><span>平台基础资料</span></RouterLink><a v-for="item in nav" :key="item.label" class="nav-item" :class="{ active: item.active(route.path) }" @click="onNav(item)"><component :is="item.icon"/><span>{{ item.label }}</span></a></nav>
      <div class="sidebar-bottom"><span class="status-dot"></span>高校课程项目协作与评审平台<span class="sidebar-version">框架版本 · 0.1</span></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><button class="mobile-toggle" @click="mobileMenu = !mobileMenu" aria-label="切换菜单">☰</button><span>工作空间</span><ArrowRight/><strong>{{ title }}</strong></div><div class="account"><span v-if="mode === 'demo'" class="mode-chip">演示环境</span><span class="avatar">{{ auth.user?.displayName.slice(0, 1) }}</span><span>{{ auth.user?.displayName }}</span><el-button text :loading="busy" @click="signOut"><SwitchButton class="button-icon"/>退出</el-button></div></header>
      <main class="page-content"><router-view :key="route.fullPath"/></main>
      <footer class="page-footer">课协 · 让每一次协作都有迹可循<span>Asia/Shanghai · UTC+8</span></footer>
    </div>
  </div>
</template>
