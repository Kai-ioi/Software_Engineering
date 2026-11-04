package cn.edu.school.review;

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

/** Peer review (module 5). Reviews are pinned to the teacher-confirmed evaluated version. */
@Service @Transactional(readOnly=true)
public class ReviewService {
    private final JdbcTemplate db; private final ProjectTeamGateway gateway;
    private final CoursePermission permission; private final AuditService audit; private final Clock clock;
    public ReviewService(JdbcTemplate db, ProjectTeamGateway gateway, CoursePermission permission, AuditService audit, Clock clock){
        this.db=db; this.gateway=gateway; this.permission=permission; this.audit=audit; this.clock=clock;
    }
    record Context(long user, Map<String,Object> project, CourseRole role){
        long projectId(){return n(project,"id");}
        long courseId(){return n(project,"course_id");}
    }
    static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static OffsetDateTime t(Object value){return value instanceof OffsetDateTime date?date:OffsetDateTime.parse(value.toString());}
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    private static void check(boolean ok,String code,String message){if(!ok)throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
    private static void deny(String message){throw new BusinessException(HttpStatus.FORBIDDEN,"REVIEW_FORBIDDEN",message);}
    private static void text(String value,int max,String name){check(value!=null&&!value.isBlank()&&value.length()<=max,"INVALID_REVIEW",name+"不能为空且不超过"+max+"字");}
    private Context context(long user,long projectId){
        var p=gateway.project(projectId);var role=permission.requireMember(user,n(p,"course_id"));
        return new Context(user,p,role);
    }
    private Map<String,Object> round(long id){return one("SELECT * FROM review_round WHERE id=?","ROUND_NOT_FOUND",id);}
    private Context roundContext(long user,long roundId){var r=one("SELECT * FROM review_round WHERE id=? FOR UPDATE","ROUND_NOT_FOUND",roundId);
        var c=context(user,n(r,"project_id"));
        check(Set.of("PUBLISHED","IN_PROGRESS","ENDED").contains(c.project.get("status")),"PROJECT_READ_ONLY","当前项目状态不允许修改互评");
        return c;}
    private void teacher(Context c){if(c.role!=CourseRole.TEACHER)deny("此操作仅限本课程教师");}
    private void openRound(Map<String,Object> r){check(r.get("status").equals("OPEN"),"ROUND_CLOSED","该评审批次已关闭");}

    public List<Map<String,Object>> projects(long user,long courseId){
        var role=permission.requireMember(user,courseId);
        String sql="SELECT p.id,p.name,p.status,p.demo_fixture FROM course_project p WHERE p.course_id=?";
        var list=role==CourseRole.STUDENT
            ? db.queryForList(sql+" AND EXISTS(SELECT 1 FROM project_team t JOIN project_team_member m ON m.team_id=t.id WHERE t.project_id=p.id AND m.user_id=? AND m.active=TRUE) ORDER BY p.id",courseId,user)
            : role==CourseRole.TA
                ? db.queryForList(sql+" AND EXISTS(SELECT 1 FROM project_team t JOIN assistant_team_scope g ON g.team_id=t.id WHERE t.project_id=p.id AND g.user_id=?) ORDER BY p.id",courseId,user)
                : db.queryForList(sql+" ORDER BY p.id",courseId);
        for(var row:list){
            long pid=n(row,"id");
            row.put("roundCount",role==CourseRole.TA?0L:db.queryForObject("SELECT COUNT(*) FROM review_round WHERE project_id=?",Long.class,pid));
            String bindings="SELECT COUNT(*) FROM project_team t JOIN team_delivery d ON d.team_id=t.id WHERE t.project_id=? AND d.review_binding_id IS NOT NULL";
            row.put("bindingCount",role==CourseRole.TA
                ? db.queryForObject(bindings+CoursePermission.ASSISTANT_TEAM_FILTER,Long.class,pid,user)
                : db.queryForObject(bindings,Long.class,pid));
            row.put("myPendingTasks",db.queryForObject("SELECT COUNT(*) FROM review_task k JOIN review_round r ON r.id=k.round_id WHERE r.project_id=? AND k.reviewer_id=? AND k.status='PENDING'",Long.class,pid,user));
        }
        return normalize(list);
    }
    public Map<String,Object> workspace(long user,long projectId){
        var c=context(user,projectId);
        var out=new LinkedHashMap<String,Object>();
        out.put("project",clean(c.project));out.put("role",c.role.name());
        out.put("isTeacher",c.role==CourseRole.TEACHER);out.put("isStudent",c.role==CourseRole.STUDENT);out.put("isTA",c.role==CourseRole.TA);
        out.put("serverTime",now().toString());
        Long myTeam=null;
        if(c.role==CourseRole.TA && db.queryForObject("""
            SELECT COUNT(*) FROM assistant_team_scope g JOIN project_team t ON t.id=g.team_id
            WHERE g.user_id=? AND t.project_id=?
            """,Long.class,user,projectId)==0)deny("没有该项目的团队检查授权");
        if(c.role==CourseRole.STUDENT){
            var teams=db.queryForList("SELECT t.id FROM project_team t JOIN project_team_member m ON m.team_id=t.id WHERE t.project_id=? AND m.user_id=? AND m.active=TRUE",projectId,user);
            if(!teams.isEmpty()) myTeam=((Number)teams.getFirst().get("id")).longValue();
        }
        out.put("myTeamId",myTeam);
        out.put("bindings",c.role==CourseRole.TA?List.of():rows("""
            SELECT d.review_binding_id AS binding_id, t.id AS team_id, t.name AS team_name,
                   b.version_id, v.version_no, i.name AS iteration_name, v.summary, b.confirmed_at, b.reassessment_required
            FROM project_team t
            JOIN team_delivery d ON d.team_id=t.id
            JOIN submission_review_binding b ON b.id=d.review_binding_id
            JOIN submission_version v ON v.id=b.version_id
            JOIN team_iteration i ON i.id=v.iteration_id
            WHERE t.project_id=? ORDER BY t.id
            """,projectId));
        out.put("rounds",c.role==CourseRole.TA?List.of():rounds(projectId));
        if(c.role==CourseRole.STUDENT){
            var myTasks=rows("""
                SELECT k.id,k.round_id,r.name AS round_name,r.deadline,r.rubric_id,k.binding_id,k.status,k.started_at,k.reevaluation,
                       t.id AS team_id,t.name AS team_name,b.version_id,v.version_no,i.name AS iteration_name,v.summary,
                       s.id AS submission_id,s.score AS submitted_score,s.comment AS submitted_comment,s.submitted_at
                FROM review_task k
                JOIN review_round r ON r.id=k.round_id
                JOIN submission_review_binding b ON b.id=k.binding_id
                JOIN project_team t ON t.id=b.team_id
                JOIN submission_version v ON v.id=b.version_id
                JOIN team_iteration i ON i.id=v.iteration_id
                LEFT JOIN review_submission s ON s.task_id=k.id
                WHERE r.project_id=? AND k.reviewer_id=? ORDER BY k.id
                """,projectId,user);
            for(var task:myTasks){
                task.put("criteria",task.get("rubric_id")==null?List.of():rows(
                    "SELECT id,name,description,max_score FROM grading_criterion WHERE rubric_id=? ORDER BY sort_order,id",task.get("rubric_id")));
                task.put("dimension_scores",rows("""
                    SELECT d.criterion_id,d.score FROM review_dimension_score d JOIN review_submission s ON s.id=d.submission_id
                    WHERE s.task_id=? ORDER BY d.criterion_id
                    """,task.get("id")));
            }
            markReviewStates(myTasks);
            out.put("myTasks",myTasks);
            out.put("receivedReviews",myTeam==null?List.of():rows("""
                SELECT s.id,s.binding_id,s.score,s.comment,s.submitted_at,r.id AS round_id,r.name AS round_name
                FROM review_submission s
                JOIN review_task k ON k.id=s.task_id
                JOIN review_round r ON r.id=k.round_id
                JOIN submission_review_binding b ON b.id=s.binding_id
                WHERE r.feedback_open=TRUE AND b.team_id=? ORDER BY s.id
                """,myTeam));
            out.put("appeals",myTeam==null?List.of():rows("""
                SELECT a.*,u.display_name AS requested_by_name,t.name AS team_name
                FROM review_appeal a JOIN app_user u ON u.id=a.requested_by JOIN project_team t ON t.id=a.team_id
                WHERE a.team_id=? ORDER BY a.id DESC
                """,myTeam));
        } else if(c.role==CourseRole.TEACHER){
            out.put("myTasks",List.of());out.put("receivedReviews",List.of());
            out.put("students",db.queryForList("SELECT u.id,u.display_name,u.username FROM course_membership c JOIN app_user u ON u.id=c.user_id WHERE c.course_id=? AND c.role='STUDENT' AND c.active=TRUE AND u.enabled=TRUE ORDER BY u.id",c.courseId()));
            out.put("appeals",rows("""
                SELECT a.*,u.display_name AS requested_by_name,t.name AS team_name
                FROM review_appeal a JOIN app_user u ON u.id=a.requested_by JOIN project_team t ON t.id=a.team_id
                JOIN review_round r ON r.id=a.round_id WHERE r.project_id=? ORDER BY a.id DESC
                """,projectId));
            out.put("progress",markReviewStates(rows("""
                SELECT k.id,k.round_id,r.name AS round_name,r.deadline,k.binding_id,k.reviewer_id,k.status,k.started_at,k.reevaluation,
                       u.display_name AS reviewer_name,t.name AS team_name,v.version_no,s.score,s.comment,s.id AS submission_id,s.superseded_by
                FROM review_task k
                JOIN review_round r ON r.id=k.round_id
                JOIN app_user u ON u.id=k.reviewer_id
                JOIN submission_review_binding b ON b.id=k.binding_id
                JOIN project_team t ON t.id=b.team_id
                JOIN submission_version v ON v.id=b.version_id
                LEFT JOIN review_submission s ON s.task_id=k.id
                WHERE r.project_id=? ORDER BY k.round_id,k.id
                """,projectId)));
        } else {
            out.put("myTasks",List.of());out.put("receivedReviews",List.of());out.put("appeals",List.of());
            out.put("progress",markReviewStates(rows("""
                SELECT k.id,k.binding_id,k.status,k.started_at,r.deadline,k.reevaluation,u.display_name AS reviewer_name,
                       t.name AS team_name,s.id AS submission_id,s.score,s.comment
                FROM review_task k JOIN review_round r ON r.id=k.round_id
                JOIN app_user u ON u.id=k.reviewer_id
                JOIN submission_review_binding b ON b.id=k.binding_id JOIN project_team t ON t.id=b.team_id
                JOIN assistant_team_scope g ON g.team_id=t.id AND g.user_id=?
                LEFT JOIN review_submission s ON s.task_id=k.id
                WHERE r.project_id=? ORDER BY k.id
                """,user,projectId)));
        }
        return out;
    }
    private List<Map<String,Object>> markReviewStates(List<Map<String,Object>> tasks){
        var disputed=new HashMap<Long,Boolean>();
        for(var task:tasks){
            String status=task.get("status").toString();
            task.put("overdue","PENDING".equals(status)&&now().isAfter(t(task.get("deadline"))));
            if("PENDING".equals(status)&&task.get("started_at")!=null)task.put("status","IN_REVIEW");
            if("SUBMITTED".equals(status)){
                boolean appeal=disputed.computeIfAbsent(n(task,"binding_id"),binding->
                    db.queryForObject("SELECT COUNT(*) FROM review_appeal WHERE binding_id=? AND status='PENDING'",Long.class,binding)>0);
                boolean reevaluation=task.get("submission_id")!=null&&db.queryForObject("""
                    SELECT COUNT(*) FROM review_task WHERE replaces_submission_id=? AND status='PENDING'
                    """,Long.class,task.get("submission_id"))>0;
                if(appeal||reevaluation)task.put("status","UNDER_REVIEW");
            }
        }
        return tasks;
    }
    private List<Map<String,Object>> rounds(long projectId){
        return rows("""
            SELECT r.*,
             (SELECT COUNT(*) FROM review_task k WHERE k.round_id=r.id) AS task_count,
             (SELECT COUNT(*) FROM review_task k WHERE k.round_id=r.id AND k.status='SUBMITTED') AS submitted_count
            FROM review_round r WHERE r.project_id=? ORDER BY r.id
            """,projectId);
    }

    public record RoundInput(String name,OffsetDateTime deadline){}
    @Transactional public long createRound(long user,long projectId,RoundInput in){
        var c=context(user,projectId);teacher(c);
        check(Set.of("PUBLISHED","IN_PROGRESS","ENDED").contains(c.project.get("status").toString()),"PROJECT_READ_ONLY","草稿、终止或归档项目不能创建互评");
        check(db.queryForObject("SELECT COUNT(*) FROM project_team WHERE project_id=?",Long.class,projectId)>1,
            "NOT_ENOUGH_TEAMS","只有一个团队时使用教师评分，无需创建互评");
        text(in.name,160,"评审批次名称");
        check(in.deadline!=null&&in.deadline.isAfter(now()),"INVALID_DEADLINE","互评截止时间须晚于当前时间");
        db.queryForObject("SELECT id FROM course_project WHERE id=? FOR UPDATE",Long.class,projectId);
        var rubrics=db.queryForList("""
            SELECT id FROM grading_rubric WHERE active=TRUE AND (project_id=? OR (project_id IS NULL AND course_id=? AND ?))
            ORDER BY CASE WHEN project_id IS NULL THEN 1 ELSE 0 END,version_no DESC LIMIT 1
            """,Long.class,projectId,c.courseId(),Boolean.TRUE.equals(c.project.get("demo_fixture")));
        check(!rubrics.isEmpty(),"RUBRIC_REQUIRED","请先配置并发布项目评分标准");
        long id=insert("INSERT INTO review_round(project_id,name,deadline,status,created_by,created_at,rubric_id) VALUES(?,?,?,'OPEN',?,?,?)",
            projectId,in.name.strip(),in.deadline,user,now(),rubrics.getFirst());
        audit.append(user,c.courseId(),"REVIEW_ROUND_CREATED","round:"+id,"教师创建互评批次："+in.name.strip());
        return id;
    }
    @Transactional public int autoAssign(long user,long roundId){
        var c=roundContext(user,roundId);teacher(c);var r=round(roundId);openRound(r);
        var students=db.queryForList("""
            SELECT DISTINCT u.id FROM course_membership c
            JOIN app_user u ON u.id=c.user_id
            JOIN project_team_member m ON m.user_id=u.id AND m.active=TRUE
            JOIN project_team t ON t.id=m.team_id AND t.project_id=?
            WHERE c.course_id=? AND c.role='STUDENT' AND c.active=TRUE AND u.enabled=TRUE ORDER BY u.id
            """,Long.class,c.projectId(),c.courseId());
        var bindings=db.queryForList("SELECT d.review_binding_id AS binding_id,t.id AS team_id FROM project_team t JOIN team_delivery d ON d.team_id=t.id WHERE t.project_id=? AND d.review_binding_id IS NOT NULL ORDER BY t.id",c.projectId());
        check(!bindings.isEmpty(),"NO_BINDING","请先确认各团队被评版本，再自动分配互评任务");
        int created=0;
        var loads=new HashMap<Long,Integer>();
        for(var row:db.queryForList("""
            SELECT b.team_id,COUNT(*) AS total FROM review_task k JOIN submission_review_binding b ON b.id=k.binding_id
            WHERE k.round_id=? AND k.status<>'CANCELLED' GROUP BY b.team_id
            """,roundId))loads.put(n(row,"team_id"),((Number)row.get("total")).intValue());
        for(long student:students){
            Collections.shuffle(bindings);
            bindings.sort(Comparator.comparingInt(b->loads.getOrDefault(n(b,"team_id"),0)));
            long myTeam=-1;
            var teams=db.queryForList("SELECT t.id FROM project_team t JOIN project_team_member m ON m.team_id=t.id WHERE t.project_id=? AND m.user_id=? AND m.active=TRUE",Long.class,c.projectId(),student);
            if(!teams.isEmpty()) myTeam=((Number)teams.getFirst()).longValue();
            var assignedTeams=new HashSet<>(db.queryForList("""
                SELECT b.team_id FROM review_task k JOIN submission_review_binding b ON b.id=k.binding_id
                WHERE k.round_id=? AND k.reviewer_id=? AND k.status<>'CANCELLED' AND k.reevaluation=FALSE
                """,Long.class,roundId,student));
            for(var b:bindings){
                if(assignedTeams.size()>=2) break;
                long teamId=n(b,"team_id");long bindingId=n(b,"binding_id");
                if(teamId==myTeam || assignedTeams.contains(teamId)) continue;
                if(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=? AND binding_id=? AND reviewer_id=? AND status<>'CANCELLED'",Long.class,roundId,bindingId,student)==0){
                    createTask(c,roundId,bindingId,student,false);created++;assignedTeams.add(teamId);loads.merge(teamId,1,Integer::sum);
                }
            }
        }
        audit.append(user,c.courseId(),"REVIEW_AUTO_ASSIGNED","round:"+roundId,"自动分配互评任务，共"+created+"项");
        return created;
    }
    public record TaskInput(Long reviewerId,Long bindingId){}
    @Transactional public long addTask(long user,long roundId,TaskInput in){
        var c=roundContext(user,roundId);teacher(c);var r=round(roundId);openRound(r);
        check(in.reviewerId!=null&&in.bindingId!=null,"MISSING_TASK","请选择评审人和被评版本");
        check(db.queryForObject("SELECT COUNT(*) FROM course_membership WHERE course_id=? AND user_id=? AND role='STUDENT' AND active=TRUE",Long.class,c.courseId(),in.reviewerId)==1,"REVIEWER_MISMATCH","评审人须为本课程学生");
        bindingInProject(c.projectId(),in.bindingId);
        return createTask(c,roundId,in.bindingId,in.reviewerId,false);
    }
    private long createTask(Context c,long roundId,long bindingId,long reviewerId,boolean reevaluation){
        var binding=bindingInProject(c.projectId(),bindingId);long teamId=n(binding,"team_id");
        if(gateway.member(teamId,reviewerId))deny("不能评审本人所在团队的成果");
        check(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=? AND binding_id=? AND reviewer_id=? AND status<>'CANCELLED' AND (?=FALSE OR status='PENDING')",Long.class,roundId,bindingId,reviewerId,reevaluation)==0,"DUPLICATE_TASK","该评审任务已存在");
        long id=insert("INSERT INTO review_task(round_id,binding_id,reviewer_id,status,reevaluation,assigned_by,assigned_at) VALUES(?,?,?,'PENDING',?,?,?)",roundId,bindingId,reviewerId,reevaluation,c.user,now());
        grantAccess(bindingId,reviewerId);
        audit.append(c.user,c.courseId(),"REVIEW_TASK_ASSIGNED","task:"+id,"互评分配：评审人#"+reviewerId+"评审被评引用#"+bindingId);
        return id;
    }
    public record CancelInput(String reason){}
    @Transactional public void cancelTask(long user,long taskId,CancelInput in){
        var k=one("SELECT * FROM review_task WHERE id=?","TASK_NOT_FOUND",taskId);
        var c=roundContext(user,n(k,"round_id"));teacher(c);
        k=one("SELECT * FROM review_task WHERE id=?","TASK_NOT_FOUND",taskId);
        check(!"CANCELLED".equals(k.get("status")),"TASK_DONE","该评审任务已取消");
        text(in==null?null:in.reason,300,"取消原因");
        db.update("UPDATE review_task SET status='CANCELLED' WHERE id=?",taskId);
        revokeAccess(n(k,"binding_id"),n(k,"reviewer_id"));
        audit.append(user,c.courseId(),"REVIEW_TASK_CANCELLED","task:"+taskId,"教师取消互评任务："+in.reason.strip());
    }
    public record ExtendInput(OffsetDateTime deadline){}
    @Transactional public void extendRound(long user,long roundId,ExtendInput in){
        var c=roundContext(user,roundId);teacher(c);var r=round(roundId);openRound(r);
        check(in.deadline!=null&&in.deadline.isAfter(now())&&in.deadline.isAfter(t(r.get("deadline"))),"INVALID_DEADLINE","新截止时间须晚于当前截止时间");
        db.update("UPDATE review_round SET deadline=? WHERE id=?",in.deadline,roundId);
        audit.append(user,c.courseId(),"REVIEW_ROUND_EXTENDED","round:"+roundId,"教师延长互评截止时间");
    }
    @Transactional public void closeRound(long user,long roundId){
        var c=roundContext(user,roundId);teacher(c);var r=round(roundId);openRound(r);
        db.update("UPDATE review_round SET status='CLOSED' WHERE id=?",roundId);
        for(var k:db.queryForList("SELECT * FROM review_task WHERE round_id=? AND status='PENDING'",roundId)){
            db.update("UPDATE review_task SET status='CANCELLED' WHERE id=?",n(k,"id"));
            revokeAccess(n(k,"binding_id"),n(k,"reviewer_id"));
        }
        audit.append(user,c.courseId(),"REVIEW_ROUND_CLOSED","round:"+roundId,"教师关闭互评批次");
    }
    public record FeedbackInput(boolean open){}
    @Transactional public void toggleFeedback(long user,long roundId,FeedbackInput in){
        var c=roundContext(user,roundId);teacher(c);round(roundId);
        db.update("UPDATE review_round SET feedback_open=?,feedback_opened_at=COALESCE(feedback_opened_at,?) WHERE id=?",
            in.open,in.open?now():null,roundId);
        audit.append(user,c.courseId(),"REVIEW_FEEDBACK_TOGGLED","round:"+roundId,in.open?"教师开放互评反馈":"教师关闭互评反馈");
    }

    @Transactional public void start(long user,long taskId){
        var task=one("SELECT * FROM review_task WHERE id=?","TASK_NOT_FOUND",taskId);
        var c=roundContext(user,n(task,"round_id"));
        task=one("SELECT * FROM review_task WHERE id=?","TASK_NOT_FOUND",taskId);
        if(c.role!=CourseRole.STUDENT||n(task,"reviewer_id")!=user||
            gateway.member(n(bindingInProject(c.projectId(),n(task,"binding_id")),"team_id"),user))
            deny("只能开始本人获分配的其他团队互评");
        var round=round(n(task,"round_id"));
        openRound(round);
        check("PENDING".equals(task.get("status")),"TASK_DONE","该互评任务已结束");
        check(!now().isAfter(t(round.get("deadline"))),"REVIEW_DEADLINE_PASSED","互评已截止");
        db.update("UPDATE review_task SET started_at=COALESCE(started_at,?) WHERE id=?",now(),taskId);
    }

    public record DimensionScore(Long criterionId,Integer score){}
    public record SubmitInput(Integer score,String comment,List<DimensionScore> scores){}
    @Transactional public void submit(long user,long taskId,SubmitInput in){
        var k=one("SELECT * FROM review_task WHERE id=?","TASK_NOT_FOUND",taskId);
        var c=roundContext(user,n(k,"round_id"));
        if(c.role!=CourseRole.STUDENT)deny("仅学生可填写并提交互评");
        k=one("SELECT * FROM review_task WHERE id=?","TASK_NOT_FOUND",taskId);
        if(n(k,"reviewer_id")!=user)deny("只能提交分配给本人的互评任务");
        var r=round(n(k,"round_id"));openRound(r);
        check(k.get("status").equals("PENDING"),"TASK_DONE","该互评任务已提交或已取消");
        check(!now().isAfter(t(r.get("deadline"))),"REVIEW_DEADLINE_PASSED","互评已截止，不能提交");
        Object rubricId=r.get("rubric_id");
        int total=0;
        if(rubricId==null){
            check(in.score!=null&&in.score>=0&&in.score<=100,"INVALID_SCORE","历史批次互评分数须在0到100之间");
            total=in.score;
        } else {
            var criteria=rows("SELECT id,max_score FROM grading_criterion WHERE rubric_id=?",rubricId);
            var limits=new HashMap<Long,Integer>();
            for(var criterion:criteria)limits.put(n(criterion,"id"),((Number)criterion.get("max_score")).intValue());
            check(in.scores!=null&&in.scores.size()==limits.size()&&!limits.isEmpty(),"INVALID_SCORE","须填写全部评分维度");
            for(var value:in.scores){
                Integer max=value==null?null:limits.remove(value.criterionId);
                check(max!=null&&value.score!=null&&value.score>=0&&value.score<=max,"INVALID_SCORE","维度分数超限、重复或不属于本次标准");
                total+=value.score;
            }
        }
        text(in.comment,2000,"评语");
        long bindingId=n(k,"binding_id");
        if(gateway.member(n(bindingInProject(c.projectId(),bindingId),"team_id"),user))deny("不能评审本人所在团队的成果");
        long id=insert("INSERT INTO review_submission(task_id,binding_id,reviewer_id,score,comment,submitted_at,rubric_id) VALUES(?,?,?,?,?,?,?)",taskId,bindingId,user,total,in.comment.strip(),now(),rubricId);
        if(rubricId!=null)for(var dimension:in.scores)
            db.update("INSERT INTO review_dimension_score(submission_id,criterion_id,score) VALUES(?,?,?)",id,dimension.criterionId,dimension.score);
        if(k.get("replaces_submission_id")!=null)
            db.update("UPDATE review_submission SET superseded_by=? WHERE id=? AND superseded_by IS NULL",id,k.get("replaces_submission_id"));
        db.update("UPDATE review_task SET status='SUBMITTED' WHERE id=?",taskId);
        audit.append(user,c.courseId(),"REVIEW_SUBMITTED","submission:"+id,"学生提交互评，被评引用#"+bindingId);
    }

    public record AppealInput(long bindingId,String reason){}
    @Transactional public long appeal(long user,AppealInput in){
        var b=one("SELECT * FROM submission_review_binding WHERE id=?","BINDING_NOT_FOUND",in.bindingId);
        long teamId=n(b,"team_id");var teamRow=gateway.team(teamId,true);
        var c=context(user,n(teamRow,"project_id"));
        if(c.role!=CourseRole.STUDENT)deny("仅学生可申请复核");
        if(n(teamRow,"leader_id")!=user||!gateway.member(teamId,user))deny("仅团队负责人可申请复核");
        text(in.reason,2000,"复核原因");
        check(db.queryForObject("SELECT COUNT(*) FROM review_submission WHERE binding_id=?",Long.class,in.bindingId)>0,"NO_REVIEW","该成果版本尚无互评结果，无法申请复核");
        check(db.queryForObject("SELECT COUNT(*) FROM review_appeal WHERE binding_id=? AND team_id=? AND status IN ('PENDING','APPROVED','REJECTED')",Long.class,in.bindingId,teamId)==0,"APPEAL_PENDING","已有待处理的复核申请");
        var rounds=db.queryForList("""
            SELECT r.id,r.feedback_opened_at FROM review_round r JOIN review_task k ON k.round_id=r.id
            JOIN review_submission s ON s.task_id=k.id WHERE k.binding_id=? AND r.feedback_open=TRUE
            ORDER BY r.id DESC LIMIT 1
            """,in.bindingId);
        check(!rounds.isEmpty(),"FEEDBACK_CLOSED","反馈开放后才能申请复核");
        long roundId=n(rounds.getFirst(),"id");
        roundContext(user,roundId);
        var opened=rounds.getFirst().get("feedback_opened_at");
        var zone=ZoneId.of("Asia/Shanghai");
        check(opened!=null&&now().atZoneSameInstant(zone).toLocalDate().isBefore(t(opened).atZoneSameInstant(zone).toLocalDate().plusDays(3)),
            "APPEAL_WINDOW_CLOSED","已超过反馈开放后的三个自然日");
        long id=insert("INSERT INTO review_appeal(round_id,binding_id,team_id,requested_by,reason,status,requested_at) VALUES(?,?,?,?,?,'PENDING',?)",roundId,in.bindingId,teamId,user,in.reason.strip(),now());
        audit.append(user,c.courseId(),"REVIEW_APPEAL_REQUESTED","appeal:"+id,"团队负责人申请复核，被评引用#"+in.bindingId);
        return id;
    }
    public record ResolveInput(Long reviewerId,String resolution,Long submissionId){}
    @Transactional public void approveAppeal(long user,long appealId,ResolveInput in){
        var a=one("SELECT * FROM review_appeal WHERE id=?","APPEAL_NOT_FOUND",appealId);
        var c=roundContext(user,n(a,"round_id"));teacher(c);
        a=one("SELECT * FROM review_appeal WHERE id=?","APPEAL_NOT_FOUND",appealId);
        check(a.get("status").equals("PENDING"),"APPEAL_RESOLVED","该复核申请已处理");
        text(in.resolution,2000,"处理意见");check(in.reviewerId!=null,"MISSING_REVIEWER","请选择重评人");
        check(db.queryForObject("SELECT COUNT(*) FROM course_membership WHERE course_id=? AND user_id=? AND role='STUDENT' AND active=TRUE",Long.class,c.courseId(),in.reviewerId)==1,"REVIEWER_MISMATCH","重评人须为本课程学生");
        long teamId=n(a,"team_id");if(gateway.member(teamId,in.reviewerId))deny("重评人不能属于被复核团队");
        long roundId=n(a,"round_id");
        var original=round(roundId);
        if(!"OPEN".equals(original.get("status"))||!now().isBefore(t(original.get("deadline")))){
            roundId=insert("""
                INSERT INTO review_round(project_id,name,deadline,status,created_by,created_at,rubric_id)
                VALUES(?,?,?,'OPEN',?,?,?)
                """,c.projectId(),"复核重评 #"+appealId,now().plusDays(3),user,now(),original.get("rubric_id"));
        }
        var priorResults=db.queryForList("SELECT id FROM review_submission WHERE binding_id=? AND superseded_by IS NULL AND task_id IN (SELECT id FROM review_task WHERE status='SUBMITTED') ORDER BY id",
            Long.class,n(a,"binding_id"));
        Long prior=in.submissionId;
        if(prior==null&&priorResults.size()==1)prior=priorResults.getFirst();
        check(prior!=null&&priorResults.contains(prior),"REVIEW_RESULT_REQUIRED","请选择同一被评成果的有效互评记录");
        long reassignment=createTask(c,roundId,n(a,"binding_id"),in.reviewerId,true);
        db.update("UPDATE review_task SET replaces_submission_id=? WHERE id=?",prior,reassignment);
        db.update("UPDATE review_appeal SET status='APPROVED',resolved_by=?,resolved_at=?,resolution=? WHERE id=?",user,now(),in.resolution.strip(),appealId);
        audit.append(user,c.courseId(),"REVIEW_APPEAL_APPROVED","appeal:"+appealId,"教师同意复核并安排重评");
    }
    @Transactional public void rejectAppeal(long user,long appealId,ResolveInput in){
        var a=one("SELECT * FROM review_appeal WHERE id=?","APPEAL_NOT_FOUND",appealId);
        var c=roundContext(user,n(a,"round_id"));teacher(c);
        a=one("SELECT * FROM review_appeal WHERE id=?","APPEAL_NOT_FOUND",appealId);
        check(a.get("status").equals("PENDING"),"APPEAL_RESOLVED","该复核申请已处理");text(in.resolution,2000,"处理意见");
        db.update("UPDATE review_appeal SET status='REJECTED',resolved_by=?,resolved_at=?,resolution=? WHERE id=?",user,now(),in.resolution.strip(),appealId);
        audit.append(user,c.courseId(),"REVIEW_APPEAL_REJECTED","appeal:"+appealId,"教师驳回复核申请");
    }

    private Map<String,Object> bindingInProject(long projectId,long bindingId){
        return one("SELECT b.*,t.id AS team_id FROM submission_review_binding b JOIN project_team t ON t.id=b.team_id WHERE b.id=? AND t.project_id=?","BINDING_NOT_FOUND",bindingId,projectId);
    }
    private void grantAccess(long bindingId,long reviewerId){
        int updated=db.update("UPDATE submission_review_access SET active=TRUE WHERE binding_id=? AND reviewer_id=?",bindingId,reviewerId);
        if(updated==0)db.update("INSERT INTO submission_review_access(binding_id,reviewer_id,active) VALUES(?,?,TRUE)",bindingId,reviewerId);
    }
    private void revokeAccess(long bindingId,long reviewerId){
        db.update("""
            UPDATE submission_review_access SET active=FALSE WHERE binding_id=? AND reviewer_id=?
            AND NOT EXISTS(SELECT 1 FROM review_task WHERE binding_id=? AND reviewer_id=? AND status<>'CANCELLED')
            """,bindingId,reviewerId,bindingId,reviewerId);
    }
    private Map<String,Object> one(String sql,String code,Object...args){var result=db.queryForList(sql,args);if(result.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,code,"对象不存在或不在授权范围内");return result.getFirst();}
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();db.update(con->{var ps=con.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    private List<Map<String,Object>> rows(String sql,Object...args){return normalize(db.queryForList(sql,args));}
    private static Map<String,Object> clean(Map<String,Object> data){var result=new LinkedHashMap<String,Object>();data.forEach((k,v)->result.put(k,v instanceof OffsetDateTime date?date.toString():v instanceof java.sql.Date date?date.toLocalDate().toString():v));return result;}
    private static List<Map<String,Object>> normalize(List<Map<String,Object>> input){return input.stream().map(ReviewService::clean).toList();}
}
