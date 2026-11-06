import { watch, type Ref } from 'vue'
import { auth } from './auth'

export interface WorkspaceContext { courseId?: number; projectId?: number; teamId?: number }
const key = () => `course-workspace-${auth.user?.id ?? 'guest'}`
export function readWorkspace(): WorkspaceContext {
  try { return JSON.parse(sessionStorage.getItem(key()) || '{}') } catch { return {} }
}
export function rememberWorkspace(next: WorkspaceContext) {
  const old = readWorkspace()
  const changedCourse = next.courseId != null && next.courseId !== old.courseId
  const changedProject = next.projectId != null && next.projectId !== old.projectId
  const result = { ...old, ...(changedCourse ? { projectId: undefined, teamId: undefined } : {}), ...(changedProject ? { teamId: undefined } : {}), ...next }
  try { sessionStorage.setItem(key(), JSON.stringify(result)) } catch { /* Navigation remains usable without browser storage. */ }
}
export function followWorkspace(course: Ref<number | undefined>, team: Ref<number | undefined>, lookup: () => { id: number; project_id: number }[]) {
  watch([course, team], ([courseId, teamId]) => {
    if (!courseId) return
    const selected = lookup().find(t => t.id === teamId)
    rememberWorkspace(selected ? { courseId, teamId: selected.id, projectId: selected.project_id } : { courseId })
  }, { flush: 'post' })
}

export function scopeTeams<T extends { project_id: number }>(rows: T[], query: { courseId?: unknown; projectId?: unknown }, courseId?: number): T[] {
  const projectId = Number(query.projectId)
  return projectId && Number(query.courseId) === courseId ? rows.filter(row => row.project_id === projectId) : rows
}
