package cn.edu.school.team;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/courses/{course}/teams")
public class CourseTeamController {
 private final CourseTeamService service;public CourseTeamController(CourseTeamService service){this.service=service;}
 public record Name(String name){} public record Student(long studentId){} public record Answer(boolean accept){} public record Selection(List<Long> projectIds){} public record Policy(int requiredProjects){}
 @GetMapping public ApiResponse<?> workspace(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course){return ApiResponse.ok(service.workspace(u.id(),course));}
 @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@RequestBody Name in){return ApiResponse.ok(service.create(u.id(),course,in.name()));}
 @GetMapping("/{team}/candidates") public ApiResponse<?> candidates(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@PathVariable long team){return ApiResponse.ok(service.candidates(u.id(),course,team));}
 @PostMapping("/{team}/invitations") public ApiResponse<?> invite(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@PathVariable long team,@RequestBody Student in){service.invite(u.id(),course,team,in.studentId());return ApiResponse.ok(null);}
 @PostMapping("/{team}/answer") public ApiResponse<?> answer(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@PathVariable long team,@RequestBody Answer in){service.answer(u.id(),course,team,in.accept());return ApiResponse.ok(null);}
 @PostMapping("/{team}/cancel-invitation") public ApiResponse<?> cancel(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@PathVariable long team,@RequestBody Student in){service.cancel(u.id(),course,team,in.studentId());return ApiResponse.ok(null);}
 @PostMapping("/{team}/projects") public ApiResponse<?> select(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@PathVariable long team,@RequestBody Selection in){service.select(u.id(),course,team,in.projectIds());return ApiResponse.ok(null);}
 @PutMapping("/policy") public ApiResponse<?> policy(@AuthenticationPrincipal UserPrincipal u,@PathVariable long course,@RequestBody Policy in){service.policy(u.id(),course,in.requiredProjects());return ApiResponse.ok(null);}
}
