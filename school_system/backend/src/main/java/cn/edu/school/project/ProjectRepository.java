package cn.edu.school.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByCourseIdOrderByCreatedAtDesc(Long courseId);
    List<Project> findByCourseIdAndStatusNotOrderByCreatedAtDesc(Long courseId, ProjectStatus status);
}
