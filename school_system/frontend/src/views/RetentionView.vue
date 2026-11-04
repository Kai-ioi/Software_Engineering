<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { request } from '../api/http'
interface CourseRow {
  id: number; name: string; status: string; ended_at: string | null; hold_reason: string | null;
  approved_at: string | null; executed_at: string | null; remaining_files: number; manifest: string | null
}
const base='/platform/retention'
const access=ref({admin:false,approver:false})
const rows=ref<CourseRow[]>([])
const busy=ref(false)
const auditOpen=ref(false)
const auditRows=ref<Record<string,string>[]>([])
async function showAudit(id: number) {
  try { auditRows.value=await request(`${base}/${id}/audit`); auditOpen.value=true } catch {}
}
async function load(){
  try {
    access.value=await request(base+'/access')
    if(access.value.admin||access.value.approver)rows.value=await request(base)
  } catch {}
}
async function action(row: CourseRow, name: string){
  if(busy.value)return
  busy.value=true
  try {
    let payload: {reason: string; release: boolean} | undefined
    if(name==='hold'){
      const {value}=await ElMessageBox.prompt('请输入操作原因',row.hold_reason?'解除暂缓':'登记暂缓')
      payload={reason:value,release:Boolean(row.hold_reason)}
    } else if(name==='purge'||name==='approve'){
      const p=await request<{projects:number;teams:number;versions:number;grades:number;files:number}>(`${base}/${row.id}/preview`)
      await ElMessageBox.confirm(`课程 ${row.name}，涉及 ${p.projects} 个项目、${p.teams} 个团队、${p.versions} 个成果版本、${p.grades} 个成绩版本、${p.files} 个文件。请核对范围。`,
        name==='approve'?'批准清理':'确认执行不可逆清理',{type:'warning'})
    } else await ElMessageBox.confirm(`确认登记 ${row.name} 已结束？`,'课程结束',{type:'warning'})
    await request(`${base}/${row.id}/${name}`,{method:'POST',headers:{'Content-Type':'application/json'},body:payload?JSON.stringify(payload):undefined})
    ElMessage.success('操作已记录')
    await load()
  } catch {} finally {busy.value=false}
}
const stamp=(value: string|null)=>value ? new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) : '—'
onMounted(load)
</script>

<template>
  <h1>课程数据留存</h1>
  <el-empty v-if="!access.admin && !access.approver" description="当前账号没有留存管理权限"/>
  <el-table v-else :data="rows" v-loading="busy">
    <el-table-column prop="name" label="课程"/>
    <el-table-column label="结束时间"><template #default="{row}">{{ stamp(row.ended_at) }}</template></el-table-column>
    <el-table-column label="清理状态" min-width="170"><template #default="{row}">
      {{ row.executed_at ? '业务数据已清理' : row.hold_reason ? '争议暂缓' : row.approved_at ? '已批准' : row.ended_at ? '待核对留存期限' : '课程进行中' }}
      <p v-if="row.hold_reason">{{ row.hold_reason }}</p>
      <p v-if="row.remaining_files">待处理附件 {{ row.remaining_files }} 个</p>
    </template></el-table-column>
    <el-table-column label="操作" min-width="240"><template #default="{row}">
      <el-button v-if="row.ended_at" @click="showAudit(row.id)">查看留痕</el-button>
      <el-button v-if="access.admin && !row.ended_at" @click="action(row,'end')">登记结束</el-button>
      <el-button v-if="row.ended_at && !row.executed_at" @click="action(row,'hold')">{{ row.hold_reason ? '解除暂缓' : '登记暂缓' }}</el-button>
      <el-button v-if="access.approver && row.ended_at && !row.hold_reason && !row.executed_at" @click="action(row,'approve')">审核并批准</el-button>
      <el-button v-if="access.admin && ((row.approved_at && !row.executed_at) || row.remaining_files)" type="danger" @click="action(row,'purge')">{{ row.executed_at ? '重试附件清理' : '执行清理' }}</el-button>
    </template></el-table-column>
  </el-table>
  <el-dialog v-model="auditOpen" title="留存操作记录">
    <el-table :data="auditRows">
      <el-table-column prop="display_name" label="操作者"/>
      <el-table-column prop="action" label="操作"/>
      <el-table-column prop="detail" label="范围或说明"/>
      <el-table-column label="时间"><template #default="{row}">{{ stamp(row.occurred_at) }}</template></el-table-column>
    </el-table>
  </el-dialog>
</template>
