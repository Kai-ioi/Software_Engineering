package cn.edu.school;

import cn.edu.school.auth.UserRepository;
import cn.edu.school.project.*;
import cn.edu.school.team.*;
import cn.edu.school.collaboration.CollaborationService;
import cn.edu.school.submission.SubmissionService;
import cn.edu.school.review.ReviewService;
import cn.edu.school.grade.GradingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:shared_workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.submission.jobs-enabled=false"})
@ActiveProfiles("demo") @Transactional
class SharedWorkflowIntegrationTest {
 @Autowired ProjectService projects;@Autowired TeamService teams;@Autowired CollaborationService tasks;
 @Autowired SubmissionService submissions;@Autowired ReviewService reviews;@Autowired GradingService grades;
 @Autowired cn.edu.school.workflow.WorkflowNotices notices;
 @Autowired cn.edu.school.workflow.WorkflowHistory history;
 @Autowired cn.edu.school.workflow.CourseRetention retention;
 @Autowired jakarta.persistence.EntityManager em;
 @Autowired UserRepository users;@Autowired JdbcTemplate db;
 @MockitoBean Clock clock;
 Instant instant;
 long u(String name){return users.findByUsername(name).orElseThrow().getId();}
 long n(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
 long revision(long team){return db.queryForObject("SELECT revision FROM project_team WHERE id=?",Long.class,team);}
 @BeforeEach void time(){instant=Instant.now();when(clock.instant()).thenAnswer(i->instant);when(clock.getZone()).thenReturn(ZoneOffset.UTC);}
 long course(){db.update("INSERT INTO course(name,semester,description,status) VALUES('跨模块独立课程','2026','验收','ACTIVE')");long id=db.queryForObject("SELECT MAX(id) FROM course",Long.class);for(String s:List.of("teacher01","student01","student03","student04"))db.update("INSERT INTO course_membership(course_id,user_id,role,active) VALUES(?,?,?,TRUE)",id,u(s),s.equals("teacher01")?"TEACHER":"STUDENT");return id;}
 ProjectService.ProjectInput input(OffsetDateTime deadline){return new ProjectService.ProjectInput("完整业务项目","按统一要求交付",deadline,1,4,List.of(new ProjectService.ProjectInput.CriterionInput("工程质量",100,"评分依据")),70,30);}
 long deliver(long leader,long team,OffsetDateTime deadline){
  long it=tasks.saveIteration(leader,team,null,new CollaborationService.IterationInput(revision(team),"","第一轮","完成交付",1,LocalDate.now(),LocalDate.now().plusDays(1)));
  long requirement=db.queryForObject("SELECT r.id FROM project_requirement r JOIN project_team t ON t.project_id=r.project_id WHERE t.id=? ORDER BY r.id LIMIT 1",Long.class,team);
  long task=tasks.saveTask(leader,team,null,new CollaborationService.TaskInput(revision(team),"",it,requirement,"实现功能","按要求完成",leader,LocalDate.now(),LocalDate.now().plusDays(1)));
  tasks.lifecycle(leader,team,it,false,new CollaborationService.EndInput(revision(team),"","",""));
  tasks.progress(leader,team,task,new CollaborationService.ProgressInput(revision(team),"","IN_PROGRESS","开发中"));
  tasks.progress(leader,team,task,new CollaborationService.ProgressInput(revision(team),"","COMPLETED","验证完成"));
  tasks.lifecycle(leader,team,it,true,new CollaborationService.EndInput(revision(team),"","全部完成",""));
  long material=submissions.link(leader,team,new SubmissionService.LinkInput(revision(team),it,"交付代码","https://example.com/project",true,"v1"));
  long version=n(submissions.submit(leader,team,new SubmissionService.SubmitInput(revision(team),it,List.of(material),"第一版完成",UUID.randomUUID())),"id");
  submissions.select(leader,team,new SubmissionService.SelectionInput(revision(team),version,"主动指定",false,false));return version;
 }
 @Test void projectTeamSubmissionReviewGradeCorrectionAndExportUseSameObjects(){ sharedWorkflow(false); }
 @Test void approvedRetentionPurgesCompleteWorkflowAndKeepsAudit(){ sharedWorkflow(true); }
 private void sharedWorkflow(boolean purge){
  long c=course(),teacher=u("teacher01"),a=u("student01"),b=u("student04"),member=u("student03");var deadline=OffsetDateTime.ofInstant(instant.plus(Duration.ofDays(2)),ZoneOffset.UTC);
  long project=projects.create(teacher,c,input(deadline)).id();projects.publish(teacher,c,project);
  long t1=teams.create(a,c,project,new TeamService.CreateInput("交付一组")).id();long t2=teams.create(b,c,project,new TeamService.CreateInput("交付二组")).id();
  teams.apply(member,c,project,t1);assertThat((List<?>)tasks.workspace(a,t1).get("members")).hasSize(1);
  long mid=db.queryForObject("SELECT id FROM project_team_member WHERE team_id=? AND user_id=?",Long.class,t1,member);teams.approve(a,c,project,t1,mid);assertThat((List<?>)tasks.workspace(a,t1).get("members")).hasSize(2);
  long v1=deliver(a,t1,deadline),v2=deliver(b,t2,deadline);
  var orig=input(deadline);
  projects.update(teacher,c,project,new ProjectService.ProjectInput(orig.title(),"调整交付内容",deadline,1,4,orig.criteria(),70,30,"发现要求遗漏",deadline.plusDays(1)));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM submission_extension WHERE team_id IN (?,?)",Long.class,t1,t2)).isEqualTo(2);
  instant=deadline.toInstant().plusSeconds(1);
  long bind1=submissions.bindReview(teacher,t1,new SubmissionService.SelectionInput(revision(t1),v1,"确认材料可访问",true,false));
  long bind2=submissions.bindReview(teacher,t2,new SubmissionService.SelectionInput(revision(t2),v2,"确认材料可访问",true,false));
  long round=reviews.createRound(teacher,project,new ReviewService.RoundInput("正式互评",deadline.plusDays(1)));
  long k1=reviews.addTask(teacher,round,new ReviewService.TaskInput(b,bind1));long k2=reviews.addTask(teacher,round,new ReviewService.TaskInput(a,bind2));
  long peerCriterion=db.queryForObject("SELECT c.id FROM grading_criterion c JOIN review_round r ON r.rubric_id=c.rubric_id WHERE r.id=?",Long.class,round);
  reviews.start(b,k1);
  assertThat((List<Map<String,Object>>)reviews.workspace(b,project).get("myTasks")).anyMatch(t->t.get("status").equals("IN_REVIEW"));
  reviews.submit(b,k1,new ReviewService.SubmitInput(null,"功能完整",List.of(new ReviewService.DimensionScore(peerCriterion,80))));
  reviews.submit(a,k2,new ReviewService.SubmitInput(null,"工程质量较好",List.of(new ReviewService.DimensionScore(peerCriterion,90))));
  for(long team:List.of(t1,t2)){
   var sheet=grades.teamSheet(teacher,team);long rubric=n((Map<String,Object>)sheet.get("rubric"),"id");var criteria=(List<Map<String,Object>>)sheet.get("criteria");assertThat(criteria).hasSize(1);assertThat(criteria.getFirst().get("name")).isEqualTo("工程质量");
   grades.save(teacher,team,new GradingService.SaveInput(rubric,List.of(new GradingService.ScoreInput(n(criteria.getFirst(),"id"),team==t1?90:80)),""),true);grades.confirmRoster(teacher,team);
  }
  grades.publish(teacher,c,new GradingService.PublishInput("先发布已确认的一组",t1));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM grade_result WHERE team_id=?",Long.class,t2)).isZero();
  grades.publish(teacher,c,new GradingService.PublishInput("核对成绩和名单后发布"));
  var old=db.queryForMap("SELECT * FROM grade_result WHERE team_id=?",t1);assertThat(((Number)old.get("final_total")).doubleValue()).isEqualTo(87);
  var scores=db.queryForMap("SELECT * FROM grading_criterion_score WHERE sheet_id=?",old.get("sheet_id"));
  grades.modify(teacher,n(old,"sheet_id"),new GradingService.SaveInput(n(old,"rubric_id"),List.of(new GradingService.ScoreInput(n(scores,"criterion_id"),70)),"核实后调整教师评分"));
  var changed=grades.correct(teacher,n(old,"id"),new GradingService.CorrectInput("重新计算"));assertThat(db.queryForObject("SELECT final_total FROM grade_result WHERE id=?",Double.class,changed.get("correctedId"))).isEqualTo(73);
  assertThat(db.queryForObject("SELECT final_total FROM grade_result WHERE id=?",Double.class,old.get("id"))).isEqualTo(87);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM grade_member_result WHERE result_id=? AND final_total=73",Long.class,changed.get("correctedId"))).isEqualTo(2);
  assertThat(new String(grades.export(teacher,c,"csv",null).bytes(),java.nio.charset.StandardCharsets.UTF_8)).contains("73.00");
  assertThat(grades.export(teacher,c,"xlsx",null).bytes()).isNotEmpty();assertThat((List<?>)grades.myGrades(member).get("results")).hasSize(1);
  if(purge){
  long admin=u("admin01");
  projects.close(teacher,c,project);
  projects.archive(teacher,c,project);
  retention.end(c,admin);
  instant=instant.plus(Duration.ofDays(740));
  assertThat(retention.preview(c)).containsEntry("versions",2L).containsEntry("grades",3L);
  retention.approve(c,teacher);
  retention.purge(c,admin);
  retention.purge(c,admin);
  assertThat(retention.preview(c)).containsEntry("versions",0L).containsEntry("grades",0L);
  assertThat(db.queryForObject("SELECT status FROM course WHERE id=?",String.class,c)).isEqualTo("PURGED");
  assertThat(retention.audit(c)).extracting(r->r.get("action")).containsExactly("PURGED","APPROVED","ENDED");
  } else {
  assertThatThrownBy(()->grades.publish(teacher,c,new GradingService.PublishInput("重复发布"))).isInstanceOf(cn.edu.school.common.BusinessException.class);
  assertThatThrownBy(()->projects.update(teacher,c,project,new ProjectService.ProjectInput("改标准","要求",deadline,1,4,List.of(new ProjectService.ProjectInput.CriterionInput("新维度",100,""))))).isInstanceOf(cn.edu.school.common.BusinessException.class);
  }
 }
 @Test void projectNoticesAndHistoryStayWithinCourseAndTeam(){
  long c=course(),teacher=u("teacher01"),a=u("student01"),b=u("student04"),outsider=u("student02");
  var deadline=OffsetDateTime.now().plusHours(12);
  long project=projects.create(teacher,c,input(deadline)).id();
  projects.publish(teacher,c,project);
  long team=teams.create(a,c,project,new TeamService.CreateInput("过程记录组")).id();
  var original=input(deadline);
  projects.update(teacher,c,project,new ProjectService.ProjectInput(original.title(),"修订交付说明",deadline,1,4,original.criteria(),70,30));
  notices.dispatch();notices.dispatch();notices.deadlines();notices.deadlines();
  var rows=notices.list(a);
  var projectRows=rows.stream().filter(row->row.get("href").equals("/courses/"+c+"/projects/"+project)).toList();
  assertThat(projectRows).hasSize(2);
  var first=projectRows.getFirst();
  notices.read(b,"workflow",n(first,"id"));
  assertThat(db.queryForObject("SELECT read_at FROM workflow_notice WHERE id=?",OffsetDateTime.class,n(first,"id"))).isNull();
  notices.read(a,"workflow",n(first,"id"));
  assertThat(db.queryForObject("SELECT read_at FROM workflow_notice WHERE id=?",OffsetDateTime.class,n(first,"id"))).isNotNull();
  assertThat(notices.list(outsider)).noneMatch(row->row.get("href").equals("/courses/"+c+"/projects/"+project));
  assertThat(history.list(a,team,null)).anyMatch(row->row.get("action").equals("PROJECT_REQUIREMENTS_CHANGED"));
  assertThat(history.list(a,team,"协作")).isEmpty();
  assertThatThrownBy(()->history.list(b,team,null)).isInstanceOf(cn.edu.school.common.BusinessException.class);
 }

 @Test void reopensWithNewApplicationAndKeepsExpiryHistory(){
  long c=course(),teacher=u("teacher01"),a=u("student01"),member=u("student03");
  var now=OffsetDateTime.now();
  long p=projects.create(teacher,c,input(now.plusDays(2))).id();projects.publish(teacher,c,p);
  long team=teams.create(a,c,p,new TeamService.CreateInput("窗口验证")).id();
  var first=teams.apply(member,c,p,team).applications();
  assertThat(first).hasSize(1);
  teams.configureWindow(teacher,c,p,new TeamService.GroupingWindow(now.minusHours(2),now.minusHours(1),false));
  assertThat(db.queryForObject("SELECT status FROM project_team_member WHERE id=?",String.class,first.getFirst().id())).isEqualTo("EXPIRED");
  teams.configureWindow(teacher,c,p,new TeamService.GroupingWindow(now.minusHours(1),now.plusHours(1),true));
  em.flush();em.clear();
  teams.apply(member,c,p,team);
  assertThat(history.list(a,team,"组队申请")).hasSize(1);
 }

 @Test void cursorVisitsMoreThan300RecordsWithIdenticalTimestamps(){
  long c=course(),teacher=u("teacher01"),a=u("student01");
  long p=projects.create(teacher,c,input(OffsetDateTime.now().plusDays(2))).id();projects.publish(teacher,c,p);
  long team=teams.create(a,c,p,new TeamService.CreateInput("分页验证")).id();
  db.update("""
      INSERT INTO audit_record(course_id,actor_id,action,object_id,summary,occurred_at)
      SELECT ?,?,'TEAM_APPROVED',?,'分页样本',? FROM SYSTEM_RANGE(1,321)
      """,c,a,Long.toString(team),OffsetDateTime.parse("2025-01-01T00:00:00Z"));
  var ids=new HashSet<Long>();
  List<Map<String,Object>> page=history.page(a,team,"成员",null,null,null,null,50);
  while(!page.isEmpty()){
   for(var row:page)assertThat(ids.add(n(row,"event_id"))).isTrue();
   var last=page.getLast();
   page=history.page(a,team,"成员",null,(OffsetDateTime)last.get("occurred_at"),last.get("category").toString(),n(last,"event_id"),50);
  }
  assertThat(ids).hasSize(322);
 }

 @Test void leadershipChangesAndMembershipRemovalReachCollaborationImmediately(){
  long c=course(),a=u("student01"),m=u("student03"),p=projects.create(u("teacher01"),c,input(OffsetDateTime.now().plusDays(2))).id();projects.publish(u("teacher01"),c,p);long t=teams.create(a,c,p,new TeamService.CreateInput("权限联调")).id();teams.apply(m,c,p,t);
  long mid=db.queryForObject("SELECT id FROM project_team_member WHERE team_id=? AND user_id=?",Long.class,t,m);teams.approve(a,c,p,t,mid);teams.transferLeadership(a,c,p,t,new TeamService.LeadershipInput(m));
  assertThat(tasks.workspace(m,t).get("isLeader")).isEqualTo(true);assertThat(tasks.workspace(a,t).get("isLeader")).isEqualTo(false);var exit=teams.leave(a,c,p,t);
  assertThat(tasks.workspace(a,t).get("isLeader")).isEqualTo(false);
  teams.decideExit(m,c,p,t,exit.members().stream().filter(member->member.userId().equals(a)).findFirst().orElseThrow().id(),true);
  assertThatThrownBy(()->tasks.workspace(a,t)).isInstanceOf(cn.edu.school.common.BusinessException.class);
 }
}
