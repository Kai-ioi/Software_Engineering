package cn.edu.school;
import cn.edu.school.team.CourseTeamService;
import cn.edu.school.project.ProjectService;
import cn.edu.school.collaboration.CollaborationService;
import cn.edu.school.common.BusinessException;
import cn.edu.school.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:course_teams;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.submission.jobs-enabled=false"})
@ActiveProfiles("demo")
class CourseTeamIntegrationTest {
 @Autowired cn.edu.school.workflow.WorkflowNotices notices;
 @Autowired CourseTeamService service;@Autowired ProjectService projects;@Autowired CollaborationService collaboration;@Autowired UserRepository users;@Autowired JdbcTemplate db;
 long u(String name){return users.findByUsername(name).orElseThrow().getId();}
 long course(){db.update("INSERT INTO course(name,semester,description,status) VALUES('课程固定组队测试','2026','独立验收','ACTIVE')");long c=db.queryForObject("SELECT MAX(id) FROM course",Long.class);for(String name:List.of("teacher01","student01","student03","student04","student05"))db.update("INSERT INTO course_membership(course_id,user_id,role,active) VALUES(?,?,?,TRUE)",c,u(name),name.equals("teacher01")?"TEACHER":"STUDENT");return c;}
 long project(long c,String name){long p=projects.create(u("teacher01"),c,new ProjectService.ProjectInput(name,"交付可验收的项目成果",OffsetDateTime.now().plusDays(5),3,5,List.of(new ProjectService.ProjectInput.CriterionInput("业务与实现",100,"功能和异常均可验收")))).id();projects.publish(u("teacher01"),c,p);return p;}
 long ready(long c){long t=service.create(u("student01"),c,"固定团队");for(String name:List.of("student03","student04")){service.invite(u("student01"),c,t,u(name));service.answer(u(name),c,t,true);}return t;}
 @Test void invitationAcceptanceAndTwoProjectsUseExactlyTheSameRoster(){long c=course(),t=ready(c);long a=project(c,"失物招领"),b=project(c,"实验室预约");service.select(u("student01"),c,t,List.of(a,b));
  var linked=db.queryForList("SELECT id FROM project_team WHERE course_team_id=? ORDER BY id",Long.class,t);assertThat(linked).hasSize(2);
  for(long id:linked){var w=collaboration.workspace(u("student03"),id);assertThat((List<?>)w.get("members")).hasSize(3);assertThat(w).containsEntry("isLeader",false);}
  assertThat(db.queryForObject("SELECT COUNT(DISTINCT user_id) FROM project_team_member WHERE team_id IN (SELECT id FROM project_team WHERE course_team_id=?) AND active=TRUE",Long.class,t)).isEqualTo(3L);
  assertThatThrownBy(()->service.invite(u("student01"),c,t,u("student05"))).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.create(u("student03"),c,"重复团队")).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.policy(u("teacher01"),c,3)).isInstanceOf(BusinessException.class);
 }
 @Test void invitationMustBeAcceptedAndOnlyLeaderCanChoose(){long c=course(),t=service.create(u("student01"),c,"邀请测试");service.invite(u("student01"),c,t,u("student03"));assertThat(service.workspace(u("student03"),c).get("myTeamId")).isNull();assertThat((List<?>)service.workspace(u("student03"),c).get("invitations")).hasSize(1);
  notices.dispatch();assertThat(db.queryForObject("SELECT COUNT(*) FROM workflow_notice WHERE course_id=? AND user_id=? AND href=?",Long.class,c,u("student03"),"/courses/"+c+"/teams")).isEqualTo(1L);
  assertThatThrownBy(()->service.create(u("student03"),c,"有待处理邀请")).isInstanceOf(BusinessException.class);service.answer(u("student03"),c,t,false);long other=service.create(u("student03"),c,"其他团队");assertThat(other).isNotEqualTo(t);
  assertThatThrownBy(()->service.invite(u("student01"),c,t,u("student03"))).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.select(u("student01"),c,t,List.of(project(c,"项目甲"),project(c,"项目乙")))).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.select(u("student04"),c,t,List.of())).isInstanceOf(BusinessException.class);
 }
 @Test void invalidSecondProjectRollsBackFirstEnrollment(){long c=course(),t=ready(c),p=project(c,"有效项目"),foreign=project(course(),"另一课程项目");assertThatThrownBy(()->service.select(u("student01"),c,t,List.of(p,foreign))).isInstanceOf(BusinessException.class);assertThat(db.queryForObject("SELECT COUNT(*) FROM project_team WHERE course_team_id=?",Long.class,t)).isZero();assertThatThrownBy(()->service.select(u("student01"),c,t,List.of(p,p))).isInstanceOf(BusinessException.class);}
 @Test void pendingInvitationCanBeCancelledAndDoesNotJoinAutomatically(){long c=course(),t=ready(c),student=u("student05");service.invite(u("student01"),c,t,student);long a=project(c,"项目一"),b=project(c,"项目二");assertThatThrownBy(()->service.select(u("student01"),c,t,List.of(a,b))).isInstanceOf(BusinessException.class);service.cancel(u("student01"),c,t,student);service.select(u("student01"),c,t,List.of(a,b));assertThat(service.workspace(student,c).get("myTeamId")).isNull();}
}
