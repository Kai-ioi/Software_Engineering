<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCourses, type Course } from '../api/courses'
import { projectStatusNames } from '../api/collaboration'
import { ApiError } from '../api/http'
import { getVersion, type VersionDetail } from '../api/submissions'
import {
  getReviewProjects, getReviewWorkspace, createRound, autoAssign, addReviewTask, cancelTask, extendRound,
  closeRound, toggleFeedback, submitReview, appealReview, approveAppeal, rejectAppeal, getInspectionTeams,
  getInspectionWorkspace, createInspection,
  type ReviewProject, type ReviewWorkspace, type ReviewRound, type MyTask, type Appeal, type InspectionTeam, type InspectionWorkspace
} from '../api/reviews'

const route = useRoute(); const router = useRouter()
const courses = ref<Course[]>([]); const courseId = ref<number>()
const tab = ref<'review' | 'inspection'>('review')
const loading = ref(false); const busy = ref(false); const failed = ref(false)
const rProjects = ref<ReviewProject[]>([]); const projectId = ref<number>(); const rw = ref<ReviewWorkspace>()
const iTeams = ref<InspectionTeam[]>([]); const teamId = ref<number>(); const iw = ref<InspectionWorkspace>()

const stamp = (v?: string | null) => v ? new Date(v).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
const roundStatus: Record<string, string> = { OPEN: '进行中', CLOSED: '已关闭' }
const taskStatus: Record<string, string> = { PENDING: '待填写', SUBMITTED: '已提交', CANCELLED: '已取消' }
const appealStatus: Record<string, string> = { PENDING: '待处理', APPROVED: '已同意重评', REJECTED: '已驳回' }
const bjt = (ms: number) => new Date(ms).toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', hour12: false }).replace(' ', 'T')

onMounted(async () => {
  try {
    courses.value = await getCourses()
    courseId.value = courses.value.find(c => c.id === Number(route.query.courseId))?.id || courses.value[0]?.id
    await loadReview(); await loadInspection()
  } catch { failed.value = true }
})
async function loadReview() {
  if (!courseId.value) return
  rw.value = undefined; projectId.value = undefined
  try { rProjects.value = await getReviewProjects(courseId.value); if (rProjects.value.length) { projectId.value = rProjects.value[0]!.id; await loadReviewWorkspace() } } catch { failed.value = true }
}
async function loadReviewWorkspace() {
  if (!projectId.value) return
  loading.value = true
  try { rw.value = await getReviewWorkspace(projectId.value) } catch { failed.value = true } finally { loading.value = false }
}
async function loadInspection() {
  if (!courseId.value) return
  iw.value = undefined; teamId.value = undefined
  try { iTeams.value = await getInspectionTeams(courseId.value); if (iTeams.value.length) { teamId.value = iTeams.value[0]!.id; await loadInspectionWorkspace() } } catch { failed.value = true }
}
async function loadInspectionWorkspace() {
  if (!teamId.value) return
  try { iw.value = await getInspectionWorkspace(teamId.value) } catch { failed.value = true }
}
function onCourseChange() { loadReview(); loadInspection() }

type Dialog = '' | 'round' | 'extend' | 'task' | 'submit' | 'appeal' | 'approve' | 'reject' | 'inspect'
const dialog = ref<Dialog>(''); const targetRound = ref<ReviewRound>(); const targetTask = ref<MyTask>()
const form = reactive({
  name: '', deadline: '', reason: '', resolution: '', conclusion: '', feedback: '', comment: '', score: 80,
  rectificationRequired: false, reported: false, reviewerId: undefined as number | undefined, bindingId: undefined as number | undefined,
  versionId: undefined as number | undefined, taskId: undefined as number | undefined, appealId: undefined as number | undefined, parentId: undefined as number | undefined
})
const titles: Record<Dialog, string> = { '': '', round: '创建互评批次', extend: '延长互评截止时间', task: '手动添加互评任务', submit: '填写并提交互评', appeal: '申请复核', approve: '同意复核并安排重评', reject: '驳回复核申请', inspect: '记录检查结论' }

