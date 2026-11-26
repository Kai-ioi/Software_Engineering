<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '../api/http'
const roleLabels = { TEACHER: '教师', TA: '助教', STUDENT: '学生' }
interface Directory {
  users: {id: number; display_name: string; username: string; enabled: boolean}[]
  courses: {id: number; name: string; semester: string; status: string}[]
  failedNotices: {event_id: number; source: string; course_id: number; action: string; attempts: number}[]
  memberships: {course_id: number; user_id: number; role: keyof typeof roleLabels; active: boolean}[]
}
const data = ref<Directory>()
const busy = ref(false)
const account = reactive({ username: '', displayName: '', password: '' })
const course = reactive({ name: '', semester: '', description: '' })
const member = reactive({ courseId: undefined as number | undefined, userId: undefined as number | undefined, role: 'STUDENT', active: true })
const memberMode = ref<'batch' | 'single'>('batch')
const studentIds = ref<number[]>([])
const selectedCourse = computed(() => data.value?.courses.find(c => c.id === member.courseId))
const courseMembers = computed(() => data.value?.memberships.filter(m => m.course_id === member.courseId) ?? [])
const accountsById = computed(() => new Map(data.value?.users.map(user => [user.id, user])))
const memberById = computed(() => new Map(courseMembers.value.map(m => [m.user_id, m])))
const enabledUsers = computed(() => data.value?.users.filter(user => user.enabled) ?? [])
const eligibleStudents = computed(() => enabledUsers.value.filter(user => {
  const existing = memberById.value.get(user.id)
  return !existing || (existing.role === 'STUDENT' && !existing.active)
}))
const canSaveMembers = computed(() => selectedCourse.value?.status === 'ACTIVE' &&
  (memberMode.value === 'batch' ? studentIds.value.length > 0 : member.userId != null))
watch(() => member.courseId, () => {
  studentIds.value = []
  member.userId = undefined
})
async function load() { try { data.value = await request('/platform/directory') } catch {} }
async function save(section: string, body: unknown) {
  busy.value = true
  try {
    const result = await request<{added: number} | null>(`/platform/directory/${section}`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) })
    if (section === 'accounts') Object.assign(account, { username: '', displayName: '', password: '' })
    if (section === 'courses') Object.assign(course, { name: '', semester: '', description: '' })
    if (section === 'memberships/students/batch' && result) {
      studentIds.value = []
      ElMessage.success(result.added ? `已加入 ${result.added} 名学生` : '所选学生已在本课程中')
    } else {
      ElMessage.success('已保存')
    }
    await load()
  } catch {} finally { busy.value = false }
}
async function saveMembers() {
  if (busy.value || !canSaveMembers.value) return
  if (memberMode.value === 'batch') {
    await save('memberships/students/batch', { courseId: member.courseId, userIds: studentIds.value })
  } else {
    await save('memberships', member)
  }
}
onMounted(load)
</script>

