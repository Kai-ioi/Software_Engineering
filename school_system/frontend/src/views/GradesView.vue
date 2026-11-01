<script setup lang="ts">
import { computed, onMounted, ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElInputNumber } from 'element-plus'
import { getCourses, type Course } from '../api/courses'
import { getOverview, getTeamSheet, saveDraft, submit, modify, type Overview, type TeamSheet, type ScoreItem } from '../api/grades'
import { ApiError } from '../api/http'

const route = useRoute(); const router = useRouter()
const courses = ref<Course[]>([]); const courseId = ref<number>()
const overview = ref<Overview>(); const teamId = ref<number>()
const sheet = ref<TeamSheet>(); const scores = reactive<Record<number, number>>({})
const loading = ref(false); const busy = ref(false); const failed = ref(false)
const modifyOpen = ref(false); const reason = ref('')

const teacher = computed(() => overview.value?.role === 'TEACHER')
const total = computed(() => (overview.value?.criteria || []).reduce((s, c) => s + (scores[c.id] || 0), 0))
const fullScore = computed(() => (overview.value?.criteria || []).reduce((s, c) => s + c.max_score, 0) || 0)
const submitted = computed(() => sheet.value?.sheet?.status === 'SUBMITTED')
const stamp = (v?: string | null) => v ? new Date(v).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
const sheetStatusName = (s?: string | null) => s === 'SUBMITTED' ? '已提交' : s === 'DRAFT' ? '草稿' : '未评分'

async function loadOverview() {
  if (!courseId.value) return
  loading.value = true; failed.value = false; sheet.value = undefined; teamId.value = undefined
  try {
    overview.value = await getOverview(courseId.value)
    if (overview.value.teams.length) { teamId.value = overview.value.teams[0]!.id; await loadTeam() }
  } catch { failed.value = true } finally { loading.value = false }
}
async function loadTeam() {
  if (!teamId.value) return
  loading.value = true; failed.value = false
  try {
    sheet.value = await getTeamSheet(teamId.value)
    Object.keys(scores).forEach(k => delete scores[Number(k)])
    for (const s of sheet.value.scores) scores[s.criterion_id] = s.score
  } catch { failed.value = true } finally { loading.value = false }
}
async function changeTeam() { sheet.value = undefined; await loadTeam() }

function collect(): ScoreItem[] {
  return (overview.value?.criteria || []).map(c => ({ criterionId: c.id, score: scores[c.id] ?? 0 }))
}
async function save(submitMode: boolean) {
  if (!teamId.value || !overview.value || busy.value) return
  if (submitMode && overview.value.criteria.some(c => scores[c.id] === undefined)) {
    ElMessage.warning('请完成所有维度的评分后再提交'); return
  }
  busy.value = true
  try {
    if (submitMode) await submit(teamId.value, overview.value.rubric.id, collect())
    else await saveDraft(teamId.value, overview.value.rubric.id, collect())
    ElMessage.success(submitMode ? '评分已正式提交' : '草稿已保存')
    await loadTeam()
  } catch (e) { if (e instanceof ApiError && e.status === 409) await loadTeam() } finally { busy.value = false }
}
async function doModify() {
  if (!sheet.value?.sheet || !reason.value.trim()) { ElMessage.warning('请填写修改原因'); return }
  busy.value = true
  try {
    await modify(sheet.value.sheet.id, collect(), reason.value.trim())
    ElMessage.success('评分已修改并保留留痕记录')
    modifyOpen.value = false; reason.value = ''
    await loadTeam()
  } catch (e) { if (e instanceof ApiError && e.status === 409) await loadTeam() } finally { busy.value = false }
}

onMounted(async () => {
  try {
    courses.value = await getCourses()
    courseId.value = courses.value.find(c => c.id === Number(route.query.courseId))?.id || courses.value[0]?.id
    await loadOverview()
  } catch { failed.value = true }
})
</script>

