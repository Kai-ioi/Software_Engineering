package cn.edu.school.audit;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AuditRepository extends JpaRepository<AuditRecord,Long>{
    Page<AuditRecord> findByCourseIdOrderByOccurredAtDesc(Long courseId,Pageable pageable);
}
