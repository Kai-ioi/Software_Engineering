package cn.edu.school.submission;

import java.time.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Profile("demo & !postgres")
public class SubmissionDemoSeeder {
    private final JdbcTemplate db;
    public SubmissionDemoSeeder(JdbcTemplate db){this.db=db;}
    @Order(200) @EventListener(ApplicationReadyEvent.class) @Transactional
    public void seed(){
        String name="成果补交与异常核实验收（演示）";
        if(db.queryForObject("SELECT COUNT(*) FROM course_project WHERE name=? AND demo_fixture=TRUE",Long.class,name)>0)return;
        var memberships=db.queryForList("SELECT c.course_id,u.id FROM course_membership c JOIN app_user u ON u.id=c.user_id WHERE u.username='student01' AND c.role='STUDENT' AND c.active=TRUE ORDER BY c.course_id");
        if(memberships.isEmpty())return;
        long course=SubmissionService.n(memberships.getFirst(),"course_id"),leader=SubmissionService.n(memberships.getFirst(),"id");
        var members=db.queryForList("SELECT u.id FROM app_user u JOIN course_membership c ON c.user_id=u.id WHERE u.username='student03' AND c.course_id=? AND c.active=TRUE AND c.role='STUDENT'",Long.class,course);
        if(members.isEmpty())return;
        OffsetDateTime deadline=OffsetDateTime.now().minusDays(1);
        db.update("INSERT INTO course_project(course_id,name,status,final_deadline,demo_fixture) VALUES(?,?,'ENDED',?,TRUE)",course,name,deadline);
        long project=db.queryForObject("SELECT id FROM course_project WHERE name=? AND demo_fixture=TRUE",Long.class,name);
        db.update("INSERT INTO project_requirement(project_id,title,description) VALUES(?,'补交与异常核实验收','独立的截止后演示项目，不修改其他项目期限。')",project);
        db.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,'基因重组·截止后验收（演示）',?)",project,leader);
        long team=db.queryForObject("SELECT id FROM project_team WHERE project_id=?",Long.class,project);
        db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?),(?,?)",team,leader,team,members.getFirst());
        var end=deadline.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        db.update("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end,status,completion_summary,leftovers,ended_at) VALUES(?,'截止后成果验收','验证补交申请、教师核实及被评引用',1,?,?,'ENDED','演示迭代已结束','材料待授权补交',?)",team,end.minusDays(7),end,deadline);
    }
}
