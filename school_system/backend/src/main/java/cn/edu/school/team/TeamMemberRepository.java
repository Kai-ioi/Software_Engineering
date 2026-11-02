package cn.edu.school.team;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    List<TeamMember> findByTeamIdOrderByCreatedAtAsc(Long teamId);
    List<TeamMember> findByTeamIdInOrderByCreatedAtAsc(Collection<Long> teamIds);
    List<TeamMember> findByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);
    long countByTeamIdAndStatus(Long teamId, MemberStatus status);
}
