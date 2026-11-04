package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.util.*;

/** Remap independent legacy IDs, keeping all B/C/D foreign keys and records intact. */
public class V10__Migrate_shared_project_data extends BaseJavaMigration {
 @Override public void migrate(Context context) {
  var db=new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(),true));
  var projects=new HashMap<Long,Long>();var teams=new HashMap<Long,Long>();
  db.update("UPDATE course_project SET description=COALESCE((SELECT MIN(description) FROM project_requirement WHERE project_id=course_project.id),'')");
  for(var p:db.queryForList("SELECT * FROM legacy_project ORDER BY id")){
   String status=p.get("status").equals("CLOSED")?"TERMINATED":p.get("status").toString();
   long id=insert(db,"INSERT INTO course_project(course_id,name,description,final_deadline,min_team_size,max_team_size,status,published_at,created_at,updated_at,demo_fixture) VALUES(?,?,?,?,?,?,?,?,?,?,FALSE)",p.get("course_id"),p.get("title"),p.get("description"),p.get("deadline"),p.get("min_team_size"),p.get("max_team_size"),status,p.get("published_at"),p.get("created_at"),p.get("updated_at"));
   projects.put(n(p,"id"),id);db.update("INSERT INTO legacy_project_mapping VALUES(?,?)",p.get("id"),id);
   insert(db,"INSERT INTO project_requirement(project_id,title,description) VALUES(?,?,?)",id,p.get("title"),p.get("description"));
   for(var c:db.queryForList("SELECT * FROM legacy_project_criterion WHERE project_id=? ORDER BY id",p.get("id")))
    insert(db,"INSERT INTO project_criterion(project_id,name,weight,description) VALUES(?,?,?,?)",id,c.get("name"),c.get("weight"),c.get("description"));
  }
  for(var t:db.queryForList("SELECT * FROM legacy_team ORDER BY id")){
   long id=insert(db,"INSERT INTO project_team(project_id,name,leader_id,created_at,updated_at) VALUES(?,?,?,?,?)",projects.get(n(t,"project_id")),t.get("name"),t.get("leader_id"),t.get("created_at"),t.get("updated_at"));
   teams.put(n(t,"id"),id);db.update("INSERT INTO legacy_team_mapping VALUES(?,?)",t.get("id"),id);
  }
  for(var m:db.queryForList("SELECT * FROM legacy_team_member ORDER BY id")){
   long id=insert(db,"INSERT INTO project_team_member(team_id,user_id,status,active,joined_at,created_at) VALUES(?,?,?,?,?,?)",teams.get(n(m,"team_id")),m.get("user_id"),m.get("status"),m.get("status").equals("ACTIVE"),m.get("joined_at"),m.get("created_at"));
   db.update("INSERT INTO legacy_member_mapping VALUES(?,?)",m.get("id"),id);
  }
  // Existing rubric definitions remain valid for historical scores. They are a fallback for legacy demo projects.
  // Real project configuration is snapshotted per project by ProjectScoringService on publish.
 }
 private static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 private static long insert(JdbcTemplate db,String sql,Object...args){var key=new GeneratedKeyHolder();db.update(c->{var s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();}
}
