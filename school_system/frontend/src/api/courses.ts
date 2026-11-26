import { request } from './http'
export type CourseRole = 'TEACHER' | 'STUDENT' | 'TA'
export interface Course { id: number; name: string; semester: string; description: string; status: string; myRole: CourseRole }
export const roleName: Record<CourseRole, string> = { TEACHER: '任课教师', STUDENT: '学生', TA: '助教' }
export const getCourses = () => request<Course[]>('/courses')
export const getCourse = (id: number) => request<Course>(`/courses/${id}`)
export const getManagement = (id: number) => request<{ courseId: number; studentCount: number }>(`/courses/${id}/management`)
export interface CourseMember { displayName: string; username: string; role: CourseRole }
export const getCourseMembers = (id: number) => request<CourseMember[]>(`/courses/${id}/members`)
export interface Audit { id: number; actorId: number; action: string; objectId: string; summary: string; occurredAt: string }
export const getAudit = (id: number) => request<{ items: Audit[]; total: number }>(`/courses/${id}/audit`)
