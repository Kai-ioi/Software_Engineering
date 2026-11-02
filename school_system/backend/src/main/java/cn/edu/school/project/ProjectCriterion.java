package cn.edu.school.project;

import jakarta.persistence.*;

@Entity @Table(name = "project_criterion")
public class ProjectCriterion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private int weight;

    @Column(length = 1000)
    private String description;

    protected ProjectCriterion() {}

    ProjectCriterion(Project project, String name, int weight, String description) {
        this.project = project;
        this.name = name;
        this.weight = weight;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getWeight() { return weight; }
    public String getDescription() { return description; }
}
