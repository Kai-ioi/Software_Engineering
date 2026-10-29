<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FolderOpened } from '@element-plus/icons-vue'
const route = useRoute(); const router = useRouter()
const names: Record<string, string> = { projects: '课程项目', teams: '团队成员', tasks: '迭代与任务', submissions: '成果与版本', reviews: '检查与互评', grades: '评分与成绩', notifications: '站内通知' }
const name = computed(() => names[String(route.params.module)] || '业务模块')
</script>
<template><section class="page-heading"><div><span class="eyebrow">WORKSPACE MODULE</span><h1>{{ name }}</h1><p class="muted">课程工作空间</p></div></section><div class="module-placeholder"><FolderOpened/><h2>{{ name }}尚未开放</h2><p>当前已完成基础框架，该模块将在后续业务开发中接入。</p><el-button type="primary" @click="router.push(route.query.courseId ? `/courses/${route.query.courseId}` : '/courses')">返回课程</el-button></div></template>
