import { request } from './http'
export type WorkStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'ENDED'
export interface TeamOption { id: number; name: string; project_id: number; project_name: string; project_status: string; demo_fixture: boolean }
export interface Iteration { id: number; name: string; goal: string; sequence_no: number; planned_start: string; planned_end: string; status: WorkStatus; completion_summary: string | null; leftovers: string | null }
export interface Task { id: number; iteration_id: number; original_iteration_id: number; requirement_id: number; title: string; description: string; assignee_id: number; planned_start: string; planned_end: string; status: WorkStatus; progress_note: string | null }
export interface Milestone { id: number; iteration_id: number; title: string; target_date: string; completion_criteria: string; completed: boolean; completion_note: string | null }
export interface Workspace {
 team: { id: number; name: string; project_id: number; leader_id: number; revision: number }
 project: { id: number; name: string; course_id: number; status: string; final_deadline: string; demo_fixture: boolean }
 role: 'TEACHER' | 'STUDENT' | 'TA'; isLeader: boolean
 members: { id: number; display_name: string; username: string }[]
 requirements: { id: number; title: string; description: string }[]
 iterations: Iteration[]; tasks: Task[]; milestones: Milestone[]
}
export interface Event { id: number; actor_name: string; action: string; object_type: string; object_id: number; reason: string; before_data: string; after_data: string; occurred_at: string }
export const statusNames: Record<WorkStatus, string> = { NOT_STARTED: '未开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', ENDED: '已结束' }
export const projectStatusNames: Record<string,string> = { DRAFT: '草稿', PUBLISHED: '已发布', IN_PROGRESS: '进行中', ENDED: '已结束', TERMINATED: '已终止', ARCHIVED: '已归档' }
export const getTeams = (course: number) => request<TeamOption[]>(`/collaboration/teams?courseId=${course}`)
export const getWorkspace = (team: number) => request<Workspace>(`/collaboration/teams/${team}`)
export const getHistory = (team: number, before = '9223372036854775807') => request<Event[]>(`/collaboration/teams/${team}/history?beforeId=${before}`)
export const mutate = (team: number, path: string, payload: object, method = 'POST') => request(`/collaboration/teams/${team}/${path}`, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
