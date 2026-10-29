package cn.edu.school.config;
import cn.edu.school.auth.*;
import cn.edu.school.course.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
@Component @Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {
    private final UserRepository users; private final CourseRepository courses;
    private final CourseMembershipRepository memberships; private final PasswordEncoder encoder; private final String password;
    public DemoDataSeeder(UserRepository users,CourseRepository courses,CourseMembershipRepository memberships,
                          PasswordEncoder encoder,@Value("${app.demo-password}")String password){
        this.users=users;this.courses=courses;this.memberships=memberships;this.encoder=encoder;this.password=password;
    }
    @Override @Transactional public void run(String...args){
        if(users.count()>0) return;
        var teacher=users.save(new AppUser("teacher01","林老师",encoder.encode(password),false));
        var student=users.save(new AppUser("student01","陈同学",encoder.encode(password),false));
        var assistant=users.save(new AppUser("assistant01","周助教",encoder.encode(password),false));
        var outsider=users.save(new AppUser("student02","李同学",encoder.encode(password),false));
        users.save(new AppUser("admin01","平台管理员",encoder.encode(password),true));
        var c1=courses.save(new Course("软件工程","2026—2027 · 秋季学期","围绕课程项目开展组队协作、迭代交付与成果评审。","ACTIVE"));
        var c2=courses.save(new Course("项目实践","2026—2027 · 秋季学期","独立课程，用于验证跨课程身份与访问边界。","ACTIVE"));
        memberships.save(new CourseMembership(c1,teacher,CourseRole.TEACHER));
        memberships.save(new CourseMembership(c1,student,CourseRole.STUDENT));
        memberships.save(new CourseMembership(c1,assistant,CourseRole.TA));
        memberships.save(new CourseMembership(c2,outsider,CourseRole.STUDENT));
        memberships.save(new CourseMembership(c2,teacher,CourseRole.STUDENT));
    }
}
