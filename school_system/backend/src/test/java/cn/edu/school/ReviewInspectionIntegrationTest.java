package cn.edu.school;

import cn.edu.school.auth.*;
import cn.edu.school.audit.AuditService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Module 5/6 acceptance: TA completes one check; two students complete their assigned peer reviews. */
@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:reviewinspection;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.storage-root=./target/review-test-files","app.submission.jobs-enabled=false"
})
@AutoConfigureMockMvc @ActiveProfiles("demo")
class ReviewInspectionIntegrationTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired UserRepository users;
 @MockitoBean Clock clock; @MockitoSpyBean AuditService audit;
 final AtomicReference<Instant> instant=new AtomicReference<>();
 long criterion;
 long project,teamA,teamB,teamC,bindingA,bindingB,bindingC,versionA,versionB,versionC;
 long leaderA,memberA,leaderB,leaderC,memberC,teacher,ta;

 @BeforeEach void setup()throws Exception{
  instant.set(Instant.parse("2030-01-01T00:00:00Z"));when(clock.instant()).thenAnswer(inv->instant.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  teacher=users.findByUsername("teacher01").orElseThrow().getId();ta=users.findByUsername("assistant01").orElseThrow().getId();
  leaderA=users.findByUsername("student01").orElseThrow().getId();memberA=users.findByUsername("student03").orElseThrow().getId();
  leaderB=users.findByUsername("student04").orElseThrow().getId();leaderC=users.findByUsername("student05").orElseThrow().getId();memberC=users.findByUsername("student06").orElseThrow().getId();
  OffsetDateTime deadline=instant.get().plusSeconds(864000).atOffset(ZoneOffset.UTC);
  db.update("INSERT INTO course_project(course_id,name,status,final_deadline) VALUES(1,?,'IN_PROGRESS',?)","评审测试"+UUID.randomUUID(),deadline);
  project=db.queryForObject("SELECT MAX(id) FROM course_project",Long.class);
  db.update("INSERT INTO grading_rubric(course_id,project_id,name,version_no,active,created_by,created_at) VALUES(1,?,'互评测试',(SELECT COALESCE(MAX(version_no),0)+1 FROM grading_rubric WHERE course_id=1),TRUE,?,CURRENT_TIMESTAMP)",project,teacher);
  long rubric=db.queryForObject("SELECT id FROM grading_rubric WHERE project_id=?",Long.class,project);
  db.update("INSERT INTO grading_criterion(rubric_id,name,description,max_score,sort_order) VALUES(?,'总评维度','维度说明',100,1)",rubric);
  criterion=db.queryForObject("SELECT id FROM grading_criterion WHERE rubric_id=?",Long.class,rubric);
  teamA=newTeam("评审测试·甲组",leaderA,new long[]{memberA});
  teamB=newTeam("评审测试·乙组",leaderB,new long[]{});
  teamC=newTeam("评审测试·丙组",leaderC,new long[]{memberC});
  bindingA=seedDelivery(teamA,leaderA,0);bindingB=seedDelivery(teamB,leaderB,1);bindingC=seedDelivery(teamC,leaderC,2);
  versionA=db.queryForObject("SELECT version_id FROM submission_review_binding WHERE id=?",Long.class,bindingA);
  versionB=db.queryForObject("SELECT version_id FROM submission_review_binding WHERE id=?",Long.class,bindingB);
  versionC=db.queryForObject("SELECT version_id FROM submission_review_binding WHERE id=?",Long.class,bindingC);
 }
 long newTeam(String name,long leader,long[] members){
  db.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,?,?)",project,name,leader);
  long team=db.queryForObject("SELECT MAX(id) FROM project_team",Long.class);
        db.update("INSERT INTO assistant_team_scope(team_id,user_id) VALUES(?,?)",team,ta);
  db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?)",team,leader);
  for(long m:members)db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?)",team,m);
  return team;
 }
 long seedDelivery(long team,long leader,int idx){
  OffsetDateTime deadline=instant.get().plusSeconds(864000).atOffset(ZoneOffset.UTC);
  db.update("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end,status,completion_summary,ended_at) VALUES(?,'交付迭代','被评成果',1,?,?,'ENDED','完成',?)",team,deadline.minusDays(7).toLocalDate(),deadline.toLocalDate(),deadline);
  long iteration=db.queryForObject("SELECT MAX(id) FROM team_iteration WHERE team_id=?",Long.class,team);
  db.update("INSERT INTO submission_package(team_id,iteration_id) VALUES(?,?)",team,iteration);
  long pkg=db.queryForObject("SELECT id FROM submission_package WHERE team_id=?",Long.class,team);
  db.update("INSERT INTO submission_version(package_id,team_id,iteration_id,version_no,submitted_by,summary,accepted_at,deadline_snapshot,late,total_bytes,request_key,payload_hash,receipt_code) VALUES(?,?,?,1,?,?,?,?,FALSE,0,?,?,?)",pkg,team,iteration,leader,"测试成果",deadline.minusDays(1),deadline,UUID.randomUUID(),"0".repeat(64),"TEST-CHK-"+team+"-"+idx);
  long version=db.queryForObject("SELECT MAX(id) FROM submission_version WHERE team_id=?",Long.class,team);
  db.update("UPDATE submission_package SET current_version_id=? WHERE id=?",version,pkg);
  db.update("INSERT INTO team_delivery(team_id,final_version_id,selected_by,selected_at,frozen_at,frozen_deadline) VALUES(?,?,?,?,?,?)",team,version,leader,deadline.minusDays(1),deadline,deadline);
  db.update("INSERT INTO submission_review_binding(team_id,version_id,confirmed_by,confirmed_at,reason,reassessment_required) VALUES(?,?,?,?,?,FALSE)",team,version,teacher,deadline,"教师确认被评版本");
  long binding=db.queryForObject("SELECT MAX(id) FROM submission_review_binding WHERE team_id=?",Long.class,team);
  db.update("UPDATE team_delivery SET review_binding_id=? WHERE team_id=?",binding,team);
  return binding;
 }
 UserPrincipal who(String name){return UserPrincipal.from(users.findByUsername(name).orElseThrow());}
 Map<String,Object> data(Object...pairs){var m=new HashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
 Map<String,Object> reviewInput(int score,String comment){return data("scores",List.of(data("criterionId",criterion,"score",score)),"comment",comment);}
 ResultActions postJson(String user,String path,Map<String,Object> in)throws Exception{return mvc.perform(post(path).with(user(who(user))).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(in)));}
 JsonNode getJson(String user,String path)throws Exception{return json.readTree(mvc.perform(get(path).with(user(who(user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
 JsonNode body(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsByteArray()).path("data");}
 long createRound(String name)throws Exception{return body(postJson("teacher01","/api/reviews/projects/"+project+"/rounds",data("name",name,"deadline",instant.get().plusSeconds(864000).atOffset(ZoneOffset.UTC))).andExpect(status().isOk()).andReturn()).path("id").asLong();}
 long taskFor(String user,long bindingId){return db.queryForObject("SELECT id FROM review_task WHERE binding_id=? AND reviewer_id=? AND status='PENDING'",Long.class,bindingId,who(user).id());}
 void submitBindingReview(String user,long bindingId,int score,String comment)throws Exception{postJson(user,"/api/reviews/tasks/"+taskFor(user,bindingId)+"/submit",reviewInput(score,comment)).andExpect(status().isOk());}
 JsonNode myTasks(String user)throws Exception{return getJson(user,"/api/reviews/projects/"+project).path("myTasks");}

 @Test void acceptanceScenario_TaChecksAndTwoStudentsSubmitAssignedReviews()throws Exception{
  // TA completes one check (module 6)
  var iws=getJson("assistant01","/api/inspections/teams/"+teamA);
  assertThat(iws.path("isTA").asBoolean()).isTrue();assertThat(iws.path("versions").size()).isEqualTo(1);
  body(postJson("assistant01","/api/inspections/teams/"+teamA,data("versionId",versionA,"conclusion","初检发现缺少测试覆盖","feedback","补充单元测试后重交","rectificationRequired",true,"reported",false)).andExpect(status().isOk()).andReturn());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM inspection WHERE team_id=?",Long.class,teamA)).isEqualTo(1);

  // Teacher creates a round and auto-assigns (module 5)
  long round=createRound("第一次互评");
  assertThat(body(postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk()).andReturn()).path("created").asInt()).isEqualTo(10);
  // No self review: student01 (甲组) reviews exactly 乙/丙, never 甲
  var tasks=myTasks("student01");assertThat(tasks.size()).isEqualTo(2);
  for(JsonNode t:tasks)assertThat(t.path("team_id").asLong()).isNotEqualTo(teamA);
  // Two students submit their assigned reviews
  for(JsonNode t:tasks)postJson("student01","/api/reviews/tasks/"+t.path("id").asLong()+"/submit",reviewInput(88,"结构清晰，论证充分")).andExpect(status().isOk());
  for(JsonNode t:myTasks("student04"))postJson("student04","/api/reviews/tasks/"+t.path("id").asLong()+"/submit",reviewInput(75,"整体达标，细节可优化")).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_submission s JOIN review_task k ON k.id=s.task_id WHERE k.round_id=?",Long.class,round)).isEqualTo(4);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=? AND status='SUBMITTED'",Long.class,round)).isEqualTo(4);
 }

 @Test void autoAssignIsDeterministicAndOnlyForProjectMembers()throws Exception{
  long round=createRound("分配边界");
  body(postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk()).andReturn());
  // Exactly 5 project members × 2 non-self teams
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=?",Long.class,round)).isEqualTo(10);
  // No reviewer is assigned their own team
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task k JOIN submission_review_binding b ON b.id=k.binding_id JOIN project_team_member m ON m.team_id=b.team_id AND m.user_id=k.reviewer_id AND m.active=TRUE WHERE k.round_id=?",Long.class,round)).isZero();
  // Idempotent: a second assign creates nothing new
  assertThat(body(postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk()).andReturn()).path("created").asInt()).isZero();
 }

 @Test void selfReviewForbiddenAndSubmissionValidated()throws Exception{
  long round=createRound("校验批次");
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderA,"bindingId",bindingA)).andExpect(status().isForbidden());
  postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk());
  long task=taskFor("student01",bindingB);
  postJson("student01","/api/reviews/tasks/"+task+"/submit",reviewInput(150,"x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCORE"));
  postJson("student01","/api/reviews/tasks/"+task+"/submit",reviewInput(80," ")).andExpect(status().isBadRequest());
  postJson("student04","/api/reviews/tasks/"+task+"/submit",reviewInput(80,"越权提交")).andExpect(status().isForbidden());
  postJson("assistant01","/api/reviews/tasks/"+task+"/submit",reviewInput(80,"助教提交")).andExpect(status().isForbidden());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_submission s JOIN review_task k ON k.id=s.task_id WHERE k.round_id=?",Long.class,round)).isZero();
 }

 @Test void deadlinePassedBlocksSubmissionButExtendReopens()throws Exception{
  long round=createRound("截止批次");
  body(postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk()).andReturn());
  long task=taskFor("student01",bindingB);
  instant.set(instant.get().plusSeconds(864001));
  postJson("student01","/api/reviews/tasks/"+task+"/submit",reviewInput(80,"迟到")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REVIEW_DEADLINE_PASSED"));
  // Extending past the new deadline reopens submission
  postJson("teacher01","/api/reviews/rounds/"+round+"/extend",data("deadline",instant.get().plusSeconds(3600).atOffset(ZoneOffset.UTC))).andExpect(status().isOk());
  postJson("student01","/api/reviews/tasks/"+task+"/submit",reviewInput(80,"延期内补交")).andExpect(status().isOk());
 }

 @Test void closeRoundCancelsPendingTasksAndRevokesReviewerAccess()throws Exception{
  long round=createRound("关闭批次");
  body(postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk()).andReturn());
  assertThat(db.queryForObject("SELECT active FROM submission_review_access WHERE binding_id=? AND reviewer_id=?",Boolean.class,bindingB,leaderA)).isTrue();
  long task=taskFor("student01",bindingB);
  postJson("teacher01","/api/reviews/rounds/"+round+"/close",data()).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT status FROM review_task WHERE id=?",String.class,task)).isEqualTo("CANCELLED");
  assertThat(db.queryForObject("SELECT active FROM submission_review_access WHERE binding_id=? AND reviewer_id=?",Boolean.class,bindingB,leaderA)).isFalse();
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderB,"bindingId",bindingA)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ROUND_CLOSED"));
 }

 @Test void appealApprovalCreatesReevaluationAndPreservesOriginalReview()throws Exception{
  long round=createRound("复核批次");
  // Manually assign two reviewers to 甲组 so a spare reviewer remains for re-evaluation
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderB,"bindingId",bindingA)).andExpect(status().isOk());
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderC,"bindingId",bindingA)).andExpect(status().isOk());
  submitBindingReview("student04",bindingA,60,"分数偏低");submitBindingReview("student05",bindingA,90,"不错");
  postJson("teacher01","/api/reviews/rounds/"+round+"/feedback",data("open",true)).andExpect(status().isOk());
  long appealId=body(postJson("student01","/api/reviews/appeals",data("bindingId",bindingA,"reason","评分差异大，申请复核")).andExpect(status().isOk()).andReturn()).path("id").asLong();
  assertThat(db.queryForObject("SELECT status FROM review_appeal WHERE id=?",String.class,appealId)).isEqualTo("PENDING");
  postJson("student03","/api/reviews/appeals",data("bindingId",bindingA,"reason","成员越权")).andExpect(status().isForbidden());
  // Approve with a reviewer who has not yet reviewed 甲组 (student06), keeping the original result
  postJson("teacher01","/api/reviews/appeals/"+appealId+"/approve",data("reviewerId",memberC,"resolution","同意重评","submissionId",db.queryForObject("SELECT MIN(id) FROM review_submission WHERE binding_id=?",Long.class,bindingA))).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT status FROM review_appeal WHERE id=?",String.class,appealId)).isEqualTo("APPROVED");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=? AND binding_id=? AND reviewer_id=? AND reevaluation=TRUE",Long.class,round,bindingA,memberC)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_submission WHERE binding_id=?",Long.class,bindingA)).isEqualTo(2);
 }

 @Test void appealRejectionRecordsResolutionAndDuplicatePendingBlocked()throws Exception{
  long round=createRound("驳回复核");
  body(postJson("teacher01","/api/reviews/rounds/"+round+"/assign",data()).andExpect(status().isOk()).andReturn());
  submitBindingReview("student04",bindingA,70,"达标");
  postJson("teacher01","/api/reviews/rounds/"+round+"/feedback",data("open",true)).andExpect(status().isOk());
  long appealId=body(postJson("student01","/api/reviews/appeals",data("bindingId",bindingA,"reason","希望更高分")).andExpect(status().isOk()).andReturn()).path("id").asLong();
  postJson("student01","/api/reviews/appeals",data("bindingId",bindingA,"reason","重复申请")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("APPEAL_PENDING"));
  postJson("teacher01","/api/reviews/appeals/"+appealId+"/reject",data("resolution","评分合理，维持原判")).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT status FROM review_appeal WHERE id=?",String.class,appealId)).isEqualTo("REJECTED");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE reevaluation=TRUE",Long.class)).isZero();
 }

 @Test void inspectionRolesVersionsAndRectificationFeedbackEnforced()throws Exception{
  // Only TA may record checks
  for(String user:new String[]{"teacher01","student01"})postJson(user,"/api/inspections/teams/"+teamA,data("versionId",versionA,"conclusion","越权检查","feedback",null,"rectificationRequired",false,"reported",false)).andExpect(status().isForbidden());
  // Version must belong to the team
  postJson("assistant01","/api/inspections/teams/"+teamA,data("versionId",versionB,"conclusion","跨团队版本","feedback",null,"rectificationRequired",false,"reported",false)).andExpect(status().isNotFound());
  // Rectification/reporting requires feedback
  postJson("assistant01","/api/inspections/teams/"+teamA,data("versionId",versionA,"conclusion","需要整改","feedback","","rectificationRequired",true,"reported",false)).andExpect(status().isBadRequest());
  // Student sees only their own team; TA sees rectification flag
  long insp=body(postJson("assistant01","/api/inspections/teams/"+teamA,data("versionId",versionA,"conclusion","初检结论","feedback","问题描述","rectificationRequired",true,"reported",false)).andExpect(status().isOk()).andReturn()).path("id").asLong();
  assertThat(getJson("assistant01","/api/inspections/teams/"+teamA).path("inspections").size()).isEqualTo(1);
  mvc.perform(get("/api/inspections/teams/"+teamB).with(user(who("student01")))).andExpect(status().isForbidden());
  // Recheck references the earlier inspection
  postJson("assistant01","/api/inspections/teams/"+teamA,data("versionId",versionA,"conclusion","复查通过","feedback",null,"rectificationRequired",false,"reported",false,"parentId",insp)).andExpect(status().isBadRequest());
  db.update("""
      INSERT INTO submission_version(package_id,team_id,iteration_id,version_no,submitted_by,summary,accepted_at,deadline_snapshot,late,total_bytes,request_key,payload_hash,receipt_code)
      SELECT package_id,team_id,iteration_id,2,submitted_by,summary,accepted_at,deadline_snapshot,late,total_bytes,?,payload_hash,? FROM submission_version WHERE id=?
      """,UUID.randomUUID(),"RECHECK-"+teamA,versionA);
  long newer=db.queryForObject("SELECT MAX(id) FROM submission_version WHERE team_id=?",Long.class,teamA);
  postJson("assistant01","/api/inspections/teams/"+teamA,data("versionId",newer,"conclusion","复查通过","feedback",null,"rectificationRequired",false,"reported",false,"parentId",insp)).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM inspection WHERE team_id=? AND parent_id=?",Long.class,teamA,insp)).isEqualTo(1);
 }

 @Test void assistantScopeChangesImmediatelyAndRestrictsReviewIdentities()throws Exception{
  long round=createRound("助教授权检查");
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderB,"bindingId",bindingA)).andExpect(status().isOk());
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderA,"bindingId",bindingB)).andExpect(status().isOk());
  String scope="/api/inspections/teams/"+teamB+"/assistant-scope/"+ta;
  byte[] revoke=json.writeValueAsBytes(data("granted",false));
  mvc.perform(put(scope).with(user(who("assistant01"))).with(csrf()).contentType("application/json").content(revoke)).andExpect(status().isForbidden());
  mvc.perform(put(scope).with(user(who("teacher01"))).with(csrf()).contentType("application/json").content(revoke)).andExpect(status().isOk());
  mvc.perform(get("/api/inspections/teams/"+teamB).with(user(who("assistant01")))).andExpect(status().isForbidden());
  mvc.perform(get("/api/submissions/teams/"+teamB+"/versions/"+versionB).with(user(who("assistant01")))).andExpect(status().isForbidden());
  var reviews=getJson("assistant01","/api/reviews/projects/"+project).path("progress");
  assertThat(reviews.size()).isEqualTo(1);
  assertThat(reviews.get(0).path("reviewer_name").asText()).isNotBlank();
  assertThat(reviews.get(0).path("binding_id").asLong()).isEqualTo(bindingA);
  mvc.perform(put(scope).with(user(who("teacher01"))).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(data("granted",true)))).andExpect(status().isOk());
  mvc.perform(get("/api/inspections/teams/"+teamB).with(user(who("assistant01")))).andExpect(status().isOk());
 }

 @Test void repeatedAutoAssignmentKeepsTwoOtherTeamsEvenWithFourTargets()throws Exception{
  long fourth=newTeam("第四团队",who("student02").id(),new long[]{});
  seedDelivery(fourth,who("student02").id(),4);
  long round=createRound("四组自动分配");
  postJson("teacher01","/api/reviews/rounds/"+round+"/assign",Map.of()).andExpect(status().isOk());
  postJson("teacher01","/api/reviews/rounds/"+round+"/assign",Map.of()).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=? AND reviewer_id=?",Long.class,round,leaderA)).isEqualTo(2);
 }

 @Test void missingDeliveryCanBeReportedOnlyAfterFinalDeadline()throws Exception{
  long empty=newTeam("待交团队",who("student02").id(),new long[]{});
  String path="/api/inspections/teams/"+empty;
  var input=data("conclusion","缺交","feedback","截止未收到任何成果","reported",true);
  postJson("assistant01",path,input).andExpect(status().isBadRequest());
  instant.set(instant.get().plusSeconds(864001));
  postJson("assistant01",path,input).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM inspection WHERE team_id=? AND version_id IS NULL AND reported=TRUE",Long.class,empty)).isEqualTo(1);
 }

 @Test void auditFailureRollsBackAssignedTaskAndAccessGrant()throws Exception{
  long round=createRound("原子性");
  AuditService target=AopTestUtils.getUltimateTargetObject(audit);
  doThrow(new IllegalStateException("audit fail")).when(target).append(anyLong(),anyLong(),eq("REVIEW_TASK_ASSIGNED"),anyString(),anyString());
  postJson("teacher01","/api/reviews/rounds/"+round+"/tasks",data("reviewerId",leaderB,"bindingId",bindingA)).andExpect(status().isInternalServerError());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM review_task WHERE round_id=?",Long.class,round)).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM submission_review_access WHERE binding_id=? AND reviewer_id=?",Long.class,bindingA,leaderB)).isZero();
 }
}
