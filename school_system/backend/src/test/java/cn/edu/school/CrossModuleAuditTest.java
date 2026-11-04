package cn.edu.school;

import cn.edu.school.auth.UserRepository;
import cn.edu.school.project.ProjectService;
import cn.edu.school.team.TeamService;
import cn.edu.school.collaboration.CollaborationService;
import cn.edu.school.review.ReviewService;
import cn.edu.school.grade.GradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:cross_module_audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.submission.jobs-enabled=false"})
@ActiveProfiles("demo") @Transactional
class CrossModuleAuditTest {
 @Autowired ProjectService projects; @Autowired TeamService teams;
 @Autowired CollaborationService collaboration; @Autowired UserRepository users;
 @Autowired ReviewService reviews; @Autowired GradingService grades; @Autowired JdbcTemplate db;
 long user(String name){return users.findByUsername(name).orElseThrow().getId();}
 long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 long newProject(){
  var p=projects.create(user("teacher01"),1L,new ProjectService.ProjectInput("跨模块验收项目","检查真实业务贯通",OffsetDateTime.now().plusDays(10),1,4,List.of(new ProjectService.ProjectInput.CriterionInput("完整性",100,"检查"))));
  projects.publish(user("teacher01"),1L,p.id()); return p.id();
 }
 @Test void createdTeamMustAppearInCollaboration(){
  long pid=newProject(); var t=teams.create(user("student01"),1L,pid,new TeamService.CreateInput("跨模块新团队"));
  assertThat(collaboration.teams(user("student01"),1)).anyMatch(row->"跨模块新团队".equals(row.get("name")));
 }
 @Test void teacherMustNotCreateStudentTeam(){
  long pid=newProject();
  assertThatThrownBy(()->teams.create(user("teacher01"),1L,pid,new TeamService.CreateInput("教师越权建队"))).isInstanceOf(cn.edu.school.common.BusinessException.class);
 }
 @Test void repeatedAutomaticAllocationMustNotAddMoreThanTwoTasksPerStudent(){
  long pid=db.queryForObject("SELECT id FROM course_project WHERE name='软件工程课程设计'",Long.class);
  long round=reviews.createRound(user("teacher01"),pid,new ReviewService.RoundInput("重复分配验收",OffsetDateTime.now().plusDays(1)));
  reviews.autoAssign(user("teacher01"),round);
  long before=db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=?",Long.class,round);
  reviews.autoAssign(user("teacher01"),round);
  long after=db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=?",Long.class,round);
  assertThat(after).isEqualTo(before);
 }
 long preparePublication(boolean addPending){
  long teacher=user("teacher01"); long rubric=db.queryForObject("SELECT id FROM grading_rubric WHERE course_id=1 AND active=TRUE",Long.class);
  var criteria=db.queryForList("SELECT id,max_score FROM grading_criterion WHERE rubric_id=? ORDER BY id",rubric);
  for(var team:db.queryForList("SELECT t.id,t.project_id,d.review_binding_id FROM project_team t JOIN course_project p ON p.id=t.project_id JOIN team_delivery d ON d.team_id=t.id WHERE p.course_id=1")){
   long tid=n(team,"id"), binding=n(team,"review_binding_id");
   grades.save(teacher,tid,new GradingService.SaveInput(rubric,criteria.stream().map(c->new GradingService.ScoreInput(n(c,"id"),((Number)c.get("max_score")).intValue()-1)).toList(),"验收评分"),true);
   grades.confirmRoster(teacher,tid);
   db.update("INSERT INTO review_round(project_id,name,deadline,status,created_by,created_at) VALUES(?,'成绩验收批次',?,'OPEN',?,?)",n(team,"project_id"),OffsetDateTime.now().plusDays(1),teacher,OffsetDateTime.now());
   long round=db.queryForObject("SELECT MAX(id) FROM review_round",Long.class);
   db.update("INSERT INTO review_task(round_id,binding_id,reviewer_id,status,assigned_by,assigned_at) VALUES(?,?,?,'SUBMITTED',?,?)",round,binding,user("student02"),teacher,OffsetDateTime.now());
   long task=db.queryForObject("SELECT MAX(id) FROM review_task",Long.class);
   db.update("INSERT INTO review_submission(task_id,binding_id,reviewer_id,score,comment,submitted_at) VALUES(?,?,?,80,'验收评语',?)",task,binding,user("student02"),OffsetDateTime.now());
   if(addPending)db.update("INSERT INTO review_task(round_id,binding_id,reviewer_id,status,assigned_by,assigned_at) VALUES(?,?,?,'PENDING',?,?)",round,binding,user("student04"),teacher,OffsetDateTime.now());
  }
  return rubric;
 }
 @Test void pendingPeerTasksMustBlockGradePublication(){
  preparePublication(true);
  assertThatThrownBy(()->grades.publish(user("teacher01"),1,new GradingService.PublishInput("部分互评仍待完成"))).isInstanceOf(cn.edu.school.common.BusinessException.class);
 }
 @Test void correctedGradeMustReflectChangedTeacherScores(){
  long rubric=preparePublication(false); grades.publish(user("teacher01"),1,new GradingService.PublishInput("验收发布"));
  var old=db.queryForMap("SELECT * FROM grade_result ORDER BY id LIMIT 1");
  long tid=n(old,"team_id"),sheet=n(old,"sheet_id"),result=n(old,"id");
  var changed=db.queryForList("SELECT criterion_id,score FROM grading_criterion_score WHERE sheet_id=? ORDER BY id",sheet).stream().map(c->new GradingService.ScoreInput(n(c,"criterion_id"),((Number)c.get("score")).intValue()-1)).toList();
  grades.modify(user("teacher01"),sheet,new GradingService.SaveInput(rubric,changed,"核实后调整评分"));
  var correction=grades.correct(user("teacher01"),result,new GradingService.CorrectInput("按调整后的评分更正"));
  double newTotal=db.queryForObject("SELECT final_total FROM grade_result WHERE id=?",Double.class,correction.get("correctedId"));
  assertThat(newTotal).isLessThan(((Number)old.get("final_total")).doubleValue());
 }
}
