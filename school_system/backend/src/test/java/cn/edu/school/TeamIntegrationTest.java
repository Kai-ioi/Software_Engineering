package cn.edu.school;

import cn.edu.school.auth.UserRepository;
import cn.edu.school.project.ProjectRepository;
import cn.edu.school.team.TeamMemberRepository;
import cn.edu.school.team.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:teamtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    "app.storage-root=./target/test-team-files"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class TeamIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectRepository projects;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository members;
    @Autowired UserRepository users;

    @BeforeEach
    void clean() { members.deleteAll(); teams.deleteAll(); projects.deleteAll(); }

    record CsrfSession(MockHttpSession session, String header, String token) {}

    private CsrfSession csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        return new CsrfSession((MockHttpSession) result.getRequest().getSession(), data.get("headerName").asText(), data.get("token").asText());
    }

    private MockHttpSession login(String username) throws Exception {
        var token = csrf(null);
        var result = mvc.perform(post("/api/auth/login").session(token.session())
            .header(token.header(), token.token()).param("username", username).param("password", "Demo@2026!"))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    private long publishProject(int min, int max) throws Exception {
        var teacher = login("teacher01");
        var t = csrf(teacher);
        var deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).toString();
        var body = """
            {"title":"组队项目","description":"完成组队","deadline":"%s","minTeamSize":%d,"maxTeamSize":%d,
             "criteria":[{"name":"功能完整性","weight":100,"description":""}]}
            """.formatted(deadline, min, max);
        var created = mvc.perform(post("/api/courses/1/projects").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        var id = json.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
        mvc.perform(post("/api/courses/1/projects/" + id + "/publish").session(teacher).header(t.header(), t.token()))
            .andExpect(status().isOk());
        return id;
    }

    private String teamPath(long projectId, long teamId) { return "/api/courses/1/projects/" + projectId + "/teams/" + teamId; }

    private long createTeam(MockHttpSession session, CsrfSession c, long projectId, String name) throws Exception {
        var result = mvc.perform(post("/api/courses/1/projects/" + projectId + "/teams").session(session)
            .header(c.header(), c.token()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private void apply(MockHttpSession session, CsrfSession c, long projectId, long teamId) throws Exception {
        mvc.perform(post(teamPath(projectId, teamId) + "/apply").session(session).header(c.header(), c.token()))
            .andExpect(status().isOk());
    }

    private long pendingMemberId(MockHttpSession session, long projectId, long teamId) throws Exception {
        var result = mvc.perform(get(teamPath(projectId, teamId)).session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("data").get("applications").get(0).get("id").asLong();
    }

    private long userId(String username) { return users.findByUsername(username).orElseThrow().getId(); }

    @Test void studentCreatesTeamBecomesLeader() throws Exception {
        long projectId = publishProject(1, 4);
        var student = login("student01");
        long teamId = createTeam(student, csrf(student), projectId, "飞虎队");
        mvc.perform(get(teamPath(projectId, teamId)).session(student))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("飞虎队"))
            .andExpect(jsonPath("$.data.leaderId").value(userId("student01")))
            .andExpect(jsonPath("$.data.activeCount").value(1))
            .andExpect(jsonPath("$.data.members.length()").value(1))
            .andExpect(jsonPath("$.data.canManage").value(true));
    }

    @Test void applyAndApprove() throws Exception {
        long projectId = publishProject(1, 4);
        var student = login("student01");
        var s = csrf(student);
        long teamId = createTeam(student, s, projectId, "飞虎队");
        var assistant = login("assistant01");
        apply(assistant, csrf(assistant), projectId, teamId);
        long memberId = pendingMemberId(student, projectId, teamId);
        mvc.perform(post(teamPath(projectId, teamId) + "/members/" + memberId + "/approve").session(student).header(s.header(), s.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.activeCount").value(2))
            .andExpect(jsonPath("$.data.members.length()").value(2))
            .andExpect(jsonPath("$.data.applications.length()").value(0));
    }

    @Test void draftProjectRejectsGrouping() throws Exception {
        var teacher = login("teacher01");
        var t = csrf(teacher);
        var deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).toString();
        var body = """
            {"title":"草稿","description":"未发布","deadline":"%s","minTeamSize":1,"maxTeamSize":4,
             "criteria":[{"name":"功能","weight":100,"description":""}]}
            """.formatted(deadline);
        var created = mvc.perform(post("/api/courses/1/projects").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        var projectId = json.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
        var student = login("student01");
        var s = csrf(student);
        mvc.perform(post("/api/courses/1/projects/" + projectId + "/teams").session(student).header(s.header(), s.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"队伍\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TEAM"));
    }

    @Test void transferLeadership() throws Exception {
        long projectId = publishProject(1, 4);
        var student = login("student01");
        var s = csrf(student);
        long teamId = createTeam(student, s, projectId, "飞虎队");
        var assistant = login("assistant01");
        apply(assistant, csrf(assistant), projectId, teamId);
        long memberId = pendingMemberId(student, projectId, teamId);
        mvc.perform(post(teamPath(projectId, teamId) + "/members/" + memberId + "/approve").session(student).header(s.header(), s.token()))
            .andExpect(status().isOk());
        long assistantId = userId("assistant01");
        mvc.perform(post(teamPath(projectId, teamId) + "/transfer-leadership").session(student).header(s.header(), s.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"newLeaderUserId\":" + assistantId + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.leaderId").value(assistantId));
    }

    @Test void teacherTransfersMemberBetweenTeams() throws Exception {
        long projectId = publishProject(1, 4);
        var student = login("student01");
        var s = csrf(student);
        long teamA = createTeam(student, s, projectId, "A队");
        var assistant = login("assistant01");
        apply(assistant, csrf(assistant), projectId, teamA);
        long memberId = pendingMemberId(student, projectId, teamA);
        mvc.perform(post(teamPath(projectId, teamA) + "/members/" + memberId + "/approve").session(student).header(s.header(), s.token()))
            .andExpect(status().isOk());
        var teacher = login("teacher01");
        var t = csrf(teacher);
        long teamB = createTeam(teacher, t, projectId, "B队");
        mvc.perform(post(teamPath(projectId, teamA) + "/members/" + memberId + "/transfer").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetTeamId\":" + teamB + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(teamB))
            .andExpect(jsonPath("$.data.activeCount").value(2));
        mvc.perform(get(teamPath(projectId, teamA)).session(student))
            .andExpect(jsonPath("$.data.activeCount").value(1));
    }

    @Test void outsiderCannotAccessTeams() throws Exception {
        long projectId = publishProject(1, 4);
        var outsider = login("student02");
        mvc.perform(get("/api/courses/1/projects/" + projectId + "/teams").session(outsider))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("COURSE_FORBIDDEN"));
    }
}
