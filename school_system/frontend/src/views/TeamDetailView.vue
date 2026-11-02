<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, User, List } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth } from '../stores/auth'
import { getTeams, getTeam, applyTeam, approveMember, rejectMember, leaveTeam, removeMember, transferLeadership, transferMember, type Team } from '../api/teams'

const route = useRoute(); const router = useRouter()
const courseId = Number(route.params.courseId)
const projectId = Number(route.params.projectId)
const teamId = Number(route.params.teamId)
const team = ref<Team | null>(null)
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
  ElMessageBox.confirm('确认退出该团队？', '退出团队', { type: 'warning' })
    .then(() => run(() => leaveTeam(courseId, projectId, teamId))).catch(() => {})
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
      <div><span class="eyebrow">TEAM DETAIL</span><h1>{{ team.name }}</h1><p class="muted">负责人 {{ team.leaderName }} · {{ team.activeCount }}/{{ team.maxTeamSize }} 人 · 组队窗口 {{ team.minTeamSize }}-{{ team.maxTeamSize }} 人</p></div>
      <span class="project-status" :class="team.full ? 'closed' : team.complete ? 'published' : 'draft'">{{ statusText() }}</span>
    </section>

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
              <el-button text type="danger" size="small" :loading="acting" @click="remove(m.id)">移出</el-button>
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
        <el-button v-if="!isMember() && !isPending() && !team.full" type="primary" :loading="acting" @click="apply">申请加入</el-button>
        <el-tag v-else-if="isPending()" type="info">申请已提交，等待负责人审批</el-tag>
        <el-button v-if="isMember() && !isLeader()" type="warning" :loading="acting" @click="quit">退出团队</el-button>
      </div>
    </div>
  </template>

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
