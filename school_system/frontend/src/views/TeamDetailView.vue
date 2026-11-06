<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '../api/http'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, User, List } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth } from '../stores/auth'
import { getTeams, getTeam, applyTeam, approveMember, rejectMember, leaveTeam, removeMember, transferLeadership, transferMember, decideExit, assignStudent, getAssignmentCandidates, type AssignmentCandidate, type Team } from '../api/teams'

const route = useRoute(); const router = useRouter()
const courseId = Number(route.params.courseId)
const projectId = Number(route.params.projectId)
const teamId = Number(route.params.teamId)
const team = ref<Team | null>(null)
const assistants = ref<{ id: number; display_name: string; granted: boolean }[]>([])
const scopePath = `/inspections/teams/${teamId}/assistant-scope`
const scopeOpen = ref(false)
async function showScope() {
  try { assistants.value = await request(scopePath); scopeOpen.value = true } catch {}
}
async function setScope(id: number, granted: boolean) {
  try {
    await request(`${scopePath}/${id}`, {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({granted})})
    await showScope()
  } catch {}
}
const allTeams = ref<Team[]>([])
const loading = ref(true); const failed = ref(false)
const acting = ref(false)

const transferMemberId = ref<number | null>(null)
const transferTargetId = ref<number | null>(null)
const showTransfer = ref(false)

onMounted(load)

async function load() {
  loading.value = true; failed.value = false
  try {
    team.value = await getTeam(courseId, projectId, teamId)
    allTeams.value = await getTeams(courseId, projectId)
  } catch { failed.value = true } finally { loading.value = false }
}

const me = () => auth.user?.id
const isLeader = () => team.value?.leaderId === me()
const isMember = () => team.value?.members.some(m => m.userId === me())
const isPending = () => team.value?.applications.some(m => m.userId === me())

async function run(fn: () => Promise<Team>) {
  acting.value = true
  try { team.value = await fn() } catch {} finally { acting.value = false }
}

function apply() { run(() => applyTeam(courseId, projectId, teamId)) }
function quit() {
  ElMessageBox.confirm('提交退出申请后，审批通过前仍是团队成员。', '申请退出', { type: 'warning' })
    .then(() => run(() => leaveTeam(courseId, projectId, teamId))).catch(() => {})
}
function exitDecision(memberId: number, approved: boolean) {
  void run(() => decideExit(courseId, projectId, teamId, memberId, approved))
}
const assignmentOpen = ref(false)
const assignmentLoading = ref(false)
const candidates = ref<AssignmentCandidate[]>([])
const studentId = ref<number>()
async function assign() {
  assignmentOpen.value = true
  assignmentLoading.value = true
  candidates.value = []
  studentId.value = undefined
  try { candidates.value = await getAssignmentCandidates(courseId, projectId, teamId) }
  catch { assignmentOpen.value = false }
  finally { assignmentLoading.value = false }
}
async function confirmAssignment() {
  if (studentId.value == null || acting.value) return
  acting.value = true
  try {
    team.value = await assignStudent(courseId, projectId, teamId, studentId.value)
    assignmentOpen.value = false
    ElMessage.success('学生已加入团队')
  } catch {} finally { acting.value = false }
}
function approve(memberId: number) { run(() => approveMember(courseId, projectId, teamId, memberId)) }
function reject(memberId: number) { run(() => rejectMember(courseId, projectId, teamId, memberId)) }
function remove(memberId: number) {
  ElMessageBox.confirm('确认将该成员移出团队？', '移出成员', { type: 'warning' })
    .then(() => run(() => removeMember(courseId, projectId, teamId, memberId))).catch(() => {})
}
function handover(memberId: number) {
  ElMessageBox.confirm('确认将负责人交接给该成员？交接后你将失去管理权限。', '负责人交接', { type: 'warning' })
    .then(() => run(() => transferLeadership(courseId, projectId, teamId, memberId))).catch(() => {})
}
function openTransfer(memberId: number) { transferMemberId.value = memberId; transferTargetId.value = null; showTransfer.value = true }
function doTransfer() {
  if (transferMemberId.value == null || transferTargetId.value == null) { ElMessage.warning('请选择目标团队'); return }
  const mid = transferMemberId.value; const target = transferTargetId.value
  showTransfer.value = false
  run(() => transferMember(courseId, projectId, teamId, mid, target))
}

const statusText = (): string => team.value ? (team.value.full ? '已满员' : team.value.complete ? '组队完成' : '组队中') : ''
</script>

