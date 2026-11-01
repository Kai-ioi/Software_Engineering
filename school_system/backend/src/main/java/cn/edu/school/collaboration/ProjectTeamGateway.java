package cn.edu.school.collaboration;

import java.util.List;
import java.util.Map;

/** Read boundary shared with project/team management; no module A mutation APIs. */
public interface ProjectTeamGateway {
    Map<String, Object> team(long teamId, boolean lock);
    Map<String, Object> project(long projectId);
    boolean member(long teamId, long userId);
    List<Map<String, Object>> members(long teamId, long courseId);
}
