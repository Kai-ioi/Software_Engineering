package cn.edu.school.file;
import cn.edu.school.auth.UserPrincipal;
import cn.edu.school.common.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/files")
public class FileController {
    private final FileService files;
    public FileController(FileService files){this.files=files;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileService.FileView> upload(@AuthenticationPrincipal UserPrincipal user,@RequestParam Long courseId,
        @RequestPart MultipartFile file)throws IOException{return ApiResponse.ok(files.upload(user.id(),courseId,file));}
    @GetMapping("/{id}") public ResponseEntity<Resource> download(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id){
        var download=files.download(user.id(),id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(download.size())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(download.name(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(new FileSystemResource(download.path()));
    }
}
