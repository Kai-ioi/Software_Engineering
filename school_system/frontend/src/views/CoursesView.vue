<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Reading, ArrowRight, Search, Connection, Calendar } from '@element-plus/icons-vue'
import { getCourses, roleName, type Course } from '../api/courses'
import { auth } from '../stores/auth'
const router = useRouter(); const courses = ref<Course[]>([]); const loading = ref(true); const failed = ref(false)
const search = ref(''); const role = ref('ALL')
const visible = computed(() => courses.value.filter(c => c.name.includes(search.value.trim()) && (role.value === 'ALL' || c.myRole === role.value)))
async function load() { loading.value = true; failed.value = false; try { courses.value = await getCourses() } catch { failed.value = true } finally { loading.value = false } }
onMounted(load)
</script>
<template>
  <section class="page-heading"><div><span class="eyebrow">MY COURSES</span><h1>我的课程</h1><p class="muted">{{ auth.user?.displayName }}，从这里进入你的课程与项目。</p></div><div class="heading-count"><Reading/><span>{{ courses.length }} 门课程</span></div></section>
  <section class="welcome-banner"><div><span class="banner-label">每个项目，都从一次协作开始</span><h2>计划清晰，交付有据，反馈可追溯。</h2><p>选择课程，进入团队的共同工作空间。</p></div><div class="banner-art" aria-hidden="true"><div class="art-card card-back"><Calendar/></div><div class="art-card card-front"><Connection/><span>COURSE<br/>PROJECT</span><i></i><i></i></div><span class="art-dot"></span></div></section>
  <div class="section-toolbar"><div class="section-title"><h2>已加入的课程</h2><span class="count-badge">{{ visible.length }}</span></div><div class="filters"><el-input v-model="search" :prefix-icon="Search" placeholder="搜索课程名称" clearable aria-label="搜索课程"/><el-select v-model="role" aria-label="筛选课程身份"><el-option label="全部身份" value="ALL"/><el-option label="任课教师" value="TEACHER"/><el-option label="学生" value="STUDENT"/><el-option label="助教" value="TA"/></el-select></div></div>
  <div v-if="loading" class="state-panel" role="status">正在加载课程…</div>
  <div v-else-if="failed" class="state-panel"><p>课程加载失败，请检查服务后重试。</p><el-button @click="load">重新加载</el-button></div>
  <div v-else-if="visible.length" class="course-grid"><article v-for="(course, index) in visible" :key="course.id" class="course-card"><div class="course-card-top"><span class="course-icon" :class="{ peach: index % 2 === 1 }"><Reading/></span><span class="course-state"><span></span>{{ course.status === 'ACTIVE' ? '课程进行中' : course.status }}</span></div><div class="course-semester">{{ course.semester }}</div><h3>{{ course.name }}</h3><p class="course-description">{{ course.description }}</p><div class="course-card-footer"><span class="role-tag">{{ roleName[course.myRole] }}</span><el-button text type="primary" @click="router.push(`/courses/${course.id}`)">进入课程<ArrowRight class="button-icon"/></el-button></div></article></div>
  <el-empty v-else :description="courses.length ? '没有符合筛选条件的课程' : '你还没有加入课程，请联系任课教师'"/>
  <div class="bottom-tip"><span class="tip-mark">i</span>课程按当前账号的有效成员关系展示，不同课程的身份独立。</div>
</template>
