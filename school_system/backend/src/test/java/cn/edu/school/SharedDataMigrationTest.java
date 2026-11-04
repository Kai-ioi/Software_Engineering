package cn.edu.school;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class SharedDataMigrationTest {
 @Test void independentLegacyIdsAreRemappedWithoutChangingExistingVersions()throws Exception{
  String url="jdbc:h2:mem:migration_shared;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(url,"sa","").target("8").load().migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
   s.execute("INSERT INTO app_user(username,display_name,password_hash,enabled,platform_admin) VALUES('u1','甲','hash',TRUE,FALSE),('u2','乙','hash',TRUE,FALSE)");
   s.execute("INSERT INTO course(name,semester,description,status) VALUES('课程','学期','要求','ACTIVE')");
   s.execute("INSERT INTO course_project(course_id,name,status,final_deadline) VALUES(1,'已有成果项目','IN_PROGRESS',CURRENT_TIMESTAMP)");
   s.execute("INSERT INTO project_team(project_id,name,leader_id) VALUES(1,'已有团队',1)");
   s.execute("INSERT INTO project_team_member(team_id,user_id) VALUES(1,1)");
   s.execute("INSERT INTO team_iteration(team_id,name,goal,sequence_no,planned_start,planned_end,status) VALUES(1,'历史轮次','交付',1,CURRENT_DATE,CURRENT_DATE,'ENDED')");
   s.execute("INSERT INTO submission_package(team_id,iteration_id) VALUES(1,1)");
   s.execute("INSERT INTO submission_version(package_id,team_id,iteration_id,version_no,submitted_by,summary,accepted_at,deadline_snapshot,late,total_bytes,request_key,payload_hash,receipt_code) VALUES(1,1,1,1,1,'原始成果',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE,0,RANDOM_UUID(),'hash','original-receipt')");
   s.execute("UPDATE submission_package SET current_version_id=1 WHERE id=1");
   s.execute("INSERT INTO team_delivery(team_id,final_version_id) VALUES(1,1)");
   s.execute("INSERT INTO project(course_id,title,description,deadline,min_team_size,max_team_size,status,created_at,updated_at) VALUES(1,'原A项目','完整要求',NULL,1,4,'CLOSED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.execute("INSERT INTO project_criterion(project_id,name,weight) VALUES(1,'质量',100)");
   s.execute("INSERT INTO team(project_id,name,leader_id,created_at,updated_at) VALUES(1,'原A团队',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
   s.execute("INSERT INTO team_member(team_id,user_id,status,created_at) VALUES(1,1,'ACTIVE',CURRENT_TIMESTAMP),(1,2,'PENDING',CURRENT_TIMESTAMP)");
  }
  Flyway.configure().dataSource(url,"sa","").load().migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
   assertThat(value(s,"SELECT COUNT(*) FROM course_project")).isEqualTo("2");
   assertThat(value(s,"SELECT project_id FROM legacy_project_mapping WHERE legacy_id=1")).isNotEqualTo("1");
   assertThat(value(s,"SELECT team_id FROM legacy_team_mapping WHERE legacy_id=1")).isNotEqualTo("1");
   assertThat(value(s,"SELECT status FROM course_project WHERE name='原A项目'")).isEqualTo("TERMINATED");
   assertThat(value(s,"SELECT COUNT(*) FROM project_team_member WHERE status='PENDING' AND active=FALSE")).isEqualTo("1");
   assertThat(value(s,"SELECT COUNT(*) FROM project_criterion WHERE name='质量'")).isEqualTo("1");
   assertThat(value(s,"SELECT receipt_code FROM submission_version WHERE id=1")).isEqualTo("original-receipt");
   assertThat(value(s,"SELECT final_version_id FROM team_delivery WHERE team_id=1")).isEqualTo("1");
   assertThat(value(s,"SELECT COUNT(*) FROM legacy_project")).isEqualTo("1");
  }
 }
 private String value(Statement s,String sql)throws Exception{try(var rs=s.executeQuery(sql)){rs.next();return rs.getString(1);}}
}
