package cn.edu.school.common;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final Environment environment;
    public HealthController(Environment environment) { this.environment = environment; }
    @GetMapping
    ApiResponse<Health> health() {
        boolean demo = environment.matchesProfiles("demo") || environment.getActiveProfiles().length == 0;
        return ApiResponse.ok(new Health("UP", demo ? "demo" : "postgres", OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))));
    }
    public record Health(String status, String mode, OffsetDateTime time) {}
}
