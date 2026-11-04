package cn.edu.school.workflow;

import cn.edu.school.audit.AuditService;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CourseRole;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/directory")
public class PlatformDirectory {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public PlatformDirectory(JdbcTemplate db, PasswordEncoder passwords, AuditService audit) {
        this.db=db; this.passwords=passwords; this.audit=audit;
    }

    private void requireAdmin(UserPrincipal user) {
        if(!user.platformAdmin() || db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND platform_admin=TRUE AND enabled=TRUE",Long.class,user.id())!=1)
            throw new BusinessException(HttpStatus.FORBIDDEN,"PLATFORM_FORBIDDEN","仅平台管理员可维护基础资料");
    }
    private String field(String value,int max,String name) {
        if(value==null||value.isBlank()||value.strip().length()>max)throw invalid(name+"为空或超过长度限制");
        return value.strip();
    }
    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_DIRECTORY",message);
    }

    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal UserPrincipal user) {
        requireAdmin(user);
        return ApiResponse.ok(Map.of(
            "users",db.queryForList("SELECT id,username,display_name,enabled FROM app_user ORDER BY id"),
            "courses",db.queryForList("SELECT id,name,semester,status FROM course ORDER BY id"),
            "memberships",db.queryForList("SELECT course_id,user_id,role,active FROM course_membership ORDER BY course_id,user_id"),
            "failedNotices",db.queryForList("""
                SELECT audit_id AS event_id,course_id,action,attempts,'workflow' AS source
                FROM workflow_notice_job WHERE done=FALSE AND attempts>=4
                UNION ALL SELECT e.id,p.course_id,e.action,e.notice_attempts,'submission'
                FROM submission_event e JOIN project_team t ON t.id=e.team_id JOIN course_project p ON p.id=t.project_id
                WHERE e.notice_status='FAILED' ORDER BY source,event_id
                """)));
    }

    @PostMapping("/notices/submission/{id}/retry")
    @Transactional
    public ApiResponse<?> retrySubmission(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id) {
        requireAdmin(user);
        var result=db.queryForList("""
            SELECT p.course_id FROM submission_event e JOIN project_team t ON t.id=e.team_id
            JOIN course_project p ON p.id=t.project_id WHERE e.id=?
            """,Long.class,id);
        if(result.isEmpty() || db.update("""
            UPDATE submission_event SET notice_status='PENDING',notice_attempts=0 WHERE id=? AND notice_status='FAILED'
            """,id)!=1)throw invalid("通知不存在或仍在投递");
        audit.append(user.id(),result.getFirst(),"NOTICE_REQUEUED","submission:"+id,"管理员重新投递成果通知");
        return ApiResponse.ok(null);
    }

    @PostMapping({"/notices/{id}/retry","/notices/workflow/{id}/retry"})
    @Transactional
    public ApiResponse<?> retry(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id) {
        requireAdmin(user);
        var jobs=db.queryForList("SELECT course_id FROM workflow_notice_job WHERE audit_id=? AND done=FALSE AND attempts>=4 FOR UPDATE",id);
        if(jobs.isEmpty())throw invalid("通知不存在或仍在投递");
        db.update("UPDATE workflow_notice_job SET attempts=0 WHERE audit_id=?",id);
        audit.append(user.id(),((Number)jobs.getFirst().get("course_id")).longValue(),"NOTICE_REQUEUED","notice:"+id,"管理员重新投递通知");
        return ApiResponse.ok(null);
    }

    public record AccountInput(String username,String displayName,String password) {}
    @PostMapping("/accounts")
    @Transactional
    public ApiResponse<?> account(@AuthenticationPrincipal UserPrincipal user,@RequestBody AccountInput in) {
        requireAdmin(user);
        String name=field(in.username(),64,"登录名");
        if(!name.matches("[A-Za-z0-9._-]+"))throw invalid("登录名只能使用字母、数字、点、下划线或短横线");
        if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Long.class,name)>0)throw invalid("登录名已存在");
        if(in.password()==null||in.password().length()<8||in.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw invalid("初始密码至少8字符且不超过72字节");
        db.update("INSERT INTO app_user(username,display_name,password_hash) VALUES(?,?,?)",name,field(in.displayName(),100,"姓名"),passwords.encode(in.password()));
        return ApiResponse.ok(null);
    }

    public record CourseInput(String name,String semester,String description) {}
    @PostMapping("/courses")
    @Transactional
    public ApiResponse<?> course(@AuthenticationPrincipal UserPrincipal user,@RequestBody CourseInput in) {
        requireAdmin(user);
        db.update("INSERT INTO course(name,semester,description,status) VALUES(?,?,?,'ACTIVE')",
            field(in.name(),160,"课程名"),field(in.semester(),40,"学期"),in.description()==null||in.description().isBlank()?"":field(in.description(),1000,"说明"));
        return ApiResponse.ok(null);
    }

    public record MembershipInput(long courseId,long userId,CourseRole role,boolean active) {}
    @PostMapping("/memberships")
    @Transactional
    public ApiResponse<?> membership(@AuthenticationPrincipal UserPrincipal user,@RequestBody MembershipInput in) {
        requireAdmin(user);
        if(in.role()==null||db.queryForList("SELECT id FROM course WHERE id=? AND status='ACTIVE' FOR UPDATE",in.courseId()).isEmpty()
            ||db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND enabled=TRUE",Long.class,in.userId())!=1)
            throw invalid("请选择有效课程、启用的用户和角色");
        if ((!in.active() || in.role()!=CourseRole.STUDENT) && db.queryForObject("""
            SELECT COUNT(*) FROM project_team_member m JOIN project_team t ON t.id=m.team_id
            JOIN course_project p ON p.id=t.project_id WHERE p.course_id=? AND m.user_id=? AND m.active=TRUE
            """,Long.class,in.courseId(),in.userId())>0) throw invalid("请先处理该学生的有效团队归属");
        int updated=db.update("UPDATE course_membership SET role=?,active=? WHERE course_id=? AND user_id=?",
            in.role().name(),in.active(),in.courseId(),in.userId());
        if(updated==0)db.update("INSERT INTO course_membership(role,active,course_id,user_id) VALUES(?,?,?,?)",
            in.role().name(),in.active(),in.courseId(),in.userId());
        if(!in.active()||in.role()!=CourseRole.TA)db.update("""
            DELETE FROM assistant_team_scope WHERE user_id=? AND team_id IN (
              SELECT t.id FROM project_team t JOIN course_project p ON p.id=t.project_id WHERE p.course_id=?
            )
            """,in.userId(),in.courseId());
        audit.append(user.id(),in.courseId(),"COURSE_MEMBERSHIP_UPDATED","user:"+in.userId(),"课程角色 "+in.role()+"，启用 "+in.active());
        return ApiResponse.ok(null);
    }
}
