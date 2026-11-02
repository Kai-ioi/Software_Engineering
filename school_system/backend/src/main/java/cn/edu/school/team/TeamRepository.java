package cn.edu.school.team;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
