package cn.edu.school;

import cn.edu.school.auth.*;
import cn.edu.school.audit.AuditService;
import cn.edu.school.submission.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:submissions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.storage-root=./target/submission-test-files","app.submission.jobs-enabled=false"
})
@AutoConfigureMockMvc @ActiveProfiles("demo")
class SubmissionIntegrationTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired UserRepository users;
 @Autowired SubmissionService service;
 @MockitoBean Clock clock;
 @MockitoSpyBean AuditService audit;
 @MockitoSpyBean SubmissionFiles storage;
 @MockitoSpyBean SubmissionNotices notices;
 final AtomicReference<Instant> instant=new AtomicReference<>();
 long team,project,iteration,leader,member;
 final Instant deadline=Instant.parse("2030-01-10T00:00:00Z");
 @BeforeEach void setup()throws Exception{
  Files.createDirectories(Path.of("target/submission-test-files"));
  instant.set(Instant.parse("2030-01-01T00:00:00Z"));when(clock.instant()).thenAnswer(inv->instant.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  leader=users.findByUsername("student01").orElseThrow().getId();member=users.findByUsername("student03").orElseThrow().getId();
  db.update("INSERT INTO course_project(course_id,name,status,final_deadline) VALUES(1,?,'IN_PROGRESS',?)","成果测试"+UUID.randomUUID(),deadline.atOffset(ZoneOffset.UTC));
  project=db.queryForObject("SELECT MAX(id) FROM course_project",Long.class);
  db.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,'成果测试团队',?)",project,leader);team=db.queryForObject("SELECT MAX(id) FROM project_team",Long.class);
  db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?),(?,?)",team,leader,team,member);
  db.update("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end,status) VALUES(?,'成果迭代','提交成果',1,DATE '2030-01-01',DATE '2030-01-09','ENDED')",team);iteration=db.queryForObject("SELECT MAX(id) FROM team_iteration",Long.class);
 }
 UserPrincipal who(String name){return UserPrincipal.from(users.findByUsername(name).orElseThrow());}
 long rev(){return db.queryForObject("SELECT revision FROM project_team WHERE id=?",Long.class,team);}
 Map<String,Object> data(Object...pairs){var value=new HashMap<String,Object>();value.put("revision",rev());for(int i=0;i<pairs.length;i+=2)value.put(pairs[i].toString(),pairs[i+1]);return value;}
 ResultActions postAs(String user,String path,Map<String,Object> in)throws Exception{return mvc.perform(post("/api/submissions/teams/"+team+"/"+path).with(user(who(user))).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(in)));}
 JsonNode body(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsByteArray()).path("data");}
 long upload(String username,String content)throws Exception{
  var result=mvc.perform(multipart("/api/submissions/teams/"+team+"/materials/files").file(new MockMultipartFile("file","report.md","text/plain",content.getBytes(java.nio.charset.StandardCharsets.UTF_8))).param("iterationId",""+iteration).param("revision",""+rev()).with(user(who(username))).with(csrf())).andExpect(status().isOk()).andReturn();return body(result).path("id").asLong();
 }
 Map<String,Object> submitData(List<Long> materials,UUID key){return data("iterationId",iteration,"materialIds",materials,"summary","本轮成果说明","requestKey",key);}
 JsonNode submit(long material)throws Exception{return body(postAs("student01","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isOk()).andReturn());}
 void select(long version)throws Exception{postAs("student01","final-selection",data("versionId",version)).andExpect(status().isOk());}
 void grant(int days)throws Exception{postAs("teacher01","extensions",data("reason","上传故障后限时补交","startsAt",instant.get().minusSeconds(1).atOffset(ZoneOffset.UTC),"endsAt",instant.get().plus(Duration.ofDays(days)).atOffset(ZoneOffset.UTC))).andExpect(status().isOk());}
 long count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE team_id=?",Long.class,team);}

 @Test void memberPreparesLeaderSubmitsAndAuthorizedUsersDownloadActualBytes()throws Exception{
  long material=upload("student03","# 第一版成果");
  postAs("student03","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isForbidden());
  var version=submit(material);long id=version.path("id").asLong(),file=version.path("materials").get(0).path("id").asLong();
  assertThat(version.path("version_no").asInt()).isEqualTo(1);assertThat(version.path("late").asBoolean()).isFalse();assertThat(version.path("submitter_name").asText()).isEqualTo(who("student01").displayName());
  for(String user:List.of("student01","student03","teacher01","assistant01"))mvc.perform(get("/api/submissions/teams/"+team+"/versions/"+id+"/files/"+file).with(user(who(user)))).andExpect(status().isOk()).andExpect(content().bytes("# 第一版成果".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  assertThat(db.queryForObject("SELECT current_version_id FROM submission_package WHERE team_id=?",Long.class,team)).isEqualTo(id);
 }
 @Test void resubmissionKeepsOldFilesSelectionAndHistory()throws Exception{
  long firstMaterial=upload("student03","old bytes");var first=submit(firstMaterial);select(first.path("id").asLong());
  long secondMaterial=upload("student01","new bytes");var second=submit(secondMaterial);
  assertThat(second.path("version_no").asInt()).isEqualTo(2);assertThat(count("submission_package")).isEqualTo(1);
  assertThat(db.queryForObject("SELECT final_version_id FROM team_delivery WHERE team_id=?",Long.class,team)).isEqualTo(first.path("id").asLong());
  postAs("student01","materials/"+firstMaterial+"/remove",data()).andExpect(status().isOk());
  mvc.perform(get("/api/submissions/teams/"+team+"/versions/"+first.path("id").asLong()+"/files/"+first.path("materials").get(0).path("id").asLong()).with(user(who("student03")))).andExpect(status().isOk()).andExpect(content().bytes("old bytes".getBytes()));
  select(second.path("id").asLong());assertThat(count("submission_version")).isEqualTo(2);
 }
 @Test void identicalRetriesRecoverSameReceiptEvenAfterDeadline()throws Exception{
  long material=upload("student01","bytes");UUID key=UUID.randomUUID();var input=submitData(List.of(material),key);
  var first=body(postAs("student01","versions",input).andExpect(status().isOk()).andReturn());instant.set(deadline.plusSeconds(1));
  var retry=body(postAs("student01","versions",input).andExpect(status().isOk()).andReturn());
  assertThat(retry.path("id")).isEqualTo(first.path("id"));assertThat(retry.path("accepted_at")).isEqualTo(first.path("accepted_at"));assertThat(count("submission_version")).isEqualTo(1);
  mvc.perform(get("/api/submissions/teams/"+team+"/receipts/"+key).with(user(who("student01")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.receipt_code").value(first.path("receipt_code").asText()));
  input.put("summary","另一次内容");postAs("student01","versions",input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_KEY_REUSED"));
 }
 @Test void concurrentRetriesCreateOnlyOneVersion()throws Exception{
  long material=upload("student01","concurrent");var payload=submitData(List.of(material),UUID.randomUUID());String request=json.writeValueAsString(payload);var principal=who("student01");var latch=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)){
   Callable<MvcResult> call=()->{latch.await();return mvc.perform(post("/api/submissions/teams/"+team+"/versions").with(user(principal)).with(csrf()).contentType("application/json").content(request)).andReturn();};
   var a=executor.submit(call);var b=executor.submit(call);latch.countDown();var ra=a.get(10,TimeUnit.SECONDS);var rb=b.get(10,TimeUnit.SECONDS);
   assertThat(ra.getResponse().getStatus()).isEqualTo(200);assertThat(rb.getResponse().getStatus()).isEqualTo(200);assertThat(body(ra).path("id")).isEqualTo(body(rb).path("id"));
  }
  assertThat(count("submission_version")).isEqualTo(1);
 }
 @Test void deadlineBoundaryUsesAcceptanceTimeAndNoAutomaticSelection()throws Exception{
  long material=upload("student01","at deadline");instant.set(deadline);var version=submit(material);assertThat(version.path("late").asBoolean()).isFalse();assertThat(OffsetDateTime.parse(version.path("accepted_at").asText()).toInstant()).isEqualTo(deadline);
  instant.set(deadline.plusNanos(1));postAs("student01","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUBMISSION_WINDOW_CLOSED"));
  service.freezeTeam(team);service.freezeTeam(team);
  assertThat(db.queryForObject("SELECT final_version_id FROM team_delivery WHERE team_id=?",Long.class,team)).isNull();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM submission_event WHERE team_id=? AND action='FINAL_FROZEN'",Long.class,team)).isEqualTo(1);
  mvc.perform(get("/api/submissions/teams/"+team).with(user(who("student01")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.exception").value("未指定最终成果"));
 }
 @Test void acceptanceAfterValidationCannotBackdateToClickTime()throws Exception{
  long material=upload("student01","late arrival");instant.set(deadline.minusSeconds(1));
  doAnswer(inv->{instant.set(deadline.plusSeconds(1));return null;}).when(storage).requireAvailable(any(UUID.class),anyLong());
  postAs("student01","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUBMISSION_WINDOW_CLOSED"));assertThat(count("submission_version")).isZero();
 }
 @Test void deadlineFreezeIsIndependentAndLeaderCannotChangeFrozenSelection()throws Exception{
  var v=submit(upload("student01","frozen"));select(v.path("id").asLong());instant.set(deadline.plusSeconds(1));
  assertThat(service.dueTeams()).contains(team);service.freezeTeam(team);
  assertThat(db.queryForObject("SELECT frozen_at FROM team_delivery WHERE team_id=?",OffsetDateTime.class,team)).isNotNull();
  postAs("student01","final-selection",data("versionId",v.path("id").asLong())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FINAL_FROZEN"));
 }
 @Test void teacherVerifiesUnselectedVersionWithReasonAndAccessConfirmation()throws Exception{
  var v=submit(upload("student01","unselected"));instant.set(deadline.plusSeconds(1));service.freezeTeam(team);
  postAs("student01","verify-final",data("versionId",v.path("id").asLong(),"reason","自助核实","accessibleConfirmed",true)).andExpect(status().isForbidden());
  postAs("teacher01","verify-final",data("versionId",v.path("id").asLong(),"reason","核对负责人说明")).andExpect(status().isBadRequest());
  postAs("teacher01","verify-final",data("versionId",v.path("id").asLong(),"reason","核对材料后确定版本","accessibleConfirmed",true)).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT final_version_id FROM team_delivery WHERE team_id=?",Long.class,team)).isEqualTo(v.path("id").asLong());
 }
 @Test void extensionRequestApprovalLateVersionAndWindowEndAreEnforced()throws Exception{
  instant.set(deadline.plusSeconds(60));db.update("UPDATE course_project SET status='ENDED' WHERE id=?",project);
  var req=body(postAs("student01","extension-requests",data("reason","上传故障，申请补交")).andExpect(status().isOk()).andReturn()).path("id").asLong();
  postAs("student01","extension-requests",data("reason","重复申请")).andExpect(status().isBadRequest());
  postAs("assistant01","extensions",data("reason","助教授权","startsAt",instant.get().atOffset(ZoneOffset.UTC),"endsAt",instant.get().plusSeconds(600).atOffset(ZoneOffset.UTC))).andExpect(status().isForbidden());
  var end=instant.get().plus(Duration.ofDays(7));postAs("teacher01","extensions",data("requestId",req,"reason","同意限时补交","startsAt",instant.get().atOffset(ZoneOffset.UTC),"endsAt",end.atOffset(ZoneOffset.UTC))).andExpect(status().isOk());
  var v=submit(upload("student03","supplement"));assertThat(v.path("late").asBoolean()).isTrue();assertThat(v.path("extension_id").asLong()).isPositive();assertThat(OffsetDateTime.parse(v.path("accepted_at").asText()).toInstant()).isEqualTo(instant.get());
  assertThat(db.queryForObject("SELECT status FROM submission_extension_request WHERE id=?",String.class,req)).isEqualTo("APPROVED");
  instant.set(end);postAs("student01","versions",submitData(List.of(db.queryForObject("SELECT MAX(id) FROM submission_material WHERE team_id=?",Long.class,team)),UUID.randomUUID())).andExpect(status().isBadRequest());
 }
 @Test void oversizedOrInvalidExtensionAndRejectionAreRecorded()throws Exception{
  instant.set(deadline.plusSeconds(60));
  postAs("teacher01","extensions",data("reason","非法长窗口","startsAt",instant.get().atOffset(ZoneOffset.UTC),"endsAt",instant.get().plus(Duration.ofDays(8)).atOffset(ZoneOffset.UTC))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WINDOW_TOO_LONG"));
  assertThat(count("submission_extension")).isZero();
  var id=body(postAs("student01","extension-requests",data("reason","申请整改")).andExpect(status().isOk()).andReturn()).path("id").asLong();
  postAs("teacher01","extension-requests/"+id+"/reject",data("reason","已核对无需新增材料")).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT status FROM submission_extension_request WHERE id=?",String.class,id)).isEqualTo("REJECTED");
 }
 @Test void finalReviewBindingAndReviewerAccessRemainPinnedAcrossLateResubmission()throws Exception{
  var first=submit(upload("student01","original version"));long v1=first.path("id").asLong();select(v1);instant.set(deadline.plusSeconds(60));service.freezeTeam(team);
  long binding=body(postAs("teacher01","review-bindings",data("versionId",v1,"reason","最终版本核对完成","accessibleConfirmed",true)).andExpect(status().isOk()).andReturn()).path("id").asLong();
  db.update("INSERT INTO submission_review_access(binding_id,reviewer_id) VALUES(?,?)",binding,who("student04").id());
  grant(1);long secondMaterial=upload("student01","corrected version");var second=submit(secondMaterial);long v2=second.path("id").asLong();
  assertThat(db.queryForObject("SELECT final_version_id FROM team_delivery WHERE team_id=?",Long.class,team)).isEqualTo(v1);
  assertThat(db.queryForObject("SELECT version_id FROM submission_review_binding WHERE id=?",Long.class,binding)).isEqualTo(v1);
  mvc.perform(get("/api/submissions/teams/"+team+"/versions/"+v1).with(user(who("student04")))).andExpect(status().isOk());
  mvc.perform(get("/api/submissions/teams/"+team+"/versions/"+v2).with(user(who("student04")))).andExpect(status().isForbidden());
  postAs("teacher01","review-bindings",data("versionId",v2,"reason","修正后需重评","accessibleConfirmed",true)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASSESSMENT_REQUIRED"));
  postAs("teacher01","review-bindings",data("versionId",v2,"reason","修正后需重评","accessibleConfirmed",true,"reassessmentRequired",true)).andExpect(status().isOk());
  assertThat(count("submission_review_binding")).isEqualTo(2);assertThat(db.queryForObject("SELECT version_id FROM submission_review_binding WHERE id=?",Long.class,binding)).isEqualTo(v1);
 }
 @Test void crossTeamCrossCourseAdminAndFormerMemberCannotAccessFilesOrWorkspace()throws Exception{
  long material=upload("student03","private team data");var v=submit(material);long id=v.path("id").asLong(),file=v.path("materials").get(0).path("id").asLong();
  for(String user:List.of("student04","student02","admin01")){
   mvc.perform(get("/api/submissions/teams/"+team).with(user(who(user)))).andExpect(status().isForbidden());
   mvc.perform(get("/api/submissions/teams/"+team+"/versions/"+id+"/files/"+file).with(user(who(user)))).andExpect(status().isForbidden());
   mvc.perform(get("/api/submissions/teams/"+team+"/materials/"+material+"/download").with(user(who(user)))).andExpect(status().isForbidden());
  }
  db.update("UPDATE project_team_member SET active=FALSE WHERE team_id=? AND user_id=?",team,member);
  mvc.perform(get("/api/submissions/teams/"+team+"/versions/"+id).with(user(who("student03")))).andExpect(status().isForbidden());
  assertThat(count("submission_version")).isEqualTo(1);
 }
 @Test void linksRequireHttpsAndCodeRefAndAreSnapshotted()throws Exception{
  postAs("student03","materials/links",data("iterationId",iteration,"title","代码","url","http://example.com/repo","codeLink",true,"codeRef","v1")).andExpect(status().isBadRequest());
  postAs("student03","materials/links",data("iterationId",iteration,"title","代码","url","https://example.com/repo","codeLink",true)).andExpect(status().isBadRequest());
  postAs("student03","materials/links",data("iterationId",iteration,"title","代码","url","https://user:pass@example.com/repo","codeLink",true,"codeRef","v1")).andExpect(status().isBadRequest());
  long id=body(postAs("student03","materials/links",data("iterationId",iteration,"title","代码","url","https://example.com/repo","codeLink",true,"codeRef","v1.0")).andExpect(status().isOk()).andReturn()).path("id").asLong();
  var v=submit(id);assertThat(v.path("materials").get(0).path("code_ref").asText()).isEqualTo("v1.0");assertThat(v.path("total_bytes").asLong()).isZero();
 }
 @Test void invalidMaterialsMissingFieldsAndCrossIterationSelectionDoNotCreateVersions()throws Exception{
  long m=upload("student01","valid");
  postAs("student01","versions",submitData(List.of(),UUID.randomUUID())).andExpect(status().isBadRequest());
  var invalid=submitData(List.of(m),UUID.randomUUID());invalid.put("summary"," ");postAs("student01","versions",invalid).andExpect(status().isBadRequest());
  invalid=submitData(List.of(m,m),UUID.randomUUID());postAs("student01","versions",invalid).andExpect(status().isBadRequest());
  invalid=submitData(List.of(m),UUID.randomUUID());invalid.put("iterationId",-1);postAs("student01","versions",invalid).andExpect(status().isNotFound());
  assertThat(count("submission_version")).isZero();
 }
 @Test void aggregateFileLimitIncludesExact200MbAndRejectsOneByteMore()throws Exception{
  var ids=new ArrayList<Long>();
  for(int i=0;i<4;i++){
   UUID file=UUID.randomUUID();db.update("INSERT INTO stored_file(id,course_id,owner_id,original_name,extension,size_bytes,sha256,created_at) VALUES(?,1,?,'boundary.zip','zip',?, ?,?)",file,leader,50L*1024*1024,"0".repeat(64),instant.get().atOffset(ZoneOffset.UTC));
   db.update("INSERT INTO submission_material(team_id,iteration_id,kind,file_id,title,created_by,created_at) VALUES(?,?,'FILE',?,'boundary.zip',?,?)",team,iteration,file,leader,instant.get().atOffset(ZoneOffset.UTC));ids.add(db.queryForObject("SELECT MAX(id) FROM submission_material",Long.class));
   // Synthetic metadata exercises arithmetic limits without writing 200 MB of dummy data.
   doNothing().when(storage).requireAvailable(eq(file),eq(1L));
  }
  var v=body(postAs("student01","versions",submitData(ids,UUID.randomUUID())).andExpect(status().isOk()).andReturn());assertThat(v.path("total_bytes").asLong()).isEqualTo(200L*1024*1024);
  ids.add(upload("student01","x"));postAs("student01","versions",submitData(ids,UUID.randomUUID())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERSION_TOO_LARGE"));assertThat(count("submission_version")).isEqualTo(1);
 }
 @Test void oversizedAndUnsupportedUploadsDoNotCreateMaterials()throws Exception{
  var huge=new MockMultipartFile("file","huge.zip","application/zip",new byte[]{1}){@Override public long getSize(){return 50L*1024*1024+1;}};
  for(var file:List.of(huge,new MockMultipartFile("file","script.html","text/html",new byte[]{1})))mvc.perform(multipart("/api/submissions/teams/"+team+"/materials/files").file(file).param("iterationId",""+iteration).param("revision",""+rev()).with(user(who("student03"))).with(csrf())).andExpect(status().isBadRequest());
  assertThat(count("submission_material")).isZero();
 }
 @Test void terminatedArchivedProjectsRejectNewVersionsAndExtensions()throws Exception{
  long material=upload("student01","kept");
  for(String state:List.of("TERMINATED","ARCHIVED","DRAFT")){
   db.update("UPDATE course_project SET status=? WHERE id=?",state,project);
   postAs("student01","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
   postAs("teacher01","extensions",data()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
  }
 }
 @Test void revisionAndCsrfProtectionApplyToSubmissionOperations()throws Exception{
  long material=upload("student01","current");var old=submitData(List.of(material),UUID.randomUUID());upload("student01","concurrent edit");
  postAs("student01","versions",old).andExpect(status().isConflict());
  mvc.perform(post("/api/submissions/teams/"+team+"/versions").with(user(who("student01"))).contentType("application/json").content(json.writeValueAsBytes(old))).andExpect(status().isForbidden());assertThat(count("submission_version")).isZero();
 }
 @Test void auditFailureRollsBackVersionPackageAndReceipt()throws Exception{
  long material=upload("student01","atomic");long revision=rev();AuditService target=AopTestUtils.getUltimateTargetObject(audit);
  doThrow(new IllegalStateException("audit fail")).when(target).append(anyLong(),anyLong(),eq("VERSION_SUBMITTED"),anyString(),anyString());
  postAs("student01","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isInternalServerError());
  assertThat(count("submission_package")).isZero();assertThat(count("submission_version")).isZero();assertThat(rev()).isEqualTo(revision);
 }
 @Test void materialAuditFailureRollsBackFileMetadataAndPhysicalFile()throws Exception{
  long before=db.queryForObject("SELECT COUNT(*) FROM stored_file",Long.class);
  AuditService target=AopTestUtils.getUltimateTargetObject(audit);doThrow(new IllegalStateException("material audit fail")).when(target).append(anyLong(),anyLong(),eq("MATERIAL_PREPARED"),anyString(),anyString());
  long countBefore;try(var stream=Files.list(Path.of("target/submission-test-files"))){countBefore=stream.count();}
  mvc.perform(multipart("/api/submissions/teams/"+team+"/materials/files").file(new MockMultipartFile("file","failed.md","text/plain",new byte[]{1})).param("iterationId",""+iteration).param("revision",""+rev()).with(user(who("student01"))).with(csrf())).andExpect(status().isInternalServerError());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM stored_file",Long.class)).isEqualTo(before);assertThat(count("submission_material")).isZero();try(var stream=Files.list(Path.of("target/submission-test-files"))){assertThat(stream.count()).isEqualTo(countBefore);}
 }
 @Test void missingPhysicalMaterialDoesNotProduceSuccessfulReceipt()throws Exception{
  long material=upload("student01","missing");UUID file=db.queryForObject("SELECT file_id FROM submission_material WHERE id=?",UUID.class,material);Files.delete(Path.of("target/submission-test-files",file+".bin"));
  postAs("student01","versions",submitData(List.of(material),UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_UNAVAILABLE"));assertThat(count("submission_version")).isZero();
 }
 @Test void noticesDeduplicateAndFailuresDoNotUndoAcceptedVersion()throws Exception{
  submit(upload("student01","notice"));long event=db.queryForObject("SELECT MAX(id) FROM submission_event WHERE team_id=? AND action='VERSION_SUBMITTED'",Long.class,team);
  notices.send(event);notices.send(event);assertThat(db.queryForObject("SELECT COUNT(*) FROM submission_notice WHERE event_id=?",Long.class,event)).isEqualTo(3);
  long request=upload("student01","another");submit(request);long failing=db.queryForObject("SELECT MAX(id) FROM submission_event WHERE team_id=? AND action='VERSION_SUBMITTED'",Long.class,team);
  SubmissionNotices target=AopTestUtils.getUltimateTargetObject(notices);doThrow(new IllegalStateException("simulated notice failure")).when(target).send(failing);
  var jobs=new SubmissionJobs(service,notices);for(int i=0;i<4;i++)jobs.notifyRecipients();
  assertThat(db.queryForObject("SELECT notice_status FROM submission_event WHERE id=?",String.class,failing)).isEqualTo("FAILED");assertThat(count("submission_version")).isEqualTo(2);
  mvc.perform(get("/api/submissions/notice-failures").with(user(who("admin01")))).andExpect(status().isOk());
  mvc.perform(get("/api/submissions/notice-failures").with(user(who("student01")))).andExpect(status().isForbidden());
 }
}
