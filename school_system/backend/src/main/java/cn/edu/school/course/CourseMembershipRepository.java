package cn.edu.school.course;
import java.util.*;
import org.springframework.data.jpa.repository.*;
public interface CourseMembershipRepository extends JpaRepository<CourseMembership,Long> {
    @EntityGraph(attributePaths="course")
    List<CourseMembership> findByUserIdAndActiveTrueOrderByCourseIdAsc(Long userId);
    Optional<CourseMembership> findByCourseIdAndUserIdAndActiveTrue(Long courseId,Long userId);
    long countByCourseIdAndRoleAndActiveTrue(Long courseId,CourseRole role);
}
