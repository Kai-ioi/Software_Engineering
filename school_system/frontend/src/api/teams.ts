import { request } from './http'
import type { CourseRole } from './courses'

export type MemberStatus = 'PENDING' | 'ACTIVE' | 'REJECTED' | 'LEFT'

export interface TeamMember {
  id: number
  userId: number
  displayName: string
  status: MemberStatus
  joinedAt: string | null
}

export interface Team {
  id: number
  projectId: number
  name: string
  leaderId: number
  leaderName: string
  activeCount: number
  minTeamSize: number
  maxTeamSize: number
  full: boolean
  complete: boolean
  members: TeamMember[]
  applications: TeamMember[]
  myRole: CourseRole
  canManage: boolean
}

function jsonInit(method: string, body?: unknown): RequestInit {
  return {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  }
}

export const getTeams = (courseId: number, projectId: number) =>
  request<Team[]>(`/courses/${courseId}/projects/${projectId}/teams`)
export const getTeam = (courseId: number, projectId: number, teamId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}`)
export const createTeam = (courseId: number, projectId: number, name: string) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams`, jsonInit('POST', { name }))
export const applyTeam = (courseId: number, projectId: number, teamId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/apply`, jsonInit('POST'))
export const approveMember = (courseId: number, projectId: number, teamId: number, memberId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/members/${memberId}/approve`, jsonInit('POST'))
export const rejectMember = (courseId: number, projectId: number, teamId: number, memberId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/members/${memberId}/reject`, jsonInit('POST'))
export const leaveTeam = (courseId: number, projectId: number, teamId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/leave`, jsonInit('POST'))
export const removeMember = (courseId: number, projectId: number, teamId: number, memberId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/members/${memberId}/remove`, jsonInit('POST'))
export const transferLeadership = (courseId: number, projectId: number, teamId: number, newLeaderUserId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/transfer-leadership`, jsonInit('POST', { newLeaderUserId }))
export const transferMember = (courseId: number, projectId: number, teamId: number, memberId: number, targetTeamId: number) =>
  request<Team>(`/courses/${courseId}/projects/${projectId}/teams/${teamId}/members/${memberId}/transfer`, jsonInit('POST', { targetTeamId }))
