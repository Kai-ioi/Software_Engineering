package cn.edu.school.config;
import cn.edu.school.auth.*;
import cn.edu.school.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login", "/error").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated()
            .requestMatchers("/api/**").authenticated().anyRequest().denyAll());
        // Keep the default session-based CSRF protection. The SPA obtains a masked token from /csrf.
        http.formLogin(login -> login.loginProcessingUrl("/api/auth/login")
            .successHandler((req,res,auth) -> write(json,res,200,ApiResponse.ok(AuthController.UserView.from((UserPrincipal)auth.getPrincipal()))))
            .failureHandler((req,res,ex) -> write(json,res,401,ApiResponse.error("BAD_CREDENTIALS","账号或密码不正确"))));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID")
            .logoutSuccessHandler((req,res,auth) -> write(json,res,200,ApiResponse.ok(null))));
        http.requestCache(cache -> cache.disable());
        http.exceptionHandling(errors -> errors
            .authenticationEntryPoint((req,res,ex) -> write(json,res,401,ApiResponse.error("UNAUTHENTICATED","请先登录")))
            .accessDeniedHandler((req,res,ex) -> {
                if (ex instanceof CsrfException) {
                    write(json,res,403,ApiResponse.error("CSRF_INVALID","安全凭证已失效，请刷新页面后重试")); return;
                }
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth instanceof AnonymousAuthenticationToken) {
                    write(json,res,401,ApiResponse.error("UNAUTHENTICATED","请先登录"));
                } else write(json,res,403,ApiResponse.error("FORBIDDEN","没有执行此操作的权限"));
            }));
        http.headers(headers -> headers.contentTypeOptions(options -> {}));
        return http.build();
    }
    private static void write(ObjectMapper json,HttpServletResponse res,int status,Object body) throws IOException {
        res.setStatus(status); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(),body);
    }
}
