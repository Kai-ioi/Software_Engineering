package cn.edu.school.course;
import cn.edu.school.common.BusinessException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class CourseService {
    private final CourseRepository courses;
    private final CourseMembershipRepository memberships;
    private final CoursePermission permission;
    public CourseService(CourseRepository courses,CourseMembershipRepository memberships,CoursePermission permission){
        this.courses=courses;this.memberships=memberships;this.permission=permission;
    }
    public List<CourseView> list(Long userId){
        return memberships.findByUserIdAndActiveTrueOrderByCourseIdAsc(userId).stream()
            .map(m -> view(m.getCourse(),m.getRole())).toList();
    }
    public CourseView detail(Long userId,Long id){
        var role=permission.requireMember(userId,id);
        var course=courses.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,"COURSE_NOT_FOUND","课程不存在"));
        return view(course,role);
    }
    public ManagementView management(Long userId,Long id){
        permission.requireTeacher(userId,id);
        return new ManagementView(id,memberships.countByCourseIdAndRoleAndActiveTrue(id,CourseRole.STUDENT));
    }
    private CourseView view(Course c,CourseRole role){return new CourseView(c.getId(),c.getName(),c.getSemester(),c.getDescription(),c.getStatus(),role);}
    public record CourseView(Long id,String name,String semester,String description,String status,CourseRole myRole){}
    public record ManagementView(Long courseId,long studentCount){}
}
