package cn.edu.school.config;

import cn.edu.school.auth.AppUser;
import cn.edu.school.auth.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps both existing and freshly created local demo teams on the agreed roster. */
@Component
@Profile("demo & !postgres")
public class DemoRosterSeeder {
    private final JdbcTemplate db;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String password;

    public DemoRosterSeeder(JdbcTemplate db, UserRepository users, PasswordEncoder encoder,
                           @Value("${app.demo-password}") String password) {
        this.db=db; this.users=users; this.encoder=encoder; this.password=password;
    }

    @Order(300)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        db.update("UPDATE app_user SET display_name=? WHERE username='teacher01'", "郭立君");
        db.update("UPDATE app_user SET display_name=? WHERE username='student01'", "石洁燃");
        db.update("UPDATE app_user SET display_name=? WHERE username='student03'", "未宁夏");
        var teams=db.queryForList("""
            SELECT t.id, t.project_id, p.course_id FROM project_team t
            JOIN course_project p ON p.id=t.project_id
            JOIN app_user u ON u.id=t.leader_id
            WHERE p.demo_fixture=TRUE AND u.username='student01' AND t.course_team_id IS NULL
            """);
        if(teams.isEmpty()) return;
        long chen=account("student05", "陈爽"), wang=account("student06", "汪欣芸");
        for(var team:teams) {
            long teamId=((Number)team.get("id")).longValue();
            long courseId=((Number)team.get("course_id")).longValue();
            for(long userId:new long[]{chen,wang}) {
                if(db.queryForObject("SELECT COUNT(*) FROM course_membership WHERE course_id=? AND user_id=?",Long.class,courseId,userId)==0)
                    db.update("INSERT INTO course_membership(course_id,user_id,role,active) VALUES(?,?,'STUDENT',TRUE)",courseId,userId);
                // A fixture must respect an existing membership in the same project.
                long projectId=((Number)team.get("project_id")).longValue();
                if(db.queryForObject("SELECT COUNT(*) FROM project_team_member m JOIN project_team t ON t.id=m.team_id WHERE t.project_id=? AND m.user_id=? AND m.team_id<>? AND m.status IN ('ACTIVE','PENDING')",Long.class,projectId,userId,teamId)>0) continue;
                if(db.queryForObject("SELECT COUNT(*) FROM project_team_member WHERE team_id=? AND user_id=?",Long.class,teamId,userId)==0) {
                    db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?)",teamId,userId);
                    db.update("UPDATE project_team SET revision=revision+1 WHERE id=?",teamId);
                }
            }
        }
    }

    private long account(String username,String name) {
        long id=users.findByUsername(username).map(AppUser::getId)
            .orElseGet(()->users.saveAndFlush(new AppUser(username,name,encoder.encode(password),false)).getId());
        db.update("UPDATE app_user SET display_name=? WHERE id=?",name,id);
        return id;
    }
}
