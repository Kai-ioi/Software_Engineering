<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { request } from '../api/http'

interface Notice { id: number; source: string; title: string; href: string; created_at: string; read_at: string | null; course_name: string; project_name: string | null; team_name: string | null }
const router = useRouter()
const rows = ref<Notice[]>([])
const unreadOnly = ref(false)
const loading = ref(false)
const failed = ref(false)
const course = ref('')
const kind = ref('')
const courses = computed(() => [...new Set(rows.value.map(item => item.course_name))])
const visible = computed(() => rows.value.filter(item =>
  (!unreadOnly.value || !item.read_at) && (!course.value || item.course_name === course.value) &&
  (!kind.value || item.source === kind.value)))
const groups = computed(() => {
  const byDay = new Map<string, Notice[]>()
  for (const item of visible.value) {
    const date = new Date(item.created_at).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai' })
    if (!byDay.has(date)) byDay.set(date, [])
    byDay.get(date)!.push(item)
  }
  return [...byDay]
})
const unread = computed(() => rows.value.filter(item => !item.read_at).length)
async function load() {
  loading.value = true; failed.value = false
  try { rows.value = await request<Notice[]>('/workflow/notices') } catch { failed.value = true } finally { loading.value = false }
}
async function open(item: Notice) {
  if (!item.read_at) {
    try {
      await request(`/workflow/notices/${item.source}/${item.id}/read`, { method: 'POST' })
      item.read_at = new Date().toISOString()
    } catch { return }
  }
  await router.push(item.href)
}
const stamp = (time: string) => new Date(time).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false })
onMounted(load)
</script>

<template>
  <section v-loading="loading" class="notice-page">
    <header class="page-heading">
      <div><span class="eyebrow">INBOX</span><h1>站内通知</h1><p class="muted">最近通知中有 {{ unread }} 条未读</p></div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>
    <div class="notice-filters">
      <el-checkbox v-model="unreadOnly">仅显示未读</el-checkbox>
      <el-select v-model="course" clearable placeholder="全部课程" aria-label="筛选课程">
        <el-option v-for="name in courses" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select v-model="kind" clearable placeholder="全部类型" aria-label="筛选通知类型">
        <el-option label="成果通知" value="submission" /><el-option label="课程动态" value="workflow" />
      </el-select>
    </div>
    <el-alert v-if="failed" title="通知加载失败，请点击刷新重试。已有通知暂时保留。" type="error" :closable="false"/>
    <el-empty v-if="!failed && !visible.length && !loading" description="当前筛选下暂无通知" />
    <section v-for="[day, items] in groups" :key="day" class="notice-group">
      <h2>{{ day }}</h2>
      <ul>
        <li v-for="item in items" :key="`${item.source}-${item.id}`">
          <button type="button" class="notice-row" :class="{ unread: !item.read_at }" @click="open(item)">
            <span class="notice-copy">
              <strong>{{ item.title }}</strong>
              <small>{{ [item.course_name, item.project_name, item.team_name].filter(Boolean).join(' · ') }}</small>
            </span>
            <span class="notice-meta"><el-tag v-if="!item.read_at" size="small" effect="plain">未读</el-tag><time :datetime="item.created_at">{{ stamp(item.created_at) }}</time></span>
          </button>
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.notice-page { max-width: 1050px; }
.notice-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.notice-filters .el-select { width: 170px; }
.notice-group { margin-top: 24px; }
.notice-group h2 { font-size: 14px; color: #526875; margin: 0 0 8px; }
.notice-group ul { list-style: none; margin: 0; padding: 0; background: white; border: 1px solid #dde7eb; border-radius: 12px; }
.notice-group li + li { border-top: 1px solid #edf1f3; }
.notice-row { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 16px; text-align: left; background: transparent; border: 0; border-radius: 10px; padding: 12px 16px; font: inherit; color: inherit; cursor: pointer; }
.notice-row:hover, .notice-row:focus-visible { background: #eef7f6; }
.notice-row.unread strong { color: #126764; }
.notice-copy, .notice-meta { display: flex; gap: 6px; }
.notice-copy { min-width:0; overflow-wrap:anywhere; }
@media(max-width:600px){.notice-row{align-items:flex-start;flex-direction:column}.notice-meta{align-self:flex-end}}
.notice-copy { flex-direction: column; }
.notice-copy small, time { color: #526875; }
.notice-meta { align-items: center; white-space: nowrap; }
</style>
