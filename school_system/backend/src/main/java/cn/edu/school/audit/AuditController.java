package cn.edu.school.audit;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.common.ApiResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/courses/{courseId}/audit")
public class AuditController {
    private final CoursePermission permission; private final AuditRepository records;
    public AuditController(CoursePermission permission,AuditRepository records){this.permission=permission;this.records=records;}
    @GetMapping public ApiResponse<AuditPage> list(@AuthenticationPrincipal UserPrincipal user,@PathVariable Long courseId,
        @RequestParam(defaultValue="0")int page){
        permission.requireTeacher(user.id(),courseId);
        var result=records.findByCourseIdOrderByOccurredAtDesc(courseId,PageRequest.of(Math.max(0,page),20));
        return ApiResponse.ok(new AuditPage(result.getContent().stream().map(r -> new AuditView(r.getId(),r.getActorId(),r.getAction(),
            r.getObjectId(),r.getSummary(),r.getOccurredAt().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toOffsetDateTime())).toList(),result.getTotalElements()));
    }
    public record AuditView(Long id,Long actorId,String action,String objectId,String summary,OffsetDateTime occurredAt){}
    public record AuditPage(List<AuditView> items,long total){}
}
