package cn.edu.school.collaboration;

import cn.edu.school.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcProjectTeamGateway implements ProjectTeamGateway {
    private final JdbcTemplate jdbc;
    public JdbcProjectTeamGateway(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Map<String,Object> team(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM project_team WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"TEAM_NOT_FOUND","团队不存在");
        return rows.getFirst();
    }
    public Map<String,Object> project(long id) {
        var rows = jdbc.queryForList("SELECT * FROM course_project WHERE id=?",id);
        if (rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"PROJECT_NOT_FOUND","项目不存在");
        return rows.getFirst();
    }
    public boolean member(long team, long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project_team_member WHERE team_id=? AND user_id=? AND active=TRUE",Long.class,team,user)>0;
    }
    public List<Map<String,Object>> members(long team, long course) {
        return jdbc.queryForList("""
            SELECT u.id,u.display_name,u.username FROM project_team_member m
            JOIN app_user u ON u.id=m.user_id
            JOIN course_membership c ON c.user_id=u.id AND c.course_id=? AND c.active=TRUE AND c.role='STUDENT'
            WHERE m.team_id=? AND m.active=TRUE AND u.enabled=TRUE ORDER BY u.id
            """,course,team);
    }
}
