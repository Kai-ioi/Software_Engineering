<script setup lang="ts">
import WorkspaceTrail from '../components/WorkspaceTrail.vue'
import { followWorkspace, scopeTeams } from '../stores/workspace'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElDatePicker, ElInputNumber, ElProgress, ElResult, ElTable, ElTableColumn, ElCheckbox, ElDrawer } from 'element-plus'
import { auth } from '../stores/auth'
import { getCourses, type Course } from '../api/courses'
import { ApiError } from '../api/http'
import { getTeams, getWorkspace, getHistory, mutate, statusNames, projectStatusNames, type TeamOption, type Workspace, type Iteration, type Task, type Milestone, type Event } from '../api/collaboration'

const route = useRoute(), router = useRouter()
const courses = ref<Course[]>([]), courseId = ref<number>(), teamId = ref<number>(), teams = ref<TeamOption[]>([])
followWorkspace(courseId, teamId, () => teams.value)
const workspace = ref<Workspace>(), selectedIteration = ref<number>(), loading = ref(false), saving = ref(false), failed = ref(false)
const events = ref<Event[]>([]), historyOpen = ref(false), moreHistory = ref(false), historyLoading = ref(false)
const filter = ref('all'), search = ref('')
const iteration = computed(() => workspace.value?.iterations.find(i => i.id === selectedIteration.value))
const writable = computed(() => !!workspace.value && ['PUBLISHED', 'IN_PROGRESS'].includes(workspace.value.project.status) && new Date(workspace.value.project.final_deadline).getTime() >= Date.now())
const readonlyReason = computed(() => {
  if (!workspace.value) return ''
  if (['ENDED','TERMINATED','ARCHIVED'].includes(workspace.value.project.status)) return `项目${projectStatusNames[workspace.value.project.status]}，协作信息只读，可继续查阅成果及过程记录。`
  if (Date.parse(workspace.value.project.final_deadline) < Date.now()) return '项目最终截止已到，迭代与任务只读。成果补交请进入“成果与版本”申请教师授权。'
  return '当前项目尚未开放协作，请联系任课教师确认项目状态。'
})
const lead = computed(() => writable.value && !!workspace.value?.isLeader)
const manager = computed(() => writable.value && (workspace.value?.isLeader || workspace.value?.role === 'TEACHER'))
const iterationOpen = computed(() => !!iteration.value && iteration.value.status !== 'ENDED')
const allTasks = computed(() => workspace.value?.tasks.filter(t => t.iteration_id === selectedIteration.value) || [])
const tasks = computed(() => allTasks.value.filter(t => (filter.value !== 'mine' || t.assignee_id === auth.user?.id) && (!search.value || t.title.includes(search.value))))
const milestones = computed(() => workspace.value?.milestones.filter(m => m.iteration_id === selectedIteration.value) || [])
const completed = computed(() => allTasks.value.filter(t => t.status === 'COMPLETED').length)
const progress = computed(() => allTasks.value.length ? Math.round(completed.value / allTasks.value.length * 100) : 0)
const assigneeName = (id: number) => workspace.value?.members.find(m => m.id === id)?.display_name || `成员 #${id}（当前非有效成员）`
const iterationName = (id: number) => workspace.value?.iterations.find(i => i.id === id)?.name || `迭代 #${id}`
const requirementName = (id: number) => workspace.value?.requirements.find(r => r.id === id)?.title || `要求 #${id}`
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
const actionNames: Record<string,string> = { ITERATION_SAVED: '维护迭代计划', ITERATION_STARTED: '开始迭代', ITERATION_ENDED: '结束迭代', TASK_SAVED: '维护任务与分工', TASK_PROGRESS: '更新任务进度', TASK_MOVED: '跨迭代调整', MILESTONE_SAVED: '维护里程碑' }

async function loadTeams() {
  teamId.value = undefined; workspace.value = undefined; selectedIteration.value = undefined; failed.value = false; teams.value = []
  if (!courseId.value) return
  loading.value = true
  try { teams.value = scopeTeams(await getTeams(courseId.value), route.query, courseId.value); if (teams.value.length) { teamId.value = (teams.value.find(t => t.id === Number(route.query.teamId)) || teams.value.find(t => t.project_id === Number(route.query.projectId)) || teams.value[0])!.id; await refresh() } }
  catch { failed.value = true } finally { loading.value = false }
}
async function refresh() {
  if (!teamId.value) return
  loading.value = true; failed.value = false
  try {
    const next = await getWorkspace(teamId.value); workspace.value = next
    if (!next.iterations.some(i => i.id === selectedIteration.value)) selectedIteration.value = next.iterations[0]?.id
  } catch { workspace.value = undefined; failed.value = true } finally { loading.value = false }
}
async function changeTeam() { workspace.value = undefined; selectedIteration.value = undefined; await refresh() }
onMounted(async () => {
  try { courses.value = await getCourses(); const requested = Number(route.query.courseId); courseId.value = courses.value.find(c => c.id === requested)?.id || courses.value[0]?.id; await loadTeams() }
  catch { failed.value = true }
})

