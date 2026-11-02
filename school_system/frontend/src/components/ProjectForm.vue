<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ProjectInput } from '../api/projects'

const props = defineProps<{ initial?: ProjectInput | null; busy?: boolean }>()
const emit = defineEmits<{ (e: 'submit', input: ProjectInput): void; (e: 'cancel'): void }>()

const title = ref('')
const description = ref('')
const deadline = ref<Date | null>(null)
const minTeamSize = ref(1)
const maxTeamSize = ref(4)
const criteria = ref<{ name: string; weight: number; description: string }[]>([])

watch(() => props.initial, fill, { immediate: true })
function fill(v?: ProjectInput | null) {
  title.value = v?.title ?? ''
  description.value = v?.description ?? ''
  deadline.value = v?.deadline ? new Date(v.deadline) : null
  minTeamSize.value = v?.minTeamSize ?? 1
  maxTeamSize.value = v?.maxTeamSize ?? 4
  criteria.value = v?.criteria?.map(c => ({ name: c.name, weight: c.weight, description: c.description ?? '' })) ?? []
}

const weightSum = computed(() => criteria.value.reduce((s, c) => s + (c.weight || 0), 0))

function addCriterion() { criteria.value.push({ name: '', weight: 0, description: '' }) }
function removeCriterion(i: number) { criteria.value.splice(i, 1) }

function submit() {
  if (!title.value.trim()) { ElMessage.warning('请填写项目名称'); return }
  if (!description.value.trim()) { ElMessage.warning('请填写项目要求'); return }
  if (minTeamSize.value > maxTeamSize.value) { ElMessage.warning('团队最少人数不能大于最多人数'); return }
  emit('submit', {
    title: title.value.trim(),
    description: description.value.trim(),
    deadline: deadline.value ? deadline.value.toISOString() : null,
    minTeamSize: minTeamSize.value,
    maxTeamSize: maxTeamSize.value,
    criteria: criteria.value.filter(c => c.name.trim()).map(c => ({ name: c.name.trim(), weight: c.weight || 0, description: c.description.trim() || undefined }))
  })
}
</script>

<template>
  <el-form label-position="top" @submit.prevent="submit">
    <el-form-item label="项目名称" required>
      <el-input v-model="title" placeholder="例如：课程管理系统开发" maxlength="160" show-word-limit />
    </el-form-item>
    <el-form-item label="项目要求" required>
      <el-input v-model="description" type="textarea" :rows="4" placeholder="项目目标、交付要求、验收说明等" maxlength="4000" show-word-limit />
    </el-form-item>
    <el-form-item label="截止时间">
      <el-date-picker v-model="deadline" type="datetime" placeholder="选择截止时间（发布前必填）" class="block-date" />
      <p class="form-hint">发布时会校验：截止时间须晚于当前时间。</p>
    </el-form-item>
    <div class="form-grid">
      <el-form-item label="团队最少人数"><el-input-number v-model="minTeamSize" :min="1" :max="30" /></el-form-item>
      <el-form-item label="团队最多人数"><el-input-number v-model="maxTeamSize" :min="1" :max="30" /></el-form-item>
    </div>
    <el-form-item label="评分标准及权重">
      <div class="criterion-list">
        <div v-for="(c, i) in criteria" :key="i" class="criterion-row">
          <el-input v-model="c.name" placeholder="评分项名称，如：功能完整性" />
          <el-input-number v-model="c.weight" :min="0" :max="100" /><span class="criterion-weight">%</span>
          <el-button text type="danger" @click="removeCriterion(i)">删除</el-button>
        </div>
        <el-button text type="primary" @click="addCriterion">+ 添加评分标准</el-button>
      </div>
      <p class="form-hint">当前权重合计 {{ weightSum }}%，发布前需合计为 100%。</p>
    </el-form-item>
    <div class="form-actions">
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" native-type="submit" :loading="busy">保存</el-button>
    </div>
  </el-form>
</template>
