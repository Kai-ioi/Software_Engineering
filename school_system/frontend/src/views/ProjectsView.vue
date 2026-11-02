<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Plus, Calendar, User, List } from '@element-plus/icons-vue'
import { getCourse, roleName, type Course } from '../api/courses'
import { getProjects, createProject, statusName, type Project, type ProjectInput } from '../api/projects'
import ProjectForm from '../components/ProjectForm.vue'

const route = useRoute(); const router = useRouter()
const courseId = Number(route.params.courseId)
const course = ref<Course | null>(null)
const projects = ref<Project[]>([])
const loading = ref(true); const failed = ref(false)
const showForm = ref(false); const saving = ref(false)

onMounted(load)

async function load() {
  loading.value = true; failed.value = false
  try {
    course.value = await getCourse(courseId)
    projects.value = await getProjects(courseId)
  } catch { failed.value = true } finally { loading.value = false }
}

function open(p: Project) { router.push(`/courses/${courseId}/projects/${p.id}`) }

async function create(input: ProjectInput) {
  saving.value = true
  try { await createProject(courseId, input); showForm.value = false; await load() }
  catch {} finally { saving.value = false }
}

function fmt(t: string | null) { return t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '未设置' }
</script>

<template>
  <el-button text @click="router.push(`/courses/${courseId}`)"><ArrowLeft class="button-icon"/>返回课程空间</el-button>
  <section class="page-heading detail-heading">
    <div><span class="eyebrow">COURSE PROJECTS</span><h1>课程项目</h1><p class="muted">{{ course ? course.name + ' · ' + roleName[course.myRole] : '课程项目' }}</p></div>
    <el-button v-if="course?.myRole === 'TEACHER'" type="primary" @click="showForm = true"><Plus class="button-icon"/>新建项目</el-button>
  </section>

  <div v-if="loading" class="state-panel">正在加载项目…</div>
  <div v-else-if="failed" class="state-panel"><p>项目加载失败，请检查服务后重试。</p><el-button @click="load">重新加载</el-button></div>
  <div v-else-if="projects.length" class="project-grid">
    <article v-for="p in projects" :key="p.id" class="project-card" @click="open(p)">
      <div class="project-card-top"><span class="project-status" :class="p.status.toLowerCase()">{{ statusName[p.status] }}</span></div>
      <h3>{{ p.title }}</h3>
      <p class="project-desc">{{ p.description }}</p>
      <div class="project-meta">
        <span><Calendar/>截止 {{ fmt(p.deadline) }}</span>
        <span><User/>{{ p.minTeamSize }}-{{ p.maxTeamSize }} 人/队</span>
        <span><List/>{{ p.criteria.length }} 项评分</span>
      </div>
    </article>
  </div>
  <el-empty v-else :description="course?.myRole === 'TEACHER' ? '还没有项目，点击右上角「新建项目」开始' : '当前课程暂无已发布的项目'"/>

  <el-dialog v-model="showForm" title="新建项目" width="min(720px, 92vw)" destroy-on-close>
    <ProjectForm :busy="saving" @submit="create" @cancel="showForm = false" />
  </el-dialog>
</template>