function openRound() { Object.assign(form, { name: '', deadline: bjt(Date.now() + 7 * 24 * 3600 * 1000) }); dialog.value = 'round' }
function openExtend(r: ReviewRound) { targetRound.value = r; Object.assign(form, { deadline: bjt(Math.max(Date.now(), Date.parse(r.deadline)) + 24 * 3600 * 1000) }); dialog.value = 'extend' }
function openTask(r: ReviewRound) { targetRound.value = r; Object.assign(form, { reviewerId: undefined, bindingId: undefined }); dialog.value = 'task' }
function openSubmit(t: MyTask) { targetTask.value = t; Object.assign(form, { score: 80, comment: '' }); dialog.value = 'submit' }
function openAppeal() { Object.assign(form, { reason: '' }); dialog.value = 'appeal' }
function openApprove(a: Appeal) { Object.assign(form, { reviewerId: undefined, resolution: '', appealId: a.id }); dialog.value = 'approve' }
function openReject(a: Appeal) { Object.assign(form, { resolution: '', appealId: a.id }); dialog.value = 'reject' }
function openInspect(parentId?: number) { Object.assign(form, { versionId: iw.value?.binding[0]?.version_id ?? undefined, conclusion: '', feedback: '', rectificationRequired: false, reported: false, parentId }); dialog.value = 'inspect' }

async function doRound() {
  if (!projectId.value || busy.value) return
  if (!form.name.trim() || !form.deadline) { ElMessage.warning('请填写批次名称与互评截止时间'); return }
  busy.value = true
  try { await createRound(projectId.value, form.name.trim(), `${form.deadline}+08:00`); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('互评批次已创建') }
  catch (e) { if (e instanceof ApiError && e.status === 409) await loadReviewWorkspace() } finally { busy.value = false }
}
async function doAssign(r: ReviewRound) {
  if (busy.value) return
  try { await ElMessageBox.confirm('为课程内每名学生分配最多2个其他团队的互评任务，禁止自评。', '自动分配互评'); busy.value = true; const res = await autoAssign(r.id); await loadReviewWorkspace(); ElMessage.success(`已分配 ${res.created} 项互评任务`) }
  catch (e) { if (e instanceof ApiError && e.status === 409) await loadReviewWorkspace() } finally { busy.value = false }
}
async function doClose(r: ReviewRound) {
  if (busy.value) return
  try { await ElMessageBox.confirm('关闭后待填写的互评任务将被取消，请确认。', '关闭互评批次', { type: 'warning' }); busy.value = true; await closeRound(r.id); await loadReviewWorkspace(); ElMessage.success('批次已关闭') }
  catch (e) { if (e instanceof ApiError && e.status === 409) await loadReviewWorkspace() } finally { busy.value = false }
}
async function doExtend() {
  if (!targetRound.value || busy.value || !form.deadline) { ElMessage.warning('请填写新的截止时间'); return }
  busy.value = true
  try { await extendRound(targetRound.value.id, `${form.deadline}+08:00`); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('截止时间已延长') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await loadReviewWorkspace() } } finally { busy.value = false }
}
async function doFeedback(r: ReviewRound) {
  if (busy.value) return
  busy.value = true
  try { await toggleFeedback(r.id, !r.feedback_open); await loadReviewWorkspace(); ElMessage.success(r.feedback_open ? '已关闭互评反馈展示' : '已开放互评反馈展示') }
  catch (e) { if (e instanceof ApiError && e.status === 409) await loadReviewWorkspace() } finally { busy.value = false }
}
async function doTask() {
  if (!targetRound.value || busy.value) return
  if (form.reviewerId == null || form.bindingId == null) { ElMessage.warning('请选择评审人与被评版本'); return }
  busy.value = true
  try { await addReviewTask(targetRound.value.id, form.reviewerId, form.bindingId); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('互评任务已添加') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await loadReviewWorkspace() } } finally { busy.value = false }
}
async function doCancelTask(t: MyTask) {
  if (busy.value) return
  try { await ElMessageBox.confirm('取消该互评任务后，评审人将失去对被评版本的读取授权。', '取消互评任务', { type: 'warning' }); busy.value = true; await cancelTask(t.id); await loadReviewWorkspace() }
  catch (e) { if (e instanceof ApiError && e.status === 409) await loadReviewWorkspace() } finally { busy.value = false }
}
async function doSubmit() {
  if (!targetTask.value || busy.value) return
  if (!form.comment.trim()) { ElMessage.warning('请填写评语'); return }
  if (form.score < 0 || form.score > 100) { ElMessage.warning('分数须在0到100之间'); return }
  busy.value = true
  try { await submitReview(targetTask.value.id, form.score, form.comment.trim()); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('互评已提交') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await loadReviewWorkspace() } } finally { busy.value = false }
}
async function doAppeal() {
  if (!rw.value || busy.value) return
  if (!form.reason.trim()) { ElMessage.warning('请填写复核原因'); return }
  busy.value = true
  try { await appealReview(rw.value.bindings[0]!.binding_id, form.reason.trim()); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('复核申请已提交') }
  catch (e) { if (e instanceof ApiError && e.status === 409) await loadReviewWorkspace() } finally { busy.value = false }
}
async function doApprove() {
  if (busy.value || form.appealId == null) return
  if (form.reviewerId == null || !form.resolution.trim()) { ElMessage.warning('请选择重评人并填写处理意见'); return }
  busy.value = true
  try { await approveAppeal(form.appealId, form.reviewerId, form.resolution.trim()); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('已同意复核并安排重评') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await loadReviewWorkspace() } } finally { busy.value = false }
}
async function doReject() {
  if (busy.value || form.appealId == null || !form.resolution.trim()) { ElMessage.warning('请填写处理意见'); return }
  busy.value = true
  try { await rejectAppeal(form.appealId, form.resolution.trim()); dialog.value = ''; await loadReviewWorkspace(); ElMessage.success('已驳回复核申请') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await loadReviewWorkspace() } } finally { busy.value = false }
}
async function doInspect() {
  if (!teamId.value || busy.value || form.versionId == null) { ElMessage.warning('请选择要检查的具体版本'); return }
  if (!form.conclusion.trim()) { ElMessage.warning('请填写检查结论'); return }
  if ((form.rectificationRequired || form.reported) && !form.feedback.trim()) { ElMessage.warning('要求整改或上报问题时须填写问题与反馈'); return }
  busy.value = true
  try { await createInspection(teamId.value, { versionId: form.versionId, conclusion: form.conclusion.trim(), feedback: form.feedback.trim() || null, rectificationRequired: form.rectificationRequired, reported: form.reported, parentId: form.parentId }); dialog.value = ''; await loadInspectionWorkspace(); ElMessage.success('检查结论已记录') }
  catch (e) { if (e instanceof ApiError && e.status === 409) { dialog.value = ''; await loadInspectionWorkspace() } } finally { busy.value = false }
}

