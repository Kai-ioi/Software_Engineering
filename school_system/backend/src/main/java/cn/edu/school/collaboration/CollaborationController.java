package cn.edu.school.collaboration;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/collaboration")
public class CollaborationController {
    private final CollaborationService service;
    public CollaborationController(CollaborationService service){this.service=service;}
    @GetMapping("/teams") public ApiResponse<?> teams(@AuthenticationPrincipal UserPrincipal u,@RequestParam long courseId){return ApiResponse.ok(service.teams(u.id(),courseId));}
    @GetMapping("/teams/{team}") public ApiResponse<?> workspace(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team){return ApiResponse.ok(service.workspace(u.id(),team));}
    @GetMapping("/teams/{team}/history") public ApiResponse<?> history(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestParam(defaultValue="9223372036854775807") long beforeId){return ApiResponse.ok(service.history(u.id(),team,beforeId));}
    @PostMapping("/teams/{team}/iterations") public ApiResponse<?> createIteration(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody CollaborationService.IterationInput in){return ApiResponse.ok(Map.of("id",service.saveIteration(u.id(),team,null,in)));}
    @PutMapping("/teams/{team}/iterations/{id}") public ApiResponse<?> editIteration(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.IterationInput in){return ApiResponse.ok(Map.of("id",service.saveIteration(u.id(),team,id,in)));}
    @PostMapping("/teams/{team}/iterations/{id}/start") public ApiResponse<?> start(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.EndInput in){service.lifecycle(u.id(),team,id,false,in);return ApiResponse.ok(null);}
    @PostMapping("/teams/{team}/iterations/{id}/end") public ApiResponse<?> end(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.EndInput in){service.lifecycle(u.id(),team,id,true,in);return ApiResponse.ok(null);}
    @PostMapping("/teams/{team}/tasks") public ApiResponse<?> createTask(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody CollaborationService.TaskInput in){return ApiResponse.ok(Map.of("id",service.saveTask(u.id(),team,null,in)));}
    @PutMapping("/teams/{team}/tasks/{id}") public ApiResponse<?> editTask(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.TaskInput in){return ApiResponse.ok(Map.of("id",service.saveTask(u.id(),team,id,in)));}
    @PostMapping("/teams/{team}/tasks/{id}/progress") public ApiResponse<?> progress(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.ProgressInput in){service.progress(u.id(),team,id,in);return ApiResponse.ok(null);}
    @PostMapping("/teams/{team}/tasks/{id}/move") public ApiResponse<?> move(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.MoveInput in){service.move(u.id(),team,id,in);return ApiResponse.ok(null);}
    @PostMapping("/teams/{team}/milestones") public ApiResponse<?> createMilestone(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody CollaborationService.MilestoneInput in){return ApiResponse.ok(Map.of("id",service.saveMilestone(u.id(),team,null,in)));}
    @PutMapping("/teams/{team}/milestones/{id}") public ApiResponse<?> editMilestone(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody CollaborationService.MilestoneInput in){return ApiResponse.ok(Map.of("id",service.saveMilestone(u.id(),team,id,in)));}
}
