package cn.edu.school.audit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class AuditService {
    private final AuditRepository repository;
    private final cn.edu.school.workflow.WorkflowNotices notices;
    public AuditService(AuditRepository repository, cn.edu.school.workflow.WorkflowNotices notices){
        this.repository=repository; this.notices=notices;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(Long actorId,Long courseId,String action,String objectId,String summary){
        var record=repository.save(new AuditRecord(actorId,courseId,action,objectId,summary));
        notices.enqueue(record.getId(),courseId,action,objectId);
    }
}
