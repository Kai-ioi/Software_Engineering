package cn.edu.school.collaboration;

import cn.edu.school.auth.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo-only fixture, runs after the framework's account/course runner. */
@Component @Profile("demo & !postgres")
public class CollaborationDemoSeeder {
    private final JdbcTemplate jdbc; private final UserRepository users; private final PasswordEncoder encoder; private final String password;
    public CollaborationDemoSeeder(JdbcTemplate jdbc,UserRepository users,PasswordEncoder encoder,@Value("${app.demo-password}")String password){this.jdbc=jdbc;this.users=users;this.encoder=encoder;this.password=password;}
    @org.springframework.core.annotation.Order(100) @EventListener(ApplicationReadyEvent.class) @Transactional
    public void seed(){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM course_project WHERE demo_fixture=TRUE",Long.class)>0)return;
        var courseIds=jdbc.queryForList("SELECT c.course_id FROM course_membership c JOIN app_user u ON u.id=c.user_id WHERE u.username='teacher01' AND c.role='TEACHER' AND c.active=TRUE",Long.class);
        if(courseIds.isEmpty())return;
        long course=courseIds.getFirst();
        long leader=users.findByUsername("student01").orElseThrow().getId();
        long member=account("student03","未宁夏"), other=account("student04","其他团队负责人（演示）");
        for(long user:new long[]{member,other}) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM course_membership WHERE course_id=? AND user_id=?",Long.class,course,user)==0)
                jdbc.update("INSERT INTO course_membership(course_id,user_id,role,active) VALUES(?,?,'STUDENT',TRUE)",course,user);
        }
        jdbc.update("INSERT INTO course_project(course_id,name,status,final_deadline,demo_fixture) VALUES(?,?,'IN_PROGRESS',?,TRUE)",course,"课程项目协作平台（模块3演示）",OffsetDateTime.now().plusMonths(4));
        long project=jdbc.queryForObject("SELECT id FROM course_project WHERE demo_fixture=TRUE ORDER BY id DESC LIMIT 1",Long.class);
        jdbc.update("INSERT INTO project_requirement(project_id,title,description) VALUES(?,?,?)",project,"完成课程项目协作与评审平台","按课程要求完成需求、设计、实现及演示，团队任务不得改变教师要求。");
        jdbc.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,?,?)",project,"基因重组（演示）",leader);
        long team=jdbc.queryForObject("SELECT id FROM project_team WHERE project_id=? AND leader_id=?",Long.class,project,leader);
        jdbc.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?),(?,?)",team,leader,team,member);
        jdbc.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,?,?)",project,"独立团队（演示）",other);
        long otherTeam=jdbc.queryForObject("SELECT id FROM project_team WHERE project_id=? AND leader_id=?",Long.class,project,other);
        jdbc.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?)",otherTeam,other);
        jdbc.update("""
            INSERT INTO assistant_team_scope(team_id,user_id)
            SELECT t.id,m.user_id FROM project_team t JOIN course_membership m ON m.course_id=?
            WHERE t.project_id=? AND m.role='TA' AND m.active=TRUE
            """,course,project);
    }
    private long account(String username,String name){return users.findByUsername(username).orElseGet(()->users.save(new AppUser(username,name,encoder.encode(password),false))).getId();}
}
