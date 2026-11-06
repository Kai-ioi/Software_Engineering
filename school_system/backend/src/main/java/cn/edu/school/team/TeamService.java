package cn.edu.school.team;

import cn.edu.school.audit.AuditService;
import cn.edu.school.auth.AppUser;
import cn.edu.school.auth.UserRepository;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import cn.edu.school.project.Project;
import cn.edu.school.project.ProjectRepository;
import cn.edu.school.project.ProjectStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
public class TeamService {
    private static final int NAME_MAX = 160;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final CoursePermission permission;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public TeamService(TeamRepository teams, TeamMemberRepository members, CoursePermission permission,
                       ProjectRepository projects, UserRepository users, AuditService audit, org.springframework.jdbc.core.JdbcTemplate db) {
        this.teams = teams;
        this.members = members;
        this.permission = permission;
        this.projects = projects;
        this.users = users;
        this.audit = audit;
        this.db = db;
    }

    public List<TeamView> list(Long userId, Long courseId, Long projectId) {
        var role = permission.requireMember(userId, courseId);
        var project = findProject(courseId, projectId);
        var all = teams.findByProjectIdOrderByCreatedAtAsc(projectId);
        if(role==CourseRole.TA){
            var allowed=db.queryForList("SELECT team_id FROM assistant_team_scope WHERE user_id=?",Long.class,userId);
            all=all.stream().filter(t->allowed.contains(t.getId())).toList();
        }
        var ids = all.stream().map(Team::getId).toList();
        var memberships = ids.isEmpty() ? List.<TeamMember>of() : members.findByTeamIdInOrderByCreatedAtAsc(ids);
        var byTeam = memberships.stream().collect(Collectors.groupingBy(TeamMember::getTeamId));
        var names = resolveNames(all, memberships);
        return all.stream().map(t -> view(t, project, role, userId, byTeam.getOrDefault(t.getId(), List.of()), names)).toList();
    }

