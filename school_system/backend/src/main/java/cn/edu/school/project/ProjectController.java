package cn.edu.school.project;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/courses/{courseId}/projects")
public class ProjectController {
    private final ProjectService service;
    public ProjectController(ProjectService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<ProjectService.ProjectView>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId) {
        return ApiResponse.ok(service.list(user.id(), courseId));
    }

    @PostMapping
    public ApiResponse<ProjectService.ProjectView> create(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                          @RequestBody ProjectService.ProjectInput input) {
        return ApiResponse.ok(service.create(user.id(), courseId, input));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectService.ProjectView> detail(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                          @PathVariable Long projectId) {
        return ApiResponse.ok(service.detail(user.id(), courseId, projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectService.ProjectView> update(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                          @PathVariable Long projectId, @RequestBody ProjectService.ProjectInput input) {
        return ApiResponse.ok(service.update(user.id(), courseId, projectId, input));
    }

    @PostMapping("/{projectId}/publish")
    public ApiResponse<ProjectService.ProjectView> publish(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                           @PathVariable Long projectId) {
        return ApiResponse.ok(service.publish(user.id(), courseId, projectId));
    }

    @PostMapping("/{projectId}/close")
    public ApiResponse<ProjectService.ProjectView> close(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                         @PathVariable Long projectId) {
        return ApiResponse.ok(service.close(user.id(), courseId, projectId));
    }

    @PostMapping("/{projectId}/archive")
    public ApiResponse<ProjectService.ProjectView> archive(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long courseId,
                                                           @PathVariable Long projectId) {
        return ApiResponse.ok(service.archive(user.id(), courseId, projectId));
    }
}
