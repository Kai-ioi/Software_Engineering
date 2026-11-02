package cn.edu.school.inspection;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/inspections")
public class InspectionController {
    private final InspectionService service;
    public InspectionController(InspectionService service){this.service=service;}
    @GetMapping("/teams") public ApiResponse<?> teams(@AuthenticationPrincipal UserPrincipal u,@RequestParam long courseId){return ApiResponse.ok(service.teams(u.id(),courseId));}
    @GetMapping("/teams/{team}") public ApiResponse<?> workspace(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team){return ApiResponse.ok(service.workspace(u.id(),team));}
    @PostMapping("/teams/{team}") public ApiResponse<?> create(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody InspectionService.CreateInput in){return ApiResponse.ok(Map.of("id",service.create(u.id(),team,in)));}
}
