package cn.edu.school;

import cn.edu.school.project.ProjectRepository;
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
    "spring.datasource.url=jdbc:h2:mem:projecttest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    "app.storage-root=./target/test-project-files"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ProjectIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectRepository projects;

    @BeforeEach
    void clean() { projects.deleteAll(); }

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

    private static String projectBody(String title) {
        var deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).toString();
        return """
            {"title":"%s","description":"完成一个课程管理系统","deadline":"%s","minTeamSize":1,"maxTeamSize":4,
             "criteria":[{"name":"功能完整性","weight":60,"description":""},{"name":"文档质量","weight":40,"description":""}]}
            """.formatted(title, deadline);
    }

    @Test void teacherPublishesAndStudentSeesIt() throws Exception {
        var teacher = login("teacher01");
        var t = csrf(teacher);
        var created = mvc.perform(post("/api/courses/1/projects").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content(projectBody("课程管理系统")))
            .andExpect(status().isOk()).andReturn();
        var id = json.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
        mvc.perform(post("/api/courses/1/projects/" + id + "/publish").session(teacher).header(t.header(), t.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        var student = login("student01");
        mvc.perform(get("/api/courses/1/projects").session(student))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].title").value("课程管理系统"))
            .andExpect(jsonPath("$.data[0].criteria.length()").value(2));
    }

    @Test void studentCannotSeeDraftNorCreateProject() throws Exception {
        var teacher = login("teacher01");
        var t = csrf(teacher);
        mvc.perform(post("/api/courses/1/projects").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content(projectBody("草稿项目")))
            .andExpect(status().isOk());
        var student = login("student01");
        mvc.perform(get("/api/courses/1/projects").session(student)).andExpect(jsonPath("$.data.length()").value(0));
        var s = csrf(student);
        mvc.perform(post("/api/courses/1/projects").session(student).header(s.header(), s.token())
            .contentType(MediaType.APPLICATION_JSON).content(projectBody("越权项目")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEACHER_REQUIRED"));
    }

    @Test void publishRequiresCompleteCriteriaAndDeadline() throws Exception {
        var teacher = login("teacher01");
        var t = csrf(teacher);
        var incomplete = """
            {"title":"不完整项目","description":"缺评分标准与截止时间","deadline":null,"minTeamSize":1,"maxTeamSize":4,"criteria":[]}
            """;
        var created = mvc.perform(post("/api/courses/1/projects").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content(incomplete)).andExpect(status().isOk()).andReturn();
        var id = json.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
        mvc.perform(post("/api/courses/1/projects/" + id + "/publish").session(teacher).header(t.header(), t.token()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PROJECT"));
    }

    @Test void outsiderCannotReadProjectsOfOtherCourse() throws Exception {
        var teacher = login("teacher01");
        var t = csrf(teacher);
        var created = mvc.perform(post("/api/courses/1/projects").session(teacher).header(t.header(), t.token())
            .contentType(MediaType.APPLICATION_JSON).content(projectBody("课程内项目")))
            .andExpect(status().isOk()).andReturn();
        var id = json.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
        mvc.perform(post("/api/courses/1/projects/" + id + "/publish").session(teacher).header(t.header(), t.token()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/courses/2/projects").session(login("student02"))).andExpect(jsonPath("$.data.length()").value(0));
    }
}
