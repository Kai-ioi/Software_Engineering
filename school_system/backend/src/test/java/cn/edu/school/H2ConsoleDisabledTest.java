package cn.edu.school;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:console-off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
@ActiveProfiles({"demo", "postgres"})
class H2ConsoleDisabledTest {
    @Autowired TestRestTemplate http;
    @Autowired ApplicationContext context;

    @Test void postgresProfileDisablesConsoleEvenWhenDemoDataIsEnabled() {
        assertThat(context.getEnvironment().getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
        assertThat(context.containsBean("h2ConsoleSecurity")).isFalse();
        assertThat(context.containsBean("h2Console")).isFalse();
        var response = http.getForEntity("/h2-console/", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
