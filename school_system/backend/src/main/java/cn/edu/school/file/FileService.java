package cn.edu.school.file;
import cn.edu.school.audit.AuditService;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
@Service
public class FileService {
    private static final Logger log=LoggerFactory.getLogger(FileService.class);
    private static final Set<String> EXTENSIONS=Set.of("pdf","docx","pptx","xlsx","txt","md","png","jpg","jpeg","zip");
    private static final long MAX=50L*1024*1024;
    private final Path root; private final StoredFileRepository files;
    private final CoursePermission permission; private final AuditService audit;
    public FileService(@Value("${app.storage-root}")String root,StoredFileRepository files,CoursePermission permission,AuditService audit){
        this.root=Path.of(root).toAbsolutePath().normalize();this.files=files;this.permission=permission;this.audit=audit;
    }
    @Transactional(rollbackFor=Exception.class)
    public FileView upload(Long userId,Long courseId,MultipartFile file)throws IOException{
        permission.requireMember(userId,courseId);
        var name=Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if(name.isBlank()||name.length()>240||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl))
            throw bad("文件名不符合要求");
        int dot=name.lastIndexOf('.');var ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!EXTENSIONS.contains(ext))throw bad("不支持该文件格式");
        if(file.isEmpty()||file.getSize()>MAX)throw bad("文件须大于0且不超过50 MB");
        Files.createDirectories(root);
        UUID id=UUID.randomUUID(); Path target=path(id); Path temp=Files.createTempFile(root,"upload-",".tmp");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){
                try{Files.deleteIfExists(temp);if(status!=STATUS_COMMITTED)Files.deleteIfExists(target);}
                catch(IOException e){log.warn("Temporary file cleanup requires attention: {}",id);}
            }
        });
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        long count=0;
        try(var in=file.getInputStream();var out=Files.newOutputStream(temp)){
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){count+=n;if(count>MAX)throw bad("单文件不得超过50 MB");out.write(buffer,0,n);digest.update(buffer,0,n);}
        }
        if(count==0||count!=file.getSize())throw bad("文件内容不完整，请重新上传");
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        var saved=files.save(new StoredFile(id,courseId,userId,name,ext,count,HexFormat.of().formatHex(digest.digest())));
        audit.append(userId,courseId,"MATERIAL_UPLOADED",id.toString(),"上传个人待提交材料");
        return view(saved);
    }
    @Transactional(readOnly=true)
    public Download download(Long userId,UUID id){
        var f=files.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,"FILE_NOT_FOUND","文件不存在"));
        permission.requireMember(userId,f.getCourseId());
        // Staged materials are private to the uploader. Formal-version access will have a separate policy.
        if(!f.getOwnerId().equals(userId))throw new BusinessException(HttpStatus.FORBIDDEN,"FILE_FORBIDDEN","没有查看该待提交材料的权限");
        var path=path(id);
        if(!Files.isRegularFile(path))throw new BusinessException(HttpStatus.NOT_FOUND,"FILE_NOT_FOUND","文件暂不可用");
        return new Download(path,f.getOriginalName(),f.getSizeBytes());
    }
    private Path path(UUID id){return root.resolve(id.toString()+".bin");}
    private BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_FILE",message);}
    private FileView view(StoredFile f){return new FileView(f.getId(),f.getOriginalName(),f.getSizeBytes(),f.getSha256());}
    public record FileView(UUID id,String name,long sizeBytes,String sha256){}
    public record Download(Path path,String name,long size){}
}
