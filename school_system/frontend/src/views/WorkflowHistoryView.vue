<script setup lang="ts">
import WorkspaceTrail from '../components/WorkspaceTrail.vue'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { followWorkspace, scopeTeams } from '../stores/workspace'
const route = useRoute()
import { getCourses, type Course } from '../api/courses'
import { getInspectionTeams, type InspectionTeam } from '../api/reviews'
import { request } from '../api/http'

interface Entry { event_id: number; category: string; action: string; object_ref: string; detail: string; occurred_at: string }
const courses = ref<Course[]>([])
const teams = ref<InspectionTeam[]>([])
const course = ref<number>()
const team = ref<number>()
followWorkspace(course, team, () => teams.value)
const type = ref('')
const objectRef = ref('')
const rows = ref<Entry[]>([])
const loading = ref(false)
const failed = ref(false)
const hasMore = ref(false)
let generation = 0
const categories = ['项目', '成员', '组队申请', '协作', '成果', '检查', '互评', '成绩']
async function loadTeams() {
  generation++
  loading.value = false
  hasMore.value = false
  team.value = undefined
  teams.value = []
  rows.value = []; failed.value = false
  if (!course.value) return
  try { teams.value = scopeTeams(await getInspectionTeams(course.value), route.query, course.value) } catch { failed.value = true; return }
  team.value = (teams.value.find(t => t.id === Number(route.query.teamId)) || teams.value.find(t => t.project_id === Number(route.query.projectId)) || teams.value[0])?.id
  await load()
}
async function load() {
  rows.value = []; hasMore.value = false; failed.value = false
  await loadPage()
}
async function loadPage() {
  if (!team.value) return
  loading.value = true
  const ticket = ++generation
  const last = rows.value.at(-1)
  try {
    const query = new URLSearchParams({ teamId: String(team.value) })
    if (type.value) query.set('type', type.value)
    if (objectRef.value.trim()) query.set('objectRef', objectRef.value.trim())
    if (last) { query.set('before', last.occurred_at); query.set('category', last.category); query.set('eventId', String(last.event_id)) }
    const result = await request<Entry[]>(`/workflow/history?${query}`)
    if (ticket === generation) { rows.value.push(...result); hasMore.value = result.length === 50 }
  } catch { if (ticket === generation) failed.value = true } finally { if (ticket === generation) loading.value = false }
}
const stamp = (time: string) => new Date(time).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
onMounted(async () => {
  try { courses.value = await getCourses() } catch { return }
  course.value = (courses.value.find(c => c.id === Number(route.query.courseId)) || courses.value[0])?.id
  await loadTeams()
})
</script>

<template>
  <WorkspaceTrail :active-course-id="course"/>
  <section v-loading="loading">
    <header class="page-heading"><div><span class="eyebrow">项目过程记录</span><h1>协作过程可追溯</h1><p class="muted">按团队核对项目、成员、任务、成果、检查、评审与成绩的关键变化。</p></div></header>
    <div class="history-filters">
    <el-select v-model="course" placeholder="选择课程" style="width: 190px" @change="loadTeams">
      <el-option v-for="item in courses" :key="item.id" :label="item.name" :value="item.id" />
    </el-select>
    <el-select v-model="team" placeholder="选择团队" style="width: 260px" @change="load">
      <el-option v-for="item in teams" :key="item.id" :label="`${item.project_name} / ${item.name}`" :value="item.id" />
    </el-select>
    <el-select v-model="type" clearable placeholder="全部类别" style="width: 130px" @change="load">
      <el-option v-for="item in categories" :key="item" :label="item" :value="item" />
    </el-select>
    <el-input v-model="objectRef" clearable placeholder="关联编号" style="width: 170px" @clear="load" @keyup.enter="load"/>
    <el-button @click="load">查询</el-button>
    </div>
    <el-alert v-if="failed" type="error" title="记录加载失败，请重新查询或选择可访问的团队。" :closable="false"/>
    <el-table :data="rows" empty-text="暂无过程记录">
      <el-table-column label="北京时间" width="180"><template #default="{row}">{{ stamp(row.occurred_at) }}</template></el-table-column>
      <el-table-column prop="category" label="类别" width="85" />
      <el-table-column prop="action" label="操作" min-width="180" />
      <el-table-column prop="object_ref" label="关联编号" width="135" />
      <el-table-column prop="detail" label="说明" min-width="220" />
    </el-table>
    <el-button v-if="hasMore" :disabled="loading" @click="loadPage">加载更早记录</el-button>
  </section>
</template>

<style scoped>
.history-filters{display:flex;flex-wrap:wrap;gap:12px;margin-bottom:22px;align-items:center}
</style>
