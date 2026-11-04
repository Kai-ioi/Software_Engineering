package cn.edu.school.workflow;

import cn.edu.school.collaboration.ProjectTeamGateway;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class WorkflowHistory {
    private final JdbcTemplate db;
    private final ProjectTeamGateway gateway;
    private final CoursePermission permission;

    public WorkflowHistory(JdbcTemplate db, ProjectTeamGateway gateway, CoursePermission permission) {
        this.db=db; this.gateway=gateway; this.permission=permission;
    }

    public List<Map<String,Object>> list(long user,long teamId,String type) {
        return list(user,teamId,type,null);
    }

    public List<Map<String,Object>> list(long user,long teamId,String type,String objectRef) {
        return page(user,teamId,type,objectRef,null,null,null,300);
    }

    public List<Map<String,Object>> page(long user,long teamId,String type,String objectRef,
            java.time.OffsetDateTime before,String category,Long eventId,int limit) {
        if((before!=null||category!=null||eventId!=null)&&(before==null||category==null||eventId==null))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","过程记录分页参数不完整");
        var team=gateway.team(teamId,false);
        long projectId=((Number)team.get("project_id")).longValue();
        var project=gateway.project(projectId);
        long courseId=((Number)project.get("course_id")).longValue();
        var role=permission.requireMember(user,courseId);
        permission.requireTeamScope(role,user,teamId);
        if (role==CourseRole.STUDENT && !gateway.member(teamId,user))
            throw new BusinessException(HttpStatus.FORBIDDEN,"HISTORY_FORBIDDEN","只能查看本团队的过程记录");
        return db.queryForList("""
            SELECT * FROM (
              SELECT '协作' AS category,id AS event_id,action,CAST(object_id AS VARCHAR(100)) AS object_ref,reason AS detail,occurred_at FROM collaboration_event WHERE team_id=?
              UNION ALL SELECT '成果',id,action,object_id,reason,occurred_at FROM submission_event WHERE team_id=?
              UNION ALL SELECT '检查',id,'INSPECTION_CREATED',CAST(id AS VARCHAR(100)),conclusion,checked_at FROM inspection WHERE team_id=?
              UNION ALL SELECT '互评',s.id,'REVIEW_SUBMITTED',CAST(s.id AS VARCHAR(100)),'收到一条互评反馈',s.submitted_at
                FROM review_submission s JOIN submission_review_binding b ON b.id=s.binding_id
                JOIN review_task k ON k.id=s.task_id JOIN review_round r ON r.id=k.round_id
                WHERE b.team_id=? AND (r.feedback_open=TRUE OR ?)
              UNION ALL SELECT '成绩',g.id,CASE WHEN g.parent_id IS NULL THEN 'GRADE_PUBLISHED' ELSE 'GRADE_CORRECTED' END,
                CAST(g.id AS VARCHAR(100)),'成绩版本已发布',g.published_at FROM grade_result g WHERE g.team_id=?
                AND (? OR EXISTS(SELECT 1 FROM grade_member_result m WHERE m.result_id=g.id AND m.user_id=?))
              UNION ALL SELECT '项目',id,'PROJECT_REQUIREMENTS_CHANGED',CAST(id AS VARCHAR(100)),summary,occurred_at
                FROM project_change WHERE project_id=?
              UNION ALL SELECT '组队申请',e.id,'TEAM_APPLICATION_EXPIRED',CAST(e.member_id AS VARCHAR(100)),
                '组队窗口截止，未处理的加入申请已失效',e.expired_at
                FROM expired_team_application e JOIN project_team_member m ON m.id=e.member_id WHERE m.team_id=?
              UNION ALL SELECT '成员',a.id,a.action,a.object_id,a.summary,a.occurred_at FROM audit_record a
                WHERE a.course_id=? AND a.object_id=? AND a.action IN
                ('TEAM_CREATED','TEAM_APPROVED','TEAM_ASSIGNED','TEAM_LEFT','TEAM_MEMBER_REMOVED','TEAM_LEADER_CHANGED','TEAM_MEMBER_MOVED')
            ) events WHERE (CAST(? AS VARCHAR(32)) IS NULL OR category=?) AND (CAST(? AS VARCHAR(100)) IS NULL OR object_ref=?)
            AND (CAST(? AS TIMESTAMP WITH TIME ZONE) IS NULL OR occurred_at<?
                OR (occurred_at=? AND (category>? OR (category=? AND event_id<?))))
            ORDER BY occurred_at DESC,category,event_id DESC LIMIT ?
            """,teamId,teamId,teamId,teamId,role==CourseRole.TEACHER,teamId,role==CourseRole.TEACHER,user,
            projectId,teamId,courseId,Long.toString(teamId),type,type,objectRef,objectRef,before,before,before,category,category,eventId,limit);
    }
}
