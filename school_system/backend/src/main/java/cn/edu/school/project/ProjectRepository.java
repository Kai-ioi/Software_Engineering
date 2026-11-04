package cn.edu.school.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Project p where p.id=:id")
    java.util.Optional<Project> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    List<Project> findByCourseIdOrderByCreatedAtDesc(Long courseId);
    List<Project> findByCourseIdAndStatusNotOrderByCreatedAtDesc(Long courseId, ProjectStatus status);
}
