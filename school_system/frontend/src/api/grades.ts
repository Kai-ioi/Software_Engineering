import { request } from './http'

export interface Criterion { id: number; rubric_id: number; name: string; description: string; max_score: number; sort_order: number }
export interface Rubric { id: number; course_id: number; name: string; version_no: number; active: boolean; created_at: string }
export interface GradeTeam {
  id: number; name: string; project_id: number; project_name: string
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

export const getOverview = (course: number) => request<Overview>(`/grades/courses/${course}`)
export const getTeamSheet = (team: number) => request<TeamSheet>(`/grades/teams/${team}`)
export const saveDraft = (team: number, rubricId: number, scores: ScoreItem[]) =>
  request<SheetResult>(`/grades/teams/${team}/draft`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rubricId, scores }) })
export const submit = (team: number, rubricId: number, scores: ScoreItem[]) =>
  request<SheetResult>(`/grades/teams/${team}/submit`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ rubricId, scores }) })
export const modify = (sheet: number, scores: ScoreItem[], reason: string) =>
  request<SheetResult>(`/grades/sheets/${sheet}/modify`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scores, reason }) })
