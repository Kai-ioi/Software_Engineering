package cn.edu.school.team;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/courses/{courseId}/projects/{projectId}/teams")
public class TeamController {
    private final TeamService service;
    public TeamController(TeamService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<TeamService.TeamView>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId, @PathVariable Long projectId) {
        return ApiResponse.ok(service.list(user.id(), courseId, projectId));
    }

    @PostMapping
    public ApiResponse<TeamService.TeamView> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                    @PathVariable Long projectId, @RequestBody TeamService.CreateInput input) {
        return ApiResponse.ok(service.create(user.id(), courseId, projectId, input));
    }

    @GetMapping("/{teamId}")
    public ApiResponse<TeamService.TeamView> detail(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                    @PathVariable Long projectId, @PathVariable Long teamId) {
        return ApiResponse.ok(service.detail(user.id(), courseId, projectId, teamId));
    }

    @PostMapping("/{teamId}/apply")
    public ApiResponse<TeamService.TeamView> apply(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                   @PathVariable Long projectId, @PathVariable Long teamId) {
        return ApiResponse.ok(service.apply(user.id(), courseId, projectId, teamId));
    }

    @PostMapping("/{teamId}/members/{memberId}/approve")
    public ApiResponse<TeamService.TeamView> approve(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                     @PathVariable Long projectId, @PathVariable Long teamId, @PathVariable Long memberId) {
        return ApiResponse.ok(service.approve(user.id(), courseId, projectId, teamId, memberId));
    }

    @PostMapping("/{teamId}/members/{memberId}/reject")
    public ApiResponse<TeamService.TeamView> reject(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                    @PathVariable Long projectId, @PathVariable Long teamId, @PathVariable Long memberId) {
        return ApiResponse.ok(service.reject(user.id(), courseId, projectId, teamId, memberId));
    }

    @PostMapping("/{teamId}/leave")
    public ApiResponse<TeamService.TeamView> leave(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                   @PathVariable Long projectId, @PathVariable Long teamId) {
        return ApiResponse.ok(service.leave(user.id(), courseId, projectId, teamId));
    }

    @PostMapping("/{teamId}/members/{memberId}/remove")
    public ApiResponse<TeamService.TeamView> remove(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                    @PathVariable Long projectId, @PathVariable Long teamId, @PathVariable Long memberId) {
        return ApiResponse.ok(service.remove(user.id(), courseId, projectId, teamId, memberId));
    }

    @PostMapping("/{teamId}/transfer-leadership")
    public ApiResponse<TeamService.TeamView> transferLeadership(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                                @PathVariable Long projectId, @PathVariable Long teamId,
                                                                @RequestBody TeamService.LeadershipInput input) {
        return ApiResponse.ok(service.transferLeadership(user.id(), courseId, projectId, teamId, input));
    }

    @PostMapping("/{teamId}/members/{memberId}/transfer")
    public ApiResponse<TeamService.TeamView> transfer(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                      @PathVariable Long projectId, @PathVariable Long teamId, @PathVariable Long memberId,
                                                      @RequestBody TeamService.TransferInput input) {
        return ApiResponse.ok(service.transfer(user.id(), courseId, projectId, teamId, memberId, input));
    }
}
