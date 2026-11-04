package cn.edu.school;

import cn.edu.school.auth.*;
import cn.edu.school.audit.AuditService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:collaboration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("demo")
class CollaborationIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired UserRepository users;
    @MockitoSpyBean AuditService audit;
    long team,project,requirement,leader,member,other;
    @BeforeEach void setup(){
        leader=users.findByUsername("student01").orElseThrow().getId();member=users.findByUsername("student03").orElseThrow().getId();other=users.findByUsername("student04").orElseThrow().getId();
        db.update("INSERT INTO course_project(course_id,name,status,final_deadline) VALUES(1,?,'IN_PROGRESS',?)","测试项目"+UUID.randomUUID(),OffsetDateTime.now().plusYears(1));
        project=db.queryForObject("SELECT MAX(id) FROM course_project",Long.class);
        db.update("INSERT INTO project_requirement(project_id,title,description) VALUES(?,'交付平台','教师规定的项目要求')",project);
        requirement=db.queryForObject("SELECT MAX(id) FROM project_requirement",Long.class);
        db.update("INSERT INTO project_team(project_id,name,leader_id) VALUES(?,'测试团队',?)",project,leader);
        team=db.queryForObject("SELECT MAX(id) FROM project_team",Long.class);
        db.update("INSERT INTO assistant_team_scope(team_id,user_id) VALUES(?,?)",team,who("assistant01").id());
        db.update("INSERT INTO project_team_member(team_id,user_id) VALUES(?,?),(?,?)",team,leader,team,member);
    }
    UserPrincipal who(String name){return UserPrincipal.from(users.findByUsername(name).orElseThrow());}
    long rev(){return db.queryForObject("SELECT revision FROM project_team WHERE id=?",Long.class,team);}
    Map<String,Object> payload(Object...pairs){var m=new HashMap<String,Object>();m.put("revision",rev());for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
    ResultActions postAs(String name,String path,Map<String,Object> data)throws Exception{
        return mvc.perform(post("/api/collaboration/teams/"+team+"/"+path).with(user(who(name))).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(data)));
    }
    long iteration(int sequence)throws Exception{
        var result=postAs("student01","iterations",payload("name","迭代"+sequence,"goal","完成可演示功能","sequence",sequence,"plannedStart",LocalDate.now().minusDays(5),"plannedEnd",LocalDate.now().plusDays(10))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }
    long task(long iteration,long assignee)throws Exception{
        var result=postAs("student01","tasks",payload("iterationId",iteration,"requirementId",requirement,"title","实现任务","description","完成后端和页面","assigneeId",assignee,"plannedStart",LocalDate.now().minusDays(2),"plannedEnd",LocalDate.now().plusDays(2))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asLong();
    }
    @Test void leaderCanPlanExecuteAndEndWithRetainedHistory()throws Exception{
        long i=iteration(1),t=task(i,member);
        postAs("student01","iterations/"+i+"/start",payload()).andExpect(status().isOk());
        postAs("student03","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","开始开发")).andExpect(status().isOk());
        postAs("student03","tasks/"+t+"/progress",payload("status","COMPLETED","note","实现并检查完成")).andExpect(status().isOk());
        postAs("student01","iterations/"+i+"/end",payload("summary","本轮全部完成","leftovers","")).andExpect(status().isOk());
        mvc.perform(get("/api/collaboration/teams/"+team).with(user(who("student03")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.iterations[0].status").value("ENDED")).andExpect(jsonPath("$.data.tasks[0].status").value("COMPLETED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM collaboration_event WHERE team_id=?",Long.class,team)).isEqualTo(6);
    }
    @Test void memberCannotManageOrChangeSomeoneElsesProgress()throws Exception{
        long i=iteration(1),t=task(i,leader);
        postAs("student03","iterations",payload()).andExpect(status().isForbidden());
        postAs("student03","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","越权修改")).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT status FROM team_task WHERE id=?",String.class,t)).isEqualTo("NOT_STARTED");
    }
    @Test void directReadsAndHistoryAreIsolatedByCourseAndTeam()throws Exception{
        for(String name:List.of("student02","student04","admin01")) {
            mvc.perform(get("/api/collaboration/teams/"+team).with(user(who(name)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/collaboration/teams/"+team+"/history").with(user(who(name)))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/collaboration/teams/"+team)).andExpect(status().isUnauthorized());
    }
    @Test void taReadOnlyAndTeacherInterventionRequiresReason()throws Exception{
        long i=iteration(1),t=task(i,member);
        mvc.perform(get("/api/collaboration/teams/"+team).with(user(who("assistant01")))).andExpect(status().isOk());
        postAs("assistant01","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","助教修改")).andExpect(status().isForbidden());
        postAs("teacher01","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","教师干预")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        postAs("teacher01","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","教师干预","reason","核实后纠正进展")).andExpect(status().isOk());
        postAs("teacher01","iterations/"+i+"/start",payload("reason","教师尝试开始")).andExpect(status().isForbidden());
    }
    @Test void taskMustHaveValidIterationRequirementAndCurrentTeamAssignee()throws Exception{
        long i=iteration(1);
        var input=payload("iterationId",i,"requirementId",requirement,"title","任务","description","任务说明","assigneeId",other,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now());
        postAs("student01","tasks",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ASSIGNEE_MISMATCH"));
        input.put("assigneeId",member);input.put("iterationId",null);
        postAs("student01","tasks",input).andExpect(status().isBadRequest());
        input.put("iterationId",i);input.put("requirementId",-1);
        postAs("student01","tasks",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUIREMENT_MISMATCH"));
    }
    @Test void staleWritesAndMissingCsrfDoNotCreateExtraObjects()throws Exception{
        long revision=rev();iteration(1);
        var input=payload("name","旧窗口","goal","目标","sequence",2,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now());input.put("revision",revision);
        postAs("student01","iterations",input).andExpect(status().isConflict());
        mvc.perform(post("/api/collaboration/teams/"+team+"/iterations").with(user(who("student01"))).contentType("application/json").content(json.writeValueAsBytes(input))).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM team_iteration WHERE team_id=?",Long.class,team)).isEqualTo(1);
    }
    @Test void completedTaskReworkNeedsReasonAndRetainsOldCompletion()throws Exception{
        long i=iteration(1),t=task(i,member);
        postAs("student03","tasks/"+t+"/progress",payload("status","COMPLETED","note","直接完成")).andExpect(status().isBadRequest());
        postAs("student03","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","开始")).andExpect(status().isOk());
        postAs("student03","tasks/"+t+"/progress",payload("status","COMPLETED","note","完成")).andExpect(status().isOk());
        postAs("student03","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","重新开发")).andExpect(status().isBadRequest());
        postAs("student03","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","重新开发","reason","发现遗漏异常分支")).andExpect(status().isOk());
        var prior=db.queryForObject("SELECT before_data FROM collaboration_event WHERE team_id=? ORDER BY id DESC LIMIT 1",String.class,team);
        assertThat(json.readTree(prior).get("completed_at").isNull()).isFalse();
    }
    @Test void endedIterationRequiresLeftoversAndCanMoveUnfinishedTasks()throws Exception{
        long first=iteration(1),second=iteration(2),t=task(first,member);
        postAs("student01","iterations/"+first+"/start",payload()).andExpect(status().isOk());
        postAs("student01","iterations/"+first+"/end",payload("summary","部分完成")).andExpect(status().isBadRequest());
        postAs("student01","iterations/"+first+"/end",payload("summary","部分完成","leftovers","任务转入下一轮")).andExpect(status().isOk());
        postAs("student03","tasks/"+t+"/progress",payload("status","IN_PROGRESS","note","结束后更新")).andExpect(status().isBadRequest());
        postAs("student01","tasks/"+t+"/move",payload("targetIterationId",second,"reason","本轮遗留","plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now().plusDays(1))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT original_iteration_id FROM team_task WHERE id=?",Long.class,t)).isEqualTo(first);
        assertThat(db.queryForObject("SELECT iteration_id FROM team_task WHERE id=?",Long.class,t)).isEqualTo(second);
        assertThat(db.queryForObject("SELECT before_data FROM collaboration_event WHERE team_id=? ORDER BY id DESC LIMIT 1",String.class,team)).contains("\"iteration_id\":"+first);
    }
    @Test void milestoneKeepsCriteriaAndCompletionResult()throws Exception{
        long i=iteration(1);
        var input=payload("iterationId",i,"title","功能演示","targetDate",LocalDate.now().plusDays(1),"criteria","完成场景演示","completed",true);
        postAs("student01","milestones",input).andExpect(status().isBadRequest());
        input.put("note","验收场景已逐项核对");
        postAs("student03","milestones",input).andExpect(status().isForbidden());
        postAs("student01","milestones",input).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT completed FROM team_milestone WHERE team_id=?",Boolean.class,team)).isTrue();
    }
    @Test void pastPlannedDatesNeverAutoCloseOrAddLateState()throws Exception{
        long i=iteration(1);
        db.update("UPDATE team_iteration SET planned_start=?,planned_end=? WHERE id=?",LocalDate.now().minusDays(10),LocalDate.now().minusDays(1),i);
        mvc.perform(get("/api/collaboration/teams/"+team).with(user(who("student01")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.iterations[0].status").value("NOT_STARTED"));
    }
    @Test void invalidDatesDuplicateOrderAndCrossTeamObjectsAreRejected()throws Exception{
        long i=iteration(1);
        postAs("student01","iterations",payload("name","重复","goal","目标","sequence",1,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now())).andExpect(status().isBadRequest());
        postAs("student01","iterations",payload("name","日期","goal","目标","sequence",2,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now().minusDays(1))).andExpect(status().isBadRequest());
        long otherIteration=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM team_iteration WHERE team_id<>?",Long.class,team);
        if(otherIteration==0)otherIteration=-1;
        postAs("student01","tasks",payload("iterationId",otherIteration,"requirementId",requirement,"title","跨组","description","非法","assigneeId",member,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now())).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM team_iteration WHERE team_id=?",Long.class,team)).isEqualTo(1);
    }
    @Test void terminatedArchivedEndedAndDeadlinePassedProjectsAreReadOnly()throws Exception{
        for(String state:List.of("TERMINATED","ARCHIVED","ENDED","DRAFT")) {
            db.update("UPDATE course_project SET status=? WHERE id=?",state,project);
            postAs("student01","iterations",payload("name","新增","goal","目标","sequence",1,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
        }
        db.update("UPDATE course_project SET status='IN_PROGRESS',final_deadline=? WHERE id=?",OffsetDateTime.now().minusHours(1),project);
        postAs("student01","iterations",payload()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROJECT_READ_ONLY"));
    }
    @Test void auditFailureRollsBackBusinessHistoryAndRevision()throws Exception{
        long revision=rev();
        AuditService target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("simulated audit failure")).when(target).append(anyLong(),anyLong(),eq("ITERATION_SAVED"),anyString(),anyString());
        postAs("student01","iterations",payload("name","回滚","goal","目标","sequence",1,"plannedStart",LocalDate.now(),"plannedEnd",LocalDate.now())).andExpect(status().isInternalServerError());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM team_iteration WHERE team_id=?",Long.class,team)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM collaboration_event WHERE team_id=?",Long.class,team)).isZero();
        assertThat(rev()).isEqualTo(revision);
    }
}
