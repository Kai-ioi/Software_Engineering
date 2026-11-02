package cn.edu.school.submission;

import cn.edu.school.common.BusinessException;
import cn.edu.school.file.*;
import java.nio.file.*;
import java.util.UUID;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Call only after the submission service validates draft/version object access. */
@Component
public class SubmissionFiles {
    private final Path root;private final StoredFileRepository files;
    public SubmissionFiles(@Value("${app.storage-root}")String root,StoredFileRepository files){this.root=Path.of(root).toAbsolutePath().normalize();this.files=files;}
    public void requireAvailable(UUID id,long courseId){open(id,courseId);}
    public FileService.Download open(UUID id,long courseId){
        var f=files.findById(id).orElseThrow(()->missing());
        if(f.getCourseId()!=courseId)throw missing();
        Path path=root.resolve(id+".bin");
        try{if(!Files.isRegularFile(path)||Files.size(path)!=f.getSizeBytes())throw missing();}
        catch(IOException e){throw missing();}
        return new FileService.Download(path,f.getOriginalName(),f.getSizeBytes());
    }
    private BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"MATERIAL_UNAVAILABLE","材料文件暂不可用，请检查后重新准备");}
}
