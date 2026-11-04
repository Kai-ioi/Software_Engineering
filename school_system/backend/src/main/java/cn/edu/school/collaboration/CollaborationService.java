package cn.edu.school.collaboration;

import cn.edu.school.audit.AuditService;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class CollaborationService {
    private final JdbcTemplate jdbc;
    private final ProjectTeamGateway gateway;
    private final CoursePermission permission;
    private final AuditService audit;
    private final ObjectMapper json;
    public CollaborationService(JdbcTemplate jdbc, ProjectTeamGateway gateway, CoursePermission permission, AuditService audit, ObjectMapper json) {
        this.jdbc=jdbc; this.gateway=gateway; this.permission=permission; this.audit=audit; this.json=json;
    }
    record Context(Map<String,Object> team, Map<String,Object> project, CourseRole role, long userId, boolean leader) {
        long teamId(){return number(team,"id");}
        long courseId(){return number(project,"course_id");}
    }
    static long number(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    private static void require(boolean ok,String code,String message){
        if(!ok) throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);
    }
    private static void forbidden(String message){throw new BusinessException(HttpStatus.FORBIDDEN,"COLLABORATION_FORBIDDEN",message);}
    private Context context(long userId,long teamId,boolean lock) {
        var team=gateway.team(teamId,lock); var project=gateway.project(number(team,"project_id"));
        var role=permission.requireMember(userId,number(project,"course_id"));
        permission.requireTeamScope(role,userId,teamId);
        boolean member=gateway.member(teamId,userId);
        if(role==CourseRole.STUDENT && !member) forbidden("只能查看本团队的迭代、任务和过程记录");
        return new Context(team,project,role,userId,role==CourseRole.STUDENT && member && number(team,"leader_id")==userId);
    }
    public List<Map<String,Object>> teams(long userId,long courseId) {
        var role=permission.requireMember(userId,courseId);
        String sql="""
            SELECT t.id,t.name,t.project_id,p.name AS project_name,p.status AS project_status,p.demo_fixture
            FROM project_team t JOIN course_project p ON p.id=t.project_id WHERE p.course_id=?
            """;
        if(role==CourseRole.STUDENT) return jdbc.queryForList(sql+" AND EXISTS(SELECT 1 FROM project_team_member m WHERE m.team_id=t.id AND m.user_id=? AND m.active=TRUE) ORDER BY t.id",courseId,userId);
        if(role==CourseRole.TA)return jdbc.queryForList(sql+CoursePermission.ASSISTANT_TEAM_FILTER+" ORDER BY t.id",courseId,userId);
        return jdbc.queryForList(sql+" ORDER BY t.id",courseId);
    }
    @Transactional
    public Map<String,Object> workspace(long userId,long teamId) {
        // Lock makes the revision and all child lists one coherent snapshot.
        var c=context(userId,teamId,true);
        return Map.of("team",c.team,"project",normalize(c.project),"role",c.role.name(),"isLeader",c.leader,
            "members",gateway.members(teamId,c.courseId()),
            "requirements",jdbc.queryForList("SELECT * FROM project_requirement WHERE project_id=? ORDER BY id",number(c.project,"id")),
            "iterations",rows("SELECT * FROM team_iteration WHERE team_id=? ORDER BY sequence_no,id",teamId),
            "tasks",rows("SELECT * FROM team_task WHERE team_id=? ORDER BY id",teamId),
            "milestones",rows("SELECT * FROM team_milestone WHERE team_id=? ORDER BY target_date,id",teamId));
    }
    public List<Map<String,Object>> history(long userId,long teamId,long beforeId) {
        context(userId,teamId,false);
        return rows("""
            SELECT e.*,u.display_name AS actor_name FROM collaboration_event e JOIN app_user u ON u.id=e.actor_id
            WHERE e.team_id=? AND e.id<? ORDER BY e.id DESC LIMIT 50
            """,teamId,beforeId);
    }
    private Context writeContext(long user,long team,long revision,boolean manager,String reason) {
        var c=context(user,team,true);
        if(manager && !c.leader && c.role!=CourseRole.TEACHER) forbidden("仅团队负责人可管理，课程教师可记录原因后干预团队任务");
        if(!manager && c.role==CourseRole.TA) forbidden("助教只能查看授权范围内的团队进度");
        if(number(c.team,"revision")!=revision) throw new BusinessException(HttpStatus.CONFLICT,"STALE_WORKSPACE","团队数据已更新，请刷新后重新操作");
        var state=c.project.get("status").toString();
        var deadline=asTime(c.project.get("final_deadline"));
        require((state.equals("PUBLISHED")||state.equals("IN_PROGRESS")) && !OffsetDateTime.now().isAfter(deadline),"PROJECT_READ_ONLY","项目当前状态或最终期限不允许修改协作信息");
        if(c.role==CourseRole.TEACHER) require(reason!=null&&!reason.isBlank(),"REASON_REQUIRED","教师干预须填写原因");
        return c;
    }
    public record IterationInput(long revision,String reason,String name,String goal,Integer sequence,LocalDate plannedStart,LocalDate plannedEnd){}
    @Transactional
    public long saveIteration(long user,long team,Long id,IterationInput in) {
        var c=writeContext(user,team,in.revision,true,in.reason);
        // Teachers intervene in tasks, while iteration lifecycle remains the team leader's responsibility.
        if(!c.leader) forbidden("迭代计划由团队负责人维护");
        var old=id==null?Map.<String,Object>of():object("team_iteration",id,team);
        if(id!=null) require(!old.get("status").equals("ENDED"),"ITERATION_ENDED","已结束迭代不能修改计划");
        validText(in.name,160,"迭代名称");validText(in.goal,2000,"迭代目标");
        require(in.sequence!=null&&in.sequence>0,"INVALID_SEQUENCE","迭代顺序须为正整数");
        dates(in.plannedStart,in.plannedEnd,c,null);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM team_iteration WHERE team_id=? AND sequence_no=? AND id<>?",Long.class,team,in.sequence,id==null?-1:id)==0,"DUPLICATE_SEQUENCE","本团队的迭代顺序不能重复");
        if(id!=null) {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM team_task WHERE iteration_id=? AND (planned_start<? OR planned_end>?)",Long.class,id,in.plannedStart,in.plannedEnd)==0,"PLAN_CONFLICT","新计划不能排除已有任务的计划日期");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM team_milestone WHERE iteration_id=? AND (target_date<? OR target_date>?)",Long.class,id,in.plannedStart,in.plannedEnd)==0,"PLAN_CONFLICT","新计划不能排除已有里程碑");
            jdbc.update("UPDATE team_iteration SET name=?,goal=?,sequence_no=?,planned_start=?,planned_end=? WHERE id=?",in.name.strip(),in.goal.strip(),in.sequence,in.plannedStart,in.plannedEnd,id);
        } else id=insert("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end) VALUES(?,?,?,?,?,?)",team,in.name.strip(),in.goal.strip(),in.sequence,in.plannedStart,in.plannedEnd);
        event(c,"ITERATION_SAVED","ITERATION",id,in.reason,old,object("team_iteration",id,team));return id;
    }
    public record EndInput(long revision,String reason,String summary,String leftovers){}
    @Transactional
    public void lifecycle(long user,long team,long id,boolean end,EndInput in) {
        var c=writeContext(user,team,in.revision,true,in.reason);
        if(!c.leader) forbidden("迭代开始与结束由团队负责人确认");
        var old=object("team_iteration",id,team);
        require(old.get("status").equals(end?"IN_PROGRESS":"NOT_STARTED"),"INVALID_TRANSITION","迭代状态不允许该转换");
        if(end){
            validText(in.summary,2000,"完成情况");
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM team_task WHERE iteration_id=? AND status<>'COMPLETED'",Long.class,id);
            if(count>0) validText(in.leftovers,2000,"未完成任务的遗留说明");
            optionalText(in.leftovers,2000,"遗留说明");
            jdbc.update("UPDATE team_iteration SET status='ENDED',completion_summary=?,leftovers=?,ended_at=? WHERE id=?",in.summary.strip(),in.leftovers,OffsetDateTime.now(),id);
        }else jdbc.update("UPDATE team_iteration SET status='IN_PROGRESS',started_at=? WHERE id=?",OffsetDateTime.now(),id);
        event(c,end?"ITERATION_ENDED":"ITERATION_STARTED","ITERATION",id,in.reason,old,object("team_iteration",id,team));
    }
    public record TaskInput(long revision,String reason,Long iterationId,Long requirementId,String title,String description,Long assigneeId,LocalDate plannedStart,LocalDate plannedEnd){}
    @Transactional
    public long saveTask(long user,long team,Long id,TaskInput in) {
        var c=writeContext(user,team,in.revision,true,in.reason);
        require(in.iterationId!=null&&in.requirementId!=null&&in.assigneeId!=null,"MISSING_ASSOCIATION","任务必须关联迭代、项目要求和执行成员");
        var iteration=openIteration(in.iterationId,team);
        var old=id==null?Map.<String,Object>of():object("team_task",id,team);
        if(id!=null) {
            require(number(old,"iteration_id")==in.iterationId,"USE_MOVE_ACTION","跨迭代调整请使用任务转移操作并填写原因");
        }
        require(jdbc.queryForObject("SELECT COUNT(*) FROM project_requirement WHERE id=? AND project_id=?",Long.class,in.requirementId,number(c.project,"id"))==1,"REQUIREMENT_MISMATCH","项目要求不属于本团队项目");
        require(gateway.members(team,c.courseId()).stream().anyMatch(m->number(m,"id")==in.assigneeId),"ASSIGNEE_MISMATCH","执行人须为本团队有效学生成员");
        validText(in.title,200,"任务名称");validText(in.description,2000,"任务内容");dates(in.plannedStart,in.plannedEnd,c,iteration);
        if(id==null) id=insert("INSERT INTO team_task(team_id,iteration_id,original_iteration_id,requirement_id,title,description,assignee_id,planned_start,planned_end) VALUES(?,?,?,?,?,?,?,?,?)",team,in.iterationId,in.iterationId,in.requirementId,in.title.strip(),in.description.strip(),in.assigneeId,in.plannedStart,in.plannedEnd);
        else jdbc.update("UPDATE team_task SET requirement_id=?,title=?,description=?,assignee_id=?,planned_start=?,planned_end=? WHERE id=?",in.requirementId,in.title.strip(),in.description.strip(),in.assigneeId,in.plannedStart,in.plannedEnd,id);
        var saved=object("team_task",id,team);
        event(c,"TASK_SAVED","TASK",id,in.reason,old,saved);
        if(saved.get("assignee_id")!=null&&!Objects.equals(old.get("assignee_id"),saved.get("assignee_id")))
            audit.append(c.userId,c.courseId(),"TEAM_TASK_ASSIGNED","task:"+id+":"+saved.get("assignee_id"),"团队任务分配");
        return id;
    }
    public record ProgressInput(long revision,String reason,String status,String note){}
    @Transactional
    public void progress(long user,long team,long id,ProgressInput in) {
        var c=writeContext(user,team,in.revision,false,in.reason);var old=object("team_task",id,team);
        if(!c.leader && c.role!=CourseRole.TEACHER && number(old,"assignee_id")!=user) forbidden("普通成员只能更新分配给本人的任务");
        openIteration(number(old,"iteration_id"),team);
        String current=old.get("status").toString(), next=in.status;
        boolean valid=current.equals(next)||(current.equals("NOT_STARTED")&&"IN_PROGRESS".equals(next))||(current.equals("IN_PROGRESS")&&"COMPLETED".equals(next))||(current.equals("COMPLETED")&&"IN_PROGRESS".equals(next));
        require(valid,"INVALID_TRANSITION","任务只能未开始→进行中→已完成，已完成可返工至进行中");
        validText(in.note,2000,"进展说明");
        if(current.equals("COMPLETED")&&"IN_PROGRESS".equals(next)) validText(in.reason,2000,"返工原因");
        jdbc.update("UPDATE team_task SET status=?,progress_note=?,completed_at=? WHERE id=?",next,in.note.strip(),"COMPLETED".equals(next)?(old.get("completed_at")==null?OffsetDateTime.now():old.get("completed_at")):null,id);
        event(c,"TASK_PROGRESS","TASK",id,in.reason,old,object("team_task",id,team));
    }
    public record MoveInput(long revision,String reason,Long targetIterationId,LocalDate plannedStart,LocalDate plannedEnd){}
    @Transactional
    public void move(long user,long team,long id,MoveInput in) {
        var c=writeContext(user,team,in.revision,true,in.reason);var old=object("team_task",id,team);
        validText(in.reason,2000,"跨迭代调整原因");
        require(in.targetIterationId!=null,"MISSING_ITERATION","请选择后续迭代");
        var from=object("team_iteration",number(old,"iteration_id"),team);var to=openIteration(in.targetIterationId,team);
        require(!old.get("status").equals("COMPLETED"),"TASK_COMPLETED","已完成任务不能作为遗留任务转移");
        require(number(to,"sequence_no")>number(from,"sequence_no"),"INVALID_TARGET","只能转入本团队后续迭代");
        dates(in.plannedStart,in.plannedEnd,c,to);
        jdbc.update("UPDATE team_task SET iteration_id=?,planned_start=?,planned_end=? WHERE id=?",in.targetIterationId,in.plannedStart,in.plannedEnd,id);
        event(c,"TASK_MOVED","TASK",id,in.reason,old,object("team_task",id,team));
    }
    public record MilestoneInput(long revision,String reason,Long iterationId,String title,LocalDate targetDate,String criteria,boolean completed,String note){}
    @Transactional
    public long saveMilestone(long user,long team,Long id,MilestoneInput in) {
        var c=writeContext(user,team,in.revision,true,in.reason);
        if(!c.leader) forbidden("里程碑由团队负责人维护");
        require(in.iterationId!=null,"MISSING_ITERATION","里程碑须关联迭代");var iteration=openIteration(in.iterationId,team);
        var old=id==null?Map.<String,Object>of():object("team_milestone",id,team);
        if(id!=null) require(number(old,"iteration_id")==in.iterationId,"ITERATION_MISMATCH","已有里程碑不能更换所属迭代");
        validText(in.title,200,"里程碑名称");validText(in.criteria,2000,"完成条件");dates(in.targetDate,in.targetDate,c,iteration);
        if(in.completed) validText(in.note,2000,"里程碑完成说明");else optionalText(in.note,2000,"完成说明");
        Object completedAt=in.completed?(old.get("completed_at")==null?OffsetDateTime.now():old.get("completed_at")):null;
        if(id==null) id=insert("INSERT INTO team_milestone(team_id,iteration_id,title,target_date,completion_criteria,completed,completion_note,completed_at) VALUES(?,?,?,?,?,?,?,?)",team,in.iterationId,in.title.strip(),in.targetDate,in.criteria.strip(),in.completed,in.note,completedAt);
        else jdbc.update("UPDATE team_milestone SET title=?,target_date=?,completion_criteria=?,completed=?,completion_note=?,completed_at=? WHERE id=?",in.title.strip(),in.targetDate,in.criteria.strip(),in.completed,in.note,completedAt,id);
        event(c,"MILESTONE_SAVED","MILESTONE",id,in.reason,old,object("team_milestone",id,team));return id;
    }
    private Map<String,Object> openIteration(long id,long team){var i=object("team_iteration",id,team);require(!i.get("status").equals("ENDED"),"ITERATION_ENDED","已结束迭代只读，遗留任务可转入后续迭代");return i;}
    private Map<String,Object> object(String table,long id,long team){
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE id=? AND team_id=?",id,team);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"OBJECT_NOT_FOUND","对象不存在或不属于该团队");return rows.getFirst();
    }
    private void dates(LocalDate start,LocalDate end,Context c,Map<String,Object> iteration){
        require(start!=null&&end!=null&&!end.isBefore(start),"INVALID_DATES","请填写有效的计划日期，结束不能早于开始");
        require(!end.isAfter(asTime(c.project.get("final_deadline")).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate()),"PLAN_CONFLICT","计划日期不能晚于项目最终期限");
        if(iteration!=null) require(!start.isBefore(asDate(iteration.get("planned_start")))&&!end.isAfter(asDate(iteration.get("planned_end"))),"PLAN_CONFLICT","任务和里程碑日期须在所属迭代计划范围内");
    }
    private static LocalDate asDate(Object value){return value instanceof java.sql.Date d?d.toLocalDate():LocalDate.parse(value.toString());}
    private static OffsetDateTime asTime(Object value){return value instanceof OffsetDateTime t?t:OffsetDateTime.parse(value.toString());}
    private static void validText(String s,int max,String label){require(s!=null&&!s.isBlank()&&s.length()<=max,"INVALID_TEXT",label+"不能为空，且不超过"+max+"字");}
    private static void optionalText(String s,int max,String label){require(s==null||s.length()<=max,"INVALID_TEXT",label+"不得超过"+max+"字");}
    private long insert(String sql,Object...args){
        var key=new GeneratedKeyHolder();jdbc.update(con->{var ps=con.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);return Objects.requireNonNull(key.getKey()).longValue();
    }
    private void event(Context c,String action,String type,long id,String reason,Map<String,Object> before,Map<String,Object> after){
        optionalText(reason,2000,"操作原因");
        try{
            jdbc.update("INSERT INTO collaboration_event(team_id,actor_id,action,object_type,object_id,reason,before_data,after_data,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",c.teamId(),c.userId,action,type,id,reason==null?"":reason,json.writeValueAsString(normalize(before)),json.writeValueAsString(normalize(after)),OffsetDateTime.now());
        }catch(JsonProcessingException e){throw new IllegalStateException("无法保存过程记录",e);}
        jdbc.update("UPDATE project_team SET revision=revision+1 WHERE id=?",c.teamId());
        audit.append(c.userId,c.courseId(),action,type+":"+id,"团队协作："+action+"，团队#"+c.teamId());
    }
    private List<Map<String,Object>> rows(String sql,Object...args){return jdbc.queryForList(sql,args).stream().map(CollaborationService::normalize).toList();}
    private static Map<String,Object> normalize(Map<String,Object> input){
        var out=new LinkedHashMap<String,Object>();input.forEach((k,v)->out.put(k,v instanceof java.sql.Date d?d.toLocalDate().toString():v instanceof OffsetDateTime t?t.toString():v));return out;
    }
}
