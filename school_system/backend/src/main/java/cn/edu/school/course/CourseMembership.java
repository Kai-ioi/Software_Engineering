package cn.edu.school.course;
import cn.edu.school.auth.AppUser;
import jakarta.persistence.*;
@Entity @Table(name="course_membership",uniqueConstraints=@UniqueConstraint(columnNames={"course_id","user_id"}))
public class CourseMembership {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="course_id") private Course course;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id") private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private CourseRole role;
    @Column(nullable=false) private boolean active=true;
    protected CourseMembership() {}
    public CourseMembership(Course course,AppUser user,CourseRole role){this.course=course;this.user=user;this.role=role;}
    public Course getCourse(){return course;} public AppUser getUser(){return user;} public CourseRole getRole(){return role;}
}