<template>
  <h1>平台基础资料</h1>
  <template v-if="data">
    <el-tabs>
      <el-tab-pane label="账号">
        <el-form label-width="90px" @submit.prevent="save('accounts', account)">
          <el-form-item label="登录名"><el-input v-model="account.username"/></el-form-item>
          <el-form-item label="姓名"><el-input v-model="account.displayName"/></el-form-item>
          <el-form-item label="初始密码"><el-input v-model="account.password" type="password" autocomplete="new-password"/></el-form-item>
          <el-button native-type="submit" :loading="busy">创建账号</el-button>
        </el-form>
        <el-table :data="data.users"><el-table-column prop="id" label="编号"/><el-table-column prop="display_name" label="姓名"/><el-table-column prop="username" label="登录名"/></el-table>
      </el-tab-pane>
      <el-tab-pane label="课程">
        <el-form label-width="90px" @submit.prevent="save('courses', course)">
          <el-form-item label="课程名"><el-input v-model="course.name"/></el-form-item>
          <el-form-item label="学期"><el-input v-model="course.semester"/></el-form-item>
          <el-form-item label="说明"><el-input v-model="course.description"/></el-form-item>
          <el-button native-type="submit" :loading="busy">创建课程</el-button>
        </el-form>
        <el-table :data="data.courses"><el-table-column prop="id" label="编号"/><el-table-column prop="name" label="课程"/><el-table-column prop="semester" label="学期"/></el-table>
      </el-tab-pane>
      <el-tab-pane label="课程成员">
        <el-form label-width="90px" :disabled="busy" @submit.prevent="saveMembers">
          <el-form-item label="课程">
            <el-select v-model="member.courseId" filterable placeholder="请选择课程">
              <el-option v-for="c in data.courses" :key="c.id" :label="`${c.name} · ${c.semester}`" :value="c.id"/>
            </el-select>
          </el-form-item>
          <el-form-item label="成员操作">
            <el-radio-group v-model="memberMode">
              <el-radio-button value="batch">批量添加学生</el-radio-button>
              <el-radio-button value="single">设置单个成员</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <template v-if="memberMode === 'batch'">
            <el-form-item label="选择学生">
              <el-select v-model="studentIds" multiple filterable :disabled="selectedCourse?.status !== 'ACTIVE'"
                placeholder="搜索姓名或登录名，可选择多人" no-data-text="暂无可加入的账号">
                <el-option v-for="u in eligibleStudents" :key="u.id"
                  :label="`${u.display_name} · ${u.username}${memberById.has(u.id) ? '（将重新启用）' : ''}`" :value="u.id"/>
              </el-select>
            </el-form-item>
            <el-button native-type="submit" type="primary" :loading="busy" :disabled="!canSaveMembers">
              {{ studentIds.length ? `加入所选学生（${studentIds.length} 人）` : '加入所选学生' }}
            </el-button>
          </template>
          <template v-else>
            <el-form-item label="用户"><el-select v-model="member.userId" filterable placeholder="搜索姓名或登录名"><el-option v-for="u in enabledUsers" :key="u.id" :label="`${u.display_name} · ${u.username}`" :value="u.id"/></el-select></el-form-item>
            <el-form-item label="课程角色"><el-select v-model="member.role"><el-option v-for="(label, role) in roleLabels" :key="role" :label="label" :value="role"/></el-select></el-form-item>
            <el-form-item label="启用"><el-switch v-model="member.active"/></el-form-item>
            <el-button native-type="submit" type="primary" :loading="busy" :disabled="!canSaveMembers">保存课程成员</el-button>
          </template>
        </el-form>
        <h2 class="course-roster-heading">{{ selectedCourse ? `${selectedCourse.name} · 成员名单` : '课程成员名单' }}</h2>
        <el-table :data="courseMembers" :empty-text="selectedCourse ? '本课程暂无成员' : '请先选择课程'">
          <el-table-column label="课程" min-width="150"><template #default>{{ selectedCourse?.name }}</template></el-table-column>
          <el-table-column label="姓名" min-width="110"><template #default="{ row }">{{ accountsById.get(row.user_id)?.display_name ?? '账号资料缺失' }}</template></el-table-column>
          <el-table-column label="登录名" min-width="130"><template #default="{ row }">{{ accountsById.get(row.user_id)?.username ?? '—' }}</template></el-table-column>
          <el-table-column label="角色" min-width="90"><template #default="{ row }">{{ roleLabels[row.role as keyof typeof roleLabels] }}</template></el-table-column>
          <el-table-column label="状态" min-width="90"><template #default="{ row }">{{ row.active ? '已启用' : '已停用' }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="投递失败通知">
        <el-table :data="data.failedNotices" empty-text="暂无待处理通知">
          <el-table-column prop="source" label="来源"/>
          <el-table-column prop="event_id" label="通知事件编号"/>
          <el-table-column prop="course_id" label="课程编号"/>
          <el-table-column prop="action" label="事件类型"/>
          <el-table-column prop="attempts" label="尝试次数"/>
          <el-table-column label="操作"><template #default="{row}"><el-button :loading="busy" @click="save(`notices/${row.source}/${row.event_id}/retry`, {})">重新投递</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </template>
</template>

<style scoped>
.course-roster-heading {
  margin: 24px 0 8px;
  font-size: 18px;
}
</style>
