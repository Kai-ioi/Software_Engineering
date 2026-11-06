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
    private Map<String,Object> rubricForProject(long course,long project){
        var rows=db.queryForList("SELECT * FROM grading_rubric WHERE project_id=? AND active=TRUE ORDER BY version_no DESC LIMIT 1",project);
        if(!rows.isEmpty())return rows.getFirst();
        // Only historical demo projects use the old course rubric. Live projects must publish their own criteria.
        if(Boolean.TRUE.equals(gateway.project(project).get("demo_fixture"))) {
            var legacy=db.queryForList("SELECT * FROM grading_rubric WHERE course_id=? AND project_id IS NULL AND active=TRUE ORDER BY version_no DESC LIMIT 1",course);
            if(!legacy.isEmpty())return legacy.getFirst();
        }
        conflict("NO_RUBRIC","项目尚未发布完整评分标准，请到项目管理配置");return Map.of();
    }
    private List<Map<String,Object>> eligibleTeams(long course){return db.queryForList("SELECT t.*,p.name AS project_name,p.demo_fixture FROM project_team t JOIN course_project p ON p.id=t.project_id WHERE p.course_id=? AND p.status IN ('PUBLISHED','IN_PROGRESS','ENDED') ORDER BY t.id",course);}
    private void peerComplete(long binding){
        check(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE binding_id=? AND status='PENDING'",Long.class,binding)==0,"PEER_PENDING","仍有互评或重评任务未完成，不能发布或更正成绩");
        check(db.queryForObject("SELECT COUNT(*) FROM review_appeal WHERE binding_id=? AND status='PENDING'",Long.class,binding)==0,"APPEAL_PENDING","互评复核尚未处理，不能发布或更正成绩");
        check(db.queryForObject("SELECT COUNT(*) FROM review_submission WHERE binding_id=? AND superseded_by IS NULL AND task_id IN (SELECT id FROM review_task WHERE status='SUBMITTED')",Long.class,binding)>0,"MISSING_PEER_SCORE","互评分数缺失");
    }
    private Map<String,Object> currentSheet(Ctx c){
        var sheet=one("SELECT * FROM grading_sheet WHERE team_id=? AND rubric_id=? AND status='SUBMITTED'","MISSING_TEACHER_SCORE",n(c.team,"id"),n(c.rubric,"id"));
        check(n(sheet,"review_binding_id")==n(c.binding,"id"),"SCORE_BINDING_STALE","被评版本已变更，请按新版本重新评分");return sheet;
    }
    private double teacherTotal(Ctx c,long sheet){
        var values=db.queryForList("SELECT criterion_id,score FROM grading_criterion_score WHERE sheet_id=?",sheet);
        validateScores(c.criteria,values.stream().map(v->new ScoreInput(n(v,"criterion_id"),ni(v,"score"))).toList(),true);
        return values.stream().mapToInt(v->ni(v,"score")).sum();
    }
    private double peerTotal(long binding){peerComplete(binding);return db.queryForList("SELECT score FROM review_submission WHERE binding_id=? AND superseded_by IS NULL AND task_id IN (SELECT id FROM review_task WHERE status='SUBMITTED')",binding).stream().mapToInt(v->ni(v,"score")).average().orElseThrow();}
    private int teacherWeight(long projectId,Map<String,Object> rubric) {
        return db.queryForObject("SELECT COUNT(*) FROM project_team WHERE project_id=?",Long.class,projectId)==1
            ? 100 : ni(rubric,"teacher_weight");
    }
    private double weighted(double teacher,double peer,int tw,int pw){check(tw>=0&&pw>=0&&tw+pw==100,"INVALID_WEIGHTS","评分权重无效");return Math.round((teacher*tw+peer*pw))/100.0;}
    private void snapshot(long result,Long sheet,long binding,boolean peerUsed){try{db.update("UPDATE grade_result SET score_snapshot=?,peer_snapshot=?,review_binding_id=? WHERE id=?",
        json.writeValueAsString(sheet==null?Map.of():sheetSnapshot(sheet)),
        json.writeValueAsString(peerUsed?rows("SELECT * FROM review_submission WHERE binding_id=? AND superseded_by IS NULL AND task_id IN (SELECT id FROM review_task WHERE status='SUBMITTED') ORDER BY id",binding):List.of()),binding,result);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
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
        if (lock && !Set.of("PUBLISHED","IN_PROGRESS","ENDED").contains(project.get("status")))
            conflict("PROJECT_READ_ONLY", "当前项目状态不允许修改评分或成绩");
        var rubric = rubricForProject(courseId,n(project,"id"));
        return new Ctx(user, courseId, team, project, rubric, criteriaOf(n(rubric, "id")), bindingOf(teamId));
    }

    public record ScoreInput(Long criterionId, Integer score){}
    public record SaveInput(Long rubricId, List<ScoreInput> scores, String reason){}

    // ────────── Module 8 ──────────

    /** Teacher: aggregate summary for every team with a confirmed binding.
     *  Returns teacher/peer/final scores plus per-team exceptions. */
    public Map<String,Object> summary(long user,long courseId){
        permission.requireTeacher(user,courseId);var out=new LinkedHashMap<String,Object>();var result=new ArrayList<Map<String,Object>>();
        for(var team:eligibleTeams(courseId)){
            long tid=n(team,"id");var tr=new LinkedHashMap<String,Object>();tr.put("team_id",tid);tr.put("team_name",team.get("name"));tr.put("project_id",team.get("project_id"));tr.put("project_name",team.get("project_name"));tr.put("demo_fixture",team.get("demo_fixture"));
            var errors=new ArrayList<String>();Map<String,Object> rubric;
            try{rubric=rubricForProject(courseId,n(team,"project_id"));}catch(BusinessException e){rubric=Map.of("id",-1L,"teacher_weight",70,"peer_weight",30);errors.add("项目未配置评分标准");}
            long rid=n(rubric,"id");int tw=teacherWeight(n(team,"project_id"),rubric),pw=100-tw;
            var d=db.queryForList("SELECT * FROM team_delivery WHERE team_id=?",tid);Object binding=d.isEmpty()?null:d.getFirst().get("review_binding_id");
            var ss=db.queryForList("SELECT * FROM grading_sheet WHERE team_id=? AND rubric_id=? AND status='SUBMITTED'",tid,rid);var roster=db.queryForList("SELECT * FROM grade_roster WHERE team_id=? AND rubric_id=?",tid,rid);
            Double tt=null,pt=null;boolean pending=pw>0;
            if(binding==null)errors.add("缺被评版本，请先到成果与版本确认");
            else if(pw==0)pt=0.0;
            else try{pt=peerTotal(((Number)binding).longValue());pending=false;}catch(BusinessException e){errors.add(e.getMessage());}
            if(tw==0)tt=0.0;
            else if(ss.isEmpty())errors.add("教师评分尚未正式提交");else{
                tt=db.queryForList("SELECT score FROM grading_criterion_score WHERE sheet_id=?",ss.getFirst().get("id")).stream().mapToDouble(x->ni(x,"score")).sum();
                if(!Objects.equals(ss.getFirst().get("review_binding_id"),binding))errors.add("教师评分对应旧被评版本，须重新评分");
            }
            if(roster.isEmpty())errors.add("计分名单尚未核对");
            tr.put("binding_id",binding);tr.put("sheet_id",ss.isEmpty()?null:ss.getFirst().get("id"));tr.put("roster_id",roster.isEmpty()?null:roster.getFirst().get("id"));tr.put("member_ids",roster.isEmpty()?null:roster.getFirst().get("member_ids"));
            tr.put("teacher_total",tt);tr.put("peer_avg",pt);tr.put("peer_missing",pending);tr.put("teacher_weight",tw);tr.put("peer_weight",pw);tr.put("final_total",tt!=null&&pt!=null?weighted(tt,pt,tw,pw):null);tr.put("exceptions",errors);
            var pub=db.queryForList("SELECT * FROM grade_result WHERE team_id=? ORDER BY version_no DESC LIMIT 1",tid);
            tr.put("published",pub.isEmpty()?null:clean(pub.getFirst()));tr.put("member_results",pub.isEmpty()?List.of():rows("SELECT gm.*,u.display_name FROM grade_member_result gm JOIN app_user u ON u.id=gm.user_id WHERE gm.result_id=? ORDER BY gm.id",pub.getFirst().get("id")));
            result.add(tr);
        }
        out.put("teams",result);out.put("serverTime",now().toString());out.put("rubric",Map.of("teacher_weight",70,"peer_weight",30));out.put("criteria",List.of());return out;
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
        var old=db.queryForList("SELECT * FROM grade_roster WHERE team_id=?",teamId);
        if(old.isEmpty())db.update("INSERT INTO grade_roster(team_id,rubric_id,member_ids,confirmed_by,confirmed_at) VALUES(?,?,?,?,?)",teamId,rubricId,idsText.toString(),user,now());
        else {
            if(db.queryForObject("SELECT COUNT(*) FROM grade_result WHERE team_id=?",Long.class,teamId)>0)
                check(old.getFirst().get("member_ids").equals(idsText.toString())&&n(old.getFirst(),"rubric_id")==rubricId,"ROSTER_FROZEN","成绩已发布，原计分名单须保留");
            else db.update("UPDATE grade_roster SET rubric_id=?,member_ids=?,confirmed_by=?,confirmed_at=? WHERE team_id=?",rubricId,idsText.toString(),user,now(),teamId);
        }
        event(c, "ROSTER_CONFIRMED", "roster:"+teamId, "核对计分名单("+members.size()+"人)", Map.of(), Map.of("memberCount", (Object)members.size()));
        return Map.of("teamId", teamId, "memberCount", members.size(), "members", members.stream().map(GradingService::clean).toList());
    }

    public record PublishInput(String reason,Long teamId,Boolean includeSamples,Long projectId){
        public PublishInput(String reason,Long teamId,Boolean includeSamples){this(reason,teamId,includeSamples,null);}
        public PublishInput(String reason){this(reason,null,null);}
        public PublishInput(String reason,Long teamId){this(reason,teamId,null);}
    }

    @Transactional public Map<String,Object> publish(long user,long courseId,PublishInput in){
        permission.requireTeacher(user,courseId);check(in!=null&&in.reason()!=null&&!in.reason().isBlank(),"REASON_REQUIRED","成绩发布须填写说明");
        int count=0;
        var eligible=eligibleTeams(courseId);
        if(in.projectId()!=null){
            check(db.queryForObject("SELECT COUNT(*) FROM course_project WHERE id=? AND course_id=?",Long.class,in.projectId(),courseId)>0,"PROJECT_NOT_FOUND","项目不属于当前课程");
            eligible=eligible.stream().filter(t->n(t,"project_id")==in.projectId()).toList();
        }
        if(in.teamId()!=null){
            eligible=eligible.stream().filter(t->n(t,"id")==in.teamId()).toList();
            check(!eligible.isEmpty(),"TEAM_NOT_ELIGIBLE","团队不在本课程可发布范围");
        }
        if(in.teamId()==null && Boolean.FALSE.equals(in.includeSamples()))
            eligible=eligible.stream().filter(t->!Boolean.TRUE.equals(t.get("demo_fixture"))).toList();
        for(var t:eligible){
            long tid=n(t,"id");var c=ctx(user,tid,true);
            if(db.queryForObject("SELECT COUNT(*) FROM grade_result WHERE team_id=?",Long.class,tid)>0)continue;
            int tw=teacherWeight(n(c.project,"id"),c.rubric),pw=100-tw;
            Long sid=tw==0?null:n(currentSheet(c),"id");
            long rid=n(c.rubric,"id"),binding=n(c.binding,"id");
            var roster=one("SELECT * FROM grade_roster WHERE team_id=? AND rubric_id=?","MISSING_ROSTER",tid,rid);
            String currentMembers=gateway.members(tid,courseId).stream().map(m->n(m,"id")).sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            check(roster.get("member_ids").equals(currentMembers),"ROSTER_STALE","团队成员已变化，请重新核对计分名单");
            double tt=sid==null?0:teacherTotal(c,sid),pt=pw==0?0:peerTotal(binding),total=weighted(tt,pt,tw,pw);
            long id=insert("INSERT INTO grade_result(course_id,team_id,rubric_id,sheet_id,roster_id,teacher_total,peer_total,final_total,teacher_weight,peer_weight,status,version_no,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,'PUBLISHED',1,?,?)",courseId,tid,rid,sid,n(roster,"id"),tt,pt,total,tw,pw,user,now());
            for(String member:roster.get("member_ids").toString().split(","))db.update("INSERT INTO grade_member_result(result_id,user_id,final_total) VALUES(?,?,?)",id,Long.parseLong(member),total);
            snapshot(id,sid,binding,pw>0);event(c,"GRADE_PUBLISHED","result:"+id,in.reason(),Map.of(),Map.of("total",total));count++;
        }
        check(count>0,"NO_TEAMS_TO_PUBLISH","没有待发布的团队；已发布成绩请使用更正流程");return Map.of("publishedTeams",count);
    }

    public record CorrectInput(String reason){}

    @Transactional public Map<String,Object> correct(long user,long resultId,CorrectInput in){
        check(in!=null&&in.reason()!=null&&!in.reason().isBlank(),"REASON_REQUIRED","成绩更正必须填写原因");
        var old=one("SELECT * FROM grade_result WHERE id=?","RESULT_NOT_FOUND",resultId);long tid=n(old,"team_id");var c=ctx(user,tid,true);
        long latest=db.queryForObject("SELECT id FROM grade_result WHERE team_id=? ORDER BY version_no DESC LIMIT 1",Long.class,tid);
        check(latest==resultId,"STALE_GRADE_RESULT","请对当前最新成绩版本发起更正");
        check(n(old,"rubric_id")==n(c.rubric,"id"),"RUBRIC_FROZEN","更正须沿用已发布评分标准");
        int tw=ni(old,"teacher_weight"),pw=ni(old,"peer_weight"),version=ni(old,"version_no")+1;
        Long sid=tw==0?null:n(currentSheet(c),"id");
        double tt=sid==null?0:teacherTotal(c,sid),pt=pw==0?0:peerTotal(n(c.binding,"id")),total=weighted(tt,pt,tw,pw);
        long id=insert("INSERT INTO grade_result(course_id,team_id,rubric_id,sheet_id,roster_id,teacher_total,peer_total,final_total,teacher_weight,peer_weight,status,version_no,parent_id,correction_reason,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,'CORRECTED',?,?,?,?,?)",c.courseId,tid,n(c.rubric,"id"),sid,n(old,"roster_id"),tt,pt,total,tw,pw,version,resultId,in.reason().strip(),user,now());
        for(var m:db.queryForList("SELECT user_id FROM grade_member_result WHERE result_id=?",resultId))db.update("INSERT INTO grade_member_result(result_id,user_id,final_total) VALUES(?,?,?)",id,n(m,"user_id"),total);
        snapshot(id,sid,n(c.binding,"id"),pw>0);event(c,"GRADE_CORRECTED","result:"+id,in.reason(),clean(old),Map.of("total",total,"parent",resultId));return Map.of("correctedId",id,"versionNo",version,"reason",in.reason());
    }

    /** Student: query my own grade results across courses (latest version per team). */
    public Map<String,Object> myGrades(long user){
        var out = new LinkedHashMap<String,Object>();
        out.put("userId", user);
        var results = rows("""
            SELECT gr.id, gr.course_id, gr.team_id, gr.teacher_total, gr.peer_total, gr.final_total,
                   gr.teacher_weight, gr.peer_weight, gr.status, gr.version_no, gr.correction_reason, gr.published_at,
                   t.name AS team_name, p.id AS project_id, p.name AS project_name, c.name AS course_name,
                   b.version_id AS evaluated_version_id, v.version_no AS evaluated_version_no
            FROM grade_member_result gm
            JOIN grade_result gr ON gr.id=gm.result_id
            JOIN project_team t ON t.id=gr.team_id
            JOIN course_project p ON p.id=t.project_id
            JOIN course c ON c.id=gr.course_id
            JOIN course_membership cm ON cm.course_id=gr.course_id AND cm.user_id=gm.user_id AND cm.active=TRUE AND cm.role='STUDENT'
            LEFT JOIN submission_review_binding b ON b.id=gr.review_binding_id
            LEFT JOIN submission_version v ON v.id=b.version_id
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

    public Map<String,Object> evidence(long user,long id) {
        var result=one("""
            SELECT g.*,v.id AS evaluated_version_id,v.version_no AS evaluated_version_no
            FROM grade_result g LEFT JOIN submission_review_binding b ON b.id=g.review_binding_id
            LEFT JOIN submission_version v ON v.id=b.version_id WHERE g.id=?
            ""","RESULT_NOT_FOUND",id);
        permission.requireTeacher(user,n(result,"course_id"));
        return clean(result);
    }

    /** Export: CSV or XLSX. */
    public Export export(long user, long courseId, String format, String filenamePrefix){
        permission.requireTeacher(user, courseId);
        var rows = rows("""
            SELECT t.name AS team_name, p.name AS project_name,
                   gr.teacher_total, gr.peer_total, gr.final_total,
                   gr.teacher_weight, gr.peer_weight, gr.status, gr.version_no, gr.correction_reason,
                   gr.published_at, u.display_name AS published_by_name
            FROM grade_result gr
            JOIN project_team t ON t.id=gr.team_id
            JOIN course_project p ON p.id=t.project_id
            JOIN app_user u ON u.id=gr.published_by
            WHERE gr.course_id=? AND NOT EXISTS(SELECT 1 FROM grade_result newer WHERE newer.team_id=gr.team_id AND newer.version_no>gr.version_no)
            ORDER BY t.id, gr.version_no DESC
            """, courseId);
        var members = rows("""
            SELECT gm.*, t.name AS team_name, au.display_name
            FROM grade_member_result gm
            JOIN grade_result gr ON gr.id=gm.result_id
            JOIN project_team t ON t.id=gr.team_id
            JOIN app_user au ON au.id=gm.user_id
            WHERE gr.course_id=? AND NOT EXISTS(SELECT 1 FROM grade_result newer WHERE newer.team_id=gr.team_id AND newer.version_no>gr.version_no)
            ORDER BY t.id, gm.id
            """, courseId);
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
    public Map<String,Object> overview(long user,long courseId){
        var role=permission.requireMember(user,courseId);var out=new LinkedHashMap<String,Object>();var ts=new ArrayList<Map<String,Object>>();
        if(role==CourseRole.TEACHER)for(var t:eligibleTeams(courseId)){
            var binds=db.queryForList("SELECT b.* FROM team_delivery d JOIN submission_review_binding b ON b.id=d.review_binding_id WHERE d.team_id=?",t.get("id"));if(binds.isEmpty())continue;
            var row=new LinkedHashMap<String,Object>(t);row.put("project_name",t.get("project_name"));var b=binds.getFirst();row.put("binding_id",b.get("id"));row.put("version_id",b.get("version_id"));row.put("confirmed_at",b.get("confirmed_at"));
            try{var r=rubricForProject(courseId,n(t,"project_id"));var sheets=db.queryForList("SELECT * FROM grading_sheet WHERE team_id=? AND rubric_id=?",t.get("id"),r.get("id"));if(!sheets.isEmpty()){row.put("sheet_id",sheets.getFirst().get("id"));row.put("sheet_status",sheets.getFirst().get("status"));row.put("last_reason",sheets.getFirst().get("last_reason"));}}catch(BusinessException e){continue;}ts.add(row);
        }
        out.put("role",role.name());out.put("teams",ts);out.put("rubric",Map.of("id",0,"name","按项目评分标准","teacher_weight",70,"peer_weight",30));out.put("criteria",List.of());return out;
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
            db.update("UPDATE grading_sheet SET status=?,graded_by=?,submitted_at=?,last_reason=?,review_binding_id=? WHERE id=?",
                submit ? "SUBMITTED" : "DRAFT", user, submit ? now() : null, in.reason(), n(c.binding,"id"), sheetId);
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
        db.update("UPDATE grading_sheet SET last_reason=?,review_binding_id=?,graded_by=?,submitted_at=? WHERE id=?", in.reason().strip(),n(c.binding,"id"),user,now(), sheetId);
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
