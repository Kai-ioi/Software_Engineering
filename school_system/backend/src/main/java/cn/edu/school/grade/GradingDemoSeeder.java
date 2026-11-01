package cn.edu.school.grade;

import java.time.*;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo-only fixture: seeds a default rubric and a confirmed review version per demo team. */
@Component @Profile("demo & !postgres")
public class GradingDemoSeeder {
    private final JdbcTemplate db;
    public GradingDemoSeeder(JdbcTemplate db){ this.db=db; }

    @Order(400) @EventListener(ApplicationReadyEvent.class) @Transactional
    public void seed(){
        var courses = db.queryForList("SELECT c.course_id FROM course_membership c JOIN app_user u ON u.id=c.user_id WHERE u.username='teacher01' AND c.role='TEACHER' AND c.active=TRUE ORDER BY c.course_id", Long.class);
        if(courses.isEmpty()) return;
        long course = courses.getFirst();
        long teacher = user("teacher01");
        if(db.queryForObject("SELECT COUNT(*) FROM grading_rubric WHERE course_id=?", Long.class, course)==0){
            long rubric = insert("INSERT INTO grading_rubric(course_id,name,version_no,active,created_by,created_at) VALUES(?,?,?,TRUE,?,?)", course, "课程项目评分标准（演示）", 1, teacher, OffsetDateTime.now());
            insert("INSERT INTO grading_criterion(rubric_id,name,description,max_score,sort_order) VALUES(?,?,?,?,?)", rubric, "功能完整性", "需求覆盖程度与功能可用性", 30, 1);
            insert("INSERT INTO grading_criterion(rubric_id,name,description,max_score,sort_order) VALUES(?,?,?,?,?)", rubric, "代码与工程质量", "结构、可维护性与质量", 25, 2);
            insert("INSERT INTO grading_criterion(rubric_id,name,description,max_score,sort_order) VALUES(?,?,?,?,?)", rubric, "文档与过程记录", "文档完整性与过程留痕", 20, 3);
            insert("INSERT INTO grading_criterion(rubric_id,name,description,max_score,sort_order) VALUES(?,?,?,?,?)", rubric, "演示与答辩", "现场演示与答辩表现", 25, 4);
        }
        var teams = db.queryForList("SELECT t.id FROM project_team t JOIN course_project p ON p.id=t.project_id WHERE p.course_id=? AND p.demo_fixture=TRUE ORDER BY t.id", Long.class, course);
        for(long team : teams){
            if(db.queryForObject("SELECT COUNT(*) FROM team_delivery WHERE team_id=? AND review_binding_id IS NOT NULL", Long.class, team)>0) continue;
            seedReviewVersion(team, teacher);
        }
    }

    private void seedReviewVersion(long team, long teacher){
        long iteration;
        var iters = db.queryForList("SELECT id FROM team_iteration WHERE team_id=? ORDER BY sequence_no, id LIMIT 1", Long.class, team);
        if(iters.isEmpty()){
            iteration = insert("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end,status,completion_summary,leftovers,ended_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                team, "成果验收迭代（演示）", "演示：完成成果交付与验收", 1, LocalDate.now().minusDays(14), LocalDate.now().minusDays(7), "ENDED", "演示迭代已结束", "", OffsetDateTime.now());
        } else iteration = iters.getFirst();
        long leader = leader(team);
        OffsetDateTime accepted = OffsetDateTime.now().minusDays(1);
        long pkg = insert("INSERT INTO submission_package(team_id,iteration_id) VALUES(?,?)", team, iteration);
        UUID key = UUID.randomUUID();
        String receipt = "G-DEMO-"+team+"-"+key.toString().substring(0,8);
        long version = insert("INSERT INTO submission_version(package_id,team_id,iteration_id,version_no,submitted_by,summary,accepted_at,deadline_snapshot,late,total_bytes,request_key,payload_hash,receipt_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            pkg, team, iteration, 1, leader, "演示成果版本（供评分使用）", accepted, accepted, false, 0L, key, "demo-grading-"+team, receipt);
        insert("INSERT INTO submission_version_material(version_id,kind,title,url,code_link,code_ref,size_bytes) VALUES(?,'LINK',?,?,FALSE,'',0)", version, "项目成果链接（演示）", "https://example.com/demo-"+team);
        db.update("UPDATE submission_package SET current_version_id=? WHERE id=?", version, pkg);
        long binding = insert("INSERT INTO submission_review_binding(team_id,version_id,confirmed_by,confirmed_at,reason,reassessment_required) VALUES(?,?,?,?,?,FALSE)",
            team, version, teacher, OffsetDateTime.now(), "演示：确认被评版本");
        if(db.queryForObject("SELECT COUNT(*) FROM team_delivery WHERE team_id=?", Long.class, team)==0)
            db.update("INSERT INTO team_delivery(team_id) VALUES(?)", team);
        db.update("UPDATE team_delivery SET final_version_id=?, review_binding_id=? WHERE team_id=?", version, binding, team);
    }

    private long user(String username){ return db.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username); }
    private long leader(long team){ return db.queryForObject("SELECT leader_id FROM project_team WHERE id=?", Long.class, team); }
    private long insert(String sql, Object... args){
        var key = new GeneratedKeyHolder();
        db.update(con -> { var ps = con.prepareStatement(sql, new String[]{"id"}); for(int i=0;i<args.length;i++) ps.setObject(i+1, args[i]); return ps; }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
}
