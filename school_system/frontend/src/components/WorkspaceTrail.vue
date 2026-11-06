<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
const props = defineProps<{ activeCourseId?: number }>()
const route = useRoute(), router = useRouter()
const courseId = computed(() => Number(route.query.courseId))
const projectId = computed(() => Number(route.query.projectId))
</script>
<template><div v-if="courseId && projectId && courseId === props.activeCourseId" class="workspace-trail"><span>当前入口已限定到所选项目，可通过项目工作台切换团队或业务环节。</span><el-button text type="primary" @click="router.push(`/courses/${courseId}/projects/${projectId}`)">返回项目工作台</el-button></div></template>
