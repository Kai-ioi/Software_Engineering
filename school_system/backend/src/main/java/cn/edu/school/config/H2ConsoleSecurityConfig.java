package cn.edu.school.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Local development console; independent of the application's course permissions. */
@Configuration(proxyBeanMethods = false)
@Profile("demo & !postgres")
@ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
public class H2ConsoleSecurityConfig {
    @Bean
    @Order(0)
    SecurityFilterChain h2ConsoleSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(PathRequest.toH2Console());
        http.authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) ->
            new AuthorizationDecision(isLoopback(context.getRequest().getRemoteAddr()))));
        // H2 uses its own login and frame-based UI. These exceptions apply only to its servlet.
        http.csrf(csrf -> csrf.disable());
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        http.requestCache(cache -> cache.disable());
        return http.build();
    }

    private static boolean isLoopback(String address) {
        if (address == null) return false;
        try { return InetAddress.getByName(address).isLoopbackAddress(); }
        catch (UnknownHostException e) { return false; }
    }
}
