import { request } from './http'

// —— 同伴互评（模块5） ——
export interface ReviewProject { id: number; name: string; status: string; demo_fixture: boolean; roundCount: number; bindingCount: number; myPendingTasks: number }
export interface ReviewBinding { binding_id: number; team_id: number; team_name: string; version_id: number; version_no: number; iteration_name: string; summary: string; confirmed_at: string; reassessment_required: boolean }
export interface ReviewRound { id: number; project_id: number; name: string; deadline: string; status: 'OPEN' | 'CLOSED'; feedback_open: boolean; created_at: string; task_count: number; submitted_count: number }
export interface MyTask { rubric_id: number | null; criteria: { id: number; name: string; description: string; max_score: number }[]; dimension_scores: {criterion_id: number; score: number}[]; id: number; round_id: number; round_name: string; deadline: string; binding_id: number; status: 'PENDING' | 'IN_REVIEW' | 'UNDER_REVIEW' | 'SUBMITTED' | 'CANCELLED'; overdue: boolean; reevaluation: boolean; team_id: number; team_name: string; version_id: number; version_no: number; iteration_name: string; summary: string; submitted_score: number | null; submitted_comment: string | null; submitted_at: string | null }
export interface ReceivedReview { id: number; binding_id: number; score: number; comment: string; submitted_at: string; round_id: number; round_name: string }
export interface Appeal { id: number; round_id: number; binding_id: number; team_id: number; reason: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; requested_at: string; resolution: string | null; requested_by_name: string; team_name: string }
export interface ReviewProgress { submission_id: number | null; superseded_by: number | null; id: number; round_id: number; round_name: string; binding_id: number; reviewer_id: number; status: string; reevaluation: boolean; reviewer_name: string; team_name: string; version_no: number; score: number | null; comment: string | null }
export interface ReviewWorkspace {
  project: { id: number; name: string; status: string; final_deadline: string; demo_fixture: boolean }
  role: 'TEACHER' | 'STUDENT' | 'TA'; isTeacher: boolean; isStudent: boolean; isTA: boolean; serverTime: string; myTeamId: number | null
  bindings: ReviewBinding[]; rounds: ReviewRound[]; myTasks: MyTask[]; receivedReviews: ReceivedReview[]; appeals: Appeal[]; progress?: ReviewProgress[]
  students?: { id: number; display_name: string; username: string }[]
}

// —— 助教检查（模块6） ——
export interface InspectionTeam { id: number; name: string; project_id: number; project_name: string; project_status: string; demo_fixture: boolean }
export interface InspectionVersion { id: number; iteration_id: number; version_no: number; summary: string; accepted_at: string; submitter_name: string; iteration_name: string }
export interface InspectionRecord { id: number; team_id: number; version_id: number | null; checker_id: number; conclusion: string; feedback: string | null; rectification_required: boolean; reported: boolean; checked_at: string; parent_id: number | null; checker_name: string }
export interface InspectionWorkspace {
  missing: boolean; progress: { id: number; name: string; status: string; task_count: number; completed_count: number }[]
  team: { id: number; name: string }; project: { id: number; name: string; status: string; demo_fixture: boolean }
  role: 'TEACHER' | 'STUDENT' | 'TA'; isTA: boolean; isTeacher: boolean; isStudent: boolean; isLeader: boolean; serverTime: string
  versions: InspectionVersion[]; binding: { binding_id: number | null; version_id: number | null; version_no: number | null }[]
  inspections: InspectionRecord[]
}

const post = <T = unknown>(path: string, payload: object) => request<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
export const getReviewProjects = (courseId: number) => request<ReviewProject[]>(`/reviews/projects?courseId=${courseId}`)
export const getReviewWorkspace = (projectId: number) => request<ReviewWorkspace>(`/reviews/projects/${projectId}`)
export const createRound = (projectId: number, name: string, deadline: string) => post<{ id: number }>(`/reviews/projects/${projectId}/rounds`, { name, deadline })
export const autoAssign = (roundId: number) => post<{ created: number }>(`/reviews/rounds/${roundId}/assign`, {})
export const addReviewTask = (roundId: number, reviewerId: number, bindingId: number) => post<{ id: number }>(`/reviews/rounds/${roundId}/tasks`, { reviewerId, bindingId })
export const cancelTask = (taskId: number, reason: string) => post(`/reviews/tasks/${taskId}/cancel`, { reason })
export const extendRound = (roundId: number, deadline: string) => post(`/reviews/rounds/${roundId}/extend`, { deadline })
export const closeRound = (roundId: number) => post(`/reviews/rounds/${roundId}/close`, {})
export const toggleFeedback = (roundId: number, open: boolean) => post(`/reviews/rounds/${roundId}/feedback`, { open })
export const submitReview = (taskId: number, score: number, comment: string, scores?: { criterionId: number; score: number }[]) => post(`/reviews/tasks/${taskId}/submit`, { score, comment, scores })
export const appealReview = (bindingId: number, reason: string) => post<{ id: number }>(`/reviews/appeals`, { bindingId, reason })
export const approveAppeal = (appealId: number, reviewerId: number, resolution: string, submissionId?: number) => post(`/reviews/appeals/${appealId}/approve`, { reviewerId, resolution, submissionId })
export const rejectAppeal = (appealId: number, resolution: string) => post(`/reviews/appeals/${appealId}/reject`, { resolution })
export const getInspectionTeams = (courseId: number) => request<InspectionTeam[]>(`/inspections/teams?courseId=${courseId}`)
export const getInspectionWorkspace = (teamId: number) => request<InspectionWorkspace>(`/inspections/teams/${teamId}`)
export const createInspection = (teamId: number, payload: object) => post<{ id: number }>(`/inspections/teams/${teamId}`, payload)

export const startReview = (taskId: number) => post(`/reviews/tasks/${taskId}/start`, {})