    public TeamView detail(Long userId, Long courseId, Long projectId, Long teamId) {
        var role = permission.requireMember(userId, courseId);
        var project = findProject(courseId, projectId);
        var team = findTeam(projectId, teamId);
        permission.requireTeamScope(role,userId,teamId);
        var memberships = members.findByTeamIdOrderByCreatedAtAsc(teamId);
        var names = resolveNames(List.of(team), memberships);
        return view(team, project, role, userId, memberships, names);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView create(Long userId, Long courseId, Long projectId, CreateInput input) {
        requireStudent(userId, courseId);
        var project = requireGroupingOpen(courseId, projectId);
        var name = validateName(input == null ? null : input.name());
        ensureNotInTeam(userId, projectId, null);
        var team = new Team(projectId, name, userId);
        if(teams.findByProjectIdOrderByCreatedAtAsc(projectId).stream().anyMatch(t->t.getName().equals(name))) throw bad("同一项目内团队名称不能重复");
        teams.saveAndFlush(team);
        members.save(new TeamMember(team.getId(), userId, MemberStatus.ACTIVE));
        audit.append(userId, courseId, "TEAM_CREATED", team.getId().toString(), "创建团队「" + name + "」");
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, team.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView apply(Long userId, Long courseId, Long projectId, Long teamId) {
        requireStudent(userId, courseId);
        requireGroupingOpen(courseId, projectId);
        var team = findTeam(projectId, teamId);
        ensureNotInTeam(userId, projectId, teamId);
        var existing = members.findByTeamIdAndUserId(teamId, userId);
        if (existing.isPresent()) {
            var m = existing.get();
            if (m.getStatus() == MemberStatus.ACTIVE) throw bad("你已在该团队中");
            if (m.getStatus() == MemberStatus.PENDING) throw bad("你已申请加入，请等待审批");
            m.reapply();
        } else {
            members.save(new TeamMember(teamId, userId, MemberStatus.PENDING));
        }
        audit.append(userId, courseId, "TEAM_APPLIED", team.getId().toString(), "申请加入团队「" + team.getName() + "」");
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView approve(Long userId, Long courseId, Long projectId, Long teamId, Long memberId) {
        permission.requireMember(userId, courseId);
        var project = findProject(courseId, projectId);
        requireMembershipChangesAllowed(findProject(courseId, projectId));
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        var member = findMember(teamId, memberId);
        requireGroupingOpen(courseId,projectId);
        if (member.getStatus() != MemberStatus.PENDING) throw bad("该申请已处理或已失效");
        ensureNotInTeam(member.getUserId(), projectId, teamId);
        if (members.countByTeamIdAndStatus(teamId, MemberStatus.ACTIVE) >= project.getMaxTeamSize())
            throw bad("团队人数已达上限，无法通过该申请");
        member.approve();
        team.touch();
        audit.append(userId, courseId, "TEAM_APPROVED", team.getId().toString(), "通过成员 #" + member.getUserId() + " 的加入申请");
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView reject(Long userId, Long courseId, Long projectId, Long teamId, Long memberId) {
        permission.requireMember(userId, courseId);
        requireMembershipChangesAllowed(findProject(courseId, projectId));
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        var member = findMember(teamId, memberId);
        requireGroupingOpen(courseId,projectId);
        if (member.getStatus() != MemberStatus.PENDING) throw bad("该申请已处理或已失效");
        member.reject();
        team.touch();
        audit.append(userId, courseId, "TEAM_REJECTED", team.getId().toString(), "拒绝成员 #" + member.getUserId() + " 的加入申请");
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView leave(Long userId, Long courseId, Long projectId, Long teamId) {
        permission.requireMember(userId, courseId);
        requireMembershipChangesAllowed(findProject(courseId, projectId));
        var team = findTeam(projectId, teamId);
        var member = members.findByTeamIdAndUserId(teamId, userId)
            .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "NOT_IN_TEAM", "你不在该团队中"));
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("你不在该团队中");
        if (team.getLeaderId().equals(userId)) throw bad("队长需先交接负责人后才能退出");
        requireGroupingOpen(courseId,projectId);
        if(member.isExitRequested())throw bad("已有待审批的退出申请");
        member.requestExit();
        team.touch();
        audit.append(userId, courseId, "TEAM_EXIT_REQUESTED", team.getId().toString(), "退出团队「" + team.getName() + "」");
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView decideExit(Long userId,Long courseId,Long projectId,Long teamId,Long memberId,boolean approved) {
        var project=findProject(courseId,projectId);
        requireMembershipChangesAllowed(project);
        var team=findTeam(projectId,teamId);
        requireLeaderOrTeacher(userId,courseId,team);
        if(permission.requireMember(userId,courseId)!=CourseRole.TEACHER)requireGroupingOpen(courseId,projectId);
        var member=findMember(teamId,memberId);
        if(!member.isExitRequested()||member.getStatus()!=MemberStatus.ACTIVE||team.getLeaderId().equals(member.getUserId()))
            throw bad("该退出申请不可处理");
        if(approved)member.leave();else member.rejectExit();
        team.touch();
        audit.append(userId,courseId,approved?"TEAM_LEFT":"TEAM_EXIT_REJECTED",teamId.toString(),"处理成员 #"+member.getUserId()+" 的退出申请");
        return detail(userId,courseId,projectId,teamId);
    }

    @Transactional
    public TeamView assign(Long userId,Long courseId,Long projectId,Long teamId,Long student) {
        permission.requireTeacher(userId,courseId);
        requireStudent(student,courseId);
        var project=findProject(courseId,projectId);
        requireMembershipChangesAllowed(project);
        var team=findTeam(projectId,teamId);
        ensureNotInTeam(student,projectId,teamId);
        var existing=members.findByTeamIdAndUserId(teamId,student);
        if(existing.isPresent()&&existing.get().getStatus()==MemberStatus.ACTIVE)throw bad("学生已在本团队");
        if(members.countByTeamIdAndStatus(teamId,MemberStatus.ACTIVE)>=project.getMaxTeamSize())throw bad("团队人数已达上限");
        if(existing.isPresent())existing.get().approve();
        else members.save(new TeamMember(teamId,student,MemberStatus.ACTIVE));
        team.touch();
        audit.append(userId,courseId,"TEAM_ASSIGNED",teamId.toString(),"教师分配学生 #"+student);
        return detail(userId,courseId,projectId,teamId);
    }

    public List<Map<String,Object>> assignmentCandidates(Long userId,Long courseId,Long projectId,Long teamId) {
        permission.requireTeacher(userId,courseId);
        var project=findProject(courseId,projectId);
        requireMembershipChangesAllowed(project);
        findTeam(projectId,teamId);
        return db.queryForList("""
            SELECT u.id,u.display_name,u.username FROM course_membership c JOIN app_user u ON u.id=c.user_id
            WHERE c.course_id=? AND c.role='STUDENT' AND c.active=TRUE AND u.enabled=TRUE
            AND NOT EXISTS (
                SELECT 1 FROM project_team_member m JOIN project_team t ON t.id=m.team_id
                WHERE t.project_id=? AND m.user_id=u.id
                AND (m.status='ACTIVE' OR (m.status='PENDING' AND t.id<>?))
            )
            ORDER BY u.display_name,u.username
            """,courseId,projectId,teamId);
    }

    public GroupingWindow window(Long userId,Long courseId,Long projectId){
        permission.requireMember(userId,courseId);
        var project=findProject(courseId,projectId);
        return new GroupingWindow(shift(project.getGroupingOpensAt()),shift(project.getGroupingClosesAt()),groupingOpen(project));
    }

    @Transactional
    public GroupingWindow configureWindow(Long userId,Long courseId,Long projectId,GroupingWindow input) {
        permission.requireTeacher(userId,courseId);
        var project=findProject(courseId,projectId);
        if(project.getStatus()!=ProjectStatus.DRAFT)requireMembershipChangesAllowed(project);
        if(input.opensAt()==null||input.closesAt()==null||!input.opensAt().isBefore(input.closesAt())
            ||project.getDeadline()==null||input.closesAt().isAfter(project.getDeadline()))throw bad("组队开放须早于截止，且组队截止不得晚于项目最终期限");
        var now=OffsetDateTime.now();
        if(project.getGroupingClosesAt()!=null && !now.isBefore(project.getGroupingClosesAt()))expire(projectId,project.getGroupingClosesAt());
        if(!now.isBefore(input.closesAt()))expire(projectId,input.closesAt());
        project.configureGrouping(input.opensAt(),input.closesAt());
        audit.append(userId,courseId,"GROUPING_WINDOW_CONFIGURED",projectId.toString(),"教师设置组队窗口");
        return new GroupingWindow(shift(input.opensAt()),shift(input.closesAt()),groupingOpen(project));
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView remove(Long userId, Long courseId, Long projectId, Long teamId, Long memberId) {
        permission.requireMember(userId, courseId);
        requireMembershipChangesAllowed(findProject(courseId, projectId));
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        permission.requireTeacher(userId,courseId);
        var member = findMember(teamId, memberId);
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("该成员不在团队中");
        if (team.getLeaderId().equals(member.getUserId())) throw bad("队长不能移出，请先交接负责人");
        member.leave();
        team.touch();
        audit.append(userId, courseId, "TEAM_MEMBER_REMOVED", team.getId().toString(), "移出成员 #" + member.getUserId());
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView transferLeadership(Long userId, Long courseId, Long projectId, Long teamId, LeadershipInput input) {
        permission.requireMember(userId, courseId);
        requireMembershipChangesAllowed(findProject(courseId, projectId));
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        var newLeaderId = input == null ? null : input.newLeaderUserId();
        if (newLeaderId == null) throw bad("请指定新的负责人");
        if (team.getLeaderId().equals(newLeaderId)) throw bad("该成员已是负责人");
        var member = members.findByTeamIdAndUserId(teamId, newLeaderId)
            .orElseThrow(() -> bad("新负责人不在该团队中"));
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("新负责人不在该团队中");
        team.transferLeadership(newLeaderId);
        audit.append(userId, courseId, "TEAM_LEADER_CHANGED", team.getId().toString(), "负责人交接给成员 #" + newLeaderId);
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView transfer(Long userId, Long courseId, Long projectId, Long teamId, Long memberId, TransferInput input) {
        permission.requireTeacher(userId, courseId);
        var project = findProject(courseId, projectId);
        requireMembershipChangesAllowed(findProject(courseId, projectId));
        var team = findTeam(projectId, teamId);
        var targetTeamId = input == null ? null : input.targetTeamId();
        if (targetTeamId == null) throw bad("请指定目标团队");
        var target = findTeam(projectId, targetTeamId);
        if (target.getId().equals(teamId)) throw bad("该成员已在目标团队中");
        var member = findMember(teamId, memberId);
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("该成员不在团队中");
        if (team.getLeaderId().equals(member.getUserId())) throw bad("队长请先交接负责人后再转组");
        var dup = members.findByTeamIdAndUserId(targetTeamId, member.getUserId());
        if (dup.isPresent() && dup.get().getStatus() != MemberStatus.LEFT && dup.get().getStatus() != MemberStatus.REJECTED && dup.get().getStatus() != MemberStatus.EXPIRED)
            throw bad("该成员已在目标团队中");
        if (members.countByTeamIdAndStatus(targetTeamId, MemberStatus.ACTIVE) >= project.getMaxTeamSize())
            throw bad("目标团队人数已达上限");
        member.leave();
        if (dup.isPresent()) dup.get().approve();
        else members.save(new TeamMember(targetTeamId, member.getUserId(), MemberStatus.ACTIVE));
        team.touch(); target.touch();
        audit.append(userId, courseId, "TEAM_MEMBER_MOVED", team.getId().toString(),
            "将成员 #" + member.getUserId() + " 转至团队「" + target.getName() + "」");
        members.flush(); teams.flush();
        return detail(userId, courseId, projectId, targetTeamId);
    }

    private void requireStudent(Long userId,Long courseId){
        if(permission.requireMember(userId,courseId)!=CourseRole.STUDENT)
            throw new BusinessException(HttpStatus.FORBIDDEN,"STUDENT_REQUIRED","仅本课程学生可以建队或申请加入团队");
    }
    private void requireMembershipChangesAllowed(Project project) {
        requireLegacyGrouping(project.getCourseId());
        if (!java.util.Set.of(ProjectStatus.PUBLISHED, ProjectStatus.IN_PROGRESS).contains(project.getStatus()))
            throw bad("当前项目状态不允许变更团队成员");
    }

    private void requireLegacyGrouping(long course) {
        if(Boolean.TRUE.equals(db.queryForObject("SELECT course_team_enabled FROM course WHERE id=?",Boolean.class,course))) throw bad("请在课程的组队与选题页面管理固定团队，项目内不再单独组队或调整成员");
    }

    private Project requireGroupingOpen(Long courseId, Long projectId) {
        requireLegacyGrouping(courseId);
        var project = findProject(courseId, projectId);
        if (project.getStatus() != ProjectStatus.PUBLISHED && project.getStatus() != ProjectStatus.IN_PROGRESS)
            throw bad("仅已发布的项目可以组队");
        if(!groupingOpen(project))throw bad("当前不在组队开放期");
        return project;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    @Transactional
    public void expireClosedApplications() {
        var now=OffsetDateTime.now();
        for(var row:db.queryForList("""
            SELECT p.id,p.course_id FROM course_project p
            WHERE COALESCE(p.grouping_closes_at,p.final_deadline)<=?
            AND EXISTS(SELECT 1 FROM project_team t JOIN project_team_member m ON m.team_id=t.id
                       WHERE t.project_id=p.id AND m.status='PENDING') ORDER BY p.id
            """,now)){
            long id=((Number)row.get("id")).longValue();
            var project=findProject(((Number)row.get("course_id")).longValue(),id);
            var closed=project.getGroupingClosesAt();
            if(closed!=null&&!now.isBefore(closed))expire(id,closed);
        }
    }

    private void expire(long project,OffsetDateTime closed) {
        db.update("""
            INSERT INTO expired_team_application(member_id,expired_at)
            SELECT m.id,? FROM project_team_member m JOIN project_team t ON t.id=m.team_id
            WHERE t.project_id=? AND m.status='PENDING'
            """,closed,project);
        db.update("""
            UPDATE project_team_member SET status='EXPIRED' WHERE status='PENDING'
            AND team_id IN (SELECT id FROM project_team WHERE project_id=?)
            """,project);
    }

    private boolean groupingOpen(Project project) {
        var now=OffsetDateTime.now();
        return java.util.Set.of(ProjectStatus.PUBLISHED,ProjectStatus.IN_PROGRESS).contains(project.getStatus())
            && (project.getGroupingOpensAt()==null||!now.isBefore(project.getGroupingOpensAt()))
            && project.getGroupingClosesAt()!=null&&now.isBefore(project.getGroupingClosesAt());
    }

    private Project findProject(Long courseId, Long projectId) {
        var project = (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? projects.findById(projectId) : projects.lockById(projectId))
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));
        if (!project.getCourseId().equals(courseId))
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        return project;
    }

    private Team findTeam(Long projectId, Long teamId) {
        var team = (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? teams.findById(teamId) : teams.lockById(teamId))
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在"));
        if (!team.getProjectId().equals(projectId))
            throw new BusinessException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在");
        return team;
    }

    private TeamMember findMember(Long teamId, Long memberId) {
        var member = members.findById(memberId)
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "成员记录不存在"));
        if (!member.getTeamId().equals(teamId))
            throw new BusinessException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "成员记录不存在");
        return member;
    }

    private void requireLeaderOrTeacher(Long userId, Long courseId, Team team) {
        requireLegacyGrouping(courseId);
        var role = permission.requireMember(userId, courseId);
        if (role != CourseRole.TEACHER && !(role == CourseRole.STUDENT && team.getLeaderId().equals(userId) && members.findByTeamIdAndUserId(team.getId(), userId).map(m -> m.getStatus() == MemberStatus.ACTIVE).orElse(false)))
            throw new BusinessException(HttpStatus.FORBIDDEN, "TEAM_FORBIDDEN", "仅团队负责人或课程教师可执行此操作");
    }

    /** 校验用户在该项目下没有其它团队的 ACTIVE 或 PENDING 记录；excludeTeamId 用于排除当前团队自身。 */
    private void ensureNotInTeam(Long userId, Long projectId, Long excludeTeamId) {
        for (var m : members.findByUserIdOrderByCreatedAtAsc(userId)) {
            if (m.getStatus() != MemberStatus.ACTIVE && m.getStatus() != MemberStatus.PENDING) continue;
            if (excludeTeamId != null && m.getTeamId().equals(excludeTeamId)) continue;
            var team = teams.findById(m.getTeamId()).orElse(null);
            if (team != null && team.getProjectId().equals(projectId))
                throw bad("你已在该项目的团队中，或已有待审批的加入申请");
        }
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) throw bad("请填写团队名称");
        var trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) throw bad("团队名称不超过" + NAME_MAX + "字");
        return trimmed;
    }

    private BusinessException bad(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TEAM", message);
    }

    private Map<Long, String> resolveNames(List<Team> teamsList, List<TeamMember> memberships) {
        var ids = new HashSet<Long>();
        teamsList.forEach(t -> ids.add(t.getLeaderId()));
        memberships.forEach(m -> ids.add(m.getUserId()));
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream()
            .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName, (a, b) -> a));
    }

    private TeamView view(Team t, Project project, CourseRole role, Long me, List<TeamMember> ms, Map<Long, String> names) {
        var active = ms.stream().filter(m -> m.getStatus() == MemberStatus.ACTIVE).toList();
        var pending = ms.stream().filter(m -> m.getStatus() == MemberStatus.PENDING).toList();
        boolean fixed=Boolean.TRUE.equals(db.queryForObject("SELECT course_team_enabled FROM course WHERE id=?",Boolean.class,project.getCourseId()));
        boolean canManage = !fixed && (role == CourseRole.TEACHER || (role == CourseRole.STUDENT && t.getLeaderId().equals(me) && active.stream().anyMatch(m -> m.getUserId().equals(me))));
        return new TeamView(t.getId(), t.getProjectId(), t.getName(), t.getLeaderId(), names.get(t.getLeaderId()),
            active.size(), project.getMinTeamSize(), project.getMaxTeamSize(),
            active.size() >= project.getMaxTeamSize(), active.size() >= project.getMinTeamSize(),
            toViews(active, names), toViews(groupingOpen(project)?pending:List.of(), names), role, canManage,
            !fixed && groupingOpen(project),shift(project.getGroupingOpensAt()),shift(project.getGroupingClosesAt()),fixed);
    }

    private List<MemberView> toViews(Collection<TeamMember> list, Map<Long, String> names) {
        return list.stream()
            .map(m -> new MemberView(m.getId(), m.getUserId(), names.get(m.getUserId()), m.getStatus(), shift(m.getJoinedAt()),m.isExitRequested()))
            .toList();
    }

    private OffsetDateTime shift(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime();
    }

    public record GroupingWindow(OffsetDateTime opensAt,OffsetDateTime closesAt,boolean open) {}
    public record CreateInput(String name) {}
    public record LeadershipInput(Long newLeaderUserId) {}
    public record TransferInput(Long targetTeamId) {}
    public record MemberView(Long id, Long userId, String displayName, MemberStatus status, OffsetDateTime joinedAt, boolean exitRequested) {}
    public record TeamView(Long id, Long projectId, String name, Long leaderId, String leaderName,
                           int activeCount, int minTeamSize, int maxTeamSize, boolean full, boolean complete,
                           List<MemberView> members, List<MemberView> applications, CourseRole myRole, boolean canManage,
                           boolean groupingOpen, OffsetDateTime groupingOpensAt, OffsetDateTime groupingClosesAt,boolean fixedCourseTeams) {}
}
