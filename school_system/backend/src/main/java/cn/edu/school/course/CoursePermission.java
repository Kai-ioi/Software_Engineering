package cn.edu.school.course;
import cn.edu.school.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
@Service
public class CoursePermission {
    private final CourseMembershipRepository memberships;
    public CoursePermission(CourseMembershipRepository memberships){this.memberships=memberships;}
    public CourseRole requireMember(Long userId,Long courseId){
        return memberships.findByCourseIdAndUserIdAndActiveTrue(courseId,userId).map(CourseMembership::getRole)
            .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN,"COURSE_FORBIDDEN","你没有访问该课程的权限"));
    }
    public void requireTeacher(Long userId,Long courseId){
        if(requireMember(userId,courseId)!=CourseRole.TEACHER)
            throw new BusinessException(HttpStatus.FORBIDDEN,"TEACHER_REQUIRED","此操作仅限本课程教师");
    }
}
