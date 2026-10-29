<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Reading, FolderOpened, User, List, Document, ChatDotRound, Medal, Bell, SwitchButton, ArrowRight } from '@element-plus/icons-vue'
import { auth, logout } from '../stores/auth'
import { request } from '../api/http'
const route = useRoute(); const router = useRouter()
const mode = ref(''); const busy = ref(false); const mobileMenu = ref(false)
const nav = [
  { path: '/courses', label: '我的课程', icon: Reading },
  { path: '/modules/projects', label: '课程项目', icon: FolderOpened },
  { path: '/modules/teams', label: '团队成员', icon: User },
  { path: '/modules/tasks', label: '迭代与任务', icon: List },
  { path: '/modules/submissions', label: '成果与版本', icon: Document },
  { path: '/modules/reviews', label: '检查与互评', icon: ChatDotRound },
  { path: '/modules/grades', label: '评分与成绩', icon: Medal },
  { path: '/modules/notifications', label: '站内通知', icon: Bell }
]
const title = computed(() => String(route.meta.title || '课程空间'))
onMounted(async () => { try { mode.value = (await request<{ mode: string }>('/health', {}, true)).mode } catch {} })
async function signOut() { busy.value = true; try { await logout(); await router.replace('/login') } catch {} finally { busy.value = false } }
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ expanded: mobileMenu }">
      <RouterLink to="/courses" class="brand"><span class="brand-symbol">协</span><span>课协<span class="brand-en">COURSE COLLAB</span></span></RouterLink>
      <div class="nav-caption">课程工作空间</div>
      <nav aria-label="主导航"><RouterLink v-for="item in nav" :key="item.path" :to="item.path" class="nav-item" :class="{ active: route.path === item.path || (item.path === '/courses' && route.path.startsWith('/courses/')) }" @click="mobileMenu = false"><component :is="item.icon"/><span>{{ item.label }}</span></RouterLink></nav>
      <div class="sidebar-bottom"><span class="status-dot"></span>高校课程项目协作与评审平台<span class="sidebar-version">框架版本 · 0.1</span></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><button class="mobile-toggle" @click="mobileMenu = !mobileMenu" aria-label="切换菜单">☰</button><span>工作空间</span><ArrowRight/><strong>{{ title }}</strong></div><div class="account"><span v-if="mode === 'demo'" class="mode-chip">演示环境</span><span class="avatar">{{ auth.user?.displayName.slice(0, 1) }}</span><span>{{ auth.user?.displayName }}</span><el-button text :loading="busy" @click="signOut"><SwitchButton class="button-icon"/>退出</el-button></div></header>
      <main class="page-content"><router-view :key="route.fullPath"/></main>
      <footer class="page-footer">课协 · 让每一次协作都有迹可循<span>Asia/Shanghai · UTC+8</span></footer>
    </div>
  </div>
</template>
