package cn.edu.school.grade;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
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

    // ── Module 8 ──

    @GetMapping("/courses/{course}/summary")
    public ApiResponse<?> summary(@AuthenticationPrincipal UserPrincipal u, @PathVariable long course){ return ApiResponse.ok(service.summary(u.id(), course)); }

    @PostMapping("/teams/{team}/roster")
    public ApiResponse<?> confirmRoster(@AuthenticationPrincipal UserPrincipal u, @PathVariable long team){ return ApiResponse.ok(service.confirmRoster(u.id(), team)); }

    @PostMapping("/courses/{course}/publish")
    public ApiResponse<?> publish(@AuthenticationPrincipal UserPrincipal u, @PathVariable long course, @RequestBody GradingService.PublishInput in){ return ApiResponse.ok(service.publish(u.id(), course, in)); }

    @PostMapping("/results/{result}/correct")
    public ApiResponse<?> correct(@AuthenticationPrincipal UserPrincipal u, @PathVariable long result, @RequestBody GradingService.CorrectInput in){ return ApiResponse.ok(service.correct(u.id(), result, in)); }

    @GetMapping("/my")
    public ApiResponse<?> myGrades(@AuthenticationPrincipal UserPrincipal u){ return ApiResponse.ok(service.myGrades(u.id())); }

    @GetMapping("/courses/{course}/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal UserPrincipal u, @PathVariable long course,
                                          @RequestParam(defaultValue="csv") String format,
                                          @RequestParam(defaultValue="") String filename){
        var e = service.export(u.id(), course, format, filename);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(e.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(e.filename(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(e.bytes());
    }
}
