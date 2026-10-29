package cn.edu.school.course;
import jakarta.persistence.*;
@Entity @Table(name="course")
public class Course {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=160) private String name;
    @Column(nullable=false,length=40) private String semester;
    @Column(nullable=false,length=1000) private String description;
    @Column(nullable=false,length=24) private String status;
    protected Course() {}
    public Course(String name,String semester,String description,String status) {
        this.name=name; this.semester=semester; this.description=description; this.status=status;
    }
    public Long getId(){return id;} public String getName(){return name;}
    public String getSemester(){return semester;} public String getDescription(){return description;}
    public String getStatus(){return status;}
}
