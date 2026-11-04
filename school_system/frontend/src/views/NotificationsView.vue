<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { request } from '../api/http'

interface Notice { id: number; source: string; title: string; href: string; created_at: string; read_at: string | null }
const router = useRouter()
const rows = ref<Notice[]>([])
const unreadOnly = ref(false)
const loading = ref(false)
const visible = computed(() => unreadOnly.value ? rows.value.filter(item => !item.read_at) : rows.value)
const unread = computed(() => rows.value.filter(item => !item.read_at).length)
async function load() {
  loading.value = true
  try { rows.value = await request<Notice[]>('/workflow/notices') } catch {} finally { loading.value = false }
}
async function open(item: Notice) {
  if (!item.read_at) {
    try {
      await request(`/workflow/notices/${item.source}/${item.id}/read`, { method: 'POST' })
      item.read_at = new Date().toISOString()
    } catch { return }
  }
  await router.push(item.href)
}
const stamp = (time: string) => new Date(time).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <h1>站内通知</h1>
    <p>未读 {{ unread }} 条</p>
    <el-checkbox v-model="unreadOnly">仅显示未读</el-checkbox>
    <el-button @click="load">刷新</el-button>
    <el-empty v-if="!visible.length" description="暂无通知" />
    <el-card v-for="item in visible" :key="`${item.source}-${item.id}`" style="margin-top: 12px">
      <el-tag v-if="!item.read_at" type="warning">未读</el-tag>
      <el-button link type="primary" @click="open(item)">{{ item.title }}</el-button>
      <span>{{ stamp(item.created_at) }}</span>
    </el-card>
  </section>
</template>
