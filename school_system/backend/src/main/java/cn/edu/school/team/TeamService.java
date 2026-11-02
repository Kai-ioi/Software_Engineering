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

    public TeamService(TeamRepository teams, TeamMemberRepository members, CoursePermission permission,
                       ProjectRepository projects, UserRepository users, AuditService audit) {
        this.teams = teams;
        this.members = members;
        this.permission = permission;
        this.projects = projects;
        this.users = users;
        this.audit = audit;
    }

    public List<TeamView> list(Long userId, Long courseId, Long projectId) {
        var role = permission.requireMember(userId, courseId);
        var project = findProject(courseId, projectId);
        var all = teams.findByProjectIdOrderByCreatedAtAsc(projectId);
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
        var memberships = members.findByTeamIdOrderByCreatedAtAsc(teamId);
        var names = resolveNames(List.of(team), memberships);
        return view(team, project, role, userId, memberships, names);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView create(Long userId, Long courseId, Long projectId, CreateInput input) {
        permission.requireMember(userId, courseId);
        var project = requireGroupingOpen(courseId, projectId);
        var name = validateName(input == null ? null : input.name());
        ensureNotInTeam(userId, projectId, null);
        var team = new Team(projectId, name, userId);
        teams.save(team);
        members.save(new TeamMember(team.getId(), userId, MemberStatus.ACTIVE));
        audit.append(userId, courseId, "TEAM_CREATED", team.getId().toString(), "创建团队「" + name + "」");
        return detail(userId, courseId, projectId, team.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView apply(Long userId, Long courseId, Long projectId, Long teamId) {
        permission.requireMember(userId, courseId);
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
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView approve(Long userId, Long courseId, Long projectId, Long teamId, Long memberId) {
        permission.requireMember(userId, courseId);
        var project = findProject(courseId, projectId);
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        var member = findMember(teamId, memberId);
        if (member.getStatus() != MemberStatus.PENDING) throw bad("该申请已被处理");
        ensureNotInTeam(member.getUserId(), projectId, teamId);
        if (members.countByTeamIdAndStatus(teamId, MemberStatus.ACTIVE) >= project.getMaxTeamSize())
            throw bad("团队人数已达上限，无法通过该申请");
        member.approve();
        audit.append(userId, courseId, "TEAM_APPROVED", team.getId().toString(), "通过成员 #" + member.getUserId() + " 的加入申请");
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView reject(Long userId, Long courseId, Long projectId, Long teamId, Long memberId) {
        permission.requireMember(userId, courseId);
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        var member = findMember(teamId, memberId);
        if (member.getStatus() != MemberStatus.PENDING) throw bad("该申请已被处理");
        member.reject();
        audit.append(userId, courseId, "TEAM_REJECTED", team.getId().toString(), "拒绝成员 #" + member.getUserId() + " 的加入申请");
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView leave(Long userId, Long courseId, Long projectId, Long teamId) {
        permission.requireMember(userId, courseId);
        var team = findTeam(projectId, teamId);
        var member = members.findByTeamIdAndUserId(teamId, userId)
            .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "NOT_IN_TEAM", "你不在该团队中"));
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("你不在该团队中");
        if (team.getLeaderId().equals(userId)) throw bad("队长需先交接负责人后才能退出");
        member.leave();
        audit.append(userId, courseId, "TEAM_LEFT", team.getId().toString(), "退出团队「" + team.getName() + "」");
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView remove(Long userId, Long courseId, Long projectId, Long teamId, Long memberId) {
        permission.requireMember(userId, courseId);
        var team = findTeam(projectId, teamId);
        requireLeaderOrTeacher(userId, courseId, team);
        var member = findMember(teamId, memberId);
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("该成员不在团队中");
        if (team.getLeaderId().equals(member.getUserId())) throw bad("队长不能移出，请先交接负责人");
        member.leave();
        audit.append(userId, courseId, "TEAM_MEMBER_REMOVED", team.getId().toString(), "移出成员 #" + member.getUserId());
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView transferLeadership(Long userId, Long courseId, Long projectId, Long teamId, LeadershipInput input) {
        permission.requireMember(userId, courseId);
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
        return detail(userId, courseId, projectId, teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamView transfer(Long userId, Long courseId, Long projectId, Long teamId, Long memberId, TransferInput input) {
        permission.requireTeacher(userId, courseId);
        var project = findProject(courseId, projectId);
        var team = findTeam(projectId, teamId);
        var targetTeamId = input == null ? null : input.targetTeamId();
        if (targetTeamId == null) throw bad("请指定目标团队");
        var target = findTeam(projectId, targetTeamId);
        if (target.getId().equals(teamId)) throw bad("该成员已在目标团队中");
        var member = findMember(teamId, memberId);
        if (member.getStatus() != MemberStatus.ACTIVE) throw bad("该成员不在团队中");
        if (team.getLeaderId().equals(member.getUserId())) throw bad("队长请先交接负责人后再转组");
        var dup = members.findByTeamIdAndUserId(targetTeamId, member.getUserId());
        if (dup.isPresent() && dup.get().getStatus() != MemberStatus.LEFT && dup.get().getStatus() != MemberStatus.REJECTED)
            throw bad("该成员已在目标团队中");
        if (members.countByTeamIdAndStatus(targetTeamId, MemberStatus.ACTIVE) >= project.getMaxTeamSize())
            throw bad("目标团队人数已达上限");
        member.moveTo(targetTeamId);
        audit.append(userId, courseId, "TEAM_MEMBER_MOVED", team.getId().toString(),
            "将成员 #" + member.getUserId() + " 转至团队「" + target.getName() + "」");
        return detail(userId, courseId, projectId, targetTeamId);
    }

    private Project requireGroupingOpen(Long courseId, Long projectId) {
        var project = findProject(courseId, projectId);
        if (project.getStatus() != ProjectStatus.PUBLISHED)
            throw bad("仅已发布的项目可以组队");
        return project;
    }

    private Project findProject(Long courseId, Long projectId) {
        var project = projects.findById(projectId)
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));
        if (!project.getCourseId().equals(courseId))
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        return project;
    }

    private Team findTeam(Long projectId, Long teamId) {
        var team = teams.findById(teamId)
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
        var role = permission.requireMember(userId, courseId);
        if (role != CourseRole.TEACHER && !team.getLeaderId().equals(userId))
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
        boolean canManage = role == CourseRole.TEACHER || t.getLeaderId().equals(me);
        return new TeamView(t.getId(), t.getProjectId(), t.getName(), t.getLeaderId(), names.get(t.getLeaderId()),
            active.size(), project.getMinTeamSize(), project.getMaxTeamSize(),
            active.size() >= project.getMaxTeamSize(), active.size() >= project.getMinTeamSize(),
            toViews(active, names), toViews(pending, names), role, canManage);
    }

    private List<MemberView> toViews(Collection<TeamMember> list, Map<Long, String> names) {
        return list.stream()
            .map(m -> new MemberView(m.getId(), m.getUserId(), names.get(m.getUserId()), m.getStatus(), shift(m.getJoinedAt())))
            .toList();
    }

    private OffsetDateTime shift(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime();
    }

    public record CreateInput(String name) {}
    public record LeadershipInput(Long newLeaderUserId) {}
    public record TransferInput(Long targetTeamId) {}
    public record MemberView(Long id, Long userId, String displayName, MemberStatus status, OffsetDateTime joinedAt) {}
    public record TeamView(Long id, Long projectId, String name, Long leaderId, String leaderName,
                           int activeCount, int minTeamSize, int maxTeamSize, boolean full, boolean complete,
                           List<MemberView> members, List<MemberView> applications, CourseRole myRole, boolean canManage) {}
}
