package cn.edu.school.review;

import cn.edu.school.auth.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo fixture for modules 5/6: three teams, each with a confirmed evaluated version. */
@Component @Profile("demo & !postgres")
public class ReviewInspectionDemoSeeder {
    private final JdbcTemplate db; private final UserRepository users; private final PasswordEncoder encoder; private final String password;
    public ReviewInspectionDemoSeeder(JdbcTemplate db, UserRepository users, PasswordEncoder encoder, @Value("${app.demo-password}") String password){
        this.db=db; this.users=users; this.encoder=encoder; this.password=password;
    }
    @Order(400) @EventListener(ApplicationReadyEvent.class) @Transactional
    public void seed(){
        String projectName="检查与互评（演示）";
        if(db.queryForObject("SELECT COUNT(*) FROM course_project WHERE name=? AND demo_fixture=TRUE",Long.class,projectName)>0)return;
        var courseIds=db.queryForList("SELECT c.course_id FROM course_membership c JOIN app_user u ON u.id=c.user_id WHERE u.username='teacher01' AND c.role='TEACHER' AND c.active=TRUE ORDER BY c.course_id",Long.class);
        if(courseIds.isEmpty())return;
        long course=courseIds.getFirst();
        long teacher=account("teacher01","郭立君");
        long leaderA=account("student01","石洁燃"),memberA=account("student03","未宁夏");
        long leaderB=account("student04","评审演示乙组负责人"),leaderC=account("student05","陈爽"),memberC=account("student06","汪欣芸");
        for(long u:new long[]{memberA,leaderB,leaderC,memberC})
            if(db.queryForObject("SELECT COUNT(*) FROM course_membership WHERE course_id=? AND user_id=?",Long.class,course,u)==0)
                db.update("INSERT INTO course_membership(course_id,user_id,role,active) VALUES(?,?,'STUDENT',TRUE)",course,u);
        OffsetDateTime deadline=OffsetDateTime.now().plusDays(30);
        db.update("INSERT INTO course_project(course_id,name,status,final_deadline,demo_fixture) VALUES(?,?,'IN_PROGRESS',?,TRUE)",course,projectName,deadline);
        long project=db.queryForObject("SELECT id FROM course_project WHERE name=? AND demo_fixture=TRUE",Long.class,projectName);
        String[] names={"评审演示·甲组（演示）","评审演示·乙组（演示）","评审演示·丙组（演示）"};
        long[][] roster={{leaderA,memberA},{leaderB,0},{leaderC,memberC}};
        for(int i=0;i<3;i++){
            db.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,?,?)",project,names[i],roster[i][0]);
            long team=db.queryForObject("SELECT id FROM project_team WHERE project_id=? AND name=?",Long.class,project,names[i]);
            db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?)",team,roster[i][0]);
            if(roster[i][1]!=0)db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?)",team,roster[i][1]);
            seedDelivery(team,roster[i][0],teacher,deadline,i);
        }
    }
    private void seedDelivery(long team,long leader,long teacher,OffsetDateTime deadline,int idx){
        LocalDate d=deadline.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        db.update("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end,status,completion_summary,ended_at) VALUES(?,'交付迭代','演示被评成果',1,?,?,'ENDED','演示迭代已完成',?)",team,d.minusDays(7),d,deadline);
        long iteration=db.queryForObject("SELECT id FROM team_iteration WHERE team_id=?",Long.class,team);
        db.update("INSERT INTO submission_package(team_id,iteration_id) VALUES(?,?)",team,iteration);
        long pkg=db.queryForObject("SELECT id FROM submission_package WHERE team_id=?",Long.class,team);
        db.update("INSERT INTO submission_version(package_id,team_id,iteration_id,version_no,submitted_by,summary,accepted_at,deadline_snapshot,late,total_bytes,request_key,payload_hash,receipt_code) VALUES(?,?,?,1,?,?,?,?,FALSE,0,?,?,?)",pkg,team,iteration,leader,"演示：团队已提交最终成果",deadline.minusDays(1),deadline,UUID.randomUUID(),"0".repeat(64),"DEMO-CHK-"+idx+"-"+team);
        long version=db.queryForObject("SELECT id FROM submission_version WHERE team_id=?",Long.class,team);
        db.update("UPDATE submission_package SET current_version_id=? WHERE id=?",version,pkg);
        db.update("INSERT INTO team_delivery(team_id,final_version_id,selected_by,selected_at,frozen_at,frozen_deadline) VALUES(?,?,?,?,?,?)",team,version,leader,deadline.minusDays(1),deadline,deadline);
        db.update("INSERT INTO submission_review_binding(team_id,version_id,confirmed_by,confirmed_at,reason,reassessment_required) VALUES(?,?,?,?,?,FALSE)",team,version,teacher,deadline,"演示：教师确认被评版本");
        long binding=db.queryForObject("SELECT id FROM submission_review_binding WHERE team_id=? ORDER BY id DESC LIMIT 1",Long.class,team);
        db.update("UPDATE team_delivery SET review_binding_id=? WHERE team_id=?",binding,team);
    }
    private long account(String username,String name){
        long id=users.findByUsername(username).map(AppUser::getId)
            .orElseGet(()->users.saveAndFlush(new AppUser(username,name,encoder.encode(password),false)).getId());
        db.update("UPDATE app_user SET display_name=? WHERE id=?",name,id);
        return id;
    }
}
