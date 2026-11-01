package cn.edu.school.submission;

import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.*;
import cn.edu.school.file.FileService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/submissions")
public class SubmissionController {
    private final SubmissionService service;private final JdbcTemplate db;
    public SubmissionController(SubmissionService service,JdbcTemplate db){this.service=service;this.db=db;}
    @GetMapping("/teams/{team}") public ApiResponse<?> workspace(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team){return ApiResponse.ok(service.workspace(u.id(),team));}
    @PostMapping(value="/teams/{team}/materials/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestParam long iterationId,@RequestParam long revision,@RequestPart MultipartFile file)throws IOException{return ApiResponse.ok(Map.of("id",service.upload(u.id(),team,iterationId,revision,file)));}
    @PostMapping("/teams/{team}/materials/links") public ApiResponse<?> link(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.LinkInput in){return ApiResponse.ok(Map.of("id",service.link(u.id(),team,in)));}
    @PostMapping("/teams/{team}/materials/{id}/remove") public ApiResponse<?> remove(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody SubmissionService.ReasonInput in){service.removeMaterial(u.id(),team,id,in);return ApiResponse.ok(null);}
    @GetMapping("/teams/{team}/materials/{id}/download") public ResponseEntity<Resource> draftDownload(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id){return download(service.draftDownload(u.id(),team,id));}
    @PostMapping("/teams/{team}/versions") public ApiResponse<?> submit(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.SubmitInput in){return ApiResponse.ok(service.submit(u.id(),team,in));}
    @GetMapping("/teams/{team}/versions/{id}") public ApiResponse<?> detail(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id){return ApiResponse.ok(service.detail(u.id(),team,id));}
    @GetMapping("/teams/{team}/versions/{id}/files/{material}") public ResponseEntity<Resource> versionFile(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@PathVariable long material){return download(service.versionDownload(u.id(),team,id,material));}
    @GetMapping("/teams/{team}/receipts/{key}") public ApiResponse<?> receipt(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable UUID key){return ApiResponse.ok(service.receipt(u.id(),team,key));}
    @PostMapping("/teams/{team}/final-selection") public ApiResponse<?> select(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.SelectionInput in){service.select(u.id(),team,in);return ApiResponse.ok(null);}
    @PostMapping("/teams/{team}/verify-final") public ApiResponse<?> verify(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.SelectionInput in){service.verifyFinal(u.id(),team,in);return ApiResponse.ok(null);}
    @PostMapping("/teams/{team}/review-bindings") public ApiResponse<?> review(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.SelectionInput in){return ApiResponse.ok(Map.of("id",service.bindReview(u.id(),team,in)));}
    @PostMapping("/teams/{team}/extension-requests") public ApiResponse<?> request(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.ReasonInput in){return ApiResponse.ok(Map.of("id",service.requestExtension(u.id(),team,in)));}
    @PostMapping("/teams/{team}/extensions") public ApiResponse<?> grant(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestBody SubmissionService.ExtensionInput in){return ApiResponse.ok(Map.of("id",service.grantExtension(u.id(),team,in)));}
    @PostMapping("/teams/{team}/extension-requests/{id}/reject") public ApiResponse<?> reject(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id,@RequestBody SubmissionService.ReasonInput in){service.rejectExtension(u.id(),team,id,in);return ApiResponse.ok(null);}
    @GetMapping("/teams/{team}/history") public ApiResponse<?> history(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@RequestParam(defaultValue="9223372036854775807") long beforeId){return ApiResponse.ok(service.history(u.id(),team,beforeId));}
    @PostMapping("/teams/{team}/notices/{id}/read") public ApiResponse<?> read(@AuthenticationPrincipal UserPrincipal u,@PathVariable long team,@PathVariable long id){service.readNotice(u.id(),team,id);return ApiResponse.ok(null);}
    @GetMapping("/notice-failures") public ApiResponse<?> failed(@AuthenticationPrincipal UserPrincipal u){
        if(!u.platformAdmin())throw new BusinessException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","仅管理员可查看通知故障队列");
        return ApiResponse.ok(db.queryForList("SELECT id,notice_status,notice_attempts FROM submission_event WHERE notice_status='FAILED' ORDER BY id"));
    }
    private ResponseEntity<Resource> download(FileService.Download f){return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(f.size()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(f.name(),StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(new FileSystemResource(f.path()));}
}