type DialogKind = '' | 'iteration' | 'task' | 'progress' | 'move' | 'milestone' | 'start' | 'end'
const dialog = ref<DialogKind>(''), editId = ref<number>(), dialogRevision = ref(0)
const form = reactive({ name: '', goal: '', sequence: 1, plannedStart: '', plannedEnd: '', title: '', description: '', assigneeId: undefined as number | undefined, requirementId: undefined as number | undefined, iterationId: undefined as number | undefined, status: 'IN_PROGRESS', note: '', reason: '', summary: '', leftovers: '', targetIterationId: undefined as number | undefined, targetDate: '', criteria: '', completed: false })
const titles: Record<string,string> = { iteration: '迭代计划', task: '团队任务', progress: '更新任务进度', move: '转入后续迭代', milestone: '迭代里程碑', start: '开始迭代', end: '结束迭代' }
const editedTask = computed(() => workspace.value?.tasks.find(t => t.id === editId.value))
const statusOptions = computed(() => {
  const current = editedTask.value?.status
  return current === 'NOT_STARTED' ? ['NOT_STARTED', 'IN_PROGRESS'] : current === 'IN_PROGRESS' ? ['IN_PROGRESS', 'COMPLETED'] : ['COMPLETED', 'IN_PROGRESS']
})
const moveTargets = computed(() => workspace.value?.iterations.filter(i => i.sequence_no > (iteration.value?.sequence_no || 0) && i.status !== 'ENDED') || [])
const reasonRequired = computed(() => workspace.value?.role === 'TEACHER' || dialog.value === 'move' || (dialog.value === 'progress' && editedTask.value?.status === 'COMPLETED' && form.status === 'IN_PROGRESS'))
function open(kind: DialogKind, item?: Iteration | Task | Milestone) {
  if (!workspace.value) return
  editId.value = item?.id; dialogRevision.value = workspace.value.team.revision
  Object.assign(form, { name: '', goal: '', sequence: Math.max(0, ...workspace.value.iterations.map(i => i.sequence_no)) + 1, plannedStart: iteration.value?.planned_start || new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }), plannedEnd: iteration.value?.planned_end || new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }), title: '', description: '', assigneeId: workspace.value.members[0]?.id, requirementId: workspace.value.requirements[0]?.id, iterationId: selectedIteration.value, status: 'IN_PROGRESS', note: '', reason: '', summary: '', leftovers: '', targetIterationId: undefined, targetDate: iteration.value?.planned_end || '', criteria: '', completed: false })
  if (kind === 'iteration' && item) { const i = item as Iteration; Object.assign(form, { name: i.name, goal: i.goal, sequence: i.sequence_no, plannedStart: i.planned_start, plannedEnd: i.planned_end }) }
  if (kind === 'task' && item) { const t = item as Task; Object.assign(form, { title: t.title, description: t.description, assigneeId: t.assignee_id, requirementId: t.requirement_id, plannedStart: t.planned_start, plannedEnd: t.planned_end }) }
  if (kind === 'progress' && item) { const t = item as Task; form.status = t.status === 'NOT_STARTED' ? 'IN_PROGRESS' : t.status; form.note = t.progress_note || '' }
  if (kind === 'milestone' && item) { const m = item as Milestone; Object.assign(form, { title: m.title, targetDate: m.target_date, criteria: m.completion_criteria, completed: m.completed, note: m.completion_note || '' }) }
  dialog.value = kind
}
watch(() => form.targetIterationId, id => { const target = workspace.value?.iterations.find(i => i.id === id); if (target) { form.plannedStart = target.planned_start; form.plannedEnd = target.planned_end } })
function validate() {
  const required: string[] = []
  if (dialog.value === 'iteration') { required.push(form.name, form.goal, form.plannedStart, form.plannedEnd); if (form.sequence < 1) return '迭代顺序须为正整数' }
  if (dialog.value === 'task') { required.push(form.title, form.description, form.plannedStart, form.plannedEnd); if (!form.assigneeId || !form.requirementId) return '请选择项目要求和执行成员' }
  if (dialog.value === 'progress') required.push(form.note)
  if (dialog.value === 'move') { required.push(form.plannedStart, form.plannedEnd); if (!form.targetIterationId) return '请选择后续迭代' }
  if (dialog.value === 'milestone') { required.push(form.title, form.targetDate, form.criteria); if (form.completed) required.push(form.note) }
  if (dialog.value === 'end') { required.push(form.summary); if (allTasks.value.some(t => t.status !== 'COMPLETED')) required.push(form.leftovers) }
  if (reasonRequired.value) required.push(form.reason)
  return required.some(s => !s?.trim()) ? '请补齐必填信息' : ''
}
async function save() {
  if (!workspace.value || !teamId.value || saving.value) return
  const error = validate(); if (error) { ElMessage.warning(error); return }
  const base = { revision: dialogRevision.value, reason: form.reason }
  let path = '', method = 'POST', payload: object = base
  if (dialog.value === 'iteration') { path = 'iterations' + (editId.value ? `/${editId.value}` : ''); method = editId.value ? 'PUT' : 'POST'; payload = { ...base, name: form.name, goal: form.goal, sequence: form.sequence, plannedStart: form.plannedStart, plannedEnd: form.plannedEnd } }
  if (dialog.value === 'task') { path = 'tasks' + (editId.value ? `/${editId.value}` : ''); method = editId.value ? 'PUT' : 'POST'; payload = { ...base, iterationId: selectedIteration.value, requirementId: form.requirementId, title: form.title, description: form.description, assigneeId: form.assigneeId, plannedStart: form.plannedStart, plannedEnd: form.plannedEnd } }
  if (dialog.value === 'progress') { path = `tasks/${editId.value}/progress`; payload = { ...base, status: form.status, note: form.note } }
  if (dialog.value === 'move') { path = `tasks/${editId.value}/move`; payload = { ...base, targetIterationId: form.targetIterationId, plannedStart: form.plannedStart, plannedEnd: form.plannedEnd } }
  if (dialog.value === 'milestone') { path = 'milestones' + (editId.value ? `/${editId.value}` : ''); method = editId.value ? 'PUT' : 'POST'; payload = { ...base, iterationId: selectedIteration.value, title: form.title, targetDate: form.targetDate, criteria: form.criteria, completed: form.completed, note: form.note } }
  if (dialog.value === 'start' || dialog.value === 'end') { path = `iterations/${selectedIteration.value}/${dialog.value}`; payload = { ...base, summary: form.summary, leftovers: form.leftovers } }
  saving.value = true
  try { await mutate(teamId.value, path, payload, method); dialog.value = ''; await refresh(); ElMessage.success('已保存，过程记录已更新') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await refresh() } }
  finally { saving.value = false }
}
async function history(more = false) {
  if (!teamId.value || historyLoading.value) return
  historyLoading.value = true
  try { const batch = await getHistory(teamId.value, more ? String(events.value.at(-1)!.id) : undefined); events.value = more ? [...events.value, ...batch] : batch; moreHistory.value = batch.length === 50; historyOpen.value = true }
  catch {} finally { historyLoading.value = false }
}
function detail(value: string) { try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } }
</script>

