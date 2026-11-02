package cn.edu.school.team;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity @Table(name = "team_member", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "user_id"}))
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

    protected TeamMember() {}

    public TeamMember(Long teamId, Long userId, MemberStatus status) {
        this.teamId = teamId;
        this.userId = userId;
        this.status = status;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void approve() {
        this.status = MemberStatus.ACTIVE;
        this.joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void reject() { this.status = MemberStatus.REJECTED; }

    public void reapply() {
        this.status = MemberStatus.PENDING;
        this.joinedAt = null;
    }

    public void leave() { this.status = MemberStatus.LEFT; }

    public void moveTo(Long targetTeamId) { this.teamId = targetTeamId; }

    public Long getId() { return id; }
    public Long getTeamId() { return teamId; }
    public Long getUserId() { return userId; }
    public MemberStatus getStatus() { return status; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
