<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Plus, User, List } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getProject, type Project } from '../api/projects'
import { getTeams, createTeam, type Team } from '../api/teams'

const route = useRoute(); const router = useRouter()
const courseId = Number(route.params.courseId)
const projectId = Number(route.params.projectId)
const project = ref<Project | null>(null)
const teams = ref<Team[]>([])
const loading = ref(true); const failed = ref(false)
const showCreate = ref(false); const name = ref(''); const creating = ref(false)

onMounted(load)

async function load() {
  loading.value = true; failed.value = false
  try {
    project.value = await getProject(courseId, projectId)
    teams.value = await getTeams(courseId, projectId)
  } catch { failed.value = true } finally { loading.value = false }
}

function open(t: Team) { router.push(`/courses/${courseId}/projects/${projectId}/teams/${t.id}`) }

function state(t: Team): { text: string; cls: string } {
  if (t.full) return { text: '已满员', cls: 'closed' }
  if (t.complete) return { text: '组队完成', cls: 'published' }
  return { text: '组队中', cls: 'draft' }
}

async function create() {
  if (!name.value.trim()) { ElMessage.warning('请填写团队名称'); return }
  creating.value = true
  try { await createTeam(courseId, projectId, name.value.trim()); showCreate.value = false; name.value = ''; await load() }
  catch {} finally { creating.value = false }
}
</script>

<template>
  <el-button text @click="router.push(`/courses/${courseId}/projects/${projectId}`)"><ArrowLeft class="button-icon"/>返回项目详情</el-button>
  <section class="page-heading detail-heading">
    <div><span class="eyebrow">PROJECT TEAMS</span><h1>团队与成员</h1><p class="muted">{{ project ? project.title + ' · ' + project.minTeamSize + '-' + project.maxTeamSize + ' 人/队' : '课程团队' }}</p></div>
    <el-button type="primary" @click="showCreate = true"><Plus class="button-icon"/>创建团队</el-button>
  </section>

  <div v-if="loading" class="state-panel">正在加载团队…</div>
  <div v-else-if="failed" class="state-panel"><p>团队加载失败，请检查服务后重试。</p><el-button @click="load">重新加载</el-button></div>
  <div v-else-if="teams.length" class="project-grid">
    <article v-for="t in teams" :key="t.id" class="project-card" @click="open(t)">
      <div class="project-card-top"><span class="project-status" :class="state(t).cls">{{ state(t).text }}</span></div>
      <h3>{{ t.name }}</h3>
      <p class="project-desc">负责人：{{ t.leaderName }}</p>
      <div class="project-meta">
        <span><User/>{{ t.activeCount }}/{{ t.maxTeamSize }} 人</span>
        <span><List/>{{ t.applications.length }} 条待审批</span>
      </div>
    </article>
  </div>
  <el-empty v-else description="还没有团队，点击右上角「创建团队」组建第一支队伍"/>

  <el-dialog v-model="showCreate" title="创建团队" width="min(420px, 92vw)" destroy-on-close>
    <el-form label-position="top" @submit.prevent="create">
      <el-form-item label="团队名称" required>
        <el-input v-model="name" placeholder="例如：飞虎队" maxlength="160" show-word-limit />
      </el-form-item>
      <div class="form-actions">
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="creating">创建</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
