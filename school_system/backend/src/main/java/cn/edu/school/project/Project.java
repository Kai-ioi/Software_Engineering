package cn.edu.school.project;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "project")
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long courseId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description;

    @Column
    private OffsetDateTime deadline;

    @Column(nullable = false)
    private int minTeamSize;

    @Column(nullable = false)
    private int maxTeamSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProjectStatus status;

    @Column
    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ProjectCriterion> criteria = new ArrayList<>();

    protected Project() {}

    public Project(Long courseId, String title, String description, OffsetDateTime deadline,
                   int minTeamSize, int maxTeamSize) {
        this.courseId = courseId;
        this.title = title;
        this.description = description;
        this.deadline = deadline;
        this.minTeamSize = minTeamSize;
        this.maxTeamSize = maxTeamSize;
        this.status = ProjectStatus.DRAFT;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    public void update(String title, String description, OffsetDateTime deadline, int minTeamSize, int maxTeamSize) {
        this.title = title;
        this.description = description;
        this.deadline = deadline;
        this.minTeamSize = minTeamSize;
        this.maxTeamSize = maxTeamSize;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void replaceCriteria(List<ProjectCriterion> next) {
        this.criteria.clear();
        this.criteria.addAll(next);
    }

    public void publish() {
        this.status = ProjectStatus.PUBLISHED;
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void close() {
        this.status = ProjectStatus.CLOSED;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void archive() {
        this.status = ProjectStatus.ARCHIVED;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() { return id; }
    public Long getCourseId() { return courseId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public OffsetDateTime getDeadline() { return deadline; }
    public int getMinTeamSize() { return minTeamSize; }
    public int getMaxTeamSize() { return maxTeamSize; }
    public ProjectStatus getStatus() { return status; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public List<ProjectCriterion> getCriteria() { return criteria; }
}
