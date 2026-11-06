<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from '../stores/auth'
import { readWorkspace, rememberWorkspace } from '../stores/workspace'
import { getTeams, type Team } from '../api/teams'
import type { Project } from '../api/projects'
const props = defineProps<{ project: Project; courseId: number }>()
const router = useRouter()
const teams = ref<Team[]>([]), loading = ref(true), failed = ref(false)
const selected = ref<number>()
const teacher = computed(() => props.project.myRole === 'TEACHER')
const ta = computed(() => props.project.myRole === 'TA')
const myTeams = computed(() => teams.value.filter(t => t.members.some(m => m.userId === auth.user?.id && m.status === 'ACTIVE')))
const own = computed(() => myTeams.value.find(t => t.id === selected.value) || myTeams.value[0])
const availableTeams = computed(() => !teacher.value && !ta.value ? myTeams.value : teams.value)
const team = computed(() => teams.value.find(t => t.id === selected.value))
const identity = computed(() => teacher.value ? '任课教师' : ta.value ? '助教' : own.value?.leaderId === auth.user?.id ? '团队负责人' : own.value ? '团队成员' : '尚未加入团队')
const expired = computed(() => !!props.project.deadline && Date.parse(props.project.deadline) <= Date.now())
const guidance = computed(() => {
  if (props.project.status === 'DRAFT') return '先完善项目要求、组队人数、截止时间及评分标准，再发布给学生。'
  if (['TERMINATED', 'ARCHIVED'].includes(props.project.status)) return '项目已停止日常协作，可查看成果版本、成绩和历史记录。'
  if (!teacher.value && !ta.value && !own.value) return expired.value ? '当前项目已截止且你尚未加入团队，请联系任课教师核实归属；历史版本和成员操作仍受权限控制。' : '请先进入课程“组队与选题”，确认组员并由组长选择本项目，然后开始协作。'
  if (expired.value) return teacher.value ? '提交截止已到。先核实各队最终成果并确认被评版本，再组织互评、正式评分和成绩发布。' : ta.value ? '请检查已提交的具体版本；整改复查需关联原检查及新版成果。' : '普通提交窗口已关闭。可查看历史版本、处理已分配互评并查询已发布成绩；补交须先获教师授权。'
  return teacher.value ? '先检查组队情况与助教授权，再跟踪迭代和成果。截止后确认被评版本，组织互评并发布成绩。' : ta.value ? '从“过程检查”进入已授权团队，选择成果版本，记录结论并跟踪整改。' : own.value?.leaderId === auth.user?.id ? '安排迭代与分工，收齐材料后正式提交。重交产生新版本，请在截止前明确指定最终成果。' : '在“迭代协作”更新自己的任务，在“成果提交”准备材料；正式提交及最终指定由负责人完成。'
})
const steps = computed(() => [
  { title: '团队与成员', desc: teacher.value ? '设置组队窗口，处理成员调整并授权助教。' : '查看成员、课程组队、邀请确认及选题。', path: 'teams', action: '查看团队' },
  { title: '迭代协作', desc: teacher.value || ta.value ? '查看团队迭代、分工和里程碑，跟踪项目进展。' : own.value?.leaderId === auth.user?.id ? '规划迭代、分配任务，跟进团队进度。' : '查看自己的分工，更新任务进度及完成说明。', path: 'tasks', action: '进入协作' },
  { title: '成果提交', desc: teacher.value ? '查看成果回执，截止后核实异常并确认被评版本。' : ta.value ? '查阅已授权团队的成果及历史版本。' : own.value?.leaderId === auth.user?.id ? '收齐材料后正式提交，查看回执并指定最终版本。' : '上传团队材料，查看负责人正式提交的版本与回执。', path: 'submissions', action: '查看成果' },
  { title: '过程检查', desc: ta.value ? '按具体版本记录检查意见，关联整改与新版复查。' : '查看助教反馈，按要求整改并跟踪复查结果。', path: 'reviews', tab: 'inspection', action: '查看检查' },
  { title: '同伴互评', desc: teacher.value ? '确认被评版本后创建批次、分配评审并开放反馈。' : '完成已分配的评审，查看开放的反馈。', path: 'reviews', tab: 'review', action: '进入互评' },
  { title: '评分与成绩', desc: teacher.value ? '正式评分、核对计分名单，处理异常后发布及导出。' : ta.value ? '由任课教师正式评分和发布，助教不处理成绩。' : '查询已发布的个人成绩及其计分依据。', path: 'grades', action: teacher.value ? '进入评分' : '查看成绩' }
])
async function load() {
  loading.value = true; failed.value = false
  try { teams.value = await getTeams(props.courseId, props.project.id); const previous = readWorkspace(); selected.value = (previous.courseId === props.courseId && previous.projectId === props.project.id ? availableTeams.value.find(t => t.id === previous.teamId)?.id : undefined) || availableTeams.value[0]?.id }
  catch { failed.value = true } finally { loading.value = false }
}
watch(selected, teamId => { if (teamId) rememberWorkspace({ courseId: props.courseId, projectId: props.project.id, teamId }) })
onMounted(load)
function open(path: string, tab?: string) {
  if (path === 'teams') { router.push(`/courses/${props.courseId}/teams`); return }
  router.push({ path: `/modules/${path}`, query: { courseId: props.courseId, projectId: props.project.id, ...(selected.value ? { teamId: selected.value } : {}), ...(tab ? { tab } : {}) } })
}
</script>
<template>
  <section class="journey-panel" aria-label="项目工作台">
    <div class="journey-heading"><div><span class="eyebrow">项目工作台</span><h2>在同一个项目中完成每一步</h2></div><el-tag effect="plain">{{ identity }}</el-tag></div>
    <p class="journey-guidance">{{ guidance }}</p>
    <div v-if="loading" class="muted" role="status">正在加载可访问团队…</div>
    <div v-else-if="failed" class="journey-toolbar"><span>团队加载失败，请重试。</span><el-button @click="load">重新加载</el-button></div>
    <div v-else class="journey-toolbar"><label v-if="teams.length">操作团队 <el-select v-model="selected" aria-label="工作台操作团队"><el-option v-for="t in availableTeams" :key="t.id" :label="t.name" :value="t.id"/></el-select></label><span class="muted">{{ team ? `负责人：${team.leaderName} · ${team.activeCount} 名有效成员` : '还没有可访问团队，请先查看组队安排。' }}</span></div>
    <div class="journey-grid"><article v-for="(step, i) in steps" :key="step.title" class="journey-card"><span class="journey-number">{{ String(i + 1).padStart(2, '0') }}</span><h3>{{ step.title }}</h3><p>{{ step.desc }}</p><el-button :disabled="loading || failed || (step.path !== 'teams' && !teacher && !ta && !own) || (step.path === 'grades' && ta)" @click="open(step.path, step.tab)">{{ step.action }}</el-button></article></div>
    <div class="journey-footer"><span>各入口保留当前课程、项目及团队；可执行操作由角色权限和业务状态决定。</span><el-button text type="primary" :disabled="!teacher && !ta && !own" @click="open('history')">查看项目过程记录</el-button></div>
  </section>
</template>
