<script setup lang="ts">
import { computed,onMounted,onUnmounted,reactive,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage,ElMessageBox,ElTable,ElTableColumn,ElCheckbox,ElTabs,ElTabPane,ElDrawer } from 'element-plus'
import { auth } from '../stores/auth'
import { getCourses,type Course } from '../api/courses'
import { getTeams,type TeamOption,projectStatusNames } from '../api/collaboration'
import { request,ApiError } from '../api/http'
import { getSubmissionWorkspace,submissionAction,getVersion,getReceipt,getSubmissionHistory,type SubmissionWorkspace,type SubmittedVersion,type VersionDetail,type SubmissionEvent,type PreparedMaterial } from '../api/submissions'

const route=useRoute(),router=useRouter(),courses=ref<Course[]>([]),courseId=ref<number>(),teams=ref<TeamOption[]>([]),teamId=ref<number>()
const w=ref<SubmissionWorkspace>(),iterationId=ref<number>(),loading=ref(false),busy=ref(false),failed=ref(false),selected=ref<number[]>([]),summary=ref(''),tab=ref('materials')
const detail=ref<VersionDetail>(),detailOpen=ref(false),historyOpen=ref(false),events=ref<SubmissionEvent[]>([]),moreHistory=ref(false)
const tick=ref(Date.now());let timer:ReturnType<typeof setInterval>|undefined;let lastRefresh=0;let serverOffset=0
const serverNow=computed(()=>tick.value+serverOffset),due=computed(()=>!!w.value&&serverNow.value>=Date.parse(w.value.project.final_deadline))
const currentIteration=computed(()=>w.value?.iterations.find(i=>i.id===iterationId.value))
const materials=computed(()=>w.value?.materials.filter(m=>m.iteration_id===iterationId.value)||[])
const versions=computed(()=>w.value?.versions.filter(v=>v.iteration_id===iterationId.value)||[])
const currentVersionId=computed(()=>w.value?.packages.find(p=>p.iteration_id===iterationId.value)?.current_version_id)
const reviewVersionId=computed(()=>w.value?.reviewBindings.find(b=>b.id===w.value?.delivery.review_binding_id)?.version_id)
const selectedBytes=computed(()=>materials.value.filter(m=>selected.value.includes(m.id)).reduce((total,m)=>total+(m.size_bytes||0),0))
const openExtension=computed(()=>w.value?.extensions.find(e=>serverNow.value>=Date.parse(e.starts_at)&&serverNow.value<Date.parse(e.ends_at)))
const mutable=computed(()=>!!w.value&&['PUBLISHED','IN_PROGRESS','ENDED'].includes(w.value.project.status))
const canPrepare=computed(()=>!!w.value?.canPrepare&&mutable.value&&(!due.value||!!openExtension.value))
const canSubmit=computed(()=>!!w.value?.canSubmit&&mutable.value&&(!due.value||!!openExtension.value))
const canSelect=computed(()=>!!w.value?.canSelect&&!due.value&&!w.value?.delivery.frozen_at)
const teacher=computed(()=>w.value?.role==='TEACHER')
const stamp=(value:string|null|undefined)=>value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'—'
const bytes=(n:number|null|undefined)=>`${((n||0)/1024/1024).toFixed(2)} MB`
const versionName=(id:number|null|undefined)=>{const v=w.value?.versions.find(v=>v.id===id);return v?`${w.value?.iterations.find(i=>i.id===v.iteration_id)?.name} · v${v.version_no}（#${v.id}）`:'尚未确定'}
const statusName:Record<string,string>={PENDING:'待处理',APPROVED:'已批准',REJECTED:'已拒绝'}
interface Pending {requestKey:string;iterationId:number;materialIds:number[];summary:string}
const pending=ref<Pending>();const pendingKey=()=>`submission-pending-${teamId.value}`
function restorePending(){try{const saved=sessionStorage.getItem(pendingKey());if(saved){pending.value=JSON.parse(saved) as Pending;}}catch{pending.value=undefined}}
async function load(){
 if(!teamId.value)return
 loading.value=true;failed.value=false
 try{const result=await getSubmissionWorkspace(teamId.value);w.value=result;serverOffset=Date.parse(result.serverTime)-Date.now();tick.value=Date.now();lastRefresh=Date.now();if(!result.iterations.some(i=>i.id===iterationId.value))iterationId.value=result.iterations[0]?.id;selected.value=selected.value.filter(id=>result.materials.some(m=>m.id===id&&m.iteration_id===iterationId.value));}
 catch{failed.value=true}finally{loading.value=false}
}
async function loadTeams(){
 w.value=undefined;teams.value=[];teamId.value=undefined;iterationId.value=undefined;selected.value=[];pending.value=undefined
 if(!courseId.value)return
 loading.value=true;failed.value=false
 try{teams.value=await getTeams(courseId.value);if(teams.value.length){teamId.value=(teams.value.find(t=>t.id===Number(route.query.teamId)) ?? teams.value[0]!).id;restorePending();await load()}}catch{failed.value=true}finally{loading.value=false}
}
async function changeTeam(){w.value=undefined;iterationId.value=undefined;selected.value=[];pending.value=undefined;restorePending();await load()}
watch(iterationId,()=>{selected.value=[];summary.value=''})
onMounted(async()=>{
 try{courses.value=await getCourses();courseId.value=courses.value.find(c=>c.id===Number(route.query.courseId))?.id||courses.value[0]?.id;await loadTeams()}catch{failed.value=true}
 timer=setInterval(()=>{tick.value=Date.now();if(w.value&&Date.now()-lastRefresh>15000&&!busy.value&&!dialog.value&&!loading.value)void load()},1000)
})
onUnmounted(()=>{if(timer)clearInterval(timer)})
function toggle(id:number,value:unknown){selected.value=value?[...new Set([...selected.value,id])]:selected.value.filter(x=>x!==id)}
const fileInput=ref<HTMLInputElement>()
async function upload(event:Event){
 const input=event.target as HTMLInputElement;if(!input.files||!w.value||!teamId.value||!iterationId.value)return
 const queue=Array.from(input.files);input.value='';busy.value=true;let added=0
 try{for(const file of queue){if(!file.size||file.size>50*1024*1024){ElMessage.warning(`${file.name}：文件须大于0且不超过50 MB`);continue}const form=new FormData();form.append('file',file);form.append('iterationId',String(iterationId.value));form.append('revision',String(w.value!.team.revision));const result=await request<{id:number}>(`/submissions/teams/${teamId.value}/materials/files`,{method:'POST',body:form});await load();selected.value.push(result.id);added++}if(added)ElMessage.success('材料已保存到待提交区，尚未正式提交')}
 catch(e){if(e instanceof ApiError&&e.status===409)await load()}finally{busy.value=false}
}
async function remove(m:PreparedMaterial){
 if(!w.value||!teamId.value)return
 try{await ElMessageBox.confirm('仅移出待提交区，已正式提交版本中的材料仍保留。','移除待提交材料',{type:'warning'});busy.value=true;await submissionAction(teamId.value,`materials/${m.id}/remove`,{revision:w.value.team.revision});await load()}catch(e){if(e instanceof ApiError&&e.status===409)await load()}finally{busy.value=false}
}
async function submit(retry=false){
 if(!w.value||!teamId.value||!iterationId.value||busy.value)return
 if(!retry){if(!summary.value.trim()||!selected.value.length){ElMessage.warning('请填写成果说明并选择材料');return}if(selectedBytes.value>200*1024*1024){ElMessage.warning('所选文件总量超过200 MB');return}pending.value={requestKey:crypto.randomUUID(),iterationId:iterationId.value,materialIds:[...selected.value],summary:summary.value.trim()};sessionStorage.setItem(pendingKey(),JSON.stringify(pending.value))}
 if(!pending.value)return
 busy.value=true
 try{detail.value=await submissionAction<VersionDetail>(teamId.value,'versions',{...pending.value,revision:w.value.team.revision});detailOpen.value=true;sessionStorage.removeItem(pendingKey());pending.value=undefined;await load();tab.value='versions';ElMessage.success('正式提交已受理，已生成版本与回执')}
 catch(e){if(e instanceof ApiError&&e.status>=400&&e.status<500&&e.code!=='REQUEST_KEY_REUSED'){sessionStorage.removeItem(pendingKey());pending.value=undefined}if(e instanceof ApiError&&e.status===409)await load()}finally{busy.value=false}
}
async function recover(){if(!teamId.value||!pending.value)return;busy.value=true;try{detail.value=await getReceipt(teamId.value,pending.value.requestKey);detailOpen.value=true;sessionStorage.removeItem(pendingKey());pending.value=undefined;await load()}catch{}finally{busy.value=false}}
async function showVersion(v:SubmittedVersion){if(!teamId.value)return;try{detail.value=await getVersion(teamId.value,v.id);detailOpen.value=true}catch{}}
async function selectVersion(v:SubmittedVersion){
 if(!w.value||!teamId.value)return
 try{await ElMessageBox.confirm(`将“${versionName(v.id)}”指定为项目最终成果？重交不会自动改变此引用。`,'指定最终成果');busy.value=true;await submissionAction(teamId.value,'final-selection',{revision:w.value.team.revision,versionId:v.id,reason:'负责人主动指定最终成果'});await load();ElMessage.success('最终指定已保存')}catch(e){if(e instanceof ApiError&&e.status===409)await load()}finally{busy.value=false}
}
type Dialog='link'|'request'|'grant'|'reject'|'verify'|'bind'|''
const dialog=ref<Dialog>(''),dialogRevision=ref(0),requestId=ref<number>(),targetVersionId=ref<number>()
const form=reactive({title:'',url:'',codeLink:false,codeRef:'',reason:'',startsAt:'',endsAt:'',accessibleConfirmed:false,reassessmentRequired:false})
const titles:Record<string,string>={link:'添加HTTPS材料链接',request:'申请限时补交或整改',grant:'教师授权补交窗口',reject:'处理补交申请：拒绝',verify:'教师核实未指定最终成果',bind:'教师确认具体被评版本'}
function bjt(value:number){return new Date(value).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai',hour12:false}).replace(' ','T')}
function open(kind:Dialog,id?:number){
 if(!w.value)return
 dialogRevision.value=w.value.team.revision;requestId.value=kind==='grant'||kind==='reject'?id:undefined;targetVersionId.value=kind==='verify'||kind==='bind'?id:undefined
 Object.assign(form,{title:'',url:'',codeLink:false,codeRef:'',reason:'',startsAt:bjt(Math.max(serverNow.value,Date.parse(w.value.project.final_deadline)+1000)),endsAt:bjt(serverNow.value+2*24*3600*1000),accessibleConfirmed:false,reassessmentRequired:false});dialog.value=kind
}
async function saveDialog(){
 if(!w.value||!teamId.value||busy.value)return
 if(dialog.value==='link'&&(!form.title.trim()||!form.url.trim()||(form.codeLink&&!form.codeRef.trim()))){ElMessage.warning('请填写链接名称、HTTPS地址及必要的代码提交编号或标签');return}
 if(dialog.value!=='link'&&!form.reason.trim()){ElMessage.warning('请填写原因或核实依据');return}
 if(['verify','bind'].includes(dialog.value)&&!form.accessibleConfirmed){ElMessage.warning('请先确认材料可访问');return}
 let path='',payload:object={revision:dialogRevision.value,reason:form.reason}
 if(dialog.value==='link'){path='materials/links';payload={revision:dialogRevision.value,iterationId:iterationId.value,title:form.title,url:form.url,codeLink:form.codeLink,codeRef:form.codeRef}}
 if(dialog.value==='request')path='extension-requests'
 if(dialog.value==='grant'){if(!form.startsAt||!form.endsAt){ElMessage.warning('请填写补交窗口起止时间');return}path='extensions';payload={...payload,requestId:requestId.value,startsAt:`${form.startsAt}+08:00`,endsAt:`${form.endsAt}+08:00`}}
 if(dialog.value==='reject')path=`extension-requests/${requestId.value}/reject`
 if(dialog.value==='verify'||dialog.value==='bind'){path=dialog.value==='verify'?'verify-final':'review-bindings';payload={...payload,versionId:targetVersionId.value,accessibleConfirmed:form.accessibleConfirmed,reassessmentRequired:form.reassessmentRequired}}
 busy.value=true
 try{const result=await submissionAction<{id?:number}>(teamId.value,path,payload);const link=dialog.value==='link';dialog.value='';await load();if(link&&result.id)selected.value.push(result.id);ElMessage.success('已保存，历史记录已保留')}
 catch(e){if(e instanceof ApiError&&e.status===409){dialog.value='';await load()}}finally{busy.value=false}
}
async function history(more=false){if(!teamId.value||busy.value)return;busy.value=true;try{const batch=await getSubmissionHistory(teamId.value,more?String(events.value.at(-1)!.id):undefined);events.value=more?[...events.value,...batch]:batch;moreHistory.value=batch.length===50;historyOpen.value=true}catch{}finally{busy.value=false}}
async function readNotice(id:number){if(!teamId.value)return;try{await submissionAction(teamId.value,`notices/${id}/read`,{});await load()}catch{}}
const eventNames:Record<string,string>={MATERIAL_PREPARED:'准备材料',MATERIAL_REMOVED:'移出待提交区',VERSION_SUBMITTED:'正式提交版本',FINAL_SELECTED:'负责人指定最终版本',FINAL_FROZEN:'截止冻结',FINAL_VERIFIED:'教师核实未指定异常',REVIEW_VERSION_CONFIRMED:'教师确认被评版本',EXTENSION_REQUESTED:'申请补交',EXTENSION_GRANTED:'授权补交',EXTENSION_REJECTED:'拒绝补交申请'}
function eventData(value:string){try{return JSON.stringify(JSON.parse(value),null,2)}catch{return value}}
</script>

<template>
 <section class="page-heading"><div><span class="eyebrow">TEAM DELIVERABLES</span><h1>成果与版本</h1><p class="muted">准备材料、正式交付，保留每一份成果与受理依据。</p></div><el-button :loading="loading" :disabled="busy||loading" @click="load">刷新</el-button></section>
 <div class="submission-selectors"><label>课程 <el-select v-model="courseId" aria-label="选择课程" :disabled="busy||loading" @change="loadTeams"><el-option v-for="c in courses" :key="c.id" :value="c.id" :label="c.name"/></el-select></label><label>团队 <el-select v-model="teamId" aria-label="选择团队" :disabled="busy||loading||!teams.length" @change="changeTeam"><el-option v-for="t in teams" :key="t.id" :value="t.id" :label="`${t.project_name} / ${t.name}`"/></el-select></label></div>
 <div v-if="loading&&!w" class="state-panel">正在加载成果工作空间…</div><el-alert v-else-if="failed" type="error" title="加载失败，请检查服务连接并重试" :closable="false"/>
 <el-empty v-else-if="!w" description="暂无可访问的项目团队"><el-button @click="router.push('/courses')">返回课程</el-button></el-empty>
 <template v-else>
  <el-alert v-if="w.project.demo_fixture" type="info" :closable="false" title="当前为演示项目与团队，正式提交会写入本地数据库并保留版本。"/>
  <div class="submission-header"><div><h2>{{w.team.name}}</h2><p class="muted">{{w.project.name}} · {{projectStatusNames[w.project.status]}} · 最终截止 {{stamp(w.project.final_deadline)}}（北京时间）</p></div><el-button :disabled="busy||loading" @click="history()">成果过程记录</el-button></div>
  <div class="reference-grid"><article><span>项目最终成果</span><strong>{{versionName(w.delivery.final_version_id)}}</strong><small>{{w.delivery.frozen_at?'已冻结，负责人不能更换':'截止前可由负责人主动指定或重新指定'}}</small></article><article><span>教师确认的被评版本</span><strong>{{versionName(reviewVersionId)}}</strong><small>独立保存，重交不替换已有评审引用</small></article><article><span>当前提交窗口</span><strong>{{!mutable?'项目只读':openExtension?'授权补交窗口开放':due?'普通提交已关闭':'普通提交窗口开放'}}</strong><small>{{openExtension?`${stamp(openExtension.starts_at)} 至 ${stamp(openExtension.ends_at)}`:'以系统成功受理时间判定；等于最终截止仍按时'}}</small></article></div>
  <el-alert v-if="w.exception" type="warning" :closable="false" :title="w.exception+'，需由教师核实处理；系统不会自动选择最新版本或扣分。'"/>
  <p v-if="w.role==='TA'" class="muted submission-note">助教可查看授权范围内材料和版本，不代替负责人提交，也不能修改最终指定或批准补交。</p>
  <p v-else-if="w.role==='STUDENT'&&!w.isLeader" class="muted submission-note">你可以准备材料、查看版本和下载文件；正式提交、重交和最终指定由团队负责人执行。</p>
  <div class="submission-selectors"><label>成果所属迭代 <el-select v-model="iterationId" aria-label="选择成果迭代" :disabled="busy||loading"><el-option v-for="i in w.iterations" :key="i.id" :value="i.id" :label="i.name"/></el-select></label><el-button v-if="w.isLeader&&due&&mutable" :disabled="busy||loading" @click="open('request')">申请补交或整改</el-button><el-button v-if="teacher&&due&&mutable" :disabled="busy||loading" @click="open('grant')">授权补交窗口</el-button></div>
  <el-empty v-if="!w.iterations.length" description="团队尚无迭代，请先到“迭代与任务”创建迭代"><el-button @click="router.push({path:'/modules/tasks',query:{courseId}})">前往迭代与任务</el-button></el-empty>
  <template v-else><el-tabs v-model="tab">
   <el-tab-pane label="准备与提交" name="materials"><section class="submission-panel">
    <div class="submission-bar"><div><h3>{{currentIteration?.name}} · 待提交材料</h3><p class="muted">单文件≤50 MB，每个正式版本文件总量≤200 MB；代码链接填写提交编号或标签。</p></div><div v-if="canPrepare" class="submission-actions"><input ref="fileInput" type="file" multiple hidden accept=".pdf,.docx,.pptx,.xlsx,.txt,.md,.png,.jpg,.jpeg,.zip" @change="upload"><el-button :disabled="busy||loading" @click="fileInput?.click()">上传材料</el-button><el-button :disabled="busy||loading" @click="open('link')">添加HTTPS链接</el-button></div></div>
    <el-table :data="materials" empty-text="本轮尚无待提交材料" stripe><el-table-column v-if="w.isLeader" label="选择" width="70"><template #default="{row}"><el-checkbox :model-value="selected.includes(row.id)" :disabled="busy||loading||!canSubmit" :aria-label="`选择材料 ${row.title}`" @change="v=>toggle(row.id,v)"/></template></el-table-column><el-table-column label="材料" min-width="250"><template #default="{row}"><strong>{{row.title}}</strong><p v-if="row.kind==='LINK'" class="muted"><a :href="row.url" target="_blank" rel="noopener noreferrer">{{row.url}}</a><br v-if="row.code_ref"><span v-if="row.code_ref">代码版本：{{row.code_ref}}</span></p><p v-else class="muted">{{bytes(row.size_bytes)}} · SHA-256 {{row.sha256?.slice(0,12)}}…</p></template></el-table-column><el-table-column label="准备人" min-width="130" prop="creator_name"/><el-table-column label="操作" width="160"><template #default="{row}"><a v-if="row.kind==='FILE'" :href="`/api/submissions/teams/${teamId}/materials/${row.id}/download`" class="download">下载</a><el-button v-if="canPrepare&&(w.isLeader||row.created_by===auth.user?.id)" link :disabled="busy||loading" @click="remove(row)">移出待提交区</el-button></template></el-table-column></el-table>
    <div v-if="w.isLeader" class="submission-compose"><div class="submission-bar"><strong>正式成果包</strong><el-button :disabled="busy||loading||!canSubmit" @click="selected=materials.map(m=>m.id)">全选本轮材料</el-button></div><p :class="selectedBytes>200*1024*1024?'limit-error':'muted'">已选 {{selected.length}} 项材料，文件合计 {{bytes(selectedBytes)}} / 200 MB</p><label class="form-label" for="submission-summary">成果说明（必填）</label><el-input id="submission-summary" v-model="summary" type="textarea" :rows="3" maxlength="2000" :disabled="busy||loading||!canSubmit" placeholder="说明本轮完成内容、已知问题与交付材料"/><p class="muted">正式提交后生成不可覆盖的历史版本；移除待提交材料不会删除历史版本。系统不提供正式成果撤回。</p><el-button type="primary" :loading="busy" :disabled="loading||!canSubmit" @click="submit()">{{currentVersionId?'正式重交（生成新版本）':'正式提交（生成首版）'}}</el-button></div>
    <el-alert v-if="pending" type="warning" :closable="false" title="存在一次待核查提交。请先查回执，避免把响应丢失误认为提交失败。"/><div v-if="pending" class="submission-actions"><el-button :disabled="busy||loading" @click="recover">查询上次提交回执</el-button><el-button v-if="w.isLeader" :disabled="busy||loading" @click="submit(true)">重试同一次提交</el-button></div>
   </section></el-tab-pane>
   <el-tab-pane label="版本与回执" name="versions"><section class="submission-panel"><h3>{{currentIteration?.name}} · 正式版本历史</h3><p class="muted">本轮最新有效版本、项目最终指定及教师被评引用分别保存。</p><el-table :data="versions" empty-text="本轮尚无正式提交版本" stripe><el-table-column label="版本" min-width="200"><template #default="{row}"><strong>v{{row.version_no}} · #{{row.id}}</strong><div class="version-tags"><el-tag v-if="row.id===currentVersionId" size="small">本轮当前有效</el-tag><el-tag v-if="row.id===w.delivery.final_version_id" type="success" size="small">最终指定{{w.delivery.frozen_at?' · 已冻结':''}}</el-tag><el-tag v-if="reviewVersionId===row.id" type="warning" size="small">当前被评版本</el-tag></div></template></el-table-column><el-table-column label="受理信息" min-width="210"><template #default="{row}">{{row.submitter_name}}<p>{{stamp(row.accepted_at)}}</p><el-tag :type="row.late?'warning':'success'">{{row.late?'授权迟交，不自动扣分':'按时受理'}}</el-tag></template></el-table-column><el-table-column label="成果说明" min-width="180" prop="summary"/><el-table-column label="操作" width="210"><template #default="{row}"><div class="row-actions"><el-button link type="primary" @click="showVersion(row)">材料与回执</el-button><el-button v-if="canSelect" link :disabled="busy||loading" @click="selectVersion(row)">指定为最终成果</el-button><el-button v-if="teacher&&due&&mutable&&!w.delivery.final_version_id" link :disabled="busy||loading" @click="open('verify',row.id)">核实未指定异常</el-button><el-button v-if="teacher&&due&&mutable&&w.delivery.final_version_id" link :disabled="busy||loading" @click="open('bind',row.id)">确认被评版本</el-button></div></template></el-table-column></el-table></section></el-tab-pane>
   <el-tab-pane label="补交与确认记录" name="extensions"><section class="submission-panel"><h3>补交或整改申请</h3><el-table :data="w.requests" empty-text="暂无补交申请"><el-table-column label="申请人 / 时间" min-width="180"><template #default="{row}">{{row.requester_name}}<p>{{stamp(row.requested_at)}}</p></template></el-table-column><el-table-column label="申请原因" min-width="180" prop="reason"/><el-table-column label="结果" min-width="180"><template #default="{row}"><el-tag>{{statusName[row.status]}}</el-tag><p>{{row.resolution}}</p></template></el-table-column><el-table-column v-if="teacher&&mutable" label="操作" width="140"><template #default="{row}"><template v-if="row.status==='PENDING'"><el-button link type="primary" :disabled="busy||loading" @click="open('grant',row.id)">批准</el-button><el-button link :disabled="busy||loading" @click="open('reject',row.id)">拒绝</el-button></template></template></el-table-column></el-table><h3 class="section-gap">授权窗口历史</h3><article v-for="e in w.extensions" :key="e.id" class="record-card"><strong>#{{e.id}} · {{serverNow<Date.parse(e.starts_at)?'尚未开始':serverNow<Date.parse(e.ends_at)?'窗口开放':'窗口已结束'}}</strong><p>{{stamp(e.starts_at)}} 至 {{stamp(e.ends_at)}}（北京时间）</p><p>授权原因：{{e.reason}}</p></article><el-empty v-if="!w.extensions.length" description="暂无授权窗口" :image-size="60"/><h3 class="section-gap">教师确认的被评版本历史</h3><article v-for="b in w.reviewBindings" :key="b.id" class="record-card"><strong>引用 #{{b.id}} · {{versionName(b.version_id)}}</strong><p>{{stamp(b.confirmed_at)}} · {{b.reason}}</p><p v-if="b.reassessment_required">已记录需要重评；具体评审任务由检查与互评模块安排，旧引用保留。</p></article></section></el-tab-pane>
   <el-tab-pane :label="`站内消息（${w.notices.filter(n=>!n.read_at).length}）`" name="notices"><section class="submission-panel"><p class="muted">成果事件消息只发给有权限的接收人；发送失败不会撤销已成功的提交。消息由后台投递，可点击刷新查看。</p><article v-for="n in w.notices" :key="n.id" class="record-card"><strong>{{n.title}}</strong><p>{{stamp(n.created_at)}}</p><el-button v-if="!n.read_at" link @click="readNotice(n.id)">标为已读</el-button><small v-else>已读</small></article><el-empty v-if="!w.notices.length" description="暂无站内消息"/></section></el-tab-pane>
  </el-tabs></template>
 </template>
 <el-dialog v-model="detailOpen" title="正式成果版本与受理回执" width="min(800px,96vw)"><template v-if="detail"><div class="receipt"><strong>{{detail.receipt_code}}</strong><p>团队：{{w?.team.name}} · 迭代：{{w?.iterations.find(i=>i.id===detail?.iteration_id)?.name}} · 版本：v{{detail.version_no}}</p><p>实际提交人：{{detail.submitter_name}}</p><p>成功受理：{{stamp(detail.accepted_at)}}（北京时间）</p><p>本次截止依据：{{stamp(detail.deadline_snapshot)}}</p><p>受理结果：{{detail.late?'截止后授权提交，保留迟交标记，不自动扣分':'按时提交'}}{{detail.extension_id?` · 授权窗口 #${detail.extension_id}`:''}}</p><p>文件总量：{{bytes(detail.total_bytes)}}</p><p>请求编号：{{detail.request_key}}</p><p>成果说明：{{detail.summary}}</p></div><article v-for="m in detail.materials" :key="m.id" class="record-card"><strong>{{m.title}}</strong><p v-if="m.kind==='FILE'">{{bytes(m.size_bytes)}} · SHA-256 {{m.sha256}}</p><p v-if="m.code_ref">代码提交编号或标签：{{m.code_ref}}</p><a v-if="m.kind==='FILE'" :href="`/api/submissions/teams/${teamId}/versions/${detail.id}/files/${m.id}`" class="download">下载本版本文件</a><a v-else :href="m.url||undefined" target="_blank" rel="noopener noreferrer" class="download">打开本版本保存的HTTPS链接</a></article></template></el-dialog>
 <el-dialog :model-value="!!dialog" :title="titles[dialog]" width="min(600px,94vw)" :close-on-click-modal="false" :show-close="!busy" @close="dialog=''"><el-form label-position="top">
  <template v-if="dialog==='link'"><el-form-item label="链接名称" required><el-input v-model="form.title" maxlength="240"/></el-form-item><el-form-item label="HTTPS地址" required><el-input v-model="form.url" maxlength="2000" placeholder="https://…"/></el-form-item><el-form-item><el-checkbox v-model="form.codeLink">这是代码仓库链接</el-checkbox></el-form-item><el-form-item label="代码提交编号或标签" :required="form.codeLink"><el-input v-model="form.codeRef" maxlength="200" placeholder="例如提交哈希 a1b2c3d，或标签 v1.0"/></el-form-item></template>
  <template v-if="dialog==='grant'"><el-alert type="info" :closable="false" title="仅授权当前团队，每次最长7个自然日；不修改最终截止、旧受理时间或冻结引用。"/><el-form-item label="窗口开始时间（北京时间）" required><input v-model="form.startsAt" class="native-date" type="datetime-local" step="1"></el-form-item><el-form-item label="窗口结束时间（北京时间）" required><input v-model="form.endsAt" class="native-date" type="datetime-local" step="1"></el-form-item></template>
  <template v-if="dialog==='verify'||dialog==='bind'"><p>{{versionName(targetVersionId)}}</p><el-form-item required><el-checkbox v-model="form.accessibleConfirmed">已核对本版本文件和外部链接可访问</el-checkbox></el-form-item><el-form-item v-if="dialog==='bind'"><el-checkbox v-model="form.reassessmentRequired">更换已有被评版本，需安排重评并保留旧引用</el-checkbox></el-form-item></template>
  <el-form-item v-if="dialog!=='link'" label="原因或核实依据" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="2000"/></el-form-item>
 </el-form><template #footer><el-button :disabled="busy||loading" @click="dialog=''">取消</el-button><el-button type="primary" :loading="busy" @click="saveDialog">确认保存</el-button></template></el-dialog>
 <el-drawer v-model="historyOpen" title="成果版本与异常处理记录" size="min(780px,96vw)"><article v-for="e in events" :key="e.id" class="record-card"><strong>{{eventNames[e.action]||e.action}} · {{e.object_id}}</strong><p>{{e.actor_name||'系统截止事件'}} · {{stamp(e.occurred_at)}}</p><p>{{e.reason}}</p><details><summary>查看变更前后内容</summary><h4>变更前</h4><pre>{{eventData(e.before_data)}}</pre><h4>变更后</h4><pre>{{eventData(e.after_data)}}</pre></details></article><el-empty v-if="!events.length" description="暂无成果过程记录"/><el-button v-if="moreHistory" :disabled="busy||loading" @click="history(true)">加载更早记录</el-button></el-drawer>
</template>

<style scoped>
.submission-selectors,.submission-header,.submission-bar,.submission-actions{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.submission-selectors{justify-content:flex-start;margin:20px 0}.submission-selectors label{display:flex;gap:10px;align-items:center}.submission-selectors .el-select{width:290px}.submission-header{margin:24px 0}.reference-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-bottom:20px}.reference-grid article,.submission-panel{border:1px solid #e3e9ee;background:white;border-radius:12px;padding:22px}.reference-grid span,.reference-grid strong,.reference-grid small{display:block}.reference-grid span{font-size:12px;color:#718189;margin-bottom:12px}.reference-grid strong{line-height:1.6;overflow-wrap:anywhere}.reference-grid small{color:#718189;font-size:12px;line-height:1.8;margin-top:10px}.submission-panel h3{margin-bottom:10px}.submission-bar{margin-bottom:18px}.submission-compose{background:#f6f9f9;padding:20px;border-radius:10px;margin-top:22px}.submission-compose p{margin:12px 0}.form-label{display:block;font-size:14px;margin-bottom:10px}.submission-note{margin:15px 0}.version-tags,.row-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:9px}.row-actions{flex-direction:column;align-items:flex-start}.row-actions .el-button{margin:0}.download{color:var(--el-color-primary);font-size:13px;margin-right:12px}.record-card{border:1px solid #e3e9ee;border-radius:8px;padding:16px;margin:15px 0;overflow-wrap:anywhere}.record-card p,.receipt p{font-size:13px;line-height:1.9;white-space:pre-wrap}.record-card pre{background:#f6f8fa;padding:12px;white-space:pre-wrap;font-size:12px}.receipt{background:#f1f7f5;padding:18px;border-radius:8px;overflow-wrap:anywhere}.receipt strong{display:block;margin-bottom:10px}.native-date{width:100%;border:1px solid #dcdfe6;border-radius:6px;padding:10px;color:#344054}.limit-error{color:#c45656}.section-gap{margin-top:26px}.el-table p{font-size:12px;line-height:1.8;white-space:pre-wrap}.submission-actions{justify-content:flex-start;margin:12px 0}@media(max-width:1050px){.reference-grid{grid-template-columns:1fr}.submission-panel{padding:16px}}@media(max-width:600px){.submission-selectors .el-select{width:220px}.submission-panel{padding:12px}.submission-compose{padding:12px}}
</style>
