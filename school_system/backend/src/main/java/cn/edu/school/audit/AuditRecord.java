package cn.edu.school.audit;
import java.time.OffsetDateTime;
import jakarta.persistence.*;
@Entity @Table(name="audit_record")
public class AuditRecord {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long actorId;
    @Column(nullable=false) private Long courseId;
    @Column(nullable=false,length=80) private String action;
    @Column(nullable=false,length=80) private String objectId;
    @Column(nullable=false,length=500) private String summary;
    @Column(nullable=false) private OffsetDateTime occurredAt;
    protected AuditRecord(){}
    public AuditRecord(Long actorId,Long courseId,String action,String objectId,String summary){
        this.actorId=actorId;this.courseId=courseId;this.action=action;this.objectId=objectId;this.summary=summary;
        this.occurredAt=OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
    public Long getId(){return id;}
    public Long getActorId(){return actorId;} public String getAction(){return action;}
    public String getObjectId(){return objectId;} public String getSummary(){return summary;}
    public OffsetDateTime getOccurredAt(){return occurredAt;}
}