<template>
  <section class="page-heading">
    <div><span class="eyebrow">TEACHER GRADING</span><h1>评分与成绩</h1><p class="muted">针对教师确认的被评版本，按维度评分、保存草稿、正式提交并留痕。</p></div>
    <el-button :loading="loading" :disabled="busy || loading" @click="loadOverview">刷新</el-button>
  </section>

  <div class="grade-selectors">
    <label>课程 <el-select v-model="courseId" aria-label="选择课程" :disabled="busy || loading" @change="loadOverview"><el-option v-for="c in courses" :key="c.id" :value="c.id" :label="c.name"/></el-select></label>
    <label v-if="teacher">团队 <el-select v-model="teamId" aria-label="选择团队" :disabled="busy || loading || !overview?.teams.length" @change="changeTeam"><el-option v-for="t in overview?.teams" :key="t.id" :value="t.id" :label="`${t.name} · ${sheetStatusName(t.sheet_status)}`"/></el-select></label>
  </div>

  <div v-if="loading && !overview" class="state-panel">正在加载评分工作空间…</div>
  <el-alert v-else-if="failed" type="error" title="加载失败，请检查服务连接并重试" :closable="false"/>
  <el-empty v-else-if="!overview" description="暂无可访问的课程"><el-button @click="router.push('/courses')">返回课程</el-button></el-empty>
  <el-alert v-else-if="!teacher" type="info" :closable="false" title="成绩尚未发布，发布后学生可在本页查询本人成绩。"/>
  <el-empty v-else-if="!overview.teams.length" description="暂无已确认被评版本的团队">
    <p class="muted">请先到「成果与版本」中确认各团队的被评成果版本。</p>
    <el-button @click="router.push({ path: '/modules/submissions', query: { courseId } })">前往成果与版本</el-button>
  </el-empty>

  <template v-else>
    <el-alert v-if="!sheet" type="info" :closable="false" title="请选择上方团队开始评分。"/>
    <template v-else>
      <div class="grade-header">
        <div><h2>{{ sheet.team.name }}</h2><p class="muted">{{ sheet.project.name }} · 评分标准：{{ sheet.rubric.name }}（v{{ sheet.rubric.version_no }}）</p></div>
      </div>

      <div class="grade-grid">
        <article class="grade-panel">
          <h3>被评成果版本</h3>
          <p class="muted">v{{ sheet.version.version_no }} · #{{ sheet.version.id }} · 提交人 {{ sheet.version.submitter_name }}</p>
          <p class="muted">确认时间 {{ stamp(sheet.binding.confirmed_at) }}</p>
          <p><strong>{{ sheet.version.summary }}</strong></p>
          <div v-if="sheet.materials.length" class="material-list">
            <div v-for="m in sheet.materials" :key="m.id" class="material-row">
              <span>{{ m.title }}</span>
              <a v-if="m.url" :href="m.url" target="_blank" rel="noopener noreferrer" class="download">打开链接</a>
              <span v-else class="muted">文件材质</span>
            </div>
          </div>
          <el-empty v-else description="该版本无材质" :image-size="60"/>
        </article>

        <article class="grade-panel">
          <h3>评分维度</h3>
          <div v-for="c in overview.criteria" :key="c.id" class="criterion-row">
            <div class="criterion-info"><strong>{{ c.name }}</strong><p class="muted">{{ c.description }}</p></div>
            <el-input-number v-model="scores[c.id]" :min="0" :max="c.max_score" :disabled="busy || loading || submitted" aria-label="维度得分"/>
            <span class="criterion-max">/ {{ c.max_score }}</span>
          </div>
          <div class="criterion-total">合计 <strong>{{ total }}</strong> / {{ fullScore }}</div>
          <div class="grade-actions">
            <el-button :disabled="busy || loading || submitted" @click="save(false)">保存草稿</el-button>
            <el-button type="primary" :loading="busy" :disabled="loading || submitted" @click="save(true)">正式提交</el-button>
            <el-button v-if="submitted" type="warning" :disabled="busy || loading" @click="modifyOpen = true">修改评分</el-button>
          </div>
          <el-alert v-if="submitted" type="success" :closable="false" :title="`已提交 · ${stamp(sheet.sheet?.submitted_at)}`"/>
          <el-alert v-if="sheet.sheet?.last_reason" type="info" :closable="false" :title="`最近修改原因：${sheet.sheet.last_reason}`"/>
        </article>
      </div>
    </template>
  </template>

  <el-dialog v-model="modifyOpen" title="修改已提交评分" width="min(520px, 92vw)" :close-on-click-modal="false">
    <p class="muted">修改已正式提交的评分须填写原因，系统会保留变更前后记录。</p>
    <el-input v-model="reason" type="textarea" :rows="3" maxlength="2000" placeholder="例如：核对后发现某维度评分有误，予以更正"/>
    <template #footer>
      <el-button :disabled="busy" @click="modifyOpen = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="doModify">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.grade-selectors, .grade-header { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.grade-selectors { justify-content: flex-start; margin: 20px 0; }
.grade-selectors label { display: flex; gap: 10px; align-items: center; }
.grade-selectors .el-select { width: 300px; }
.grade-header { margin: 24px 0 16px; }
.grade-grid { display: grid; grid-template-columns: 1fr 1.2fr; gap: 16px; }
.grade-panel { border: 1px solid #e3e9ee; background: white; border-radius: 12px; padding: 22px; }
.grade-panel h3 { margin-bottom: 14px; }
.grade-panel p { line-height: 1.8; }
.material-list { margin-top: 14px; }
.material-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid #eef2f5; }
.criterion-row { display: flex; align-items: center; gap: 14px; padding: 12px 0; border-bottom: 1px solid #eef2f5; }
.criterion-info { flex: 1; }
.criterion-info p { font-size: 12px; }
.criterion-max { color: #718189; }
.criterion-total { text-align: right; padding: 16px 0 8px; font-size: 16px; }
.grade-actions { display: flex; gap: 10px; margin: 14px 0; }
.download { color: var(--el-color-primary); font-size: 13px; }
@media (max-width: 900px) { .grade-grid { grid-template-columns: 1fr; } .grade-selectors .el-select { width: 220px; } }
</style>
