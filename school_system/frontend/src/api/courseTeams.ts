import { request } from './http'
export interface CourseTeamMember { id:number;user_id:number;display_name:string;username:string;status:'ACTIVE'|'INVITED'|'DECLINED'|'CANCELLED' }
export interface CourseTeam { id:number;name:string;leader_id:number;leader_name:string;members:CourseTeamMember[];projects:{project_id:number;work_team_id:number;title:string;status:string}[] }
export interface CourseTeamWorkspace {role:'TEACHER'|'STUDENT'|'TA';requiredProjects:number;minSize:number;maxSize:number;myTeamId:number|null;teams:CourseTeam[];invitations:(CourseTeamMember&{team_id:number;team_name:string})[]}
export const getCourseTeams=(course:number)=>request<CourseTeamWorkspace>(`/courses/${course}/teams`)
export const courseTeamAction=(course:number,path:string,body:object,method='POST')=>request(`/courses/${course}/teams${path}`,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
export const getCourseTeamCandidates=(course:number,team:number)=>request<{id:number;display_name:string;username:string}[]>(`/courses/${course}/teams/${team}/candidates`)