<template>
  <WorkspaceTrail :active-course-id="courseId"/>
  <section class="page-heading"><div><span class="eyebrow">TEAM WORKSPACE</span><h1>迭代与任务</h1><p class="muted">组长规划本团队的迭代与分工，组员更新自己的任务；教师查看进展并按需指导。</p></div><el-button :loading="loading" @click="refresh">刷新</el-button></section>
  <div class="collab-selectors"><label>课程 <el-select v-model="courseId" aria-label="选择课程" @change="loadTeams"><el-option v-for="c in courses" :key="c.id" :label="c.name" :value="c.id"/></el-select></label><label>团队 <el-select v-model="teamId" aria-label="选择团队" :disabled="!teams.length" @change="changeTeam"><el-option v-for="t in teams" :key="t.id" :label="`${t.project_name} / ${t.name}`" :value="t.id"/></el-select></label></div>
  <div v-if="loading && !workspace" class="state-panel">正在加载团队工作空间…</div>
  <el-result v-else-if="failed" icon="warning" title="暂时无法加载" sub-title="请检查服务连接或重新选择课程"><template #extra><el-button @click="loadTeams">重试</el-button></template></el-result>
  <el-empty v-else-if="!workspace" description="当前课程暂无你可访问的项目团队"><el-button @click="router.push('/courses')">返回课程</el-button></el-empty>
  <template v-else>
    <div class="collab-project"><div><h2>{{ workspace.team.name }}</h2><p class="muted">{{ workspace.project.name }} · {{ projectStatusNames[workspace.project.status] }} · 最终截止 {{ time(workspace.project.final_deadline) }}</p></div><el-button :loading="historyLoading" @click="history()">查看过程记录</el-button></div>
    <el-alert v-if="!writable" type="warning" :closable="false" :title="readonlyReason"/>
    <p v-else class="muted collab-policy">{{ workspace.role === 'TA' ? '助教可查看课程内团队进度，不能修改协作计划。' : workspace.role === 'TEACHER' ? '教师可干预团队任务，操作须填写原因；迭代和里程碑由团队负责人维护。' : workspace.isLeader ? '你是团队负责人，可维护迭代、任务和里程碑。' : '你可查看团队进度，并更新分配给自己的任务。' }} 计划日期不自动产生延期标记或结束迭代。</p>
    <div class="collab-grid"><aside class="collab-iterations"><div class="collab-bar"><h3>团队迭代</h3><el-button v-if="lead" type="primary" size="small" @click="open('iteration')">新建迭代</el-button></div><el-empty v-if="!workspace.iterations.length" description="尚未安排迭代" :image-size="60"/><button v-for="i in workspace.iterations" :key="i.id" class="iteration-choice" :class="{selected: i.id === selectedIteration}" @click="selectedIteration = i.id"><strong>{{ i.sequence_no }}. {{ i.name }}</strong><span>{{ statusNames[i.status] }}</span><small>{{ i.planned_start }} — {{ i.planned_end }}</small></button></aside>
    <section class="collab-main"><el-empty v-if="!iteration" description="选择或创建一轮迭代"/><template v-else>
      <div class="collab-bar"><div><h2>{{ iteration.name }}</h2><p>{{ iteration.goal }}</p><span class="muted">{{ iteration.planned_start }} — {{ iteration.planned_end }} · {{ statusNames[iteration.status] }}</span></div><div class="collab-actions"><el-button v-if="lead && iterationOpen" @click="open('iteration',iteration)">编辑计划</el-button><el-button v-if="lead && iteration.status === 'NOT_STARTED'" type="primary" @click="open('start')">开始迭代</el-button><el-button v-if="lead && iteration.status === 'IN_PROGRESS'" @click="open('end')">结束迭代</el-button></div></div>
      <div class="collab-progress"><span>{{ allTasks.length ? `已完成 ${completed} / ${allTasks.length} 项任务` : '尚无任务，进度未形成' }}</span><el-progress v-if="allTasks.length" :percentage="progress"/></div>
      <el-alert v-if="iteration.status === 'ENDED'" type="info" :closable="false" :title="`完成情况：${iteration.completion_summary || '未记录'}`" :description="`遗留事项：${iteration.leftovers || '无'}。可将未完成任务转入后续迭代，历史记录保留。`"/>
      <div class="collab-bar task-toolbar"><h3>团队任务</h3><div class="collab-actions"><el-input v-model="search" placeholder="搜索任务名称" aria-label="搜索任务" clearable style="width:180px"/><el-select v-model="filter" aria-label="任务筛选" style="width:130px"><el-option label="全部任务" value="all"/><el-option label="我的任务" value="mine"/></el-select><el-button v-if="manager && iterationOpen" type="primary" @click="open('task')">新建任务</el-button></div></div>
      <el-table :data="tasks" empty-text="暂无符合条件的任务" stripe><el-table-column label="任务 / 项目要求" min-width="240"><template #default="{row}"><strong>{{ row.title }}</strong><p class="muted">{{ row.description }}</p><small>关联：{{ requirementName(row.requirement_id) }}</small><p v-if="row.original_iteration_id !== row.iteration_id" class="muted">原归属：{{ iterationName(row.original_iteration_id) }}</p></template></el-table-column><el-table-column label="执行成员" min-width="130"><template #default="{row}">{{ assigneeName(row.assignee_id) }}</template></el-table-column><el-table-column label="进展" min-width="160"><template #default="{row}"><el-tag>{{ statusNames[row.status as keyof typeof statusNames] || '未知' }}</el-tag><p class="muted">{{ row.progress_note || '尚未更新进展说明' }}</p></template></el-table-column><el-table-column label="计划日期" min-width="120"><template #default="{row}">{{ row.planned_start }}<br>{{ row.planned_end }}</template></el-table-column><el-table-column label="操作" width="135"><template #default="{row}"><div class="task-row-actions"><el-button v-if="manager && iterationOpen" link type="primary" @click="open('task',row)">调整分工</el-button><el-button v-if="writable && iterationOpen && (manager || (workspace.role === 'STUDENT' && row.assignee_id === auth.user?.id))" link type="primary" @click="open('progress',row)">更新进度</el-button><el-button v-if="manager && row.status !== 'COMPLETED' && moveTargets.length" link @click="open('move',row)">转入后续迭代</el-button></div></template></el-table-column></el-table>
      <div class="collab-bar task-toolbar"><h3>里程碑</h3><el-button v-if="lead && iterationOpen" @click="open('milestone')">新建里程碑</el-button></div><el-empty v-if="!milestones.length" description="尚未设置里程碑" :image-size="55"/><article v-for="m in milestones" :key="m.id" class="milestone-card"><div class="collab-bar"><strong>{{ m.title }}</strong><span><el-tag :type="m.completed ? 'success' : 'info'">{{ m.completed ? '已完成' : '未完成' }}</el-tag><el-button v-if="lead && iterationOpen" link type="primary" @click="open('milestone',m)">编辑</el-button></span></div><p>目标日期：{{ m.target_date }}</p><p>完成条件：{{ m.completion_criteria }}</p><p v-if="m.completion_note">完成情况：{{ m.completion_note }}</p></article>
    </template></section></div>
  </template>
  <el-dialog :model-value="!!dialog" :title="titles[dialog]" width="min(620px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @close="dialog = ''"><el-form label-position="top" @submit.prevent="save">
    <template v-if="dialog === 'iteration'"><el-form-item label="迭代名称" required><el-input v-model="form.name" maxlength="160"/></el-form-item><el-form-item label="迭代目标" required><el-input v-model="form.goal" type="textarea" maxlength="2000"/></el-form-item><el-form-item label="迭代顺序" required><el-input-number v-model="form.sequence" :min="1" :precision="0"/></el-form-item></template>
    <template v-if="dialog === 'task'"><el-form-item label="任务名称" required><el-input v-model="form.title" maxlength="200"/></el-form-item><el-form-item label="任务内容" required><el-input v-model="form.description" type="textarea" maxlength="2000"/></el-form-item><el-form-item label="关联项目要求" required><el-select v-model="form.requirementId"><el-option v-for="r in workspace?.requirements" :key="r.id" :value="r.id" :label="r.title"/></el-select></el-form-item><el-form-item label="执行成员" required><el-select v-model="form.assigneeId"><el-option v-for="m in workspace?.members" :key="m.id" :value="m.id" :label="`${m.display_name}（${m.username}）`"/></el-select></el-form-item></template>
    <template v-if="dialog === 'progress'"><el-form-item label="任务状态" required><el-select v-model="form.status"><el-option v-for="s in statusOptions" :key="s" :value="s" :label="statusNames[s as keyof typeof statusNames]"/></el-select></el-form-item><el-form-item label="进展说明" required><el-input v-model="form.note" type="textarea" maxlength="2000"/></el-form-item></template>
    <el-form-item v-if="dialog === 'move'" label="后续迭代" required><el-select v-model="form.targetIterationId"><el-option v-for="i in moveTargets" :key="i.id" :value="i.id" :label="`${i.sequence_no}. ${i.name}`"/></el-select></el-form-item>
    <div v-if="['iteration','task','move'].includes(dialog)" class="date-fields"><el-form-item label="计划开始日期" required><el-date-picker v-model="form.plannedStart" type="date" value-format="YYYY-MM-DD" placeholder="选择日期"/></el-form-item><el-form-item label="计划结束日期" required><el-date-picker v-model="form.plannedEnd" type="date" value-format="YYYY-MM-DD" placeholder="选择日期"/></el-form-item></div>
    <template v-if="dialog === 'milestone'"><el-form-item label="里程碑名称" required><el-input v-model="form.title" maxlength="200"/></el-form-item><el-form-item label="目标日期" required><el-date-picker v-model="form.targetDate" type="date" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="完成条件" required><el-input v-model="form.criteria" type="textarea" maxlength="2000"/></el-form-item><el-form-item label="完成情况"><el-checkbox v-model="form.completed">已完成</el-checkbox></el-form-item><el-form-item label="完成说明" :required="form.completed"><el-input v-model="form.note" type="textarea" maxlength="2000"/></el-form-item></template>
    <el-alert v-if="dialog === 'start'" title="确认开始本轮迭代？计划日期不会自动启动或结束迭代。" type="info" :closable="false"/>
    <template v-if="dialog === 'end'"><el-alert title="结束后迭代内计划与进度只读；未完成任务仍可转入后续迭代。" type="warning" :closable="false"/><el-form-item label="完成情况" required><el-input v-model="form.summary" type="textarea" maxlength="2000"/></el-form-item><el-form-item label="遗留事项及后续安排" :required="allTasks.some(t => t.status !== 'COMPLETED')"><el-input v-model="form.leftovers" type="textarea" maxlength="2000"/></el-form-item></template>
    <el-form-item label="操作原因" :required="reasonRequired"><el-input v-model="form.reason" type="textarea" maxlength="2000" placeholder="教师干预、返工及跨迭代调整须填写原因"/></el-form-item>
  </el-form><template #footer><el-button :disabled="saving" @click="dialog = ''">取消</el-button><el-button type="primary" :loading="saving" @click="save">确认保存</el-button></template></el-dialog>
  <el-drawer v-model="historyOpen" title="团队协作过程记录" size="min(780px,96vw)"><el-empty v-if="!events.length" description="暂无过程记录"/><article v-for="e in events" :key="e.id" class="event-card"><strong>{{ actionNames[e.action] || e.action }} · #{{ e.object_id }}</strong><p>{{ e.actor_name }} · {{ time(e.occurred_at) }}（北京时间）</p><p v-if="e.reason">原因：{{ e.reason }}</p><details><summary>查看变更前后内容</summary><h4>变更前</h4><pre>{{ detail(e.before_data) }}</pre><h4>变更后</h4><pre>{{ detail(e.after_data) }}</pre></details></article><el-button v-if="moreHistory" :loading="historyLoading" @click="history(true)">加载更早记录</el-button></el-drawer>
