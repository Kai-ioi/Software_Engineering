package cn.edu.school.workflow;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import cn.edu.school.common.BusinessException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/retention")
public class CourseRetentionController {
    private final CourseRetention retention;
    private final JdbcTemplate db;
    private final String approver;

    public CourseRetentionController(CourseRetention retention,JdbcTemplate db,
            @Value("${app.retention-approver:}") String approver) {
        this.retention=retention; this.db=db; this.approver=approver.strip();
    }

    private boolean admin(UserPrincipal u){
        return u.platformAdmin() && db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id=? AND enabled=TRUE AND platform_admin=TRUE",Long.class,u.id())==1;
    }
    private boolean approver(UserPrincipal u){
        return !approver.isEmpty() && approver.equals(u.username()) && db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id=? AND username=? AND enabled=TRUE",Long.class,u.id(),approver)==1;
    }
    private void require(boolean condition){
        if(!condition)throw new BusinessException(HttpStatus.FORBIDDEN,"RETENTION_FORBIDDEN","没有对应的数据留存管理权限");
    }
    @GetMapping("/access")
    public ApiResponse<?> access(@AuthenticationPrincipal UserPrincipal u){
        return ApiResponse.ok(Map.of("admin",admin(u),"approver",approver(u)));
    }
    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal UserPrincipal u){
        require(admin(u)||approver(u));
        return ApiResponse.ok(retention.overview());
    }
    @GetMapping("/{id}/preview")
    public ApiResponse<?> preview(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){
        require(admin(u)||approver(u));
        return ApiResponse.ok(retention.preview(id));
    }
    @GetMapping("/{id}/audit")
    public ApiResponse<?> audit(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){
        require(admin(u)||approver(u));
        return ApiResponse.ok(retention.audit(id));
    }

    @PostMapping("/{id}/end")
    public ApiResponse<?> end(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){
        require(admin(u));
        retention.end(id,u.id());
        return ApiResponse.ok(null);
    }
    public record Hold(String reason,boolean release) {}
    @PostMapping("/{id}/hold")
    public ApiResponse<?> hold(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id,@RequestBody Hold input){
        require(admin(u)||approver(u));
        retention.hold(id,u.id(),input.reason(),input.release());
        return ApiResponse.ok(null);
    }
    @PostMapping("/{id}/approve")
    public ApiResponse<?> approve(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){
        require(approver(u));
        retention.approve(id,u.id());
        return ApiResponse.ok(null);
    }
    @PostMapping("/{id}/purge")
    public ApiResponse<?> purge(@AuthenticationPrincipal UserPrincipal u,@PathVariable long id){
        require(admin(u));
        retention.purge(id,u.id());
        retention.removeFiles(id,u.id());
        return ApiResponse.ok(null);
    }
}
