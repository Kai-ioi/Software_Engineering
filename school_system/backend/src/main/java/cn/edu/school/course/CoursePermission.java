package cn.edu.school.course;
import cn.edu.school.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
@Service
public class CoursePermission {
    private final CourseMembershipRepository memberships;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public CoursePermission(CourseMembershipRepository memberships,org.springframework.jdbc.core.JdbcTemplate db){
        this.memberships=memberships;this.db=db;
    }
    public static final String ASSISTANT_TEAM_FILTER =
        " AND EXISTS(SELECT 1 FROM assistant_team_scope g WHERE g.team_id=t.id AND g.user_id=?)";
    public void requireTeamScope(CourseRole role,long user,long team){
        if(role==CourseRole.TA && db.queryForObject(
            "SELECT COUNT(*) FROM assistant_team_scope WHERE user_id=? AND team_id=?",Long.class,user,team)==0)
            throw new BusinessException(HttpStatus.FORBIDDEN,"ASSISTANT_SCOPE_REQUIRED","该团队尚未授权给当前助教");
    }
    public CourseRole requireMember(Long userId,Long courseId){
        return memberships.findByCourseIdAndUserIdAndActiveTrue(courseId,userId).map(CourseMembership::getRole)
            .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN,"COURSE_FORBIDDEN","你没有访问该课程的权限"));
    }
    public void requireActiveCourse(long course){
        if(!"ACTIVE".equals(db.queryForObject("SELECT status FROM course WHERE id=? FOR UPDATE",String.class,course)))
            throw new BusinessException(HttpStatus.CONFLICT,"COURSE_READ_ONLY","课程已结束");
    }

    public void requireTeacher(Long userId,Long courseId){
        if(requireMember(userId,courseId)!=CourseRole.TEACHER)
            throw new BusinessException(HttpStatus.FORBIDDEN,"TEACHER_REQUIRED","此操作仅限本课程教师");
    }
}