<template>
  <el-button text @click="router.push(`/courses/${courseId}/projects/${projectId}/teams`)"><ArrowLeft class="button-icon"/>返回团队列表</el-button>
  <div v-if="loading" class="state-panel">正在加载团队…</div>
  <el-empty v-else-if="failed || !team" description="无法访问该团队，请返回团队列表"/>

  <template v-else>
    <section class="page-heading detail-heading">
      <div><span class="eyebrow">TEAM DETAIL</span><h1>{{ team.name }}</h1><p class="muted">{{ team.activeCount ? '负责人' : '历史负责人（当前无有效成员）' }} {{ team.leaderName }} · {{ team.activeCount }}/{{ team.maxTeamSize }} 人 · 组队窗口 {{ team.minTeamSize }}-{{ team.maxTeamSize }} 人</p></div>
      <span class="project-status" :class="team.full ? 'closed' : team.complete ? 'published' : 'draft'">{{ statusText() }}</span>
    </section>

    <el-alert v-if="team.fixedCourseTeams" type="info" :closable="false" title="本课程采用固定团队。组队、邀请及选题请进入“组队与选题”；各项目不再单独调整成员。"/>
    <section class="team-next-actions"><div><h2>团队工作入口</h2><p class="muted">继续处理本团队的协作与成果，进入后保留当前项目和团队。</p></div><div><el-button type="primary" @click="router.push({path:'/modules/tasks',query:{courseId,projectId,teamId}})">迭代与任务</el-button><el-button @click="router.push({path:'/modules/submissions',query:{courseId,projectId,teamId}})">成果与版本</el-button><el-button @click="router.push({path:'/modules/reviews',query:{courseId,projectId,teamId,tab:'inspection'}})">检查与整改</el-button></div></section>
    <div class="detail-panel">
      <div class="detail-section">
        <h3><User/>团队成员（{{ team.members.length }}）</h3>
        <div class="member-list">
          <div v-for="m in team.members" :key="m.id" class="member-row">
            <div class="member-info">
              <span class="member-name">{{ m.displayName }}</span>
              <el-tag v-if="m.userId === team.leaderId" size="small" type="warning">负责人</el-tag>
            </div>
            <div v-if="team.canManage && m.userId !== team.leaderId" class="member-actions">
              <el-button text type="primary" size="small" :loading="acting" @click="handover(m.userId)">设为负责人</el-button>
              <template v-if="m.exitRequested">
                <el-button text type="primary" @click="exitDecision(m.id, true)">批准退出</el-button>
                <el-button text @click="exitDecision(m.id, false)">驳回退出</el-button>
              </template>
              <el-button v-if="team.myRole === 'TEACHER'" text type="danger" size="small" :loading="acting" @click="remove(m.id)">移出</el-button>
              <el-button v-if="team.myRole === 'TEACHER'" text size="small" :loading="acting" @click="openTransfer(m.id)">转组</el-button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="team.canManage && team.applications.length" class="detail-section">
        <h3><List/>待审批申请（{{ team.applications.length }}）</h3>
        <div class="member-list">
          <div v-for="a in team.applications" :key="a.id" class="member-row">
            <span class="member-name">{{ a.displayName }}</span>
            <div class="member-actions">
              <el-button text type="primary" size="small" :loading="acting" @click="approve(a.id)">通过</el-button>
              <el-button text type="danger" size="small" :loading="acting" @click="reject(a.id)">拒绝</el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="detail-actions">
        <el-button v-if="team.myRole === 'STUDENT' && team.groupingOpen && !isMember() && !isPending() && !team.full" type="primary" :loading="acting" @click="apply">申请加入</el-button>
        <el-tag v-else-if="isPending()" type="info">申请已提交，等待负责人审批</el-tag>
        <el-button v-if="team.myRole === 'TEACHER' && !team.fixedCourseTeams && !team.full" @click="assign">分配学生</el-button>
        <el-tag v-if="team.members.some(m => m.userId === me() && m.exitRequested)">退出申请待处理</el-tag>
        <el-button v-else-if="team.groupingOpen && isMember() && !isLeader()" type="warning" :loading="acting" @click="quit">申请退出</el-button>
      </div>
    </div>
    <el-button v-if="team.myRole === 'TEACHER'" @click="showScope">助教检查授权</el-button>
    <el-dialog v-model="scopeOpen" title="本团队的助教授权">
      <el-table :data="assistants" empty-text="本课程暂无助教">
        <el-table-column prop="display_name" label="助教"/>
        <el-table-column label="授权"><template #default="{row}">
          <el-button @click="setScope(row.id,!row.granted)">{{ row.granted ? '撤销授权' : '授予检查权限' }}</el-button>
        </template></el-table-column>
      </el-table>
    </el-dialog>
  </template>

  <el-dialog v-model="assignmentOpen" title="分配学生" width="min(480px, 92vw)">
    <p class="muted">按姓名或登录账号搜索本课程可分配的学生。已加入其他团队的学生请使用转组功能。</p>
    <el-select v-model="studentId" filterable :loading="assignmentLoading" placeholder="选择学生"
               no-data-text="当前没有可分配的学生" style="width: 100%">
      <el-option v-for="student in candidates" :key="student.id" :value="student.id"
                 :label="`${student.display_name}（${student.username}）`" />
    </el-select>
    <template #footer>
      <el-button @click="assignmentOpen = false">取消</el-button>
      <el-button type="primary" :loading="acting" :disabled="studentId == null" @click="confirmAssignment">确认分配</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="showTransfer" title="成员转组" width="min(420px, 92vw)" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="目标团队" required>
        <el-select v-model="transferTargetId" placeholder="选择要转入的团队" style="width: 100%">
          <el-option v-for="t in allTeams.filter(x => x.id !== teamId)" :key="t.id" :label="`${t.name}（${t.activeCount}/${t.maxTeamSize}）`" :value="t.id" />
        </el-select>
      </el-form-item>
      <div class="form-actions">
        <el-button @click="showTransfer = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doTransfer">确认转组</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
