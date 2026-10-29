package cn.edu.school.file;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="stored_file")
public class StoredFile {
    @Id private UUID id;
    @Column(nullable=false) private Long courseId;
    @Column(nullable=false) private Long ownerId;
    @Column(nullable=false,length=240) private String originalName;
    @Column(nullable=false,length=12) private String extension;
    @Column(nullable=false) private Long sizeBytes;
    @Column(nullable=false,length=64) private String sha256;
    @Column(nullable=false) private OffsetDateTime createdAt;
    protected StoredFile(){}
    public StoredFile(UUID id,Long courseId,Long ownerId,String originalName,String extension,long size,String sha256){
        this.id=id;this.courseId=courseId;this.ownerId=ownerId;this.originalName=originalName;this.extension=extension;
        this.sizeBytes=size;this.sha256=sha256;this.createdAt=OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
    public UUID getId(){return id;} public Long getCourseId(){return courseId;} public Long getOwnerId(){return ownerId;}
    public String getOriginalName(){return originalName;} public String getExtension(){return extension;}
    public Long getSizeBytes(){return sizeBytes;} public String getSha256(){return sha256;}
}
