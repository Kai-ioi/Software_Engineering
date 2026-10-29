<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Reading, ArrowRight, Lock } from '@element-plus/icons-vue'
import { login } from '../stores/auth'
import { request } from '../api/http'
const router = useRouter(); const route = useRoute()
const username = ref(''); const password = ref(''); const busy = ref(false); const error = ref(''); const demo = ref(false)
request<{ mode: string }>('/health', {}, true).then(h => { demo.value = h.mode === 'demo' }).catch(() => {})
async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = '请输入账号和密码'; return }
  busy.value = true
  try {
    await login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/courses'
    await router.replace(redirect.startsWith('/') && !redirect.startsWith('//') && redirect !== '/login' ? redirect : '/courses')
  } catch { error.value = '登录未完成，请检查账号、密码或服务连接' }
  finally { busy.value = false }
}
</script>
<template>
  <div class="login-page">
    <section class="login-story"><div class="brand light"><span class="brand-symbol">协</span><span>课协<span class="brand-en">COURSE COLLAB</span></span></div><div class="story-content"><span class="eyebrow">从一个想法，到共同完成</span><h1>让课程项目，<br/>成为一次有序的协作。</h1><p>连接团队的计划、交付与反馈，<br/>留下每一步成长的轨迹。</p><div class="story-flow"><span>组队协作</span><ArrowRight/><span>迭代交付</span><ArrowRight/><span>评审反馈</span></div></div><div class="story-footer"><Reading/>高校课程项目协作与评审平台</div></section>
    <section class="login-form-side"><div class="login-card"><span class="eyebrow">欢迎回来</span><h2>登录课程工作空间</h2><p class="muted">使用课程账号，继续你的项目协作。</p>
      <el-form label-position="top" @submit.prevent="submit"><el-form-item label="账号"><el-input v-model="username" placeholder="请输入账号" autocomplete="username" size="large"/></el-form-item><el-form-item label="密码"><el-input v-model="password" placeholder="请输入密码" type="password" show-password autocomplete="current-password" size="large"/></el-form-item><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/><el-button class="login-submit" type="primary" size="large" native-type="submit" :loading="busy">进入工作空间<ArrowRight class="button-icon"/></el-button></el-form>
      <div class="login-note"><Lock/>你的课程身份与访问范围由课程授权决定</div>
      <details v-if="demo" class="demo-help"><summary>本地演示账号</summary><p>教师 teacher01 · 学生 student01<br/>助教 assistant01 · 另一课程学生 student02<br/>管理员 admin01（默认没有课程权限）</p><p>初始密码：Demo@2026!<br/>设置过 DEMO_PASSWORD 时使用自定义密码。</p></details>
    </div><div class="login-copyright">课协 · COURSE COLLAB</div></section>
  </div>
</template>
