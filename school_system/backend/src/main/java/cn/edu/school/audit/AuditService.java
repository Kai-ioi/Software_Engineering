package cn.edu.school.audit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class AuditService {
    private final AuditRepository repository;
    public AuditService(AuditRepository repository){this.repository=repository;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(Long actorId,Long courseId,String action,String objectId,String summary){
        repository.save(new AuditRecord(actorId,courseId,action,objectId,summary));
    }
}
