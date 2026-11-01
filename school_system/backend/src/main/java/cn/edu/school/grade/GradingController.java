package cn.edu.school.grade;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/grades")
public class GradingController {
    private final GradingService service;
    public GradingController(GradingService service){ this.service=service; }

    @GetMapping("/courses/{course}")
    public ApiResponse<?> overview(@AuthenticationPrincipal UserPrincipal u, @PathVariable long course){ return ApiResponse.ok(service.overview(u.id(), course)); }

    @GetMapping("/teams/{team}")
    public ApiResponse<?> teamSheet(@AuthenticationPrincipal UserPrincipal u, @PathVariable long team){ return ApiResponse.ok(service.teamSheet(u.id(), team)); }

    @PostMapping("/teams/{team}/draft")
    public ApiResponse<?> draft(@AuthenticationPrincipal UserPrincipal u, @PathVariable long team, @RequestBody GradingService.SaveInput in){ return ApiResponse.ok(service.save(u.id(), team, in, false)); }

    @PostMapping("/teams/{team}/submit")
    public ApiResponse<?> submit(@AuthenticationPrincipal UserPrincipal u, @PathVariable long team, @RequestBody GradingService.SaveInput in){ return ApiResponse.ok(service.save(u.id(), team, in, true)); }

    @PostMapping("/sheets/{sheet}/modify")
    public ApiResponse<?> modify(@AuthenticationPrincipal UserPrincipal u, @PathVariable long sheet, @RequestBody GradingService.SaveInput in){ return ApiResponse.ok(service.modify(u.id(), sheet, in)); }
}
