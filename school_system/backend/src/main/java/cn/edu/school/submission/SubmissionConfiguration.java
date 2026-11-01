package cn.edu.school.submission;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration @EnableScheduling
public class SubmissionConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock businessClock(){return Clock.systemUTC();}
}
