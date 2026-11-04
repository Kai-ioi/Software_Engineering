package cn.edu.school.team;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity @Table(name = "project_team_member", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "user_id"}))
public class TeamMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemberStatus status;

    @Column
    private OffsetDateTime joinedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable=false) private boolean active;
    @Column(nullable=false) private boolean exitRequested;
    protected TeamMember() {}

    public boolean isExitRequested() { return exitRequested; }
    public void requestExit() { exitRequested=true; }
    public void rejectExit() { exitRequested=false; }

    public TeamMember(Long teamId, Long userId, MemberStatus status) {
        this.teamId = teamId;
        this.userId = userId;
        this.status = status;
        this.active = status == MemberStatus.ACTIVE;
        if(active) this.joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void approve() {
        this.status = MemberStatus.ACTIVE; this.active = true;
        this.joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void reject() { this.status = MemberStatus.REJECTED; this.active = false; }

    public void reapply() {
        this.status = MemberStatus.PENDING; this.active = false;
        this.joinedAt = null;
    }

    public void leave() { this.status = MemberStatus.LEFT; this.active = false; this.exitRequested=false; }


    public Long getId() { return id; }
    public Long getTeamId() { return teamId; }
    public Long getUserId() { return userId; }
    public MemberStatus getStatus() { return status; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
