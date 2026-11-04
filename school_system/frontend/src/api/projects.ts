import { request } from './http'
import type { CourseRole } from './courses'

export type ProjectStatus = 'DRAFT' | 'PUBLISHED' | 'IN_PROGRESS' | 'ENDED' | 'TERMINATED' | 'ARCHIVED'

export interface Criterion { id?: number; name: string; weight: number; description?: string }

export interface Project {
  id: number
  courseId: number
  title: string
  description: string
  deadline: string | null
  teacherWeight?: number
  peerWeight?: number
  minTeamSize: number
  maxTeamSize: number
  status: ProjectStatus
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  myRole: CourseRole
  criteria: Criterion[]
}

export interface ProjectInput {
  changeReason?: string
  remediationUntil?: string
  title: string
  description: string
  deadline: string | null
  teacherWeight?: number
  peerWeight?: number
  minTeamSize: number
  maxTeamSize: number
  criteria: { name: string; weight: number; description?: string }[]
}

export const statusName: Record<ProjectStatus, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', IN_PROGRESS: '进行中', ENDED: '已结束', TERMINATED: '已终止', ARCHIVED: '已归档'
}

function jsonInit(method: string, body?: unknown): RequestInit {
  return {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  }
}

export const getProjects = (courseId: number) => request<Project[]>(`/courses/${courseId}/projects`)
export const getProject = (courseId: number, projectId: number) => request<Project>(`/courses/${courseId}/projects/${projectId}`)
export const createProject = (courseId: number, input: ProjectInput) => request<Project>(`/courses/${courseId}/projects`, jsonInit('POST', input))
export const updateProject = (courseId: number, projectId: number, input: ProjectInput) => request<Project>(`/courses/${courseId}/projects/${projectId}`, jsonInit('PUT', input))
export const publishProject = (courseId: number, projectId: number) => request<Project>(`/courses/${courseId}/projects/${projectId}/publish`, jsonInit('POST'))
export const closeProject = (courseId: number, projectId: number) => request<Project>(`/courses/${courseId}/projects/${projectId}/close`, jsonInit('POST'))
export const archiveProject = (courseId: number, projectId: number) => request<Project>(`/courses/${courseId}/projects/${projectId}/archive`, jsonInit('POST'))

export const startProject = (courseId: number, projectId: number) => request<Project>(`/courses/${courseId}/projects/${projectId}/start`, jsonInit('POST'))
export const endProject = (courseId: number, projectId: number) => request<Project>(`/courses/${courseId}/projects/${projectId}/end`, jsonInit('POST'))

export interface ProjectChange {
  id: number; summary: string; before_title: string; after_title: string;
  before_description: string; after_description: string; before_deadline: string | null; after_deadline: string | null;
  reason: string; remediation_until: string | null; occurred_at: string; affected_teams: number
}
export const getProjectChanges = (course: number, project: number) =>
  request<ProjectChange[]>(`/courses/${course}/projects/${project}/changes`)
