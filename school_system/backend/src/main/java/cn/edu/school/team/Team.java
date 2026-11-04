package cn.edu.school.team;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity @Table(name = "project_team")
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private Long leaderId;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version private long revision;
    public void touch(){updatedAt=OffsetDateTime.now(ZoneOffset.UTC);}
    protected Team() {}

    public Team(Long projectId, String name, Long leaderId) {
        this.projectId = projectId;
        this.name = name;
        this.leaderId = leaderId;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void transferLeadership(Long newLeaderId) {
        this.leaderId = newLeaderId;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getName() { return name; }
    public Long getLeaderId() { return leaderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
