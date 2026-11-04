<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '../api/http'
interface Directory {
  users: {id: number; display_name: string; username: string}[]
  courses: {id: number; name: string; semester: string}[]
  failedNotices: {event_id: number; source: string; course_id: number; action: string; attempts: number}[]
  memberships: {course_id: number; user_id: number; role: string; active: boolean}[]
}
const data = ref<Directory>()
const busy = ref(false)
const account = reactive({ username: '', displayName: '', password: '' })
const course = reactive({ name: '', semester: '', description: '' })
const member = reactive({ courseId: undefined as number | undefined, userId: undefined as number | undefined, role: 'STUDENT', active: true })
async function load() { try { data.value = await request('/platform/directory') } catch {} }
async function save(section: string, body: unknown) {
  busy.value = true
  try {
    await request(`/platform/directory/${section}`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) })
    if (section === 'accounts') Object.assign(account, { username: '', displayName: '', password: '' })
    if (section === 'courses') Object.assign(course, { name: '', semester: '', description: '' })
    ElMessage.success('已保存')
    await load()
  } catch {} finally { busy.value = false }
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
        <el-form label-width="90px" @submit.prevent="save('memberships', member)">
          <el-form-item label="课程"><el-select v-model="member.courseId"><el-option v-for="c in data.courses" :key="c.id" :label="c.name" :value="c.id"/></el-select></el-form-item>
          <el-form-item label="用户"><el-select v-model="member.userId" filterable><el-option v-for="u in data.users" :key="u.id" :label="`${u.display_name} · ${u.username}`" :value="u.id"/></el-select></el-form-item>
          <el-form-item label="课程角色"><el-select v-model="member.role"><el-option label="教师" value="TEACHER"/><el-option label="助教" value="TA"/><el-option label="学生" value="STUDENT"/></el-select></el-form-item>
          <el-form-item label="启用"><el-switch v-model="member.active"/></el-form-item>
          <el-button native-type="submit" :loading="busy">保存课程成员</el-button>
        </el-form>
        <el-table :data="data.memberships"><el-table-column prop="course_id" label="课程编号"/><el-table-column prop="user_id" label="用户编号"/><el-table-column prop="role" label="角色"/><el-table-column prop="active" label="启用"/></el-table>
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
