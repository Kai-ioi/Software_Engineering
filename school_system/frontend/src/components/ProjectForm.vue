<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ProjectInput } from '../api/projects'

const props = defineProps<{ initial?: ProjectInput | null; busy?: boolean }>()
const emit = defineEmits<{ (e: 'submit', input: ProjectInput): void; (e: 'cancel'): void }>()

const teacherWeight = ref(70)
const title = ref('')
const changeReason = ref('')
const remediationUntil = ref('')
const description = ref('')
const deadline = ref<Date | null>(null)
const minTeamSize = ref(3)
const maxTeamSize = ref(5)
const criteria = ref<{ name: string; weight: number; description: string }[]>([])

watch(() => props.initial, fill, { immediate: true })
function fill(v?: ProjectInput | null) {
  teacherWeight.value = v?.teacherWeight ?? 70
  title.value = v?.title ?? ''
  description.value = v?.description ?? ''
  deadline.value = v?.deadline ? new Date(v.deadline) : null
  minTeamSize.value = v?.minTeamSize ?? 3
  maxTeamSize.value = v?.maxTeamSize ?? 5
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
    changeReason: changeReason.value.trim() || undefined,
    remediationUntil: remediationUntil.value ? remediationUntil.value + ':00+08:00' : undefined,
    description: description.value.trim(),
    deadline: deadline.value ? deadline.value.toISOString() : null,
    teacherWeight: teacherWeight.value, peerWeight: 100-teacherWeight.value,
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
      <el-form-item label="组队方式"><p class="muted">课程内固定团队，每队3—5人。学生先在课程中组队，再选择项目。</p></el-form-item>

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
    <el-form-item label="成绩构成"><el-input-number v-model="teacherWeight" :min="0" :max="100" :precision="0"/><span>教师 {{teacherWeight}}% · 互评 {{100-teacherWeight}}%</span></el-form-item>
    <template v-if="initial">
      <el-form-item label="变更原因"><el-input v-model="changeReason" maxlength="2000"/></el-form-item>
      <el-form-item label="整改截止（北京时间，涉及既有提交时填写）"><input v-model="remediationUntil" type="datetime-local"></el-form-item>
    </template>
    <div class="form-actions">
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" native-type="submit" :loading="busy">保存</el-button>
    </div>
  </el-form>
</template>
