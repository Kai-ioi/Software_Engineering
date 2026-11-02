import { request } from './http'
export interface PreparedMaterial { id:number; iteration_id:number; kind:'FILE'|'LINK'; title:string; url:string|null; code_link:boolean; code_ref:string|null; size_bytes:number|null; sha256:string|null; creator_name:string; created_by:number }
export interface SubmittedVersion { id:number; package_id:number; iteration_id:number; version_no:number; summary:string; accepted_at:string; deadline_snapshot:string; late:boolean; extension_id:number|null; total_bytes:number; request_key:string; receipt_code:string; submitter_name:string }
export interface VersionMaterial { id:number; kind:'FILE'|'LINK'; title:string; url:string|null; code_link:boolean; code_ref:string|null; size_bytes:number; sha256:string|null }
export interface VersionDetail extends SubmittedVersion { materials:VersionMaterial[] }
export interface SubmissionWorkspace {
 team:{id:number;name:string;revision:number};project:{id:number;name:string;status:string;final_deadline:string;demo_fixture:boolean}
 role:'STUDENT'|'TEACHER'|'TA';isLeader:boolean;canPrepare:boolean;canSubmit:boolean;canSelect:boolean;serverTime:string
 delivery:{final_version_id:number|null;selected_at:string|null;frozen_at:string|null;frozen_deadline:string|null;review_binding_id:number|null}
 iterations:{id:number;name:string;status:string}[];materials:PreparedMaterial[];versions:SubmittedVersion[];packages:{id:number;iteration_id:number;current_version_id:number|null}[]
 extensions:{id:number;starts_at:string;ends_at:string;reason:string;granted_by:number}[]
 requests:{id:number;reason:string;status:'PENDING'|'APPROVED'|'REJECTED';requested_at:string;requester_name:string;resolution:string|null}[]
 reviewBindings:{id:number;version_id:number;reason:string;reassessment_required:boolean;confirmed_at:string}[]
 notices:{id:number;title:string;created_at:string;read_at:string|null}[];exception:string
}
export interface SubmissionEvent { id:number;actor_name:string|null;action:string;object_id:string;reason:string;before_data:string;after_data:string;occurred_at:string }
export const getSubmissionWorkspace=(team:number)=>request<SubmissionWorkspace>(`/submissions/teams/${team}`)
export const submissionAction=<T=unknown>(team:number,path:string,payload:object)=>request<T>(`/submissions/teams/${team}/${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
export const getVersion=(team:number,id:number)=>request<VersionDetail>(`/submissions/teams/${team}/versions/${id}`)
export const getReceipt=(team:number,key:string)=>request<VersionDetail>(`/submissions/teams/${team}/receipts/${key}`)
export const getSubmissionHistory=(team:number,before='9223372036854775807')=>request<SubmissionEvent[]>(`/submissions/teams/${team}/history?beforeId=${before}`)