const versionOpen = ref(false); const versionDetail = ref<VersionDetail>()
async function showEvaluated(t: MyTask) { try { versionDetail.value = await getVersion(t.team_id, t.version_id); versionOpen.value = true } catch { ElMessage.error('暂无法读取该被评成果') } }
const bytes = (n: number | null | undefined) => `${((n || 0) / 1024 / 1024).toFixed(2)} MB`
</script>

<template>
  <section class="page-heading"><div><span class="eyebrow">REVIEW & INSPECTION</span><h1>检查与互评</h1><p class="muted">助教过程检查与整改复查，教师分配互评并处理复核重评。</p></div><el-button :loading="loading" :disabled="busy || loading" @click="loadReview(); loadInspection()">刷新</el-button></section>
  <div class="submission-selectors"><label>课程 <el-select v-model="courseId" :disabled="busy || loading" @change="onCourseChange"><el-option v-for="c in courses" :key="c.id" :value="c.id" :label="c.name"/></el-select></label></div>
  <el-alert v-if="failed" type="error" title="加载失败，请检查服务连接并重试" :closable="false"/>
  <el-tabs v-model="tab" class="review-tabs">
    <!-- ============ 同伴互评 ============ -->
    <el-tab-pane label="同伴互评" name="review">
      <div class="submission-selectors"><label>项目 <el-select v-model="projectId" :disabled="busy || loading || !rProjects.length" @change="loadReviewWorkspace"><el-option v-for="p in rProjects" :key="p.id" :value="p.id" :label="`${p.name} · ${p.bindingCount} 个被评版本`"/></el-select></label><el-button v-if="rw?.isTeacher" type="primary" :disabled="busy || loading" @click="openRound">创建互评批次</el-button></div>
      <el-empty v-if="!rw" description="暂无可用项目"/>
      <template v-else>
        <el-alert v-if="rw.project.demo_fixture" type="info" :closable="false" title="当前为演示项目，已为三个团队准备好被评版本，可直接创建批次并自动分配。"/>
        <!-- 学生：我的互评待办 -->
        <template v-if="rw.isStudent">
          <section class="submission-panel" v-if="rw.myTasks.length">
            <h3>我的互评待办（{{ rw.myTasks.filter(t => t.status === 'PENDING').length }} 项待填写）</h3>
            <p class="muted">评审固定被评版本，禁止自评；提交后不可修改。点击“查看成果”可读取对方团队材料。</p>
            <el-table :data="rw.myTasks" stripe>
              <el-table-column label="被评团队 / 版本" min-width="200"><template #default="{ row }"><strong>{{ row.team_name }}</strong><p class="muted">{{ row.iteration_name }} · v{{ row.version_no }} · #{{ row.version_id }}</p></template></el-table-column>
              <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'SUBMITTED' ? 'success' : row.status === 'CANCELLED' ? 'info' : 'warning'" size="small">{{ taskStatus[row.status] }}{{ row.reevaluation ? ' · 重评' : '' }}</el-tag></template></el-table-column>
              <el-table-column label="截止" min-width="160"><template #default="{ row }">{{ stamp(row.deadline) }}</template></el-table-column>
              <el-table-column label="我的评分 / 评语" min-width="200"><template #default="{ row }"><template v-if="row.status === 'SUBMITTED'"><strong>{{ row.submitted_score }} 分</strong><p class="muted">{{ row.submitted_comment }}</p></template><span v-else class="muted">尚未提交</span></template></el-table-column>
              <el-table-column label="操作" width="210"><template #default="{ row }"><div class="row-actions"><el-button link type="primary" @click="showEvaluated(row)">查看成果</el-button><el-button v-if="row.status === 'PENDING'" link type="primary" :disabled="busy || loading" @click="openSubmit(row)">填写互评</el-button></div></template></el-table-column>
            </el-table>
          </section>
          <el-empty v-else description="暂无分配给你的互评任务"/>
          <!-- 学生：收到的互评反馈（匿名） -->
          <section class="submission-panel section-gap">
            <h3>本团队收到的互评反馈</h3>
            <p class="muted">互评人身份对学生隐藏；教师开放反馈后，此处展示匿名评分与评语。</p>
            <el-empty v-if="!rw.receivedReviews.length" description="暂无已开放的互评反馈"/>
            <article v-for="r in rw.receivedReviews" :key="r.id" class="record-card"><strong>{{ r.score }} 分 · {{ r.round_name }}</strong><p>{{ r.comment }}</p><p class="muted">{{ stamp(r.submitted_at) }}</p></article>
            <el-button v-if="rw.bindings.length" type="primary" plain :disabled="busy || loading" @click="openAppeal">申请复核</el-button>
            <h3 class="section-gap">复核申请记录</h3>
            <el-table :data="rw.appeals" empty-text="暂无复核申请" stripe><el-table-column label="原因" min-width="220" prop="reason"/><el-table-column label="状态" width="140"><template #default="{ row }"><el-tag :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'" size="small">{{ appealStatus[row.status] }}</el-tag></template></el-table-column><el-table-column label="处理意见" min-width="180"><template #default="{ row }">{{ row.resolution || '—' }}</template></el-table-column><el-table-column label="申请时间" min-width="160"><template #default="{ row }">{{ stamp(row.requested_at) }}</template></el-table-column></el-table>
          </section>
        </template>
        <!-- 教师：分配、进度、复核 -->
        <template v-else-if="rw.isTeacher">
          <el-alert v-if="!rw.bindings.length" type="warning" :closable="false" title="暂无已确认的被评版本。请先到“成果与版本”模块确认各团队被评版本，再创建互评批次。"/>
          <section class="submission-panel">
            <h3>互评批次</h3>
            <el-empty v-if="!rw.rounds.length" description="尚未创建互评批次"/>
            <article v-for="r in rw.rounds" :key="r.id" class="record-card">
              <div class="submission-bar"><strong>#{{ r.id }} · {{ r.name }}</strong><div><el-tag size="small">{{ roundStatus[r.status] }}</el-tag><el-tag size="small" :type="r.feedback_open ? 'success' : 'info'">{{ r.feedback_open ? '反馈已开放' : '反馈未开放' }}</el-tag></div></div>
              <p class="muted">截止 {{ stamp(r.deadline) }}（北京时间） · 已提交 {{ r.submitted_count }} / 共 {{ r.task_count }} 项</p>
              <div class="submission-actions" v-if="r.status === 'OPEN'">
                <el-button size="small" :disabled="busy || loading" @click="doAssign(r)">自动分配（每人2个其他团队）</el-button>
                <el-button size="small" :disabled="busy || loading" @click="openTask(r)">手动添加任务</el-button>
                <el-button size="small" :disabled="busy || loading" @click="openExtend(r)">延长截止</el-button>
                <el-button size="small" :disabled="busy || loading" @click="doFeedback(r)">{{ r.feedback_open ? '关闭反馈' : '开放反馈' }}</el-button>
                <el-button size="small" type="danger" plain :disabled="busy || loading" @click="doClose(r)">关闭批次</el-button>
              </div>
              <el-table v-if="rw.progress?.some(p => p.round_id === r.id)" :data="rw.progress.filter(p => p.round_id === r.id)" size="small" stripe style="margin-top:12px">
                <el-table-column label="评审人" width="120" prop="reviewer_name"/>
                <el-table-column label="被评团队" min-width="160" prop="team_name"/>
                <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'SUBMITTED' ? 'success' : row.status === 'CANCELLED' ? 'info' : 'warning'" size="small">{{ taskStatus[row.status] }}{{ row.reevaluation ? ' · 重评' : '' }}</el-tag></template></el-table-column>
                <el-table-column label="评分 / 评语" min-width="200"><template #default="{ row }"><template v-if="row.status === 'SUBMITTED'"><strong>{{ row.score }} 分</strong><p class="muted">{{ row.comment }}</p></template></template></el-table-column>
                <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.status === 'PENDING'" link type="danger" @click="doCancelTask(row)">取消</el-button></template></el-table-column>
              </el-table>
            </article>
          </section>
          <section class="submission-panel section-gap">
            <h3>复核申请</h3>
            <el-table :data="rw.appeals" empty-text="暂无复核申请" stripe>
              <el-table-column label="被评团队" min-width="150" prop="team_name"/>
              <el-table-column label="申请原因" min-width="220" prop="reason"/>
              <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'" size="small">{{ appealStatus[row.status] }}</el-tag></template></el-table-column>
              <el-table-column label="处理意见" min-width="180"><template #default="{ row }">{{ row.resolution || '—' }}</template></el-table-column>
              <el-table-column v-if="rw.appeals.some(a => a.status === 'PENDING')" label="操作" width="140"><template #default="{ row }"><template v-if="row.status === 'PENDING'"><el-button link type="primary" :disabled="busy || loading" @click="openApprove(row)">同意重评</el-button><el-button link :disabled="busy || loading" @click="openReject(row)">驳回</el-button></template></template></el-table-column>
            </el-table>
          </section>
        </template>
        <!-- 助教：只读 -->
        <section v-else class="submission-panel"><h3>被评版本与互评批次</h3><p class="muted">助教只读查看；过程检查请使用“助教检查”页签。</p><article v-for="b in rw.bindings" :key="b.binding_id" class="record-card"><strong>{{ b.team_name }} · {{ b.iteration_name }} · v{{ b.version_no }}</strong><p class="muted">{{ b.summary }}</p></article></section>
      </template>
    </el-tab-pane>

    <!-- ============ 助教检查 ============ -->
    <el-tab-pane label="助教检查" name="inspection">
      <div class="submission-selectors"><label>团队 <el-select v-model="teamId" :disabled="busy || loading || !iTeams.length" @change="loadInspectionWorkspace"><el-option v-for="t in iTeams" :key="t.id" :value="t.id" :label="`${t.project_name} / ${t.name}`"/></el-select></label><el-button v-if="iw?.isTA" type="primary" :disabled="busy || loading || !iw?.versions.length" @click="openInspect()">新建检查</el-button></div>
      <el-empty v-if="!iw" description="暂无可检查的团队"/>
      <template v-else>
        <el-alert v-if="iw.team && iw.project.demo_fixture" type="info" :closable="false" title="演示团队。助教可针对具体版本记录检查结论与整改反馈。"/>
        <div class="submission-header" v-if="iw.team"><h2>{{ iw.team.name }}</h2><p class="muted">{{ iw.project.name }} · {{ projectStatusNames[iw.project.status] }}</p></div>
        <p v-if="iw.isTeacher" class="muted submission-note">教师可查看助教检查与问题上报，评分请使用“评分与成绩”模块。</p>
        <p v-else-if="iw.isStudent" class="muted submission-note">你可查看本团队收到的检查结论与整改反馈；整改材料由负责人正式重交。</p>
        <section class="submission-panel">
          <h3>检查记录</h3>
          <el-empty v-if="!iw.inspections.length" description="暂无检查记录"/>
          <article v-for="i in iw.inspections" :key="i.id" class="record-card">
            <div class="submission-bar"><strong>检查 #{{ i.id }} · v{{ iw.versions.find(v => v.id === i.version_id)?.version_no || i.version_id }}<span v-if="i.parent_id" class="muted">（复查自 #{{ i.parent_id }}）</span></strong><div><el-tag v-if="i.rectification_required" type="warning" size="small">要求整改</el-tag><el-tag v-if="i.reported" type="danger" size="small">问题上报</el-tag></div></div>
            <p><strong>结论：</strong>{{ i.conclusion }}</p>
            <p v-if="i.feedback"><strong>问题与整改反馈：</strong>{{ i.feedback }}</p>
            <p class="muted">{{ i.checker_name }} · {{ stamp(i.checked_at) }}</p>
            <el-button v-if="iw.isTA && i.rectification_required" link type="primary" :disabled="busy || loading" @click="openInspect(i.id)">针对新版复查</el-button>
          </article>
        </section>
      </template>
    </el-tab-pane>
  </el-tabs>

  <!-- 互评提交 -->
  <el-dialog :model-value="!!dialog" :title="titles[dialog] || ''" width="min(600px,94vw)" :close-on-click-modal="false" :show-close="!busy" @close="dialog=''">
    <el-form label-position="top">
      <template v-if="dialog==='round'"><el-form-item label="批次名称" required><el-input v-model="form.name" maxlength="160"/></el-form-item><el-form-item label="互评截止时间（北京时间）" required><input v-model="form.deadline" class="native-date" type="datetime-local" step="1"></el-form-item></template>
      <template v-if="dialog==='extend'"><el-alert type="info" :closable="false" title="新截止时间须晚于当前截止时间；不改变已完成评分。"/><el-form-item label="新截止时间（北京时间）" required><input v-model="form.deadline" class="native-date" type="datetime-local" step="1"></el-form-item></template>
      <template v-if="dialog==='task'"><el-form-item label="评审人" required><el-select v-model="form.reviewerId" placeholder="选择学生"><el-option v-for="s in rw?.students || []" :key="s.id" :value="s.id" :label="`${s.display_name}（${s.username}）`"/></el-select></el-form-item><el-form-item label="被评版本" required><el-select v-model="form.bindingId" placeholder="选择被评团队版本"><el-option v-for="b in rw?.bindings || []" :key="b.binding_id" :value="b.binding_id" :label="`${b.team_name} · v${b.version_no}`"/></el-select></el-form-item></template>
      <template v-if="dialog==='submit'"><el-alert type="info" :closable="false" title="评审固定成果及标准版本，禁止自评；提交后不可修改。评语对学生匿名展示。"/><p><strong>{{ targetTask?.team_name }} · {{ targetTask?.iteration_name }} · v{{ targetTask?.version_no }}</strong></p><el-form-item label="评分（0-100）" required><el-input-number v-model="form.score" :min="0" :max="100"/></el-form-item><el-form-item label="评语（必填）" required><el-input v-model="form.comment" type="textarea" :rows="4" maxlength="2000"/></el-form-item></template>
      <template v-if="dialog==='appeal'"><el-alert type="info" :closable="false" title="对本人团队收到的互评结果申请复核，教师决定是否重评并保留历史。"/><el-form-item label="复核原因" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="2000"/></el-form-item></template>
      <template v-if="dialog==='approve'"><el-alert type="info" :closable="false" title="同意后新增一次重评任务，原互评结果保留。"/><el-form-item label="重评人" required><el-select v-model="form.reviewerId" placeholder="选择学生"><el-option v-for="s in rw?.students || []" :key="s.id" :value="s.id" :label="`${s.display_name}（${s.username}）`"/></el-select></el-form-item><el-form-item label="处理意见" required><el-input v-model="form.resolution" type="textarea" :rows="3" maxlength="2000"/></el-form-item></template>
      <template v-if="dialog==='reject'"><el-form-item label="处理意见" required><el-input v-model="form.resolution" type="textarea" :rows="3" maxlength="2000"/></el-form-item></template>
      <template v-if="dialog==='inspect'"><el-alert type="info" :closable="false" title="检查关联具体版本；要求整改或上报问题须填写反馈。整改后由负责人正式重交新版，再进行复查。"/><el-form-item label="检查的具体版本" required><el-select v-model="form.versionId"><el-option v-for="v in iw?.versions || []" :key="v.id" :value="v.id" :label="`${v.iteration_name} · v${v.version_no}（#${v.id}）`"/></el-select></el-form-item><el-form-item label="检查结论" required><el-input v-model="form.conclusion" type="textarea" :rows="3" maxlength="2000"/></el-form-item><el-form-item label="问题与整改反馈"><el-input v-model="form.feedback" type="textarea" :rows="3" maxlength="2000"/></el-form-item><el-form-item><el-checkbox v-model="form.rectificationRequired">需要整改（负责人需重交新版）</el-checkbox><el-checkbox v-model="form.reported">缺交 / 迟交 / 问题上报教师</el-checkbox></el-form-item></template>
    </el-form>
    <template #footer>
      <el-button :disabled="busy || loading" @click="dialog=''">取消</el-button>
      <el-button type="primary" :loading="busy" @click="dialog==='round' ? doRound() : dialog==='extend' ? doExtend() : dialog==='task' ? doTask() : dialog==='submit' ? doSubmit() : dialog==='appeal' ? doAppeal() : dialog==='approve' ? doApprove() : dialog==='reject' ? doReject() : doInspect()">确认</el-button>
    </template>
  </el-dialog>

  <!-- 被评成果详情 -->
  <el-dialog v-model="versionOpen" title="被评成果版本" width="min(720px,94vw)">
    <template v-if="versionDetail"><div class="receipt"><strong>{{ versionDetail.receipt_code }}</strong><p>版本：v{{ versionDetail.version_no }} · {{ stamp(versionDetail.accepted_at) }}</p><p>提交人：{{ versionDetail.submitter_name }}</p><p>成果说明：{{ versionDetail.summary }}</p></div><article v-for="m in versionDetail.materials" :key="m.id" class="record-card"><strong>{{ m.title }}</strong><p v-if="m.kind==='FILE'" class="muted">{{ bytes(m.size_bytes) }}</p><a v-if="m.kind==='FILE'" :href="`/api/submissions/teams/${targetTask?.team_id}/versions/${versionDetail.id}/files/${m.id}`" class="download">下载文件</a><a v-else :href="m.url || undefined" target="_blank" rel="noopener noreferrer" class="download">打开HTTPS链接</a></article></template>
  </el-dialog>
