package cn.edu.school.workflow;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class WorkflowNotices {
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private static final Set<String> ACTIONS = Set.of("PROJECT_PUBLISHED","PROJECT_REQUIREMENTS_CHANGED",
        "REVIEW_TASK_ASSIGNED","TEAM_TASK_ASSIGNED","INSPECTION_CREATED","GRADE_PUBLISHED","GRADE_CORRECTED","COURSE_TEAM_INVITED","COURSE_TEAM_JOINED","COURSE_TEAM_DECLINED");

    public WorkflowNotices(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager manager) {
        this.db=db;
        transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    public void enqueue(long audit,long course,String action,String object) {
        if(ACTIONS.contains(action))db.update(
            "INSERT INTO workflow_notice_job(audit_id,course_id,action,object_id) VALUES(?,?,?,?)",audit,course,action,object);
    }

    @Scheduled(fixedDelay = 5000)
    public void dispatch() {
        for(long id:db.queryForList("SELECT audit_id FROM workflow_notice_job WHERE done=FALSE AND attempts<4 ORDER BY audit_id LIMIT 100",Long.class)){
            try {
                transaction.executeWithoutResult(status->{
                    var jobs=db.queryForList("SELECT * FROM workflow_notice_job WHERE audit_id=? AND done=FALSE AND attempts<4 FOR UPDATE",id);
                    if(jobs.isEmpty())return;
                    var job=jobs.getFirst();
                    fromAudit(id,((Number)job.get("course_id")).longValue(),job.get("action").toString(),job.get("object_id").toString());
                    db.update("UPDATE workflow_notice_job SET done=TRUE WHERE audit_id=?",id);
                });
            } catch (RuntimeException failure) {
                db.update("UPDATE workflow_notice_job SET attempts=attempts+1 WHERE audit_id=? AND done=FALSE",id);
                org.slf4j.LoggerFactory.getLogger(WorkflowNotices.class).warn("站内通知投递失败，审计编号 {}",id,failure);
            }
        }
    }

    private void fromAudit(long event, long course, String action, String object) {
        long id;
        try { id = Long.parseLong(object.substring(object.lastIndexOf(':') + 1)); }
        catch (NumberFormatException ignored) { return; }
        String key = "audit:" + event;
        String reviews = "/modules/reviews?courseId=" + course;
        switch (action) {
            case "COURSE_TEAM_INVITED" -> {
                long team=Long.parseLong(object.substring(0,object.indexOf(':')));
                var names=db.queryForList("SELECT name FROM course_team WHERE id=? AND course_id=?",String.class,team,course);
                if(!names.isEmpty())add(key,course,"邀请你加入课程团队「"+names.getFirst()+"」","/courses/"+course+"/teams",
                    "SELECT user_id FROM course_team_member WHERE team_id=? AND user_id=? AND status='INVITED'",team,id);
            }
            case "COURSE_TEAM_JOINED", "COURSE_TEAM_DECLINED" -> add(key,course,
                action.equals("COURSE_TEAM_JOINED")?"组员已接受邀请，请查看课程团队名单":"组队邀请已被谢绝，请查看课程团队名单","/courses/"+course+"/teams",
                "SELECT leader_id AS user_id FROM course_team WHERE id=? AND course_id=?",id,course);
            case "PROJECT_PUBLISHED", "PROJECT_REQUIREMENTS_CHANGED" -> add(key,course,
                action.equals("PROJECT_PUBLISHED") ? "课程项目已发布" : "项目交付要求或截止时间已更新",
                "/courses/"+course+"/projects/"+id,"SELECT user_id FROM course_membership WHERE course_id=? AND active=TRUE",course);
            case "TEAM_TASK_ASSIGNED" -> add(key,course,"收到团队任务","/modules/tasks?courseId="+course,
                "SELECT id AS user_id FROM app_user WHERE id=?",id);
            case "REVIEW_TASK_ASSIGNED" -> add(key,course,"收到互评任务",reviews,
                "SELECT reviewer_id AS user_id FROM review_task WHERE id=?",id);
            case "INSPECTION_CREATED" -> {
                add(key+":staff",course,"助教上报了过程检查问题",reviews,
                    "SELECT m.user_id FROM course_membership m JOIN inspection i ON i.id=? AND i.reported=TRUE WHERE m.course_id=? AND m.role='TEACHER' AND m.active=TRUE",id,course);
                add(key,course,"团队成果需要整改",reviews,
                    "SELECT m.user_id FROM inspection i JOIN project_team_member m ON m.team_id=i.team_id WHERE i.id=? AND i.rectification_required=TRUE AND m.active=TRUE",id);
            }
            case "GRADE_PUBLISHED", "GRADE_CORRECTED" -> add(key,course,
                action.equals("GRADE_PUBLISHED") ? "课程成绩已发布" : "课程成绩已更正","/modules/grades?courseId="+course,
                "SELECT user_id FROM grade_member_result WHERE result_id=?",id);
            default -> { }
        }
    }

    private void add(String key,long course,String title,String href,String recipients,Object... params) {
        var args = new ArrayList<Object>(List.of(key,course,title,href));
        args.addAll(List.of(params)); args.add(key); args.add(course);
        db.update("""
            INSERT INTO workflow_notice(event_key,user_id,course_id,title,href)
            SELECT DISTINCT ?,r.user_id,?,?,? FROM (%s) r
            JOIN app_user u ON u.id=r.user_id AND u.enabled=TRUE
            WHERE NOT EXISTS(SELECT 1 FROM workflow_notice n WHERE n.event_key=? AND n.user_id=r.user_id)
              AND EXISTS(SELECT 1 FROM course_membership m WHERE m.user_id=r.user_id AND m.course_id=? AND m.active=TRUE)
            """.formatted(recipients),args.toArray());
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void deadlines() {
        for (var p : db.queryForList("""
            SELECT id,course_id,final_deadline,final_deadline<=CURRENT_TIMESTAMP AS overdue FROM course_project
            WHERE status IN ('PUBLISHED','IN_PROGRESS','ENDED') AND final_deadline<=CURRENT_TIMESTAMP+INTERVAL '1' DAY
            """)) {
            long project=((Number)p.get("id")).longValue(), course=((Number)p.get("course_id")).longValue();
            boolean overdue=Boolean.TRUE.equals(p.get("overdue"));
            add("submission-deadline:"+project+":"+p.get("final_deadline")+":"+overdue,course,
                overdue?"项目成果截止后仍未指定最终成果":"项目成果将在24小时内截止",
                "/modules/submissions?courseId="+course,
                "SELECT t.leader_id AS user_id FROM project_team t LEFT JOIN team_delivery d ON d.team_id=t.id WHERE t.project_id=? AND d.final_version_id IS NULL",project);
        }
        for (var r : db.queryForList("""
            SELECT r.id,p.course_id,r.deadline,r.deadline<=CURRENT_TIMESTAMP AS overdue FROM review_round r JOIN course_project p ON p.id=r.project_id
            WHERE r.status='OPEN' AND p.status IN ('PUBLISHED','IN_PROGRESS','ENDED')
            AND r.deadline<=CURRENT_TIMESTAMP+INTERVAL '1' DAY
            """)) {
            long round=((Number)r.get("id")).longValue(), course=((Number)r.get("course_id")).longValue();
            boolean overdue=Boolean.TRUE.equals(r.get("overdue"));
            add("review-deadline:"+round+":"+r.get("deadline")+":"+overdue,course,overdue?"互评任务已逾期":"未完成的互评将在24小时内截止",
                "/modules/reviews?courseId="+course,
                "SELECT reviewer_id AS user_id FROM review_task WHERE round_id=? AND status='PENDING'",round);
        }
    }

    public List<Map<String,Object>> list(long user) {
        return db.queryForList("""
            SELECT n.id,'workflow' AS source,n.title,n.href,n.created_at,n.read_at,c.name AS course_name,
                   CAST(NULL AS VARCHAR(160)) AS project_name,CAST(NULL AS VARCHAR(160)) AS team_name
            FROM workflow_notice n JOIN course c ON c.id=n.course_id
            JOIN course_membership m ON m.course_id=n.course_id AND m.user_id=n.user_id AND m.active=TRUE
            WHERE n.user_id=?
            UNION ALL
            SELECT n.id,'submission',n.title,CONCAT('/modules/submissions?courseId=',p.course_id,'&teamId=',n.team_id),n.created_at,n.read_at,c.name,p.name,t.name
            FROM submission_notice n JOIN project_team t ON t.id=n.team_id JOIN course_project p ON p.id=t.project_id
            JOIN course c ON c.id=p.course_id
            JOIN course_membership m ON m.course_id=p.course_id AND m.user_id=n.user_id AND m.active=TRUE
            WHERE n.user_id=? AND (m.role='TEACHER' OR EXISTS(SELECT 1 FROM project_team_member tm WHERE tm.team_id=t.id AND tm.user_id=n.user_id AND tm.active=TRUE))
            ORDER BY created_at DESC,id DESC LIMIT 200
            """,user,user);
    }

    @Transactional public void read(long user,String source,long id) {
        if ("workflow".equals(source))
            db.update("UPDATE workflow_notice SET read_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND read_at IS NULL",id,user);
        if ("submission".equals(source))
            db.update("UPDATE submission_notice SET read_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND read_at IS NULL",id,user);
    }
}
