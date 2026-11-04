package cn.edu.school.workflow;

import cn.edu.school.common.BusinessException;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseRetention {
    private final JdbcTemplate db;
    private final Clock clock;
    private final Path storage;

    public CourseRetention(JdbcTemplate db,Clock clock,@Value("${app.storage-root}") String storage) {
        this.db=db; this.clock=clock; this.storage=Path.of(storage).toAbsolutePath().normalize();
    }

    public List<Map<String,Object>> overview(){
        return db.queryForList("""
            SELECT c.id,c.name,c.status,c.ended_at,r.hold_reason,r.approved_at,r.executed_at,r.manifest,
            (SELECT COUNT(*) FROM retention_file f WHERE f.course_id=c.id AND NOT f.removed) AS remaining_files
            FROM course c LEFT JOIN course_retention r ON r.course_id=c.id ORDER BY c.id
            """);
    }

    public List<Map<String,Object>> audit(long course){
        return db.queryForList("""
            SELECT a.action,a.detail,a.occurred_at,u.display_name FROM retention_audit a
            JOIN app_user u ON u.id=a.actor_id WHERE a.course_id=? ORDER BY a.id DESC LIMIT 100
            """,course);
    }

    private Map<String,Object> lock(long course){
        var rows=db.queryForList("""
            SELECT c.*,r.approved_by,r.executed_at,r.hold_reason FROM course c
            LEFT JOIN course_retention r ON r.course_id=c.id WHERE c.id=?
            """,course);
        check(!rows.isEmpty(),"课程不存在");
        db.queryForObject("SELECT id FROM course WHERE id=? FOR UPDATE",Long.class,course);
        return db.queryForMap("""
            SELECT c.*,r.approved_by,r.executed_at,r.hold_reason FROM course c
            LEFT JOIN course_retention r ON r.course_id=c.id WHERE c.id=?
            """,course);
    }

    private void record(long course,long actor,String action,String detail){
        db.update("INSERT INTO retention_audit(course_id,actor_id,action,detail) VALUES(?,?,?,?)",course,actor,action,detail);
    }

    @Transactional
    public void end(long course,long actor){
        var c=lock(course);
        check(c.get("ended_at")==null,"课程已经结束");
        check(count("SELECT COUNT(*) FROM course_project WHERE course_id=? AND status NOT IN ('ARCHIVED','TERMINATED')",course)==0,
            "请先归档或终止该课程的全部项目");
        db.update("UPDATE course SET ended_at=?,status='ENDED' WHERE id=?",OffsetDateTime.now(clock),course);
        db.update("INSERT INTO course_retention(course_id) VALUES(?)",course);
        record(course,actor,"ENDED","记录课程实际结束时间");
    }

    @Transactional
    public void hold(long course,long actor,String reason,boolean release){
        var c=lock(course);
        check(c.get("ended_at")!=null&&c.get("executed_at")==null,"只有已结束且未执行清理的课程可设置争议暂缓");
        check(reason!=null&&!reason.isBlank()&&reason.length()<=1000,"请输入1至1000字的暂缓或解除理由");
        boolean held=c.get("hold_reason")!=null;
        check(held==release,"暂缓状态已变更，请刷新后重试");
        db.update("UPDATE course_retention SET hold_reason=?,approved_by=NULL,approved_at=NULL WHERE course_id=?",held?null:reason.strip(),course);
        record(course,actor,held?"HOLD_RELEASED":"HOLD_SET",reason.strip());
    }

    private void eligible(Map<String,Object> course,long id){
        check(course.get("ended_at") instanceof OffsetDateTime,"请先登记课程结束");
        var ended=(OffsetDateTime)course.get("ended_at");
        var now=ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of("Asia/Shanghai"));
        check(!now.isBefore(ended.atZoneSameInstant(now.getZone()).plusYears(2)),"课程结束尚未满两年");
        check(course.get("hold_reason")==null,"课程存在已登记的未结争议");
        check(course.get("executed_at")==null,"业务数据已经清理");
        check(count("""
            SELECT COUNT(*) FROM review_appeal a JOIN review_round r ON r.id=a.round_id
            JOIN course_project p ON p.id=r.project_id WHERE p.course_id=? AND a.status='PENDING'
            """,id)==0 && count("""
            SELECT COUNT(*) FROM review_task k JOIN review_round r ON r.id=k.round_id
            JOIN course_project p ON p.id=r.project_id WHERE p.course_id=? AND k.reevaluation=TRUE AND k.status='PENDING'
            """,id)==0,"课程存在尚未结案的复核或重评");
    }

    @Transactional
    public void approve(long id,long actor){
        var c=lock(id);
        eligible(c,id);
        db.update("UPDATE course_retention SET approved_by=?,approved_at=? WHERE course_id=?",actor,OffsetDateTime.now(clock),id);
        record(id,actor,"APPROVED","批准清理达到留存期限的课程业务数据");
    }

    public Map<String,Long> preview(long id) {
        String project="SELECT id FROM course_project WHERE course_id=?";
        String team="SELECT id FROM project_team WHERE project_id IN ("+project+")";
        return Map.of("projects",count("SELECT COUNT(*) FROM course_project WHERE course_id=?",id),
            "teams",count("SELECT COUNT(*) FROM project_team WHERE id IN ("+team+")",id),
            "versions",count("SELECT COUNT(*) FROM submission_version WHERE team_id IN ("+team+")",id),
            "grades",count("SELECT COUNT(*) FROM grade_result WHERE course_id=?",id),
            "files",count("SELECT COUNT(*) FROM stored_file WHERE course_id=?",id));
    }

    @Transactional
    public void purge(long id,long actor){
        var c=lock(id);
        if(c.get("executed_at")!=null)return;
        eligible(c,id);
        check(c.get("approved_by") instanceof Number && ((Number)c.get("approved_by")).longValue()!=actor,
            "须由另一位具有审批权限的业务负责人批准");
        var manifest=preview(id);
        db.update("INSERT INTO retention_file(course_id,file_id) SELECT course_id,id FROM stored_file WHERE course_id=?",id);
        String projects="SELECT id FROM course_project WHERE course_id=?";
        String teams="SELECT id FROM project_team WHERE project_id IN ("+projects+")";
        String versions="SELECT id FROM submission_version WHERE team_id IN ("+teams+")";
        String bindings="SELECT id FROM submission_review_binding WHERE team_id IN ("+teams+")";
        String tasks="SELECT id FROM review_task WHERE binding_id IN ("+bindings+")";
        String reviews="SELECT id FROM review_submission WHERE task_id IN ("+tasks+")";
        delete("workflow_notice_job","course_id=?",id);
        delete("workflow_notice","course_id=?",id);
        delete("grade_member_result","result_id IN (SELECT id FROM grade_result WHERE course_id=?)",id);
        db.update("UPDATE grade_result SET parent_id=NULL WHERE course_id=?",id);
        delete("grade_result","course_id=?",id);
        delete("grading_event","course_id=?",id);
        delete("grade_roster","team_id IN ("+teams+")",id);
        delete("grading_criterion_score","sheet_id IN (SELECT id FROM grading_sheet WHERE team_id IN ("+teams+"))",id);
        delete("grading_sheet","team_id IN ("+teams+")",id);
        delete("review_appeal","team_id IN ("+teams+")",id);
        delete("review_dimension_score","submission_id IN ("+reviews+")",id);
        db.update("UPDATE review_task SET replaces_submission_id=NULL WHERE binding_id IN ("+bindings+")",id);
        db.update("UPDATE review_submission SET superseded_by=NULL WHERE task_id IN ("+tasks+")",id);
        delete("review_submission","task_id IN ("+tasks+")",id);
        delete("review_task","binding_id IN ("+bindings+")",id);
        delete("review_round","project_id IN ("+projects+")",id);
        delete("grading_criterion","rubric_id IN (SELECT id FROM grading_rubric WHERE course_id=?)",id);
        delete("grading_rubric","course_id=?",id);
        db.update("UPDATE inspection SET parent_id=NULL WHERE team_id IN ("+teams+")",id);
        delete("inspection","team_id IN ("+teams+")",id);
        delete("submission_notice","team_id IN ("+teams+")",id);
        delete("submission_event","team_id IN ("+teams+")",id);
        delete("submission_review_access","binding_id IN ("+bindings+")",id);
        delete("team_delivery","team_id IN ("+teams+")",id);
        delete("submission_review_binding","team_id IN ("+teams+")",id);
        delete("submission_version_material","version_id IN ("+versions+")",id);
        db.update("UPDATE submission_package SET current_version_id=NULL WHERE team_id IN ("+teams+")",id);
        delete("submission_version","team_id IN ("+teams+")",id);
        delete("submission_package","team_id IN ("+teams+")",id);
        for(String table:List.of("submission_material","submission_extension","submission_extension_request",
            "collaboration_event","team_task","team_milestone","team_iteration","assistant_team_scope","project_change_team"))
            delete(table,"team_id IN ("+teams+")",id);
        String members="SELECT id FROM project_team_member WHERE team_id IN ("+teams+")";
        delete("expired_team_application","member_id IN ("+members+")",id);
        delete("legacy_member_mapping","member_id IN ("+members+")",id);
        delete("project_team_member","team_id IN ("+teams+")",id);
        delete("legacy_team_mapping","team_id IN ("+teams+")",id);
        delete("project_team","project_id IN ("+projects+")",id);
        for(String table:List.of("project_change","project_requirement","project_criterion","legacy_project_mapping"))
            delete(table,"project_id IN ("+projects+")",id);
        delete("course_project","course_id=?",id);
        String oldProjects="SELECT id FROM legacy_project WHERE course_id=?";
        delete("legacy_team_member","team_id IN (SELECT id FROM legacy_team WHERE project_id IN ("+oldProjects+"))",id);
        delete("legacy_team","project_id IN ("+oldProjects+")",id);
        delete("legacy_project_criterion","project_id IN ("+oldProjects+")",id);
        delete("legacy_project","course_id=?",id);
        delete("stored_file","course_id=?",id);
        delete("audit_record","course_id=?",id);
        delete("course_membership","course_id=?",id);
        db.update("UPDATE course SET status='PURGED',description='' WHERE id=?",id);
        db.update("UPDATE course_retention SET executed_by=?,executed_at=?,manifest=? WHERE course_id=?",
            actor,OffsetDateTime.now(clock),manifest.toString(),id);
        record(id,actor,"PURGED",manifest.toString());
    }

    // Execute after the purge transaction commits. The queue survives restart, including after a file was removed.
    public void removeFiles(long course,long actor){
        for(UUID id:db.queryForList("""
            SELECT f.file_id FROM retention_file f JOIN course_retention r ON r.course_id=f.course_id
            WHERE f.course_id=? AND NOT f.removed AND r.executed_at IS NOT NULL
            """,UUID.class,course)){
            try {
                Files.deleteIfExists(storage.resolve(id+".bin"));
                db.update("UPDATE retention_file SET removed=TRUE,error=NULL WHERE course_id=? AND file_id=?",course,id);
                record(course,actor,"FILE_REMOVED",id.toString());
            } catch(IOException ex){
                db.update("UPDATE retention_file SET error=? WHERE course_id=? AND file_id=?","附件删除失败，可重新执行清理",course,id);
                record(course,actor,"FILE_FAILED",id.toString());
            }
        }
    }

    private long count(String query,long id){return db.queryForObject(query,Long.class,id);}
    private void delete(String table,String where,long id){db.update("DELETE FROM "+table+" WHERE "+where,id);}
    private static void check(boolean ok,String message){
        if(!ok)throw new BusinessException(HttpStatus.CONFLICT,"RETENTION_BLOCKED",message);
    }
}
