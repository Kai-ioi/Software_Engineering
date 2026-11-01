package cn.edu.school.submission;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Delivery runs in its own transaction so notification failures cannot undo a submission. */
@Service
public class SubmissionNotices {
    private final JdbcTemplate db;private final Clock clock;
    public SubmissionNotices(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}
    public List<Long> pending(){return db.queryForList("SELECT id FROM submission_event WHERE notice_status='PENDING' AND notice_attempts<=3 ORDER BY id LIMIT 100",Long.class);}
    @Transactional public void send(long id){
        var e=db.queryForMap("SELECT * FROM submission_event WHERE id=? FOR UPDATE",id);
        if(!e.get("notice_status").equals("PENDING"))return;
        long team=SubmissionService.n(e,"team_id");
        var recipients=new LinkedHashSet<Long>();
        if(Boolean.TRUE.equals(e.get("notify_team")))recipients.addAll(db.queryForList("""
            SELECT m.user_id FROM project_team_member m JOIN project_team t ON t.id=m.team_id
            JOIN course_project p ON p.id=t.project_id JOIN course_membership c ON c.course_id=p.course_id AND c.user_id=m.user_id
            JOIN app_user u ON u.id=m.user_id
            WHERE m.team_id=? AND m.active=TRUE AND c.active=TRUE AND c.role='STUDENT' AND u.enabled=TRUE
            """,Long.class,team));
        if(Boolean.TRUE.equals(e.get("notify_teachers")))recipients.addAll(db.queryForList("SELECT c.user_id FROM project_team t JOIN course_project p ON p.id=t.project_id JOIN course_membership c ON c.course_id=p.course_id WHERE t.id=? AND c.role='TEACHER' AND c.active=TRUE",Long.class,team));
        String title=switch(e.get("action").toString()){
            case "VERSION_SUBMITTED" -> "团队已正式提交成果版本";
            case "FINAL_SELECTED" -> "团队已指定最终成果版本";
            case "FINAL_FROZEN" -> "最终截止已到，最终成果指定已冻结，请核对异常";
            case "FINAL_VERIFIED" -> "教师已核实未指定最终成果的异常";
            case "REVIEW_VERSION_CONFIRMED" -> "教师已确认具体被评版本";
            case "EXTENSION_REQUESTED" -> "团队申请补交或整改，请处理";
            case "EXTENSION_GRANTED" -> "教师已开放限时补交窗口";
            case "EXTENSION_REJECTED" -> "教师已处理补交申请，结果为拒绝";
            default -> "成果管理状态已更新";
        };
        for(long user:recipients)if(db.queryForObject("SELECT COUNT(*) FROM submission_notice WHERE event_id=? AND user_id=?",Long.class,id,user)==0)
            db.update("INSERT INTO submission_notice(event_id,user_id,team_id,title,created_at) VALUES(?,?,?,?,?)",id,user,team,title,OffsetDateTime.now(clock));
        db.update("UPDATE submission_event SET notice_status='DELIVERED' WHERE id=?",id);
    }
    @Transactional public void failed(long id){db.update("UPDATE submission_event SET notice_attempts=notice_attempts+1,notice_status=CASE WHEN notice_attempts>=3 THEN 'FAILED' ELSE 'PENDING' END WHERE id=? AND notice_status='PENDING'",id);}
}
