package cn.edu.school.inspection;

import cn.edu.school.audit.AuditService;
import cn.edu.school.collaboration.ProjectTeamGateway;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** TA process inspection and rectification recheck (module 6). */
@Service @Transactional(readOnly=true)
public class InspectionService {
    private final JdbcTemplate db; private final ProjectTeamGateway gateway;
    private final CoursePermission permission; private final AuditService audit; private final Clock clock;
    public InspectionService(JdbcTemplate db, ProjectTeamGateway gateway, CoursePermission permission, AuditService audit, Clock clock){
        this.db=db; this.gateway=gateway; this.permission=permission; this.audit=audit; this.clock=clock;
    }
    record Context(long user, Map<String,Object> team, Map<String,Object> project, CourseRole role, boolean member){
        long teamId(){return n(team,"id");}
        long courseId(){return n(project,"course_id");}
        boolean leader(){return role==CourseRole.STUDENT&&member&&n(team,"leader_id")==user;}
    }
    static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    private static void check(boolean ok,String code,String message){if(!ok)throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
    private static void deny(String message){throw new BusinessException(HttpStatus.FORBIDDEN,"INSPECTION_FORBIDDEN",message);}
    private static void text(String value,int max,String name){check(value!=null&&!value.isBlank()&&value.length()<=max,"INVALID_INSPECTION",name+"不能为空且不超过"+max+"字");}
    private static void optionalText(String value,int max,String name){check(value==null||value.length()<=max,"INVALID_INSPECTION",name+"不得超过"+max+"字");}
    private Context context(long user,long teamId,boolean lock){
        var tr=gateway.team(teamId,lock);var p=gateway.project(n(tr,"project_id"));var role=permission.requireMember(user,n(p,"course_id"));
        boolean member=gateway.member(teamId,user);
        if(role==CourseRole.STUDENT&&!member)deny("只能查看本团队的检查记录");
        return new Context(user,tr,p,role,member);
    }
    public List<Map<String,Object>> teams(long user,long courseId){
        var role=permission.requireMember(user,courseId);
        String sql="SELECT t.id,t.name,t.project_id,p.name AS project_name,p.status AS project_status,p.demo_fixture FROM project_team t JOIN course_project p ON p.id=t.project_id WHERE p.course_id=?";
        if(role==CourseRole.STUDENT)return db.queryForList(sql+" AND EXISTS(SELECT 1 FROM project_team_member m WHERE m.team_id=t.id AND m.user_id=? AND m.active=TRUE) ORDER BY t.id",courseId,user);
        return db.queryForList(sql+" ORDER BY t.id",courseId);
    }
    public Map<String,Object> workspace(long user,long teamId){
        var c=context(user,teamId,false);
        var out=new LinkedHashMap<String,Object>();
        out.put("team",clean(c.team));out.put("project",clean(c.project));
        out.put("role",c.role.name());out.put("isTA",c.role==CourseRole.TA);out.put("isTeacher",c.role==CourseRole.TEACHER);
        out.put("isStudent",c.role==CourseRole.STUDENT);out.put("isLeader",c.leader());
        out.put("serverTime",now().toString());
        out.put("versions",rows("SELECT v.*,u.display_name AS submitter_name,i.name AS iteration_name FROM submission_version v JOIN app_user u ON u.id=v.submitted_by JOIN team_iteration i ON i.id=v.iteration_id WHERE v.team_id=? ORDER BY v.id DESC",teamId));
        out.put("binding",rows("SELECT d.review_binding_id AS binding_id,b.version_id,v.version_no FROM team_delivery d LEFT JOIN submission_review_binding b ON b.id=d.review_binding_id LEFT JOIN submission_version v ON v.id=b.version_id WHERE d.team_id=?",teamId));
        out.put("inspections",rows("SELECT i.*,u.display_name AS checker_name FROM inspection i JOIN app_user u ON u.id=i.checker_id WHERE i.team_id=? ORDER BY i.id DESC",teamId));
        return out;
    }
    public record CreateInput(long versionId,String conclusion,String feedback,boolean rectificationRequired,boolean reported,Long parentId){}
    @Transactional public long create(long user,long teamId,CreateInput in){
        var c=context(user,teamId,true);
        if(c.role!=CourseRole.TA)deny("过程检查由本课程助教执行，助教不能正式评分");
        one("SELECT id FROM submission_version WHERE id=? AND team_id=?","VERSION_NOT_FOUND",in.versionId,teamId);
        text(in.conclusion,2000,"检查结论");
        if(in.rectificationRequired||in.reported)text(in.feedback,2000,"问题与整改反馈");else optionalText(in.feedback,2000,"问题与整改反馈");
        if(in.parentId!=null)one("SELECT id FROM inspection WHERE id=? AND team_id=?","INSPECTION_NOT_FOUND",in.parentId,teamId);
        long id=insert("INSERT INTO inspection(team_id,version_id,checker_id,conclusion,feedback,rectification_required,reported,checked_at,parent_id) VALUES(?,?,?,?,?,?,?,?,?)",teamId,in.versionId,user,in.conclusion.strip(),in.feedback==null||in.feedback.isBlank()?null:in.feedback.strip(),in.rectificationRequired,in.reported,now(),in.parentId);
        audit.append(user,c.courseId(),"INSPECTION_CREATED","inspection:"+id,"助教检查团队#"+teamId+(in.rectificationRequired?"（要求整改）":"")+(in.reported?"（问题上报）":""));
        return id;
    }
    private Map<String,Object> one(String sql,String code,Object...args){var result=db.queryForList(sql,args);if(result.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,code,"对象不存在或不在授权范围内");return result.getFirst();}
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();db.update(con->{var ps=con.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    private List<Map<String,Object>> rows(String sql,Object...args){return db.queryForList(sql,args).stream().map(InspectionService::clean).toList();}
    private static Map<String,Object> clean(Map<String,Object> data){var result=new LinkedHashMap<String,Object>();data.forEach((k,v)->result.put(k,v instanceof OffsetDateTime date?date.toString():v instanceof java.sql.Date date?date.toLocalDate().toString():v));return result;}
}
