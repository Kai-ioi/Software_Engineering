package cn.edu.school.project;

import cn.edu.school.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import java.time.OffsetDateTime;
import java.util.*;

/** Published project criteria are the source; grading rubrics are immutable versioned snapshots. */
@Service
public class ProjectScoringService {
 private final JdbcTemplate db;
 public ProjectScoringService(JdbcTemplate db){this.db=db;}
 @Transactional public void sync(long project,long teacher){
  var p=db.queryForMap("SELECT * FROM course_project WHERE id=?",project);
  var cs=db.queryForList("SELECT * FROM project_criterion WHERE project_id=? ORDER BY id",project).stream().filter(c->((Number)c.get("weight")).intValue()>0).toList();
  if(cs.isEmpty())return;
  var old=db.queryForList("SELECT * FROM grading_rubric WHERE project_id=? AND active=TRUE ORDER BY version_no DESC LIMIT 1",project);
  if(!old.isEmpty()){
   var rubric=old.getFirst();var prior=db.queryForList("SELECT * FROM grading_criterion WHERE rubric_id=? ORDER BY sort_order,id",rubric.get("id"));
   boolean same=prior.size()==cs.size()&&Objects.equals(p.get("teacher_weight"),rubric.get("teacher_weight"))&&Objects.equals(p.get("peer_weight"),rubric.get("peer_weight"));
   for(int i=0;same&&i<cs.size();i++)same=Objects.equals(cs.get(i).get("name"),prior.get(i).get("name"))&&Objects.equals(cs.get(i).get("weight"),prior.get(i).get("max_score"))&&Objects.equals(Objects.toString(cs.get(i).get("description"),""),prior.get(i).get("description"));
   if(same)return;
  }
  if(db.queryForObject("SELECT COUNT(*) FROM review_round WHERE project_id=?",Long.class,project)>0 || db.queryForObject("SELECT COUNT(*) FROM grading_sheet s JOIN project_team t ON t.id=s.team_id WHERE t.project_id=?",Long.class,project)>0)
   throw new BusinessException(HttpStatus.CONFLICT,"RUBRIC_FROZEN","正式互评或评分已开始，评分维度和成绩权重已冻结");
  db.queryForMap("SELECT id FROM course WHERE id=? FOR UPDATE",p.get("course_id"));
  int version=db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM grading_rubric WHERE course_id=?",Integer.class,p.get("course_id"));
  db.update("UPDATE grading_rubric SET active=FALSE WHERE project_id=?",project);
  long id=insert("INSERT INTO grading_rubric(course_id,project_id,name,version_no,active,created_by,created_at,teacher_weight,peer_weight) VALUES(?,?,?,?,TRUE,?,?,?,?)",p.get("course_id"),project,p.get("name").toString().substring(0,Math.min(150,p.get("name").toString().length()))+" · 评分标准",version,teacher,OffsetDateTime.now(),p.get("teacher_weight"),p.get("peer_weight"));
  int order=0;for(var c:cs)if(((Number)c.get("weight")).intValue()>0)insert("INSERT INTO grading_criterion(rubric_id,name,description,max_score,sort_order) VALUES(?,?,?,?,?)",id,c.get("name"),Objects.toString(c.get("description"),""),c.get("weight"),++order);
 }
 @Order(500) @EventListener(ApplicationReadyEvent.class) @Transactional public void backfill(){
  for(var p:db.queryForList("SELECT p.id,MIN(c.user_id) AS teacher FROM course_project p JOIN course_membership c ON c.course_id=p.course_id AND c.role='TEACHER' AND c.active=TRUE WHERE EXISTS(SELECT 1 FROM project_criterion k WHERE k.project_id=p.id) AND NOT EXISTS(SELECT 1 FROM grading_rubric r WHERE r.project_id=p.id) GROUP BY p.id"))sync(((Number)p.get("id")).longValue(),((Number)p.get("teacher")).longValue());
 }
 private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();}
}
