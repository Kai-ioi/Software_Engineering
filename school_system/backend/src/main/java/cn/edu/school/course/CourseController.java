package cn.edu.school.course;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/courses")
public class CourseController {
    private final CourseService service;
    public CourseController(CourseService service){this.service=service;}
    @GetMapping public ApiResponse<List<CourseService.CourseView>> list(@AuthenticationPrincipal UserPrincipal user){return ApiResponse.ok(service.list(user.id()));}
    @GetMapping("/{id}") public ApiResponse<CourseService.CourseView> detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable Long id){return ApiResponse.ok(service.detail(user.id(),id));}
    @GetMapping("/{id}/management") public ApiResponse<CourseService.ManagementView> management(@AuthenticationPrincipal UserPrincipal user,@PathVariable Long id){return ApiResponse.ok(service.management(user.id(),id));}
}
