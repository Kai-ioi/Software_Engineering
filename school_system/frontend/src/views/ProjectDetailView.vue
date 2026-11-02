<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Calendar, User, List } from '@element-plus/icons-vue'
import { getCourse, roleName, type Course } from '../api/courses'
import { getProject, updateProject, publishProject, closeProject, archiveProject, statusName, type Project, type ProjectInput } from '../api/projects'
import ProjectForm from '../components/ProjectForm.vue'

const route = useRoute(); const router = useRouter()
const courseId = Number(route.params.courseId)
const projectId = Number(route.params.projectId)
const course = ref<Course | null>(null)
const project = ref<Project | null>(null)
const loading = ref(true); const failed = ref(false)
const editing = ref(false); const saving = ref(false); const acting = ref(false)
const formInitial = ref<ProjectInput | null>(null)

onMounted(async () => {
  try {
    course.value = await getCourse(courseId)
    project.value = await getProject(courseId, projectId)
  } catch { failed.value = true } finally { loading.value = false }
})

const isTeacher = () => course.value?.myRole === 'TEACHER'

function openEdit() {
  if (!project.value) return
  formInitial.value = {
    title: project.value.title,
    description: project.value.description,
    deadline: project.value.deadline,
    minTeamSize: project.value.minTeamSize,
    maxTeamSize: project.value.maxTeamSize,
    criteria: project.value.criteria.map(c => ({ name: c.name, weight: c.weight, description: c.description ?? '' }))
  }
  editing.value = true
}

async function save(input: ProjectInput) {
  saving.value = true
  try { project.value = await updateProject(courseId, projectId, input); editing.value = false }
  catch {} finally { saving.value = false }
}

async function run(action: 'publish' | 'close' | 'archive') {
  acting.value = true
  try {
    if (action === 'publish') project.value = await publishProject(courseId, projectId)
    else if (action === 'close') project.value = await closeProject(courseId, projectId)
    else project.value = await archiveProject(courseId, projectId)
  } catch {} finally { acting.value = false }
}

function fmt(t: string | null) { return t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '未设置' }

function goTeams() { router.push(`/courses/${courseId}/projects/${projectId}/teams`) }
</script>

<template>
  <el-button text @click="router.push(`/courses/${courseId}/projects`)"><ArrowLeft class="button-icon"/>返回项目列表</el-button>
  <div v-if="loading" class="state-panel">正在加载项目…</div>
  <el-empty v-else-if="failed || !project" description="无法访问此项目，请返回项目列表"/>

  <template v-else>
    <section class="page-heading detail-heading">
      <div><span class="eyebrow">PROJECT DETAIL</span><h1>{{ project.title }}</h1><p class="muted">{{ course ? course.name + ' · ' + roleName[course.myRole] : '' }}</p></div>
      <div class="detail-heading-actions">
        <span class="project-status" :class="project.status.toLowerCase()">{{ statusName[project.status] }}</span>
        <el-button type="primary" @click="goTeams">团队与成员</el-button>
      </div>
    </section>

    <div class="detail-panel">
      <div class="panel-row">
        <span><Calendar/>截止 {{ fmt(project.deadline) }}</span>
        <span><User/>{{ project.minTeamSize }}-{{ project.maxTeamSize }} 人/队</span>
        <span><List/>{{ project.criteria.length }} 项评分标准</span>
      </div>
      <div class="detail-section"><h3>项目要求</h3><p class="detail-description">{{ project.description }}</p></div>
      <div class="detail-section" v-if="project.criteria.length">
        <h3>评分标准及权重</h3>
        <table class="criterion-table">
          <thead><tr><th>评分项</th><th>权重</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="c in project.criteria" :key="c.id"><td>{{ c.name }}</td><td>{{ c.weight }}%</td><td>{{ c.description || '—' }}</td></tr>
          </tbody>
        </table>
      </div>

      <div v-if="isTeacher()" class="detail-actions">
        <el-button v-if="project.status === 'DRAFT'" type="primary" :loading="acting" @click="run('publish')">发布项目</el-button>
        <el-button v-if="project.status === 'DRAFT' || project.status === 'PUBLISHED'" :loading="false" @click="openEdit">编辑</el-button>
        <el-button v-if="project.status === 'PUBLISHED'" type="warning" :loading="acting" @click="run('close')">终止项目</el-button>
        <el-button v-if="project.status !== 'ARCHIVED'" :loading="acting" @click="run('archive')">归档</el-button>
      </div>
    </div>
  </template>

  <el-dialog v-model="editing" title="编辑项目" width="min(720px, 92vw)" destroy-on-close>
    <ProjectForm :initial="formInitial" :busy="saving" @submit="save" @cancel="editing = false" />
  </el-dialog>
</template>
