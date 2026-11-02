package cn.edu.school.project;

import cn.edu.school.audit.AuditService;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
public class ProjectService {
    private static final int TITLE_MAX = 160;
    private static final int DESCRIPTION_MAX = 4000;
    private static final int TEAM_SIZE_MAX = 30;
    private static final int CRITERION_MAX = 20;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ProjectRepository projects;
    private final CoursePermission permission;
    private final AuditService audit;

    public ProjectService(ProjectRepository projects, CoursePermission permission, AuditService audit) {
        this.projects = projects;
        this.permission = permission;
        this.audit = audit;
    }

    public List<ProjectView> list(Long userId, Long courseId) {
        var role = permission.requireMember(userId, courseId);
        var all = role == CourseRole.TEACHER
            ? projects.findByCourseIdOrderByCreatedAtDesc(courseId)
            : projects.findByCourseIdAndStatusNotOrderByCreatedAtDesc(courseId, ProjectStatus.DRAFT);
        return all.stream().map(p -> view(p, role)).toList();
    }

    public ProjectView detail(Long userId, Long courseId, Long projectId) {
        var role = permission.requireMember(userId, courseId);
        var project = find(courseId, projectId);
        if (project.getStatus() == ProjectStatus.DRAFT && role != CourseRole.TEACHER)
            throw new BusinessException(HttpStatus.FORBIDDEN, "PROJECT_FORBIDDEN", "草稿仅教师可见");
        return view(project, role);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView create(Long userId, Long courseId, ProjectInput input) {
        permission.requireTeacher(userId, courseId);
        validateCommon(input);
        var project = new Project(courseId, input.title().trim(), input.description().trim(),
            input.deadline(), input.minTeamSize(), input.maxTeamSize());
        applyCriteria(project, input.criteria(), false);
        projects.save(project);
        audit.append(userId, courseId, "PROJECT_CREATED", project.getId().toString(), "创建项目草稿「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView update(Long userId, Long courseId, Long projectId, ProjectInput input) {
        permission.requireTeacher(userId, courseId);
        validateCommon(input);
        var project = find(courseId, projectId);
        project.update(input.title().trim(), input.description().trim(), input.deadline(),
            input.minTeamSize(), input.maxTeamSize());
        applyCriteria(project, input.criteria(), false);
        audit.append(userId, courseId, "PROJECT_UPDATED", project.getId().toString(), "修改项目「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView publish(Long userId, Long courseId, Long projectId) {
        permission.requireTeacher(userId, courseId);
        var project = find(courseId, projectId);
        if (project.getStatus() != ProjectStatus.DRAFT)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOT_DRAFT", "只有草稿可以发布");
        validateForPublish(project);
        project.publish();
        audit.append(userId, courseId, "PROJECT_PUBLISHED", project.getId().toString(), "发布项目「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView close(Long userId, Long courseId, Long projectId) {
        permission.requireTeacher(userId, courseId);
        var project = find(courseId, projectId);
        if (project.getStatus() != ProjectStatus.PUBLISHED)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOT_PUBLISHED", "只有已发布的项目可以终止");
        project.close();
        audit.append(userId, courseId, "PROJECT_CLOSED", project.getId().toString(), "终止项目「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView archive(Long userId, Long courseId, Long projectId) {
        permission.requireTeacher(userId, courseId);
        var project = find(courseId, projectId);
        if (project.getStatus() == ProjectStatus.ARCHIVED)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ALREADY_ARCHIVED", "项目已归档");
        project.archive();
        audit.append(userId, courseId, "PROJECT_ARCHIVED", project.getId().toString(), "归档项目「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    private Project find(Long courseId, Long projectId) {
        var project = projects.findById(projectId)
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));
        if (!project.getCourseId().equals(courseId))
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        return project;
    }

    private void validateCommon(ProjectInput input) {
        if (input.title() == null || input.title().isBlank()) throw bad("项目名称不能为空");
        if (input.title().length() > TITLE_MAX) throw bad("项目名称不超过" + TITLE_MAX + "字");
        if (input.description() == null || input.description().isBlank()) throw bad("项目要求不能为空");
        if (input.description().length() > DESCRIPTION_MAX) throw bad("项目要求不超过" + DESCRIPTION_MAX + "字");
        if (input.minTeamSize() == null || input.minTeamSize() < 1 || input.minTeamSize() > TEAM_SIZE_MAX)
            throw bad("团队最少人数须在1到" + TEAM_SIZE_MAX + "之间");
        if (input.maxTeamSize() == null || input.maxTeamSize() < 1 || input.maxTeamSize() > TEAM_SIZE_MAX)
            throw bad("团队最多人数须在1到" + TEAM_SIZE_MAX + "之间");
        if (input.minTeamSize() > input.maxTeamSize()) throw bad("团队最少人数不能大于最多人数");
    }

    private void applyCriteria(Project project, List<ProjectInput.CriterionInput> criteria, boolean requireComplete) {
        var list = criteria == null ? List.<ProjectInput.CriterionInput>of() : criteria;
        if (list.size() > CRITERION_MAX) throw bad("评分标准最多" + CRITERION_MAX + "条");
        var next = new ArrayList<ProjectCriterion>();
        int sum = 0;
        for (var c : list) {
            if (c.name() == null || c.name().isBlank() || c.name().length() > 160) throw bad("评分标准名称不能为空且不超过160字");
            if (c.weight() == null || c.weight() < 0 || c.weight() > 100) throw bad("评分标准权重须在0到100之间");
            sum += c.weight();
            next.add(new ProjectCriterion(project, c.name().trim(), c.weight(), c.description()));
        }
        if (requireComplete && next.isEmpty()) throw bad("请至少配置一条评分标准");
        if (requireComplete && sum != 100) throw bad("评分标准权重之和须为100");
        project.replaceCriteria(next);
    }

    private void validateForPublish(Project project) {
        if (project.getDeadline() == null) throw bad("请先设置项目截止时间");
        if (!project.getDeadline().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) throw bad("截止时间须晚于当前时间");
        var criteria = project.getCriteria();
        if (criteria.isEmpty()) throw bad("请至少配置一条评分标准");
        if (criteria.stream().mapToInt(ProjectCriterion::getWeight).sum() != 100) throw bad("评分标准权重之和须为100");
    }

    private BusinessException bad(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROJECT", message);
    }

    private ProjectView view(Project p, CourseRole role) {
        return new ProjectView(p.getId(), p.getCourseId(), p.getTitle(), p.getDescription(),
            shift(p.getDeadline()), p.getMinTeamSize(), p.getMaxTeamSize(), p.getStatus(),
            shift(p.getPublishedAt()), shift(p.getCreatedAt()), shift(p.getUpdatedAt()), role,
            p.getCriteria().stream().map(c -> new CriterionView(c.getId(), c.getName(), c.getWeight(), c.getDescription())).toList());
    }

    private OffsetDateTime shift(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime();
    }

    public record ProjectInput(String title, String description, OffsetDateTime deadline,
                               Integer minTeamSize, Integer maxTeamSize, List<CriterionInput> criteria) {
        public record CriterionInput(String name, Integer weight, String description) {}
    }
    public record CriterionView(Long id, String name, int weight, String description) {}
    public record ProjectView(Long id, Long courseId, String title, String description,
                              OffsetDateTime deadline, int minTeamSize, int maxTeamSize,
                              ProjectStatus status, OffsetDateTime publishedAt,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt,
                              CourseRole myRole, List<CriterionView> criteria) {}
}