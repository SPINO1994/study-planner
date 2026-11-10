package dev.studyplanner;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

final class TestDatabase {
    private static PostgreSQLContainer<?> container;

    private TestDatabase() {}

    static synchronized void configure(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            if (!url.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]*_test")) {
                throw new IllegalArgumentException("TEST_DB_URL must name a dedicated database ending in _test");
            }
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        } else {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("study_planner_test");
                container.start();
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }
}
