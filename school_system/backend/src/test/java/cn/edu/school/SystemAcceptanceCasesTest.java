package cn.edu.school;

import cn.edu.school.auth.AppUser;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.auth.UserRepository;
import cn.edu.school.project.ProjectService;
import cn.edu.school.team.CourseTeamService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:acceptance_cases;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.storage-root=./target/acceptance-test-files",
    "app.submission.jobs-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class SystemAcceptanceCasesTest {
    private static final String TEACHER = "teacher01";
    private static final String ASSISTANT = "assistant01";
    private static final String LEADER_A = "student01";
    private static final String MEMBER_A = "student03";
    private static final String OTHER_A = "student05";
    private static final String LEADER_B = "student04";
    private static final String MEMBER_B = "student06";
    private static final String OTHER_B = "acceptance-only";
    private static final String PROJECT_A = "校园失物招领系统需求与设计";
    private static final String PROJECT_B = "实验室预约系统设计";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired ProjectService projects;
    @Autowired CourseTeamService teams;
    @MockitoBean Clock clock;

    private long course;
    private Instant now;
    private OffsetDateTime deadline;

    @BeforeEach
    void prepareCourse() {
        now = Instant.now();
        deadline = now.atOffset(ZoneOffset.UTC).plusDays(2);
        when(clock.instant()).thenAnswer(call -> now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        String name = "独立验收课程 " + UUID.randomUUID();
        db.update("INSERT INTO course(name, semester, description, status, course_team_enabled, required_projects) VALUES (?, '验收学期', '合成测试数据', 'ACTIVE', TRUE, 2)", name);
        course = number("SELECT id FROM course WHERE name=?", name);
        users.saveAndFlush(new AppUser(OTHER_B, "测试组员乙", "unused-by-mock-authentication", false));
        enroll(TEACHER, "TEACHER");
        enroll(ASSISTANT, "TA");
        for (String student : List.of(LEADER_A, MEMBER_A, OTHER_A, LEADER_B, MEMBER_B, OTHER_B)) {
            enroll(student, "STUDENT");
        }
    }

    @Test void case01() throws Exception {
        long project = draft(PROJECT_A);
        send(TEACHER, projectPath(project) + "/publish", object()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        read(MEMBER_A, projectPath()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem((int) project)));
        read(MEMBER_A, projectPath(project)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value(PROJECT_A))
            .andExpect(jsonPath("$.data.description").value("梳理登记、查询、认领核验和归还流程"))
            .andExpect(jsonPath("$.data.deadline").isNotEmpty())
            .andExpect(jsonPath("$.data.criteria[0].weight").value(100));
    }

    @Test void case02() throws Exception {
        long published = publish(PROJECT_A);
        long draft = draft(PROJECT_B);
        read(MEMBER_A, projectPath()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem((int) published)))
            .andExpect(jsonPath("$.data[*].id", not(hasItem((int) draft))));
        read(MEMBER_A, projectPath(draft)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.data.title").doesNotExist());
    }

    @Test void case03() throws Exception {
        long team = data(send(LEADER_A, courseTeams(), object().put("name", "甲队"))
            .andExpect(status().isOk())).asLong();
        var workspace = data(read(LEADER_A, courseTeams()).andExpect(status().isOk()));
        assertThat(workspace.path("myTeamId").asLong()).isEqualTo(team);
        var row = workspace.path("teams").get(0);
        assertThat(row.path("leader_id").asLong()).isEqualTo(id(LEADER_A));
        assertThat(roster(team)).containsExactly(id(LEADER_A));
        read(LEADER_A, courseTeams() + "/" + team + "/candidates").andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].username", hasItem(MEMBER_A)));
    }

    @Test void case04() throws Exception {
        long team = teams.create(id(LEADER_A), course, "甲队");
        for (String member : List.of(MEMBER_A, OTHER_A)) {
            send(LEADER_A, courseTeams() + "/" + team + "/invitations", object().put("studentId", id(member)))
                .andExpect(status().isOk());
            read(member, courseTeams()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitations[0].team_id").value(team));
        }
        assertThat(roster(team)).containsExactly(id(LEADER_A));
        for (String member : List.of(MEMBER_A, OTHER_A)) {
            send(member, courseTeams() + "/" + team + "/answer", object().put("accept", true))
                .andExpect(status().isOk());
        }
        assertThat(roster(team)).containsExactlyInAnyOrder(id(LEADER_A), id(MEMBER_A), id(OTHER_A));
        for (String account : List.of(LEADER_A, MEMBER_A, OTHER_A)) {
            read(account, courseTeams()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teams[0].members.length()").value(3))
                .andExpect(jsonPath("$.data.myTeamId").value(team));
        }
    }

    @Test void case05() throws Exception {
        long a = readyTeam("甲队", LEADER_A, MEMBER_A, OTHER_A);
        long b = teams.create(id(LEADER_B), course, "乙队");
        String url = courseTeams() + "/" + b;
        read(LEADER_B, url + "/candidates").andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].username", hasItem(MEMBER_B)))
            .andExpect(jsonPath("$.data[*].username", not(hasItem(MEMBER_A))));
        send(LEADER_B, url + "/invitations", object().put("studentId", id(MEMBER_A)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COURSE_TEAM_RULE"));
        assertThat(db.queryForList("""
            SELECT m.team_id FROM course_team_member m JOIN course_team t ON t.id=m.team_id
            WHERE t.course_id=? AND m.user_id=? AND m.status='ACTIVE'
            """, Long.class, course, id(MEMBER_A))).containsExactly(a);
        assertThat(db.queryForList("SELECT user_id FROM course_team_member WHERE team_id=?", Long.class, b))
            .containsExactly(id(LEADER_B));
    }

    @Test void case06() throws Exception {
        long team = readyTeam("甲队", LEADER_A, MEMBER_A, OTHER_A);
        long a = publish(PROJECT_A), b = publish(PROJECT_B);
        var selection = object();
        selection.set("projectIds", json.valueToTree(List.of(a, b)));
        send(LEADER_A, courseTeams() + "/" + team + "/projects", selection).andExpect(status().isOk());
        read(LEADER_A, courseTeams()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.teams[0].projects.length()").value(2));
        long teamA = projectTeam(team, a), teamB = projectTeam(team, b);
        assertThat(teamA).isNotEqualTo(teamB);
        for (long projectTeam : List.of(teamA, teamB)) {
            read(MEMBER_A, "/api/collaboration/teams/" + projectTeam).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members[*].id", hasItem((int) id(MEMBER_A))));
            assertThat(db.queryForList("SELECT user_id FROM project_team_member WHERE team_id=? AND active=TRUE", Long.class, projectTeam))
                .containsExactlyInAnyOrder(id(LEADER_A), id(MEMBER_A), id(OTHER_A));
            assertThat(number("SELECT leader_id FROM project_team WHERE id=?", projectTeam)).isEqualTo(id(LEADER_A));
        }
        long iteration = startIteration(teamA);
        task(new Fixture(a, teamA, 0), iteration, MEMBER_A, "项目 A 的任务");
        long material = upload(LEADER_A, teamA, iteration, testPdf());
        submit(LEADER_A, teamA, requestSubmission(teamA, iteration, material));
        read(MEMBER_A, collaboration(teamB)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tasks").isEmpty());
        read(MEMBER_A, submissions(teamB)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.versions").isEmpty());
    }

    @Test void case07() throws Exception {
        var fixture = fixture(false);
        long iteration = startIteration(fixture.a());
        long task = task(fixture, iteration, MEMBER_A, "梳理失物认领核验流程");
        read(MEMBER_A, collaboration(fixture.a())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.iterations[0].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.tasks[0].id").value(task))
            .andExpect(jsonPath("$.data.tasks[0].title").value("梳理失物认领核验流程"))
            .andExpect(jsonPath("$.data.tasks[0].assignee_id").value(id(MEMBER_A)));
        assertThat(number("SELECT requirement_id FROM team_task WHERE id=?", task)).isEqualTo(requirement(fixture.project()));
    }

    @Test void case08() throws Exception {
        var fixture = fixture(false);
        long iteration = startIteration(fixture.a());
        long mine = task(fixture, iteration, MEMBER_A, "我的任务");
        long other = task(fixture, iteration, OTHER_A, "其他组员任务");
        String note = "已梳理认领核验步骤";
        var progress = object().put("status", "IN_PROGRESS").put("note", note);
        send(MEMBER_A, collaboration(fixture.a()) + "/tasks/" + mine + "/progress", revision(fixture.a(), progress))
            .andExpect(status().isOk());
        send(MEMBER_A, collaboration(fixture.a()) + "/tasks/" + other + "/progress", revision(fixture.a(), progress))
            .andExpect(status().isForbidden());
        read(MEMBER_A, collaboration(fixture.a())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tasks[?(@.id==" + mine + ")].status", hasItem("IN_PROGRESS")))
            .andExpect(jsonPath("$.data.tasks[?(@.id==" + mine + ")].progress_note", hasItem(note)))
            .andExpect(jsonPath("$.data.tasks[?(@.id==" + other + ")].status", hasItem("NOT_STARTED")));
    }

    @Test void case09() throws Exception {
        var fixture = fixture(false);
        long iteration = startIteration(fixture.a());
        byte[] pdf = testPdf();
        long material = upload(LEADER_A, fixture.a(), iteration, pdf);
        var version = submit(LEADER_A, fixture.a(), requestSubmission(fixture.a(), iteration, material));
        assertThat(version.path("version_no").asInt()).isEqualTo(1);
        assertThat(version.path("late").asBoolean()).isFalse();
        assertThat(version.path("receipt_code").asText()).isNotBlank();
        assertThat(version.path("accepted_at").asText()).isNotBlank();
        assertThat(version.path("submitter_name").asText()).isEqualTo(who(LEADER_A).displayName());
        long file = version.path("materials").get(0).path("id").asLong();
        read(LEADER_A, submissions(fixture.a()) + "/versions/" + version.path("id").asLong() + "/files/" + file)
            .andExpect(status().isOk()).andExpect(content().bytes(pdf));
    }

    @Test void case10() throws Exception {
        var fixture = fixture(false);
        long iteration = startIteration(fixture.a());
        long material = upload(MEMBER_A, fixture.a(), iteration, testPdf());
        var payload = requestSubmission(fixture.a(), iteration, material);
        read(MEMBER_A, submissions(fixture.a())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canPrepare").value(true)).andExpect(jsonPath("$.data.canSubmit").value(false));
        send(MEMBER_A, submissions(fixture.a()) + "/versions", payload).andExpect(status().isForbidden());
        assertThat(versionDepth(fixture.a())).isEqualTo(List.of(0L, 0L, 0L));
        read(LEADER_A, submissions(fixture.a()) + "/receipts/" + payload.path("requestKey").asText())
            .andExpect(status().isNotFound());
    }

    @Test void case11() throws Exception {
        var fixture = fixture(false);
        long iteration = startIteration(fixture.a());
        long material = upload(LEADER_A, fixture.a(), iteration, testPdf());
        var payload = requestSubmission(fixture.a(), iteration, material);
        var first = submit(LEADER_A, fixture.a(), payload);
        var before = versionDepth(fixture.a());
        assertThat(before).isEqualTo(List.of(1L, 1L, 1L));
        pastDeadline();
        var retry = submit(LEADER_A, fixture.a(), payload);
        for (String field : List.of("id", "accepted_at", "receipt_code")) {
            assertThat(retry.path(field)).isEqualTo(first.path(field));
        }
        assertThat(versionDepth(fixture.a())).isEqualTo(before);
    }

    @Test void case12() throws Exception {
        var fixture = fixture(false);
        long iteration = startIteration(fixture.a());
        long material = upload(LEADER_A, fixture.a(), iteration, testPdf());
        var original = submit(LEADER_A, fixture.a(), requestSubmission(fixture.a(), iteration, material));
        pastDeadline();
        read(LEADER_A, submissions(fixture.a())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canSubmit").value(false));
        send(LEADER_A, submissions(fixture.a()) + "/versions", requestSubmission(fixture.a(), iteration, material))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUBMISSION_WINDOW_CLOSED"));
        read(LEADER_A, submissions(fixture.a()) + "/versions/" + original.path("id").asLong())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.receipt_code").value(original.path("receipt_code").asText()));
        assertThat(versionDepth(fixture.a())).isEqualTo(List.of(1L, 1L, 1L));
    }

    @Test void case13() throws Exception {
        var fixture = fixture(false);
        long version = deliver(fixture.a(), LEADER_A);
        String inspection = "/api/inspections/teams/" + fixture.a();
        mvc.perform(put(inspection + "/assistant-scope/" + id(ASSISTANT)).with(user(who(TEACHER))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"granted\":true}"))
            .andExpect(status().isOk());
        String conclusion = "缺少认领核验异常场景";
        String feedback = "补充证据不一致的反馈与验收条件";
        send(ASSISTANT, inspection, object().put("versionId", version).put("conclusion", conclusion)
            .put("feedback", feedback).put("rectificationRequired", true).put("reported", false))
            .andExpect(status().isOk());
        read(MEMBER_A, inspection).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.inspections[0].version_id").value(version))
            .andExpect(jsonPath("$.data.inspections[0].conclusion").value(conclusion))
            .andExpect(jsonPath("$.data.inspections[0].feedback").value(feedback))
            .andExpect(jsonPath("$.data.inspections[0].rectification_required").value(true));
    }

    @Test void case14() throws Exception {
        var fixture = fixture(false);
        long version = deliver(fixture.a(), LEADER_A);
        pastDeadline();
        bind(fixture.a(), version);
        read(TEACHER, submissions(fixture.a())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.delivery.final_version_id").value(version))
            .andExpect(jsonPath("$.data.delivery.frozen_at").isNotEmpty())
            .andExpect(jsonPath("$.data.reviewBindings[0].version_id").value(version));
    }

    @Test void case15() throws Exception {
        var review = prepareReview();
        var task = myReviewTask(review, LEADER_B);
        assertThat(task.path("team_id").asLong()).isEqualTo(review.fixture().a());
        assertThat(task.path("binding_id").asLong()).isEqualTo(review.binding());
        read(LEADER_B, submissions(review.fixture().a()) + "/versions/" + review.version()).andExpect(status().isOk());
        long before = number("SELECT COUNT(*) FROM review_task WHERE round_id=? AND status='SUBMITTED'", review.round());
        submitReview(review, LEADER_B, task);
        assertThat(number("SELECT COUNT(*) FROM review_task WHERE round_id=? AND status='SUBMITTED'", review.round()))
            .isEqualTo(before + 1);
        assertThat(myReviewTask(review, LEADER_B).path("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test void case16() throws Exception {
        var fixture = fixture(true);
        long secondary = number("SELECT id FROM course_project WHERE course_id=? AND name=?", course, PROJECT_B);
        long fixedA = number("SELECT course_team_id FROM project_team WHERE id=?", fixture.a());
        long fixedB = number("SELECT course_team_id FROM project_team WHERE id=?", fixture.b());
        var otherProject = new Fixture(secondary, projectTeam(fixedA, secondary), projectTeam(fixedB, secondary));
        long versionA = deliver(fixture.a(), LEADER_A), versionB = deliver(fixture.b(), LEADER_B);
        long otherA = deliver(otherProject.a(), LEADER_A), otherB = deliver(otherProject.b(), LEADER_B);
        readyToPublish(prepareReview(otherProject, otherA, otherB));
        publishGrades(otherProject);
        readyToPublish(prepareReview(fixture, versionA, versionB));

        JsonNode team = publishedSummary(fixture.a());
        assertThat(team.path("published").isNull() || team.path("published").isMissingNode()).isTrue();
        read(MEMBER_A, "/api/grades/my").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[?(@.project_id==" + fixture.project() + ")]").isEmpty())
            .andExpect(jsonPath("$.data.results[*].project_id", hasItem((int) secondary)));
    }

    @Test void case17() throws Exception {
        var fixture = readyToPublish();
        publishGrades(fixture);
        var team = publishedSummary(fixture.a());
        assertThat(team.path("teacher_total").asDouble()).isEqualTo(90.0);
        assertThat(team.path("peer_avg").asDouble()).isEqualTo(80.0);
        var published = team.path("published");
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("final_total").asDouble()).isEqualTo(87.0);
        read(TEACHER, "/api/grades/results/" + published.path("id").asLong() + "/evidence")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.evaluated_version_no").value(1));
        assertThat(number("SELECT COUNT(*) FROM grade_result WHERE team_id=?", fixture.b())).isZero();
    }

    @Test void case18() throws Exception {
        var fixture = readyToPublish();
        publishGrades(fixture);
        read(MEMBER_A, "/api/grades/my").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].project_id").value(fixture.project()))
            .andExpect(jsonPath("$.data.results[0].team_id").value(fixture.a()))
            .andExpect(jsonPath("$.data.results[0].final_total").value(87.0))
            .andExpect(jsonPath("$.data.results[0].status").value("PUBLISHED"));
        read(MEMBER_B, "/api/grades/my").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[?(@.project_id==" + fixture.project() + ")]").isEmpty());
    }

    private record Fixture(long project, long a, long b) {}
    private record ReviewFixture(Fixture fixture, long round, long binding, long version) {}

    private Fixture fixture(boolean twoTeams) {
        long target = publish(PROJECT_A), secondary = publish(PROJECT_B);
        long teamA = readyTeam("甲队", LEADER_A, MEMBER_A, OTHER_A);
        teams.select(id(LEADER_A), course, teamA, List.of(target, secondary));
        long teamB = 0;
        if (twoTeams) {
            long fixedB = readyTeam("乙队", LEADER_B, MEMBER_B, OTHER_B);
            teams.select(id(LEADER_B), course, fixedB, List.of(target, secondary));
            teamB = projectTeam(fixedB, target);
        }
        return new Fixture(target, projectTeam(teamA, target), teamB);
    }

    private ReviewFixture prepareReview() throws Exception {
        var fixture = fixture(true);
        return prepareReview(fixture, deliver(fixture.a(), LEADER_A), deliver(fixture.b(), LEADER_B));
    }

    private ReviewFixture prepareReview(Fixture fixture, long versionA, long versionB) throws Exception {
        pastDeadline();
        long bindingA = bind(fixture.a(), versionA);
        bind(fixture.b(), versionB);
        var round = object().put("name", "验收互评").put("deadline", deadline.plusDays(1).toString());
        long roundId = data(send(TEACHER, "/api/reviews/projects/" + fixture.project() + "/rounds", round)
            .andExpect(status().isOk())).path("id").asLong();
        send(TEACHER, "/api/reviews/rounds/" + roundId + "/assign", object()).andExpect(status().isOk());
        var result = new ReviewFixture(fixture, roundId, bindingA, versionA);
        assertThat(myReviewTask(result, LEADER_B).path("team_id").asLong()).isEqualTo(fixture.a());
        return result;
    }

    private JsonNode myReviewTask(ReviewFixture review, String reviewer) throws Exception {
        var tasks = data(read(reviewer, "/api/reviews/projects/" + review.fixture().project()).andExpect(status().isOk()))
            .path("myTasks");
        assertThat(tasks.size()).isEqualTo(1);
        return tasks.get(0);
    }

    private void submitReview(ReviewFixture review, String reviewer, JsonNode task) throws Exception {
        long criterion = number("SELECT c.id FROM grading_criterion c JOIN review_round r ON r.rubric_id=c.rubric_id WHERE r.id=?", review.round());
        var input = object().put("comment", "流程完整，建议补充异常案例");
        input.putArray("scores").addObject().put("criterionId", criterion).put("score", 80);
        String path = "/api/reviews/tasks/" + task.path("id").asLong();
        send(reviewer, path + "/start", object()).andExpect(status().isOk());
        send(reviewer, path + "/submit", input).andExpect(status().isOk());
    }

    private Fixture readyToPublish() throws Exception {
        return readyToPublish(prepareReview());
    }

    private Fixture readyToPublish(ReviewFixture review) throws Exception {
        for (String reviewer : List.of(LEADER_B, MEMBER_B, OTHER_B)) {
            submitReview(review, reviewer, myReviewTask(review, reviewer));
        }
        var fixture = review.fixture();
        String path = "/api/grades/teams/" + fixture.a();
        var sheet = data(read(TEACHER, path).andExpect(status().isOk()));
        var score = object().put("rubricId", sheet.path("rubric").path("id").asLong());
        score.putArray("scores").addObject().put("criterionId", sheet.path("criteria").get(0).path("id").asLong()).put("score", 90);
        send(TEACHER, path + "/submit", score).andExpect(status().isOk());
        send(TEACHER, path + "/roster", object()).andExpect(status().isOk());
        return fixture;
    }

    private JsonNode publishedSummary(long team) throws Exception {
        var rows = data(read(TEACHER, "/api/grades/courses/" + course + "/summary").andExpect(status().isOk())).path("teams");
        for (JsonNode row : rows) {
            if (row.path("team_id").asLong() == team) return row;
        }
        throw new AssertionError("成绩汇总缺少目标队伍");
    }

    private void publishGrades(Fixture fixture) throws Exception {
        send(TEACHER, "/api/grades/courses/" + course + "/publish",
            object().put("reason", "核对计分名单后发布").put("teamId", fixture.a()).put("projectId", fixture.project()))
            .andExpect(status().isOk());
    }

    private long deliver(long team, String leader) throws Exception {
        long iteration = startIteration(team, leader);
        long material = upload(leader, team, iteration, testPdf());
        long version = submit(leader, team, requestSubmission(team, iteration, material)).path("id").asLong();
        send(leader, submissions(team) + "/final-selection", revision(team, object().put("versionId", version)))
            .andExpect(status().isOk());
        return version;
    }

    private long bind(long team, long version) throws Exception {
        var input = object().put("versionId", version).put("reason", "已核对成果材料").put("accessibleConfirmed", true);
        return data(send(TEACHER, submissions(team) + "/review-bindings", revision(team, input))
            .andExpect(status().isOk())).path("id").asLong();
    }

    private long startIteration(long team) throws Exception { return startIteration(team, LEADER_A); }

    private long startIteration(long team, String leader) throws Exception {
        LocalDate date = now.atZone(ZoneOffset.UTC).toLocalDate();
        var plan = object().put("name", "第1轮·需求与流程").put("goal", "完成业务流程梳理").put("sequence", 1)
            .put("plannedStart", date.toString()).put("plannedEnd", date.plusDays(1).toString());
        var path = collaboration(team) + "/iterations";
        long iteration = data(send(leader, path, revision(team, plan)).andExpect(status().isOk())).path("id").asLong();
        send(leader, path + "/" + iteration + "/start", revision(team, object())).andExpect(status().isOk());
        return iteration;
    }

    private long task(Fixture fixture, long iteration, String assignee, String title) throws Exception {
        LocalDate date = now.atZone(ZoneOffset.UTC).toLocalDate();
        var input = object().put("iterationId", iteration).put("requirementId", requirement(fixture.project()))
            .put("title", title).put("description", "交付操作流程与验收条件").put("assigneeId", id(assignee))
            .put("plannedStart", date.toString()).put("plannedEnd", date.plusDays(1).toString());
        return data(send(LEADER_A, collaboration(fixture.a()) + "/tasks", revision(fixture.a(), input))
            .andExpect(status().isOk())).path("id").asLong();
    }

    private long upload(String account, long team, long iteration, byte[] bytes) throws Exception {
        var file = new MockMultipartFile("file", "失物招领需求说明.pdf", "application/pdf", bytes);
        return data(mvc.perform(multipart(submissions(team) + "/materials/files").file(file)
                .param("iterationId", Long.toString(iteration)).param("revision", Long.toString(revision(team)))
                .with(user(who(account))).with(csrf()))
            .andExpect(status().isOk())).path("id").asLong();
    }

    private ObjectNode requestSubmission(long team, long iteration, long material) {
        var input = object().put("iterationId", iteration).put("summary", "失物认领需求设计")
            .put("requestKey", UUID.randomUUID().toString());
        input.putArray("materialIds").add(material);
        return revision(team, input);
    }

    private JsonNode submit(String account, long team, ObjectNode input) throws Exception {
        return data(send(account, submissions(team) + "/versions", input).andExpect(status().isOk()));
    }

    private List<Long> versionDepth(long team) {
        return List.of(number("SELECT COUNT(*) FROM submission_version WHERE team_id=?", team),
            number("SELECT COUNT(*) FROM submission_version_material m JOIN submission_version v ON v.id=m.version_id WHERE v.team_id=?", team),
            number("SELECT COUNT(*) FROM submission_event WHERE team_id=? AND action='VERSION_SUBMITTED'", team));
    }

    // 生成可打开的一页合成 PDF，用于核对上传及下载的原始字节。
    private byte[] testPdf() {
        var articles = List.of("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> >>");
        var pdf = new StringBuilder("%PDF-1.4\n");
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < articles.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(articles.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 4\n0000000000 65535 f \n");
        for (int offset : offsets) pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        pdf.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private long readyTeam(String name, String leader, String... members) {
        long team = teams.create(id(leader), course, name);
        for (String member : members) {
            teams.invite(id(leader), course, team, id(member));
            teams.answer(id(member), course, team, true);
        }
        return team;
    }

    private long draft(String title) {
        var criteria = List.of(new ProjectService.ProjectInput.CriterionInput("业务与实现", 100, "系统规则和验收条件"));
        return projects.create(id(TEACHER), course, new ProjectService.ProjectInput(title,
            "梳理登记、查询、认领核验和归还流程", deadline, 3, 5, criteria, 70, 30)).id();
    }

    private long publish(String title) {
        long project = draft(title);
        projects.publish(id(TEACHER), course, project);
        return project;
    }

    private void enroll(String username, String role) {
        db.update("INSERT INTO course_membership(course_id, user_id, role, active) VALUES (?, ?, ?, TRUE)", course, id(username), role);
    }

    private List<Long> roster(long team) {
        return db.queryForList("SELECT user_id FROM course_team_member WHERE team_id=? AND status='ACTIVE'", Long.class, team);
    }

    private long projectTeam(long fixedTeam, long project) {
        return number("SELECT id FROM project_team WHERE course_team_id=? AND project_id=?", fixedTeam, project);
    }

    private long requirement(long project) { return number("SELECT id FROM project_requirement WHERE project_id=?", project); }
    private long revision(long team) { return number("SELECT revision FROM project_team WHERE id=?", team); }
    private ObjectNode revision(long team, ObjectNode input) { return input.put("revision", revision(team)); }
    private long number(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    private void pastDeadline() { now = deadline.toInstant().plusSeconds(1); }
    private long id(String name) { return who(name).id(); }
    private UserPrincipal who(String name) { return UserPrincipal.from(users.findByUsername(name).orElseThrow()); }
    private ObjectNode object() { return json.createObjectNode(); }
    private String courseTeams() { return "/api/courses/" + course + "/teams"; }
    private String projectPath() { return "/api/courses/" + course + "/projects"; }
    private String projectPath(long project) { return projectPath() + "/" + project; }
    private String collaboration(long team) { return "/api/collaboration/teams/" + team; }
    private String submissions(long team) { return "/api/submissions/teams/" + team; }

    private ResultActions read(String account, String path) throws Exception {
        return mvc.perform(get(path).with(user(who(account))));
    }

    private ResultActions send(String account, String path, ObjectNode payload) throws Exception {
        return mvc.perform(post(path).with(user(who(account))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(payload)));
    }

    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray()).path("data");
    }
}
