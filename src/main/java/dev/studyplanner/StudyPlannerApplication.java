package dev.studyplanner;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class StudyPlannerApplication {
    public static void main(String[] args) {
        SpringApplication.run(StudyPlannerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
