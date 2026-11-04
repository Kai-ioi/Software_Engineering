package cn.edu.school.inspection;

import cn.edu.school.audit.AuditService;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.collaboration.ProjectTeamGateway;
import cn.edu.school.common.ApiResponse;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inspections/teams/{team}/assistant-scope")
public class AssistantScopeController {
    private final JdbcTemplate db;
    private final ProjectTeamGateway gateway;
    private final CoursePermission permission;
    private final AuditService audit;

    public AssistantScopeController(JdbcTemplate db,ProjectTeamGateway gateway,CoursePermission permission,AuditService audit) {
        this.db=db; this.gateway=gateway; this.permission=permission; this.audit=audit;
    }

    private long course(long teacher,long team,boolean lock) {
        var t=gateway.team(team,lock);
        long project=((Number)t.get("project_id")).longValue();
        long course=((Number)gateway.project(project).get("course_id")).longValue();
        permission.requireTeacher(teacher,course);
        return course;
    }

    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal UserPrincipal user,@PathVariable long team) {
        long course=course(user.id(),team,false);
        return ApiResponse.ok(db.queryForList("""
            SELECT u.id,u.display_name,CASE WHEN g.user_id IS NULL THEN FALSE ELSE TRUE END AS granted
            FROM course_membership m JOIN app_user u ON u.id=m.user_id
            LEFT JOIN assistant_team_scope g ON g.team_id=? AND g.user_id=u.id
            WHERE m.course_id=? AND m.role='TA' AND m.active=TRUE AND u.enabled=TRUE ORDER BY u.id
            """,team,course));
    }

    public record Grant(boolean granted) {}

    @PutMapping("/{assistant}")
    @Transactional
    public ApiResponse<?> set(@AuthenticationPrincipal UserPrincipal user,@PathVariable long team,
                              @PathVariable long assistant,@RequestBody Grant input) {
        long course=course(user.id(),team,true);
        if(input.granted() && permission.requireMember(assistant,course)!=CourseRole.TA)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"ASSISTANT_REQUIRED","请选择本课程的助教");
        int changed=input.granted()
            ? db.update("""
                INSERT INTO assistant_team_scope(team_id,user_id) SELECT ?,?
                WHERE NOT EXISTS(SELECT 1 FROM assistant_team_scope WHERE team_id=? AND user_id=?)
                """,team,assistant,team,assistant)
            : db.update("DELETE FROM assistant_team_scope WHERE team_id=? AND user_id=?",team,assistant);
        if(changed>0)audit.append(user.id(),course,"ASSISTANT_SCOPE_CHANGED",Long.toString(team),
            (input.granted()?"授权助教 #":"撤销助教 #")+assistant+" 的团队检查权限");
        return ApiResponse.ok(null);
    }
}
