package cn.edu.school.review;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service){this.service=service;}
    @GetMapping("/projects") public ApiResponse<?> projects(@AuthenticationPrincipal UserPrincipal u,@RequestParam long courseId){return ApiResponse.ok(service.projects(u.id(),courseId));}
    @GetMapping("/projects/{project}") public ApiResponse<?> workspace(@AuthenticationPrincipal UserPrincipal u,@PathVariable long project){return ApiResponse.ok(service.workspace(u.id(),project));}
    @PostMapping("/projects/{project}/rounds") public ApiResponse<?> createRound(@AuthenticationPrincipal UserPrincipal u,@PathVariable long project,@RequestBody ReviewService.RoundInput in){return ApiResponse.ok(Map.of("id",service.createRound(u.id(),project,in)));}
    @PostMapping("/rounds/{round}/assign") public ApiResponse<?> assign(@AuthenticationPrincipal UserPrincipal u,@PathVariable long round){return ApiResponse.ok(Map.of("created",service.autoAssign(u.id(),round)));}
    @PostMapping("/rounds/{round}/tasks") public ApiResponse<?> addTask(@AuthenticationPrincipal UserPrincipal u,@PathVariable long round,@RequestBody ReviewService.TaskInput in){return ApiResponse.ok(Map.of("id",service.addTask(u.id(),round,in)));}
    @PostMapping("/tasks/{task}/cancel") public ApiResponse<?> cancel(@AuthenticationPrincipal UserPrincipal u,@PathVariable long task){service.cancelTask(u.id(),task);return ApiResponse.ok(null);}
    @PostMapping("/rounds/{round}/extend") public ApiResponse<?> extend(@AuthenticationPrincipal UserPrincipal u,@PathVariable long round,@RequestBody ReviewService.ExtendInput in){service.extendRound(u.id(),round,in);return ApiResponse.ok(null);}
    @PostMapping("/rounds/{round}/close") public ApiResponse<?> close(@AuthenticationPrincipal UserPrincipal u,@PathVariable long round){service.closeRound(u.id(),round);return ApiResponse.ok(null);}
    @PostMapping("/rounds/{round}/feedback") public ApiResponse<?> feedback(@AuthenticationPrincipal UserPrincipal u,@PathVariable long round,@RequestBody ReviewService.FeedbackInput in){service.toggleFeedback(u.id(),round,in);return ApiResponse.ok(null);}
    @PostMapping("/tasks/{task}/submit") public ApiResponse<?> submit(@AuthenticationPrincipal UserPrincipal u,@PathVariable long task,@RequestBody ReviewService.SubmitInput in){service.submit(u.id(),task,in);return ApiResponse.ok(null);}
    @PostMapping("/appeals") public ApiResponse<?> appeal(@AuthenticationPrincipal UserPrincipal u,@RequestBody ReviewService.AppealInput in){return ApiResponse.ok(Map.of("id",service.appeal(u.id(),in)));}
    @PostMapping("/appeals/{appeal}/approve") public ApiResponse<?> approve(@AuthenticationPrincipal UserPrincipal u,@PathVariable long appeal,@RequestBody ReviewService.ResolveInput in){service.approveAppeal(u.id(),appeal,in);return ApiResponse.ok(null);}
    @PostMapping("/appeals/{appeal}/reject") public ApiResponse<?> reject(@AuthenticationPrincipal UserPrincipal u,@PathVariable long appeal,@RequestBody ReviewService.ResolveInput in){service.rejectAppeal(u.id(),appeal,in);return ApiResponse.ok(null);}
}