</template>

<style scoped>
.collab-selectors,.collab-bar,.collab-project,.collab-actions,.date-fields{display:flex;gap:12px;align-items:center;justify-content:space-between}.collab-selectors{justify-content:flex-start;margin-bottom:22px;flex-wrap:wrap}.collab-selectors label{display:flex;align-items:center;gap:10px}.collab-selectors .el-select{width:280px}.collab-project{margin:24px 0}.collab-project h2,.collab-bar h2,.collab-bar h3{margin:0}.collab-policy{line-height:1.8}.collab-grid{display:grid;grid-template-columns:240px minmax(0,1fr);gap:20px;margin-top:22px}.collab-iterations,.collab-main{background:white;border:1px solid #e4e9ef;border-radius:12px;padding:20px;min-width:0}.collab-iterations{padding:16px;height:fit-content}.iteration-choice{display:flex;flex-direction:column;gap:10px;width:100%;border:1px solid #e4e9ef;border-radius:8px;background:#fff;text-align:left;padding:14px;margin-top:12px;cursor:pointer;color:#344054}.iteration-choice.selected{border-color:#2e6f65;background:#edf6f3}.iteration-choice small{font-size:11px;color:#667085}.collab-progress{background:#f6f8fa;border-radius:8px;padding:16px;margin:22px 0}.collab-progress span{display:block;margin-bottom:8px}.task-toolbar{margin:26px 0 14px;flex-wrap:wrap}.task-row-actions{display:flex;flex-direction:column;align-items:flex-start;gap:10px}.task-row-actions .el-button{margin:0}.milestone-card,.event-card{border:1px solid #e4e9ef;border-radius:8px;padding:16px;margin:14px 0}.milestone-card p,.event-card p{font-size:13px;line-height:1.8;white-space:pre-wrap}.event-card pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f6f8fa;padding:12px;font-size:12px}.date-fields{align-items:flex-start}.date-fields>.el-form-item{min-width:0;flex:1}:deep(.el-form-item .el-select){width:100%}:deep(.el-form-item .el-date-editor){max-width:100%}.collab-main p{overflow-wrap:anywhere}.el-table p{font-size:12px;white-space:pre-wrap}@media(max-width:1100px){.collab-grid{grid-template-columns:1fr}.collab-actions{flex-wrap:wrap}.collab-bar{align-items:flex-start;flex-wrap:wrap}}@media(max-width:600px){.collab-selectors .el-select{width:220px}.date-fields{flex-direction:column}.collab-main{padding:12px}}
</style>
