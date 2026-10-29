package cn.edu.school.auth;
import cn.edu.school.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth")
public class AuthController {
    @GetMapping("/csrf")
    public ApiResponse<CsrfView> csrf(CsrfToken token) {
        return ApiResponse.ok(new CsrfView(token.getHeaderName(), token.getToken()));
    }
    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal UserPrincipal user) { return ApiResponse.ok(UserView.from(user)); }
    public record CsrfView(String headerName, String token) {}
    public record UserView(Long id, String username, String displayName, boolean platformAdmin) {
        public static UserView from(UserPrincipal user) { return new UserView(user.id(),user.username(),user.displayName(),user.platformAdmin()); }
    }
}