</template>

<style scoped>
.submission-selectors,.submission-header,.submission-bar,.submission-actions{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.submission-selectors{justify-content:flex-start;margin:16px 0}.submission-selectors label{display:flex;gap:10px;align-items:center}.submission-selectors .el-select{width:300px}.submission-header{margin:18px 0}.review-tabs{margin-top:8px}.submission-panel{border:1px solid #e3e9ee;background:white;border-radius:12px;padding:22px;margin-top:8px}.submission-panel h3{margin-bottom:10px}.submission-bar{margin-bottom:14px}.submission-actions{justify-content:flex-start;margin:12px 0}.row-actions{display:flex;flex-direction:column;align-items:flex-start;gap:4px}.row-actions .el-button{margin:0}.record-card{border:1px solid #e3e9ee;border-radius:8px;padding:16px;margin:15px 0;overflow-wrap:anywhere}.record-card p,.receipt p{font-size:13px;line-height:1.9;white-space:pre-wrap}.muted{color:#7b888e}.el-table p{font-size:12px;line-height:1.8;white-space:pre-wrap}.native-date{width:100%;border:1px solid #dcdfe6;border-radius:6px;padding:10px;color:#344054}.section-gap{margin-top:24px}.download{color:var(--el-color-primary);font-size:13px;margin-right:12px}.receipt{background:#f1f7f5;padding:18px;border-radius:8px;overflow-wrap:anywhere;margin-bottom:14px}.receipt strong{display:block;margin-bottom:10px}.submission-note{margin:12px 0}.el-select{width:100%}@media(max-width:600px){.submission-selectors .el-select{width:220px}.submission-panel{padding:12px}}
</style>
