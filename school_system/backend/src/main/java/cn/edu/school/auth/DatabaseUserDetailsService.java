package cn.edu.school.auth;
import org.springframework.stereotype.Service;
import org.springframework.security.core.userdetails.*;
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository users;
    public DatabaseUserDetailsService(UserRepository users) { this.users=users; }
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsername(username).map(UserPrincipal::from)
            .orElseThrow(() -> new UsernameNotFoundException("账号或密码不正确"));
    }
}
