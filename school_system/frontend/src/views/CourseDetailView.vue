<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, FolderOpened, User, List, Document, ChatDotRound, Medal, Upload } from '@element-plus/icons-vue'
import { getCourse, getManagement, getAudit, roleName, type Course, type Audit } from '../api/courses'
import { request } from '../api/http'
import type { UploadRequestOptions } from 'element-plus'
const route = useRoute(); const router = useRouter(); const course = ref<Course | null>(null)
const failed = ref(false); const loading = ref(true); const count = ref<number | null>(null)
const logs = ref<Audit[]>([]); const showLogs = ref(false); const logsLoading = ref(false)
const uploaded = ref<{ id: string; name: string }[]>([]); const uploading = ref(false)
const modules = [ { key: 'projects', title: '课程项目', desc: '项目要求与交付安排', icon: FolderOpened }, { key: 'teams', title: '团队成员', desc: '组队申请与成员管理', icon: User }, { key: 'tasks', title: '迭代与任务', desc: '迭代计划与任务进展', icon: List }, { key: 'submissions', title: '成果与版本', desc: '正式提交与最终版本', icon: Document }, { key: 'reviews', title: '检查与互评', desc: '过程检查与评审反馈', icon: ChatDotRound }, { key: 'grades', title: '评分与成绩', desc: '评分、发布与结果查询', icon: Medal } ]
onMounted(async () => {
  try { course.value = await getCourse(Number(route.params.id)); if (course.value.myRole === 'TEACHER') count.value = (await getManagement(course.value.id)).studentCount }
  catch { failed.value = true } finally { loading.value = false }
})
async function audit() { if (!course.value) return; logsLoading.value = true; try { logs.value = (await getAudit(course.value.id)).items; showLogs.value = true } catch {} finally { logsLoading.value = false } }
async function upload(options: UploadRequestOptions) {
  if (!course.value) return
  uploading.value = true
  const data = new FormData(); data.append('file', options.file); data.append('courseId', String(course.value.id))
  try { const result = await request<{ id: string; name: string }>('/files', { method: 'POST', body: data }); uploaded.value.push(result); options.onSuccess(result) }
  catch (e) { options.onError(Object.assign(e instanceof Error ? e : new Error('上传失败'), { status: 0, method: 'POST', url: '/api/files' })) } finally { uploading.value = false }
}
</script>
<template>
  <el-button text @click="router.push('/courses')"><ArrowLeft class="button-icon"/>返回我的课程</el-button>
  <div v-if="loading" class="state-panel">正在加载课程…</div><el-empty v-else-if="failed || !course" description="无法访问此课程，请返回课程列表"/>
  <template v-else><section class="page-heading detail-heading"><div><span class="eyebrow">COURSE WORKSPACE</span><h1>{{ course.name }}</h1><p class="muted">{{ course.semester }} · {{ roleName[course.myRole] }}</p></div><el-button v-if="course.myRole === 'TEACHER'" :loading="logsLoading" @click="audit">查看操作记录</el-button></section><p class="detail-description">{{ course.description }}</p><div v-if="count !== null" class="small-stat"><User/><strong>{{ count }}</strong>名课程学生</div>
  <div class="module-grid"><article v-for="m in modules" :key="m.key" class="module-card"><component :is="m.icon"/><h3>{{ m.title }}</h3><p>{{ m.desc }}</p><el-button text type="primary" @click="router.push({ path: `/modules/${m.key}`, query: { courseId: course.id } })">查看模块</el-button></article></div>
  <section class="materials-panel"><div><h2>个人待提交材料</h2><p class="muted">上传的材料仅本人可下载；正式成果提交将在成果模块开放。</p></div><el-upload :http-request="upload" :show-file-list="false" :disabled="uploading" accept=".pdf,.docx,.pptx,.xlsx,.txt,.md,.png,.jpg,.jpeg,.zip"><el-button :loading="uploading"><Upload class="button-icon"/>上传材料</el-button></el-upload><p class="material-note">单文件不超过50 MB。支持报告、图片和 ZIP 等需求规定格式。</p><a v-for="f in uploaded" :key="f.id" :href="`/api/files/${f.id}`" class="download-link">{{ f.name }} · 下载</a></section>
  <el-dialog v-model="showLogs" title="课程操作记录" width="min(720px, 92vw)"><el-empty v-if="!logs.length" description="暂无操作记录"/><div v-for="log in logs" :key="log.id" class="audit-row"><strong>{{ log.summary }}</strong><span>{{ log.occurredAt }} · 操作者 #{{ log.actorId }}</span></div></el-dialog></template>
</template>
