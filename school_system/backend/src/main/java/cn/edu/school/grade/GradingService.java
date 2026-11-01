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
