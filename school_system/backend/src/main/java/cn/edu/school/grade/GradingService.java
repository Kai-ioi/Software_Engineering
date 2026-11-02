package cn.edu.school.grade;

import cn.edu.school.audit.AuditService;
import cn.edu.school.collaboration.ProjectTeamGateway;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class GradingService {
    private final JdbcTemplate db;
    private final ProjectTeamGateway gateway;
    private final CoursePermission permission;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    public GradingService(JdbcTemplate db, ProjectTeamGateway gateway, CoursePermission permission,
                          AuditService audit, ObjectMapper json, Clock clock) {
        this.db=db; this.gateway=gateway; this.permission=permission; this.audit=audit; this.json=json; this.clock=clock;
    }

    static long n(Map<String,Object> m, String k){ return ((Number)m.get(k)).longValue(); }
    static int ni(Map<String,Object> m, String k){ return ((Number)m.get(k)).intValue(); }
    private static void check(boolean ok, String code, String message){ if(!ok) throw new BusinessException(HttpStatus.BAD_REQUEST, code, message); }
    private static void conflict(String code, String message){ throw new BusinessException(HttpStatus.CONFLICT, code, message); }
    private OffsetDateTime now(){ return OffsetDateTime.now(clock); }

    private record Ctx(long userId, long courseId, Map<String,Object> team, Map<String,Object> project,
                       Map<String,Object> rubric, List<Map<String,Object>> criteria, Map<String,Object> binding) {}

    private Map<String,Object> rubricOf(long courseId){
        var rows = db.queryForList("SELECT * FROM grading_rubric WHERE course_id=? AND active=TRUE ORDER BY version_no DESC, id DESC LIMIT 1", courseId);
        if(rows.isEmpty()) conflict("NO_RUBRIC", "本课程尚未配置评分标准，请先配置后再评分");
        return rows.getFirst();
    }
    private List<Map<String,Object>> criteriaOf(long rubricId){
        return rows("SELECT * FROM grading_criterion WHERE rubric_id=? ORDER BY sort_order, id", rubricId);
    }
    private Map<String,Object> bindingOf(long teamId){
        var rows = db.queryForList("SELECT b.* FROM team_delivery d JOIN submission_review_binding b ON b.id=d.review_binding_id WHERE d.team_id=?", teamId);
        if(rows.isEmpty()) conflict("NO_REVIEW_BINDING", "该团队尚未确认被评成果版本，请先到“成果与版本”确认后再评分");
        return rows.getFirst();
    }
    private Ctx ctx(long user, long teamId, boolean lock){
        var team = gateway.team(teamId, lock);
        var project = gateway.project(n(team, "project_id"));
        long courseId = n(project, "course_id");
        permission.requireTeacher(user, courseId);
        var rubric = rubricOf(courseId);
        return new Ctx(user, courseId, team, project, rubric, criteriaOf(n(rubric, "id")), bindingOf(teamId));
    }

    public record ScoreInput(Long criterionId, Integer score){}
    public record SaveInput(Long rubricId, List<ScoreInput> scores, String reason){}

    // ────────── Module 8 ──────────

    /** Teacher: aggregate summary for every team with a confirmed binding.
     *  Returns teacher/peer/final scores plus per-team exceptions. */
    public Map<String,Object> summary(long user, long courseId){
        permission.requireTeacher(user, courseId);
        var rubric = rubricOf(courseId); long rubricId = n(rubric, "id");
        var criteria = criteriaOf(rubricId);
        var rows = rows("""
            SELECT t.id AS team_id, t.name AS team_name,
                   p.id AS project_id, p.name AS project_name,
                   d.review_binding_id AS binding_id, b.version_id,
                   s.id AS sheet_id, s.status AS sheet_status,
                   s.submitted_at, s.graded_by,
                   u.display_name AS graded_by_name,
                   r.id AS roster_id, r.member_ids
            FROM project_team t
            JOIN course_project p ON p.id=t.project_id
            JOIN team_delivery d ON d.team_id=t.id
            JOIN submission_review_binding b ON b.id=d.review_binding_id
            LEFT JOIN grading_sheet s ON s.team_id=t.id AND s.rubric_id=? AND s.status='SUBMITTED'
            LEFT JOIN app_user u ON u.id=s.graded_by
            LEFT JOIN grade_roster r ON r.team_id=t.id AND r.rubric_id=?
            WHERE p.course_id=?
            ORDER BY t.id
            """, rubricId, rubricId, courseId);
        var out = new LinkedHashMap<String,Object>();
        out.put("rubric", clean(rubric)); out.put("criteria", criteria);
        var teams = new ArrayList<Map<String,Object>>();
        for(var row : rows){
            long teamId = n(row, "team_id");
            var tr = new LinkedHashMap<>(row);
            // teacher total: sum criterion scores
            Integer teacherTotal = null;
            if(row.get("sheet_id")!=null){
                long sid = n(row, "sheet_id");
                var scRows = db.queryForList("SELECT score FROM grading_criterion_score WHERE sheet_id=?", sid);
                teacherTotal = scRows.stream().mapToInt(r2 -> ni(r2, "score")).sum();
            }
            tr.put("teacher_total", teacherTotal);
            // peer total: avg of all submitted reviews for this binding
            Long bindingId = row.get("binding_id")==null ? null : n(row, "binding_id");
            Double peerAvg = null;
            boolean peerMissing = false;
            if(bindingId!=null){
                var peerRows = db.queryForList("SELECT score FROM review_submission WHERE binding_id=? ORDER BY id", bindingId);
                if(peerRows.isEmpty()) peerMissing = true;
                else peerAvg = peerRows.stream().mapToInt(r2 -> ni(r2, "score")).average().orElse(0);
            } else peerMissing = true;
            tr.put("peer_avg", peerAvg==null ? null : Math.round(peerAvg * 100.0) / 100.0);
            tr.put("peer_missing", peerMissing);
            // final
            int tw = ni(rubric, "teacher_weight")>0 ? ni(rubric, "teacher_weight") : 70;
            int pw = 100 - tw;
            Double finalScore = null;
            if(teacherTotal!=null && peerAvg!=null)
                finalScore = Math.round((teacherTotal * tw / 100.0 + peerAvg * pw / 100.0) * 100.0) / 100.0;
            tr.put("final_total", finalScore);
            tr.put("teacher_weight", tw); tr.put("peer_weight", pw);
            // exceptions
            var excs = new ArrayList<String>();
            if(bindingId==null) excs.add("缺被评版本，请先到成果与版本确认");
            else if(peerMissing) excs.add("互评未完成，所有学生的评审提交后才可发布");
            if(row.get("sheet_id")==null) excs.add("教师评分尚未正式提交");
            if(row.get("roster_id")==null) excs.add("计分名单尚未核对");
            tr.put("exceptions", excs);
            // published status
            var pub = db.queryForList("SELECT * FROM grade_result WHERE team_id=? AND rubric_id=? ORDER BY version_no DESC LIMIT 1", teamId, rubricId);
            if(!pub.isEmpty()){
                var p = clean(pub.getFirst());
                tr.put("published", p);
                var members = rows("SELECT gm.*, u.display_name FROM grade_member_result gm JOIN app_user u ON u.id=gm.user_id WHERE gm.result_id=? ORDER BY gm.id", n(p, "id"));
                tr.put("member_results", members);
            } else { tr.put("published", null); tr.put("member_results", List.of()); }
            teams.add(tr);
        }
        out.put("teams", teams);
        out.put("serverTime", now().toString());
        return out;
    }

    @Transactional
    public Map<String,Object> confirmRoster(long user, long teamId){
        var c = ctx(user, teamId, true);
        long rubricId = n(c.rubric, "id");
        var members = gateway.members(teamId, c.courseId);
        check(!members.isEmpty(), "NO_MEMBERS", "该团队没有有效学生成员");
        var ids = members.stream().map(m -> n(m, "id")).sorted().toList();
        var idsText = new StringBuilder();
        for(long id : ids){ if(!idsText.isEmpty()) idsText.append(','); idsText.append(id); }
        db.update("DELETE FROM grade_roster WHERE team_id=?", teamId);
        db.update("INSERT INTO grade_roster(team_id,rubric_id,member_ids,confirmed_by,confirmed_at) VALUES(?,?,?,?,?)",
            teamId, rubricId, idsText.toString(), user, now());
        event(c, "ROSTER_CONFIRMED", "roster:"+teamId, "核对计分名单("+members.size()+"人)", Map.of(), Map.of("memberCount", (Object)members.size()));
        return Map.of("teamId", teamId, "memberCount", members.size(), "members", members.stream().map(GradingService::clean).toList());
    }

    public record PublishInput(String reason){}

    @Transactional
    public Map<String,Object> publish(long user, long courseId, PublishInput in){
        permission.requireTeacher(user, courseId);
        var rubric = rubricOf(courseId); long rubricId = n(rubric, "id");
        var rows = db.queryForList("""
            SELECT t.id AS team_id, d.review_binding_id, s.id AS sheet_id, r.id AS roster_id
            FROM project_team t
            JOIN course_project p ON p.id=t.project_id
            JOIN team_delivery d ON d.team_id=t.id
            LEFT JOIN grading_sheet s ON s.team_id=t.id AND s.rubric_id=? AND s.status='SUBMITTED'
            LEFT JOIN grade_roster r ON r.team_id=t.id AND r.rubric_id=?
            WHERE p.course_id=?
            """, rubricId, rubricId, courseId);
        check(!rows.isEmpty(), "NO_TEAMS_TO_PUBLISH", "没有可发布的团队");
        int tw = ni(rubric, "teacher_weight")>0 ? ni(rubric, "teacher_weight") : 70;
        int pw = 100 - tw;
        int published = 0;
        for(var row : rows){
            long teamId = n(row, "team_id");
            Long sheetId = row.get("sheet_id")==null ? null : n(row, "sheet_id");
            Long rosterId = row.get("roster_id")==null ? null : n(row, "roster_id");
            Long bindingId = row.get("review_binding_id")==null ? null : n(row, "review_binding_id");
            // validate
            check(bindingId!=null, "MISSING_BINDING_TEAM_"+teamId, "团队#"+teamId+"缺少被评版本");
            check(sheetId!=null, "MISSING_TEACHER_SCORE_"+teamId, "团队#"+teamId+"教师评分未提交");
            check(rosterId!=null, "MISSING_ROSTER_"+teamId, "团队#"+teamId+"计分名单未核对");
            var peerRows = db.queryForList("SELECT score FROM review_submission WHERE binding_id=?", bindingId);
            check(!peerRows.isEmpty(), "MISSING_PEER_SCORE_"+teamId, "团队#"+teamId+"互评未完成或评分缺失");
            // compute
            var scRows = db.queryForList("SELECT score FROM grading_criterion_score WHERE sheet_id=?", sheetId);
            double teacherTotal = scRows.stream().mapToInt(r2 -> ni(r2, "score")).sum();
            double peerAvg = peerRows.stream().mapToInt(r2 -> ni(r2, "score")).average().orElse(0);
            double finalScore = Math.round((teacherTotal * tw / 100.0 + peerAvg * pw / 100.0) * 100.0) / 100.0;
            long resultId = insert("INSERT INTO grade_result(course_id,team_id,rubric_id,sheet_id,roster_id,teacher_total,peer_total,final_total,teacher_weight,peer_weight,status,version_no,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,'PUBLISHED',1,?,?)",
                courseId, teamId, rubricId, sheetId, rosterId, teacherTotal, Math.round(peerAvg*100.0)/100.0, finalScore, tw, pw, user, now());
            // write member results (same score for all)
            var roster = one("SELECT member_ids FROM grade_roster WHERE id=?", "ROSTER_NOT_FOUND", rosterId);
            for(var idStr : roster.get("member_ids").toString().split(",")){
                long mid = Long.parseLong(idStr.trim());
                db.update("INSERT INTO grade_member_result(result_id,user_id,final_total) VALUES(?,?,?)", resultId, mid, finalScore);
            }
            published++;
        }
        check(in.reason()!=null && !in.reason().isBlank(), "REASON_REQUIRED", "成绩发布须填写发布说明");
        audit.append(user, courseId, "GRADE_PUBLISHED", "course:"+courseId, "发布成绩（"+published+"个团队），"+in.reason().strip());
        return Map.of("publishedTeams", published, "reason", in.reason().strip());
    }

    public record CorrectInput(String reason){}

    @Transactional
    public Map<String,Object> correct(long user, long resultId, CorrectInput in){
        check(in.reason()!=null && !in.reason().isBlank(), "REASON_REQUIRED", "成绩更正必须填写原因");
        var old = one("SELECT * FROM grade_result WHERE id=? AND status='PUBLISHED'", "RESULT_NOT_FOUND_OR_NOT_PUBLISHED", resultId);
        long courseId = n(old, "course_id");
        permission.requireTeacher(user, courseId);
        long teamId = n(old, "team_id");
        int nextVersion = ni(old, "version_no") + 1;
        long newId = insert("INSERT INTO grade_result(course_id,team_id,rubric_id,sheet_id,roster_id,teacher_total,peer_total,final_total,teacher_weight,peer_weight,status,version_no,parent_id,correction_reason,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,'CORRECTED',?,?,?,?,?)",
            courseId, teamId, n(old, "rubric_id"), n(old, "sheet_id"), n(old, "roster_id"),
            ((Number)old.get("teacher_total")).doubleValue(), ((Number)old.get("peer_total")).doubleValue(),
            ((Number)old.get("final_total")).doubleValue(), ni(old, "teacher_weight"), ni(old, "peer_weight"),
            nextVersion, resultId, in.reason().strip(), user, now());
        for(var member : db.queryForList("SELECT * FROM grade_member_result WHERE result_id=?", resultId))
            db.update("INSERT INTO grade_member_result(result_id,user_id,final_total) VALUES(?,?,?)", newId, n(member, "user_id"), ((Number)member.get("final_total")).doubleValue());
        audit.append(user, courseId, "GRADE_CORRECTED", "result:"+resultId+"->"+newId, "成绩更正v"+nextVersion+"："+in.reason().strip());
        return Map.of("correctedId", newId, "versionNo", nextVersion, "reason", in.reason().strip());
    }

    /** Student: query my own grade results across courses (latest version per team). */
    public Map<String,Object> myGrades(long user){
        var out = new LinkedHashMap<String,Object>();
        out.put("userId", user);
        var results = rows("""
            SELECT gr.id, gr.course_id, gr.team_id, gr.teacher_total, gr.peer_total, gr.final_total,
                   gr.teacher_weight, gr.peer_weight, gr.status, gr.version_no, gr.correction_reason, gr.published_at,
                   t.name AS team_name, p.name AS project_name, c.name AS course_name
            FROM grade_member_result gm
            JOIN grade_result gr ON gr.id=gm.result_id
            JOIN project_team t ON t.id=gr.team_id
            JOIN course_project p ON p.id=t.project_id
            JOIN course c ON c.id=gr.course_id
            WHERE gm.user_id=? ORDER BY gr.team_id, gr.version_no DESC
            """, user);
        var latest = new LinkedHashMap<Long,Map<String,Object>>();
        for(var r : results){
            long teamId = n(r, "team_id");
            latest.putIfAbsent(teamId, r); // ordered version_no DESC → first is latest
        }
        out.put("results", new ArrayList<>(latest.values()));
        return out;
    }

    /** Export: CSV or XLSX. */
    public Export export(long user, long courseId, String format, String filenamePrefix){
        permission.requireTeacher(user, courseId);
        var rubric = rubricOf(courseId); long rubricId = n(rubric, "id");
        var rows = rows("""
            SELECT t.name AS team_name, p.name AS project_name,
                   gr.teacher_total, gr.peer_total, gr.final_total,
                   gr.teacher_weight, gr.peer_weight, gr.status, gr.version_no, gr.correction_reason,
                   gr.published_at, u.display_name AS published_by_name
            FROM grade_result gr
            JOIN project_team t ON t.id=gr.team_id
            JOIN course_project p ON p.id=t.project_id
            JOIN app_user u ON u.id=gr.published_by
            WHERE gr.course_id=? AND gr.rubric_id=?
            ORDER BY t.id, gr.version_no DESC
            """, courseId, rubricId);
        var members = rows("""
            SELECT gm.*, t.name AS team_name, au.display_name
            FROM grade_member_result gm
            JOIN grade_result gr ON gr.id=gm.result_id
            JOIN project_team t ON t.id=gr.team_id
            JOIN app_user au ON au.id=gm.user_id
            WHERE gr.course_id=? AND gr.rubric_id=?
            ORDER BY t.id, gm.id
            """, courseId, rubricId);
        String prefix = filenamePrefix!=null && !filenamePrefix.isBlank() ? filenamePrefix : "grades_"+courseId;
        if("xlsx".equalsIgnoreCase(format)){
            try{
                var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                var s = wb.createSheet("成绩汇总");
                var header = s.createRow(0);
                String[] th = {"团队","项目","教师得分","互评均分","最终总分","教师权重","互评权重","状态","版本号","更正原因","发布时间","发布人"};
                for(int i=0;i<th.length;i++) header.createCell(i).setCellValue(th[i]);
                int ri=1;
                for(var r : rows){
                    var xr = s.createRow(ri++);
                    xr.createCell(0).setCellValue((String)r.get("team_name"));
                    xr.createCell(1).setCellValue((String)r.get("project_name"));
                    xr.createCell(2).setCellValue(r.get("teacher_total")==null?0:((Number)r.get("teacher_total")).doubleValue());
                    xr.createCell(3).setCellValue(r.get("peer_total")==null?0:((Number)r.get("peer_total")).doubleValue());
                    xr.createCell(4).setCellValue(r.get("final_total")==null?0:((Number)r.get("final_total")).doubleValue());
                    xr.createCell(5).setCellValue(r.get("teacher_weight")==null?70:((Number)r.get("teacher_weight")).intValue());
                    xr.createCell(6).setCellValue(r.get("peer_weight")==null?30:((Number)r.get("peer_weight")).intValue());
                    xr.createCell(7).setCellValue((String)r.get("status"));
                    xr.createCell(8).setCellValue(r.get("version_no")==null?1:((Number)r.get("version_no")).intValue());
                    xr.createCell(9).setCellValue((String)r.getOrDefault("correction_reason",""));
                    xr.createCell(10).setCellValue((String)r.get("published_at"));
                    xr.createCell(11).setCellValue((String)r.get("published_by_name"));
                }
                var s2 = wb.createSheet("成员成绩");
                var h2 = s2.createRow(0);
                h2.createCell(0).setCellValue("团队"); h2.createCell(1).setCellValue("姓名"); h2.createCell(2).setCellValue("最终总分");
                ri=1;
                for(var m : members){
                    var xr = s2.createRow(ri++);
                    xr.createCell(0).setCellValue((String)m.get("team_name"));
                    xr.createCell(1).setCellValue((String)m.get("display_name"));
                    xr.createCell(2).setCellValue(((Number)m.get("final_total")).doubleValue());
                }
                var bos = new java.io.ByteArrayOutputStream();
                wb.write(bos); wb.close();
                return new Export(prefix+".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());
            }catch(java.io.IOException e){ throw new IllegalStateException("XLSX生成失败", e); }
        } else {
            var sb = new StringBuilder("团队,项目,教师得分,互评均分,最终总分,教师权重,互评权重,状态,版本号,更正原因,发布时间,发布人\n");
            for(var r : rows){
                sb.append(escapeCsv(r.get("team_name"))).append(",");
                sb.append(escapeCsv(r.get("project_name"))).append(",");
                sb.append(r.get("teacher_total")==null?"":r.get("teacher_total")).append(",");
                sb.append(r.get("peer_total")==null?"":r.get("peer_total")).append(",");
                sb.append(r.get("final_total")==null?"":r.get("final_total")).append(",");
                sb.append(r.get("teacher_weight")==null?70:r.get("teacher_weight")).append(",");
                sb.append(r.get("peer_weight")==null?30:r.get("peer_weight")).append(",");
                sb.append(r.get("status")).append(",");
                sb.append(r.get("version_no")==null?1:r.get("version_no")).append(",");
                sb.append(escapeCsv(r.getOrDefault("correction_reason",""))).append(",");
                sb.append(escapeCsv(r.get("published_at"))).append(",");
                sb.append(escapeCsv(r.get("published_by_name"))).append("\n");
            }
            sb.append("\n成员姓名,团队,最终总分\n");
            for(var m : members){
                sb.append(escapeCsv(m.get("display_name"))).append(",");
                sb.append(escapeCsv(m.get("team_name"))).append(",");
                sb.append(m.get("final_total")).append("\n");
            }
            return new Export(prefix+".csv", "text/csv; charset=UTF-8", ("﻿"+sb.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private static String escapeCsv(Object value){
        if(value==null) return "";
        String s = value.toString();
        if(s.contains(",")||s.contains("\"")||s.contains("\n")) return "\""+s.replace("\"","\"\"")+"\"";
        return s;
    }
    public record Export(String filename, String contentType, byte[] bytes){}

    // ────────── end Module 8 ──────────

    /** Teacher: pending list + active rubric. Student/TA: nothing to show until module 8 publishes. */
    public Map<String,Object> overview(long user, long courseId){
        var role = permission.requireMember(user, courseId);
        var rubric = rubricOf(courseId);
        var out = new LinkedHashMap<String,Object>();
        out.put("role", role.name());
        out.put("rubric", clean(rubric));
        out.put("criteria", criteriaOf(n(rubric, "id")));
        if(role == CourseRole.TEACHER){
            out.put("teams", rows("""
                SELECT t.id, t.name, p.id AS project_id, p.name AS project_name,
                       b.id AS binding_id, b.version_id, b.confirmed_at,
                       s.id AS sheet_id, s.status AS sheet_status, s.submitted_at, s.last_reason
                FROM project_team t
                JOIN course_project p ON p.id=t.project_id
                JOIN team_delivery d ON d.team_id=t.id
                JOIN submission_review_binding b ON b.id=d.review_binding_id
                LEFT JOIN grading_sheet s ON s.team_id=t.id AND s.rubric_id=?
                WHERE p.course_id=?
                ORDER BY t.id
                """, n(rubric, "id"), courseId));
        } else {
            out.put("teams", List.of());
        }
        return out;
    }

    /** Confirmed review version + its materials + active rubric + existing sheet/scores. */
    public Map<String,Object> teamSheet(long user, long teamId){
        var c = ctx(user, teamId, false);
        long rubricId = n(c.rubric, "id");
        long versionId = n(c.binding, "version_id");
        var version = one("SELECT v.*, u.display_name AS submitter_name FROM submission_version v JOIN app_user u ON u.id=v.submitted_by WHERE v.id=?", "VERSION_NOT_FOUND", versionId);
        var materials = rows("SELECT * FROM submission_version_material WHERE version_id=? ORDER BY id", versionId);
        var sheets = db.queryForList("SELECT * FROM grading_sheet WHERE team_id=? AND rubric_id=?", teamId, rubricId);
        Map<String,Object> sheet = sheets.isEmpty() ? null : clean(sheets.getFirst());
        List<Map<String,Object>> scores = sheet==null ? List.of() : rows("SELECT * FROM grading_criterion_score WHERE sheet_id=? ORDER BY id", n(sheet, "id"));
        var out = new LinkedHashMap<String,Object>();
        out.put("team", clean(c.team));
        out.put("project", clean(c.project));
        out.put("rubric", clean(c.rubric));
        out.put("criteria", c.criteria);
        out.put("binding", clean(c.binding));
        out.put("version", clean(version));
        out.put("materials", materials);
        out.put("sheet", sheet);
        out.put("scores", scores);
        return out;
    }

    @Transactional
    public Map<String,Object> save(long user, long teamId, SaveInput in, boolean submit){
        var c = ctx(user, teamId, true);
        long rubricId = n(c.rubric, "id");
        check(in.rubricId()!=null && in.rubricId().equals(rubricId), "RUBRIC_MISMATCH", "评分标准版本已变化，请刷新后重试");
        var scores = validateScores(c.criteria, in.scores(), submit);
        var existing = db.queryForList("SELECT * FROM grading_sheet WHERE team_id=? AND rubric_id=?", teamId, rubricId);
        if(!existing.isEmpty() && "SUBMITTED".equals(existing.getFirst().get("status")))
            conflict("SHEET_SUBMITTED", "评分已正式提交，如需修改请使用“修改评分”并填写原因");
        long sheetId;
        Map<String,Object> before;
        if(existing.isEmpty()){
            before = Map.of("status", "NEW");
            sheetId = insert("INSERT INTO grading_sheet(rubric_id,team_id,review_binding_id,status,graded_by,submitted_at) VALUES(?,?,?,?,?,?)",
                rubricId, teamId, n(c.binding, "id"), submit ? "SUBMITTED" : "DRAFT", user, submit ? now() : null);
        } else {
            var sheet = existing.getFirst();
            sheetId = n(sheet, "id");
            before = clean(sheet);
            db.update("UPDATE grading_sheet SET status=?,graded_by=?,submitted_at=?,last_reason=? WHERE id=?",
                submit ? "SUBMITTED" : "DRAFT", user, submit ? now() : null, in.reason(), sheetId);
            db.update("DELETE FROM grading_criterion_score WHERE sheet_id=?", sheetId);
        }
        for(var e : scores.entrySet())
            db.update("INSERT INTO grading_criterion_score(sheet_id,criterion_id,score) VALUES(?,?,?)", sheetId, e.getKey(), e.getValue());
        var after = sheetSnapshot(sheetId);
        event(c, submit ? "SCORE_SUBMITTED" : "SCORE_DRAFTED", "sheet:"+sheetId, in.reason()==null ? "" : in.reason(), before, after);
        return sheetResult(c, sheetId);
    }

    @Transactional
    public Map<String,Object> modify(long user, long sheetId, SaveInput in){
        check(in.reason()!=null && !in.reason().isBlank(), "REASON_REQUIRED", "修改已提交评分必须填写原因");
        var sheet = one("SELECT * FROM grading_sheet WHERE id=?", "SHEET_NOT_FOUND", sheetId);
        check("SUBMITTED".equals(sheet.get("status")), "NOT_SUBMITTED", "仅已正式提交的评分可走修改流程");
        long teamId = n(sheet, "team_id");
        var c = ctx(user, teamId, true);
        check(n(sheet, "rubric_id")==n(c.rubric, "id"), "RUBRIC_MISMATCH", "评分标准版本已变化，请刷新后重试");
        var scores = validateScores(c.criteria, in.scores(), true);
        var before = sheetSnapshot(sheetId);
        db.update("UPDATE grading_sheet SET last_reason=? WHERE id=?", in.reason().strip(), sheetId);
        db.update("DELETE FROM grading_criterion_score WHERE sheet_id=?", sheetId);
        for(var e : scores.entrySet())
            db.update("INSERT INTO grading_criterion_score(sheet_id,criterion_id,score) VALUES(?,?,?)", sheetId, e.getKey(), e.getValue());
        var after = sheetSnapshot(sheetId);
        event(c, "SCORE_MODIFIED", "sheet:"+sheetId, in.reason().strip(), before, after);
        return sheetResult(c, sheetId);
    }

    private Map<Long,Integer> validateScores(List<Map<String,Object>> criteria, List<ScoreInput> input, boolean complete){
        var map = new LinkedHashMap<Long,Integer>();
        if(input!=null){
            for(var s : input){
                check(s.criterionId()!=null && s.score()!=null, "INVALID_SCORE", "评分项不能为空");
                if(map.containsKey(s.criterionId())) conflict("DUPLICATE_SCORE", "同一维度不能重复评分");
                map.put(s.criterionId(), s.score());
            }
        }
        var ids = new HashSet<Long>();
        for(var c : criteria){
            long id = n(c, "id");
            ids.add(id);
            Integer sc = map.get(id);
            if(sc==null){
                if(complete) check(false, "INCOMPLETE_SCORE", "请完成维度「"+c.get("name")+"」的评分后再提交");
                continue;
            }
            int max = ni(c, "max_score");
            check(sc>=0 && sc<=max, "SCORE_OUT_OF_RANGE", "维度「"+c.get("name")+"」得分须在 0~"+max+" 之间");
        }
        for(var id : map.keySet()) check(ids.contains(id), "CRITERION_MISMATCH", "存在不属于当前评分标准的维度");
        return map;
    }

    private Map<String,Object> sheetSnapshot(long sheetId){
        var s = clean(one("SELECT * FROM grading_sheet WHERE id=?", "SHEET_NOT_FOUND", sheetId));
        var scores = rows("SELECT criterion_id, score FROM grading_criterion_score WHERE sheet_id=? ORDER BY id", sheetId);
        return Map.of("sheet", s, "scores", scores);
    }
    private Map<String,Object> sheetResult(Ctx c, long sheetId){
        var out = new LinkedHashMap<String,Object>();
        out.put("sheet", clean(one("SELECT * FROM grading_sheet WHERE id=?", "SHEET_NOT_FOUND", sheetId)));
        out.put("scores", rows("SELECT * FROM grading_criterion_score WHERE sheet_id=? ORDER BY id", sheetId));
        out.put("rubric", clean(c.rubric));
        out.put("criteria", c.criteria);
        return out;
    }
    private void event(Ctx c, String action, String objectId, String reason, Map<String,Object> before, Map<String,Object> after){
        check(reason==null || reason.length()<=2000, "INVALID_REASON", "操作原因不得超过2000字");
        try{
            db.update("INSERT INTO grading_event(course_id,team_id,actor_id,action,object_id,reason,before_data,after_data,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
                c.courseId, n(c.team, "id"), c.userId, action, objectId, reason==null ? "" : reason, json.writeValueAsString(before), json.writeValueAsString(after), now());
        } catch(JsonProcessingException e){ throw new IllegalStateException("无法保存评分记录", e); }
        audit.append(c.userId, c.courseId, action, objectId, "评分："+action+"，团队#"+n(c.team, "id"));
    }

    private Map<String,Object> one(String sql, String code, Object... args){
        var rows = db.queryForList(sql, args);
        if(rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND, code, "对象不存在或不在授权范围内");
        return rows.getFirst();
    }
    private long insert(String sql, Object... args){
        var key = new GeneratedKeyHolder();
        db.update(con -> { var ps = con.prepareStatement(sql, new String[]{"id"}); for(int i=0;i<args.length;i++) ps.setObject(i+1, args[i]); return ps; }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private List<Map<String,Object>> rows(String sql, Object... args){
        return db.queryForList(sql, args).stream().map(GradingService::clean).toList();
    }
    private static Map<String,Object> clean(Map<String,Object> data){
        var out = new LinkedHashMap<String,Object>();
        data.forEach((k,v) -> out.put(k, v instanceof java.sql.Date d ? d.toLocalDate().toString() : v instanceof OffsetDateTime t ? t.toString() : v));
        return out;
    }
}
