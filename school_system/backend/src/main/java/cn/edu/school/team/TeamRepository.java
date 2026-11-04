package cn.edu.school.team;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Team t where t.id=:id")
    java.util.Optional<Team> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    List<Team> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
