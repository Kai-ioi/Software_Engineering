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
    private final ProjectScoringService scoring;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public ProjectService(ProjectRepository projects, CoursePermission permission, AuditService audit, ProjectScoringService scoring, org.springframework.jdbc.core.JdbcTemplate db) {
        this.projects = projects;
        this.permission = permission;
        this.audit = audit; this.scoring=scoring; this.db=db;
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
        permission.requireActiveCourse(courseId);
        validateCommon(input);
        var project = new Project(courseId, input.title().trim(), input.description().trim(),
            input.deadline(), input.minTeamSize(), input.maxTeamSize());
        project.weights(input.teacherWeight()==null?project.getTeacherWeight():input.teacherWeight(),input.peerWeight()==null?project.getPeerWeight():input.peerWeight());
        applyCriteria(project, input.criteria(), false);
        projects.saveAndFlush(project);
        syncRequirement(project);
        audit.append(userId, courseId, "PROJECT_CREATED", project.getId().toString(), "创建项目草稿「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView update(Long userId, Long courseId, Long projectId, ProjectInput input) {
        permission.requireTeacher(userId, courseId);
        validateCommon(input);
        var project = find(courseId, projectId);
        if(project.getStatus()==ProjectStatus.TERMINATED||project.getStatus()==ProjectStatus.ARCHIVED||project.getStatus()==ProjectStatus.ENDED) throw bad("当前项目状态不允许修改要求");
        if(project.getDeadline()!=null&&!project.getDeadline().isAfter(OffsetDateTime.now())&&!java.util.Objects.equals(project.getDeadline(),input.deadline()))throw bad("截止后不能修改原截止时间，请使用限时补交");
        if(project.getStatus()!=ProjectStatus.DRAFT && input.deadline()==null)throw bad("已发布项目必须保留截止时间");
        if(project.getExplicitGroupingClose()!=null&&(input.deadline()==null||input.deadline().isBefore(project.getExplicitGroupingClose())))
            throw bad("最终截止不得早于已设置的组队截止，请先调整组队窗口");
        var oldTitle=project.getTitle();
        var oldDescription=project.getDescription();
        var oldDeadline=project.getDeadline();
        project.update(input.title().trim(), input.description().trim(), input.deadline(),
            input.minTeamSize(), input.maxTeamSize());
        project.weights(input.teacherWeight()==null?project.getTeacherWeight():input.teacherWeight(),input.peerWeight()==null?project.getPeerWeight():input.peerWeight());
        applyCriteria(project, input.criteria(), false);
        projects.flush(); syncRequirement(project);
        if(project.getStatus()!=ProjectStatus.DRAFT){
            scoring.sync(projectId,userId);
            boolean deadlineChanged = oldDeadline == null ? project.getDeadline()!=null
                : project.getDeadline()==null || !oldDeadline.isEqual(project.getDeadline());
            if(!oldTitle.equals(project.getTitle())||!oldDescription.equals(project.getDescription())||deadlineChanged){
                var impacted=db.queryForList("""
                    SELECT DISTINCT t.id FROM project_team t JOIN submission_version v ON v.team_id=t.id WHERE t.project_id=?
                    """,Long.class,projectId);
                String reason=input.changeReason()==null?"":input.changeReason().strip();
                if(reason.length()>2000)throw bad("修改原因不得超过2000字");
                if(!impacted.isEmpty()&&!oldDescription.equals(project.getDescription())){
                    var now=OffsetDateTime.now();
                    if(reason.isBlank()||input.remediationUntil()==null||!input.remediationUntil().isAfter(now)
                        ||input.remediationUntil().isAfter(now.plusDays(7)))throw bad("已有成果时调整要求，须填写原因并设置未来7天内的整改截止");
                }
                var keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
                db.update(connection->{
                    var statement=connection.prepareStatement("""
                    INSERT INTO project_change(project_id,actor_id,summary,before_title,after_title,
                    before_description,after_description,before_deadline,after_deadline,reason,remediation_until) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,new String[]{"id"});
                    Object[] values={projectId,userId,"教师调整项目说明、交付要求或截止时间",oldTitle,project.getTitle(),
                        oldDescription,project.getDescription(),oldDeadline,project.getDeadline(),reason,input.remediationUntil()};
                    for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
                    return statement;
                },keys);
                if(!oldDescription.equals(project.getDescription())){
                    long changeId=java.util.Objects.requireNonNull(keys.getKey()).longValue();
                    for(long team:impacted){
                        db.update("INSERT INTO project_change_team(change_id,team_id) VALUES(?,?)",changeId,team);
                        var now=OffsetDateTime.now();
                        var opens=project.getDeadline().isAfter(now)?project.getDeadline():now;
                        if(input.remediationUntil().isAfter(opens))db.update("""
                            INSERT INTO submission_extension(team_id,starts_at,ends_at,reason,granted_by,granted_at)
                            VALUES(?,?,?,?,?,?)
                            """,team,opens,input.remediationUntil(),reason,userId,now);
                    }
                }
                audit.append(userId,courseId,"PROJECT_REQUIREMENTS_CHANGED",projectId.toString(),"项目关键变更");
            }
        }
        audit.append(userId, courseId, "PROJECT_UPDATED", project.getId().toString(), "修改项目「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    public java.util.List<java.util.Map<String,Object>> changes(Long user,Long course,Long project){
        permission.requireMember(user,course);
        detail(user,course,project);
        return db.queryForList("""
            SELECT id,summary,before_title,after_title,before_description,after_description,before_deadline,after_deadline,
                   reason,remediation_until,occurred_at,(SELECT COUNT(*) FROM project_change_team t WHERE t.change_id=c.id) AS affected_teams
            FROM project_change c WHERE project_id=? ORDER BY id DESC
            """,project);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView publish(Long userId, Long courseId, Long projectId) {
        permission.requireTeacher(userId, courseId);
        var project = find(courseId, projectId);
        if (project.getStatus() != ProjectStatus.DRAFT)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOT_DRAFT", "只有草稿可以发布");
        validateForPublish(project);
        project.publish(); projects.flush(); syncRequirement(project); scoring.sync(projectId,userId);
        audit.append(userId, courseId, "PROJECT_PUBLISHED", project.getId().toString(), "发布项目「" + project.getTitle() + "」");
        return view(project, CourseRole.TEACHER);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectView close(Long userId, Long courseId, Long projectId) {
        permission.requireTeacher(userId, courseId);
        var project = find(courseId, projectId);
        if (project.getStatus() != ProjectStatus.PUBLISHED && project.getStatus() != ProjectStatus.IN_PROGRESS && project.getStatus()!=ProjectStatus.ENDED)
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

    private void syncRequirement(Project p){
        var ids=db.queryForList("SELECT id FROM project_requirement WHERE project_id=? ORDER BY id LIMIT 1",Long.class,p.getId());
        if(ids.isEmpty())db.update("INSERT INTO project_requirement(project_id,title,description) VALUES(?,?,?)",p.getId(),p.getTitle(),p.getDescription());
        else db.update("UPDATE project_requirement SET title=?,description=? WHERE id=?",p.getTitle(),p.getDescription(),ids.getFirst());
    }
    @Transactional public ProjectView transition(Long user,Long course,Long id,boolean end){
        permission.requireTeacher(user,course);var p=find(course,id);
        if(p.getStatus()!=(end?ProjectStatus.IN_PROGRESS:ProjectStatus.PUBLISHED))throw bad("项目状态不允许该操作");
        if(end)p.end();else p.start();
        audit.append(user,course,end?"PROJECT_ENDED":"PROJECT_STARTED",id.toString(),end?"结束项目":"开始项目");return view(p,CourseRole.TEACHER);
    }
    private Project find(Long courseId, Long projectId) {
        var project = (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()?projects.findById(projectId):projects.lockById(projectId))
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));
        if (!project.getCourseId().equals(courseId))
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        return project;
    }

    private void validateCommon(ProjectInput input) {
        if(input==null)throw bad("请填写项目内容");
        int tw=input.teacherWeight()==null?70:input.teacherWeight(),pw=input.peerWeight()==null?30:input.peerWeight();
        if(tw<0||pw<0||tw>100||pw>100||tw+pw!=100)throw bad("教师与互评权重之和须为100");
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
            if(c.description()!=null&&c.description().length()>1000)throw bad("评分维度说明不得超过1000字");
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
        return new ProjectView(p.getTeacherWeight(),p.getPeerWeight(),p.getId(), p.getCourseId(), p.getTitle(), p.getDescription(),
            shift(p.getDeadline()), p.getMinTeamSize(), p.getMaxTeamSize(), p.getStatus(),
            shift(p.getPublishedAt()), shift(p.getCreatedAt()), shift(p.getUpdatedAt()), role,
            p.getCriteria().stream().map(c -> new CriterionView(c.getId(), c.getName(), c.getWeight(), c.getDescription())).toList());
    }

    private OffsetDateTime shift(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime();
    }

    public record ProjectInput(String title, String description, OffsetDateTime deadline,
                               Integer minTeamSize, Integer maxTeamSize, List<CriterionInput> criteria,Integer teacherWeight,Integer peerWeight,
                               String changeReason,OffsetDateTime remediationUntil) {
        public ProjectInput(String title,String description,OffsetDateTime deadline,Integer min,Integer max,List<CriterionInput> criteria,Integer teacherWeight,Integer peerWeight){this(title,description,deadline,min,max,criteria,teacherWeight,peerWeight,null,null);}
        public ProjectInput(String title,String description,OffsetDateTime deadline,Integer min,Integer max,List<CriterionInput> criteria){this(title,description,deadline,min,max,criteria,70,30);}
        public record CriterionInput(String name, Integer weight, String description) {}
    }
    public record CriterionView(Long id, String name, int weight, String description) {}
    public record ProjectView(int teacherWeight,int peerWeight,Long id, Long courseId, String title, String description,
                              OffsetDateTime deadline, int minTeamSize, int maxTeamSize,
                              ProjectStatus status, OffsetDateTime publishedAt,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt,
                              CourseRole myRole, List<CriterionView> criteria) {}
}