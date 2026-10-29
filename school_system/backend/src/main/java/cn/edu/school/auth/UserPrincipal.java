package cn.edu.school.auth;
import java.util.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
public record UserPrincipal(Long id, String username, String displayName, String passwordHash,
                            boolean enabled, boolean platformAdmin) implements UserDetails {
    public static UserPrincipal from(AppUser user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getDisplayName(),
            user.getPasswordHash(), user.isEnabled(), user.isPlatformAdmin());
    }
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return platformAdmin ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")) : List.of();
    }
    public String getPassword() { return passwordHash; }
    public String getUsername() { return username; }
    public boolean isEnabled() { return enabled; }
}
