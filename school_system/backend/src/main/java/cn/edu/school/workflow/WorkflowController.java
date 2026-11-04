package cn.edu.school.workflow;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {
    private final WorkflowNotices notices;
    private final WorkflowHistory history;

    public WorkflowController(WorkflowNotices notices, WorkflowHistory history) {
        this.notices = notices;
        this.history = history;
    }

    @GetMapping("/notices")
    public ApiResponse<?> notices(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(notices.list(user.id()));
    }

    @PostMapping("/notices/{source}/{id}/read")
    public ApiResponse<?> read(@AuthenticationPrincipal UserPrincipal user, @PathVariable String source, @PathVariable long id) {
        notices.read(user.id(),source,id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/history")
    public ApiResponse<?> history(@AuthenticationPrincipal UserPrincipal user, @RequestParam long teamId,
                                  @RequestParam(required=false) String type,@RequestParam(required=false) String objectRef,
                                  @RequestParam(required=false) java.time.OffsetDateTime before,
                                  @RequestParam(required=false) String category,@RequestParam(required=false) Long eventId) {
        return ApiResponse.ok(history.page(user.id(),teamId,type,objectRef,before,category,eventId,50));
    }
}
