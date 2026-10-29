package cn.edu.school.auth;
import jakarta.persistence.*;
@Entity @Table(name="app_user")
public class AppUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, unique=true, length=64) private String username;
    @Column(nullable=false, length=100) private String displayName;
    @Column(nullable=false, length=100) private String passwordHash;
    @Column(nullable=false) private boolean enabled = true;
    @Column(nullable=false) private boolean platformAdmin;
    protected AppUser() {}
    public AppUser(String username, String displayName, String passwordHash, boolean platformAdmin) {
        this.username=username; this.displayName=displayName; this.passwordHash=passwordHash; this.platformAdmin=platformAdmin;
    }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public boolean isPlatformAdmin() { return platformAdmin; }
}
