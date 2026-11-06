import { request } from './http'

export interface Criterion { id: number; rubric_id: number; name: string; description: string; max_score: number; sort_order: number }
export interface Rubric { id: number; course_id: number; name: string; version_no: number; active: boolean; created_at: string; teacher_weight: number; peer_weight: number }
export interface GradeTeam {
  id: number; name: string; project_id: number; project_name: string; demo_fixture: boolean
  binding_id: number; version_id: number; confirmed_at: string
  sheet_id: number | null; sheet_status: 'DRAFT' | 'SUBMITTED' | null; submitted_at: string | null; last_reason: string | null
}
export interface ScoreItem { criterionId: number; score: number }
export interface ScoreRow { id: number; sheet_id: number; criterion_id: number; score: number }
export interface VersionMaterial { id: number; kind: 'FILE' | 'LINK'; title: string; url: string | null }
export interface TeamSheet {
  team: { id: number; name: string }; project: { id: number; name: string }
  rubric: Rubric; criteria: Criterion[]
  binding: { id: number; version_id: number; reason: string; confirmed_at: string }
  version: { id: number; version_no: number; summary: string; submitter_name: string; accepted_at: string }
  materials: VersionMaterial[]
  sheet: { id: number; status: 'DRAFT' | 'SUBMITTED'; submitted_at: string | null; last_reason: string | null } | null
  scores: ScoreRow[]
}
export interface Overview { role: 'TEACHER' | 'STUDENT' | 'TA'; rubric: Rubric; criteria: Criterion[]; teams: GradeTeam[] }
export interface SheetResult { sheet: { id: number; status: 'DRAFT' | 'SUBMITTED'; submitted_at: string | null; last_reason: string | null }; scores: ScoreRow[]; rubric: Rubric; criteria: Criterion[] }

// Module 8 types
export interface PublishedResult { id: number; version_no: number; teacher_total: number; peer_total: number; final_total: number; teacher_weight: number; peer_weight: number; status: string; correction_reason: string | null; published_at: string }
export interface MemberResult { id: number; result_id: number; user_id: number; display_name: string; final_total: number }
export interface SummaryTeam {
  team_id: number; team_name: string; project_id: number; project_name: string; demo_fixture: boolean
  binding_id: number; version_id: number
  sheet_id: number | null; sheet_status: string | null; submitted_at: string | null; graded_by: number | null; graded_by_name: string | null
  roster_id: number | null; member_ids: string | null
  teacher_total: number | null; peer_avg: number | null; peer_missing: boolean; final_total: number | null
  teacher_weight: number; peer_weight: number
  exceptions: string[]
  published: PublishedResult | null; member_results: MemberResult[]
}
export interface Summary { rubric: Rubric; criteria: Criterion[]; teams: SummaryTeam[]; serverTime: string }
export interface RosterResult { teamId: number; memberCount: number; members: { id: number; display_name: string; username: string }[] }
export interface MyGrade { results: { id: number; course_id: number; team_id: number; teacher_total: number; peer_total: number; final_total: number; teacher_weight: number; peer_weight: number; status: string; version_no: number; correction_reason: string | null; published_at: string; team_name: string; project_id: number; project_name: string; course_name: string; evaluated_version_no: number | null }[] }

export const getOverview = (course: number) => request<Overview>(`/grades/courses/${course}`)
export const getTeamSheet = (team: number) => request<TeamSheet>(`/grades/teams/${team}`)
export const saveDraft = (team: number, rubricId: number, scores: ScoreItem[]) =>
  request<SheetResult>(`/grades/teams/${team}/draft`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rubricId, scores }) })
export const submit = (team: number, rubricId: number, scores: ScoreItem[]) =>
  request<SheetResult>(`/grades/teams/${team}/submit`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rubricId, scores }) })
export const modify = (sheet: number, scores: ScoreItem[], reason: string) =>
  request<SheetResult>(`/grades/sheets/${sheet}/modify`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scores, reason }) })

export const getSummary = (course: number) => request<Summary>(`/grades/courses/${course}/summary`)
export const confirmRoster = (team: number) => request<RosterResult>(`/grades/teams/${team}/roster`, { method: 'POST' })
export const publish = (course: number, reason: string, teamId?: number, includeSamples?: boolean, projectId?: number) => request<{ publishedTeams: number }>(`/grades/courses/${course}/publish`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason, teamId, includeSamples, projectId }) })
export const correct = (result: number, reason: string) => request<{ correctedId: number; versionNo: number }>(`/grades/results/${result}/correct`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }) })
export const getMyGrades = () => request<MyGrade>('/grades/my')
export const exportUrl = (course: number, format = 'csv', filename = '') => `/api/grades/courses/${course}/export?format=${format}&filename=${encodeURIComponent(filename || 'grades')}`
export interface GradeEvidence {
  id: number; version_no: number; evaluated_version_id: number | null; evaluated_version_no: number | null;
  score_snapshot: string | null; peer_snapshot: string | null; parent_id: number | null
}
export const getGradeEvidence = (id: number) => request<GradeEvidence>(`/grades/results/${id}/evidence`)
