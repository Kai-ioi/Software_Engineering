package cn.edu.school.team;

import cn.edu.school.audit.AuditService;
import cn.edu.school.common.BusinessException;
import cn.edu.school.course.CoursePermission;
import cn.edu.school.course.CourseRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @Transactional(readOnly=true)
public class CourseTeamService {
 private final JdbcTemplate db; private final CoursePermission permission; private final AuditService audit;
 public CourseTeamService(JdbcTemplate db,CoursePermission permission,AuditService audit){this.db=db;this.permission=permission;this.audit=audit;}
 private long n(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
 private void check(boolean yes,String message){if(!yes)throw new BusinessException(HttpStatus.CONFLICT,"COURSE_TEAM_RULE",message);}
 private void lock(long user,long course){permission.requireMember(user,course);permission.requireActiveCourse(course);}
 private void student(long user,long course){check(permission.requireMember(user,course)==CourseRole.STUDENT,"仅本课程学生可执行组队操作");}
 private Map<String,Object> team(long course,long id){var rows=db.queryForList("SELECT * FROM course_team WHERE id=? AND course_id=?",id,course);check(!rows.isEmpty(),"课程团队不存在");return rows.getFirst();}
 private boolean selected(long id){return db.queryForObject("SELECT COUNT(*) FROM project_team WHERE course_team_id=?",Long.class,id)>0;}
 private void leader(long user,Map<String,Object> t){check(n(t,"leader_id")==user,"仅课程团队负责人可执行此操作");}
 private boolean reserved(long course,long user,long except){return db.queryForObject("SELECT COUNT(*) FROM course_team_member m JOIN course_team t ON t.id=m.team_id WHERE t.course_id=? AND m.user_id=? AND m.status IN ('ACTIVE','INVITED') AND m.team_id<>?",Long.class,course,user,except)>0;}
 public Map<String,Object> workspace(long user,long course){
  var role=permission.requireMember(user,course);var result=new LinkedHashMap<String,Object>();result.put("role",role);result.put("requiredProjects",db.queryForObject("SELECT required_projects FROM course WHERE id=?",Integer.class,course));result.put("minSize",3);result.put("maxSize",5);
  var teams=db.queryForList("SELECT t.*,u.display_name AS leader_name FROM course_team t JOIN app_user u ON u.id=t.leader_id WHERE t.course_id=? ORDER BY t.id",course);
  Long mine=null;var invitations=new ArrayList<Map<String,Object>>();
  for(var t:teams){long id=n(t,"id");var members=db.queryForList("SELECT m.*,u.display_name,u.username FROM course_team_member m JOIN app_user u ON u.id=m.user_id WHERE m.team_id=? ORDER BY m.id",id);
   for(var m:members)if(n(m,"user_id")==user){if("ACTIVE".equals(m.get("status")))mine=id;if("INVITED".equals(m.get("status"))){var v=new LinkedHashMap<String,Object>(m);v.put("team_name",t.get("name"));invitations.add(v);}}
   boolean privateRoster=role==CourseRole.TEACHER||members.stream().anyMatch(m->n(m,"user_id")==user&&"ACTIVE".equals(m.get("status")));
   t.put("members",members.stream().filter(m->privateRoster||"ACTIVE".equals(m.get("status"))).toList());
   t.put("projects",db.queryForList("SELECT p.id AS project_id,p.name AS title,p.status,t.id AS work_team_id FROM project_team t JOIN course_project p ON p.id=t.project_id WHERE t.course_team_id=? ORDER BY p.id",id));
  }
  result.put("teams",teams);result.put("myTeamId",mine);result.put("invitations",invitations);return result;
 }
 @Transactional public long create(long user,long course,String name){
  lock(user,course);student(user,course);check(name!=null&&!name.isBlank()&&name.trim().length()<=160,"请填写不超过160字的团队名称");check(!reserved(course,user,-1),"你已加入课程团队或有待确认邀请，请先处理邀请");
  check(db.queryForObject("SELECT COUNT(*) FROM course_team WHERE course_id=? AND name=?",Long.class,course,name.trim())==0,"同一课程内团队名称不能重复");
  db.update("INSERT INTO course_team(course_id,name,leader_id) VALUES(?,?,?)",course,name.trim(),user);long id=db.queryForObject("SELECT id FROM course_team WHERE course_id=? AND name=?",Long.class,course,name.trim());
  db.update("INSERT INTO course_team_member(team_id,user_id,status,answered_at) VALUES(?,?,'ACTIVE',CURRENT_TIMESTAMP)",id,user);db.update("UPDATE course SET course_team_enabled=TRUE WHERE id=?",course);
  audit.append(user,course,"COURSE_TEAM_CREATED",Long.toString(id),"创建课程团队「"+name.trim()+"」");return id;
 }
 public List<Map<String,Object>> candidates(long user,long course,long id){
  permission.requireMember(user,course);var t=team(course,id);leader(user,t);check(!selected(id),"选题后团队成员固定");
  return db.queryForList("SELECT u.id,u.display_name,u.username FROM app_user u JOIN course_membership c ON c.user_id=u.id WHERE c.course_id=? AND c.active=TRUE AND c.role='STUDENT' AND u.enabled=TRUE AND NOT EXISTS(SELECT 1 FROM course_team_member m JOIN course_team t ON t.id=m.team_id WHERE t.course_id=? AND m.user_id=u.id AND m.status IN ('ACTIVE','INVITED')) ORDER BY u.display_name,u.id",course,course);
 }
 @Transactional public void invite(long user,long course,long id,long studentId){
  lock(user,course);student(user,course);var t=team(course,id);leader(user,t);check(!selected(id),"选题后团队成员固定");student(studentId,course);
  check(Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM app_user WHERE id=?",Boolean.class,studentId)),"学生账号已停用");check(!reserved(course,studentId,-1),"该学生已加入课程团队或已有待确认邀请");
  check(db.queryForObject("SELECT COUNT(*) FROM course_team_member WHERE team_id=? AND status IN ('ACTIVE','INVITED')",Long.class,id)<5,"团队成员及待确认邀请已达5人，请先处理邀请");
  var old=db.queryForList("SELECT id FROM course_team_member WHERE team_id=? AND user_id=?",id,studentId);
  if(old.isEmpty())db.update("INSERT INTO course_team_member(team_id,user_id,status) VALUES(?,?,'INVITED')",id,studentId);
  else db.update("UPDATE course_team_member SET status='INVITED',invited_at=CURRENT_TIMESTAMP,answered_at=NULL WHERE team_id=? AND user_id=?",id,studentId);
  audit.append(user,course,"COURSE_TEAM_INVITED",id+":"+studentId,"邀请课程学生加入「"+t.get("name")+"」");
 }
 @Transactional public void answer(long user,long course,long id,boolean accept){
  lock(user,course);student(user,course);team(course,id);check(!selected(id),"团队已选题，无法再接纳成员");
  check(db.queryForObject("SELECT COUNT(*) FROM course_team_member WHERE team_id=? AND user_id=? AND status='INVITED'",Long.class,id,user)==1,"邀请已处理或不存在");
  if(accept){check(!reserved(course,user,id),"你已加入该课程的其他团队");check(db.queryForObject("SELECT COUNT(*) FROM course_team_member WHERE team_id=? AND status='ACTIVE'",Long.class,id)<5,"团队已满5人");}
  db.update("UPDATE course_team_member SET status=?,answered_at=CURRENT_TIMESTAMP WHERE team_id=? AND user_id=?",accept?"ACTIVE":"DECLINED",id,user);
  audit.append(user,course,accept?"COURSE_TEAM_JOINED":"COURSE_TEAM_DECLINED",Long.toString(id),accept?"接受课程组队邀请":"谢绝课程组队邀请");
 }
 @Transactional public void cancel(long user,long course,long id,long studentId){
  lock(user,course);var t=team(course,id);leader(user,t);check(!selected(id),"团队已选题");check(db.update("UPDATE course_team_member SET status='CANCELLED',answered_at=CURRENT_TIMESTAMP WHERE team_id=? AND user_id=? AND status='INVITED'",id,studentId)==1,"该邀请已处理");
  audit.append(user,course,"COURSE_TEAM_INVITATION_CANCELLED",Long.toString(id),"撤销未确认的组队邀请");
 }
 @Transactional public void policy(long user,long course,int required){
  lock(user,course);permission.requireTeacher(user,course);check(required>=1&&required<=10,"每队项目数须为1—10");check(db.queryForObject("SELECT COUNT(*) FROM project_team p JOIN course_team t ON t.id=p.course_team_id WHERE t.course_id=?",Long.class,course)==0,"已有团队选题，不能再改变完成数量要求");
  db.update("UPDATE course SET required_projects=? WHERE id=?",required,course);audit.append(user,course,"COURSE_SELECTION_POLICY",Long.toString(course),"每队需完成"+required+"个项目；团队3—5人");
 }
 @Transactional public void select(long user,long course,long id,List<Long> projects){
  lock(user,course);student(user,course);var t=team(course,id);leader(user,t);check(!selected(id),"团队已选定项目，请从团队工作入口继续完成");
  int required=db.queryForObject("SELECT required_projects FROM course WHERE id=?",Integer.class,course);
  check(projects!=null&&projects.size()==required&&new HashSet<>(projects).size()==required,"请选择"+required+"个不同项目");
  var members=db.queryForList("SELECT m.user_id FROM course_team_member m JOIN course_membership c ON c.user_id=m.user_id AND c.course_id=? JOIN app_user u ON u.id=m.user_id WHERE m.team_id=? AND m.status='ACTIVE' AND c.active=TRUE AND c.role='STUDENT' AND u.enabled=TRUE ORDER BY m.user_id",course,id);
  check(members.size()>=3&&members.size()<=5,"至少3名学生确认加入后才能选项目，最多5人");
  check(db.queryForObject("SELECT COUNT(*) FROM course_team_member WHERE team_id=? AND status='INVITED'",Long.class,id)==0,"请先让组员处理邀请，或撤销尚未确认的邀请");
  for(long project:projects){var rows=db.queryForList("SELECT * FROM course_project WHERE id=? AND course_id=? FOR UPDATE",project,course);check(!rows.isEmpty(),"项目不属于当前课程");var p=rows.getFirst();check(List.of("PUBLISHED","IN_PROGRESS").contains(p.get("status")),"只能选择已发布或进行中的项目");
   check(db.queryForObject("SELECT COALESCE(SUM(weight),0) FROM project_criterion WHERE project_id=?",Integer.class,project)==100,"该项目评分标准尚未完善，请联系教师");
   check(p.get("final_deadline")!=null&&((java.time.OffsetDateTime)p.get("final_deadline")).isAfter(java.time.OffsetDateTime.now()),"项目已过最终截止，不能选题");
   var existing=db.queryForList("SELECT id,leader_id,course_team_id FROM project_team WHERE project_id=? AND name=?",project,t.get("name"));long work;
   if(existing.isEmpty()){
    db.update("INSERT INTO project_team(project_id,name,leader_id,course_team_id) VALUES(?,?,?,?)",project,t.get("name"),user,id);work=db.queryForObject("SELECT id FROM project_team WHERE project_id=? AND course_team_id=?",Long.class,project,id);
    for(var m:members)db.update("INSERT INTO project_team_member(team_id,user_id,status,active,joined_at) VALUES(?,?,'ACTIVE',TRUE,CURRENT_TIMESTAMP)",work,m.get("user_id"));
   }else{
    var old=existing.getFirst();work=n(old,"id");var before=db.queryForList("SELECT user_id FROM project_team_member WHERE team_id=? AND active=TRUE ORDER BY user_id",Long.class,work);var after=members.stream().map(m->n(m,"user_id")).toList();
    check(old.get("course_team_id")==null&&n(old,"leader_id")==user&&before.equals(after),"该项目存在不同成员的同名历史团队，请更换课程团队名称或联系教师核实");db.update("UPDATE project_team SET course_team_id=?,revision=revision+1 WHERE id=?",id,work);
   }
   audit.append(user,course,"COURSE_TEAM_PROJECT_SELECTED",Long.toString(work),"课程团队「"+t.get("name")+"」选择项目「"+p.get("name")+"」");
  }
 }
}
