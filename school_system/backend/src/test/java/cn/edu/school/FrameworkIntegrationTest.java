package cn.edu.school;

import cn.edu.school.audit.AuditRepository;
import cn.edu.school.auth.UserRepository;
import cn.edu.school.file.StoredFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:framework;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    "app.storage-root=./target/test-files"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class FrameworkIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired AuditRepository audits;
    @Autowired JdbcTemplate db;

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

    @Test void anonymousCannotReadCourses() throws Exception {
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test void consoleRejectsNonLoopbackRequests() throws Exception {
        mvc.perform(get("/h2-console/").with(request -> {
            request.setRemoteAddr("203.0.113.10");
            return request;
        })).andExpect(status().isForbidden());
    }

    @Test void loginNeedsCsrf() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "teacher01").param("password", "Demo@2026!"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test void incorrectPasswordIsRejected() throws Exception {
        var token = csrf(null);
        mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(), token.token())
            .param("username", "teacher01").param("password", "wrong"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test void loginKeepsSessionAndNeverReturnsHash() throws Exception {
        var session = login("student01");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("student01"))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        assertThat(users.findByUsername("student01").orElseThrow().getPasswordHash()).startsWith("$2");
    }

    @Test void logoutInvalidatesSession() throws Exception {
        var session = login("teacher01");
        var token = csrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header(token.header(), token.token())).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void rolesAreIndependentAcrossCourses() throws Exception {
        var session = login("teacher01");
        mvc.perform(get("/api/courses").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].myRole").value("TEACHER"))
            .andExpect(jsonPath("$.data[1].myRole").value("STUDENT"));
        mvc.perform(get("/api/courses/1/management").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/courses/2/management").session(session)).andExpect(status().isForbidden());
    }

    @Test void studentCannotCallTeacherEndpointDirectly() throws Exception {
        mvc.perform(get("/api/courses/1/management").session(login("student01")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEACHER_REQUIRED"));
    }

    @Test void courseOutsiderIsDeniedEvenWithKnownId() throws Exception {
        var session = login("student02");
        mvc.perform(get("/api/courses").session(session)).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(2));
        mvc.perform(get("/api/courses/1").session(session)).andExpect(status().isForbidden());
    }

    @Test void platformAdministratorHasNoAutomaticCourseAccess() throws Exception {
        var session = login("admin01");
        mvc.perform(get("/api/courses").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/courses/1").session(session)).andExpect(status().isForbidden());
    }

    @Test void platformAdminAddsStudentsInOneBatchWithoutDuplicatingOrChangingStaff() throws Exception {
        String courseName = "批量选课测试-" + UUID.randomUUID();
        db.update("INSERT INTO course(name,semester,description,status) VALUES(?,?,?,'ACTIVE')", courseName, "测试学期", "");
        long courseId = db.queryForObject("SELECT id FROM course WHERE name=?", Long.class, courseName);
        long teacherId = users.findByUsername("teacher01").orElseThrow().getId();
        long first = users.findByUsername("student01").orElseThrow().getId();
        long second = users.findByUsername("student03").orElseThrow().getId();
        long third = users.findByUsername("student04").orElseThrow().getId();
        var admin = csrf(login("admin01"));
        mvc.perform(post("/api/platform/directory/memberships").session(admin.session())
                .header(admin.header(), admin.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("courseId",courseId,"userId",teacherId,"role","TEACHER","active",true))))
            .andExpect(status().isOk());

        addStudents(admin, courseId, List.of(third, teacherId)).andExpect(status().isBadRequest());
        assertThat(db.queryForList("SELECT role FROM course_membership WHERE course_id=?", String.class, courseId))
            .containsExactly("TEACHER");
        addStudents(csrf(login("teacher01")), courseId, List.of(first)).andExpect(status().isForbidden());

        var students = List.of(first, second, first);
        addStudents(admin, courseId, students).andExpect(status().isOk()).andExpect(jsonPath("$.data.added").value(2));
        addStudents(admin, courseId, students).andExpect(status().isOk()).andExpect(jsonPath("$.data.added").value(0));
        assertThat(db.queryForList("""
            SELECT user_id FROM course_membership WHERE course_id=? AND role='STUDENT' AND active=TRUE
            """, Long.class, courseId)).containsExactlyInAnyOrder(first, second);
        var rosterPath = "/api/courses/" + courseId + "/members";
        mvc.perform(get(rosterPath).session(login("teacher01"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.username == 'student01' && @.role == 'STUDENT')]").isNotEmpty())
            .andExpect(jsonPath("$.data[?(@.username == 'student03' && @.role == 'STUDENT')]").isNotEmpty())
            .andExpect(jsonPath("$.data[?(@.username == 'teacher01' && @.role == 'TEACHER')]").isNotEmpty())
            .andExpect(jsonPath("$.data[0].displayName").isNotEmpty());
        var student = login("student03");
        mvc.perform(get("/api/courses/" + courseId).session(student)).andExpect(status().isOk());
        mvc.perform(get(rosterPath).session(student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/2/members").session(login("teacher01"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/" + courseId).session(login("student04"))).andExpect(status().isForbidden());
        // Keep the shared demo course fixtures used by the other framework tests independent.
        db.update("DELETE FROM course_membership WHERE course_id=?", courseId);
    }

    private org.springframework.test.web.servlet.ResultActions addStudents(CsrfSession auth, long courseId, List<Long> students) throws Exception {
        return mvc.perform(post("/api/platform/directory/memberships/students/batch").session(auth.session())
            .header(auth.header(), auth.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("courseId", courseId, "userIds", students))));
    }

    @Test void auditIsNotVisibleToStudentsOrTeachingAssistants() throws Exception {
        mvc.perform(get("/api/courses/1/audit").session(login("student01"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/1/audit").session(login("assistant01"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/1/audit").session(login("teacher01"))).andExpect(status().isOk());
    }

    @Test void uploadDownloadChecksBytesOwnershipAndAudit() throws Exception {
        var session = login("student01");
        var token = csrf(session);
        byte[] content = "# 课程材料\n框架联调".getBytes(StandardCharsets.UTF_8);
        var result = mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "材料.md", "text/plain", content))
            .param("courseId", "1").session(session).header(token.header(), token.token()))
            .andExpect(status().isOk()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        var id = data.get("id").asText();
        assertThat(data.get("sha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        mvc.perform(get("/api/files/" + id).session(session)).andExpect(status().isOk()).andExpect(content().bytes(content))
            .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get("/api/files/" + id).session(login("teacher01"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/files/" + id)).andExpect(status().isUnauthorized());
        assertThat(audits.findAll().stream().anyMatch(a -> a.getObjectId().equals(id))).isTrue();
        Files.deleteIfExists(Path.of("target/test-files", id + ".bin"));
        files.deleteById(UUID.fromString(id));
    }

    @Test void invalidUploadDoesNotCreateMetadata() throws Exception {
        var session = login("student01");
        var token = csrf(session);
        long before = files.count();
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "payload.html", "text/html", "<script>1</script>".getBytes()))
            .param("courseId", "1").session(session).header(token.header(), token.token()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILE"));
        assertThat(files.count()).isEqualTo(before);
    }

    @Test void traversalFilenameAndCrossCourseUploadAreRejected() throws Exception {
        var session = login("student01");
        var token = csrf(session);
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "../secret.txt", "text/plain", new byte[]{1}))
            .param("courseId", "1").session(session).header(token.header(), token.token())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "report.txt", "text/plain", new byte[]{1}))
            .param("courseId", "2").session(session).header(token.header(), token.token())).andExpect(status().isForbidden());
    }

    @Test void malformedParametersReturnControlledError() throws Exception {
        mvc.perform(get("/api/courses/not-a-number").session(login("teacher01")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
