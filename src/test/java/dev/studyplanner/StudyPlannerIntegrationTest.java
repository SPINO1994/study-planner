package dev.studyplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth-requests-per-minute=1000", "spring.datasource.password="})
class StudyPlannerIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.configure(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE app_user CASCADE").update();
    }

    @Test
    void uiAssetsArePublicWhileStudyDataRequiresAuthentication() {
        var page = http.getForEntity("/", String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Study Planner", "/app.js", "/app.css");
        assertThat(http.getForEntity("/app.js", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/app.css", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/topics", null).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/plans", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void restWorkflowTracksQuizAccuracyAndCountsCompletedMinutesOnce() {
        String token = register();
        var topic = topic(token, "Transactions", 120);
        String topicId = topic.path("id").asText();
        var quiz = Map.of("requestId", UUID.randomUUID(), "correctAnswers", 3, "totalQuestions", 10);
        var firstQuiz = post("/api/topics/" + topicId + "/quiz-results", token, quiz);
        assertThat(firstQuiz.getStatusCode().value()).isEqualTo(201);
        assertThat(post("/api/topics/" + topicId + "/quiz-results", token, quiz).getBody()).isEqualTo(firstQuiz.getBody());
        var progress = get("/api/progress", token).getBody();
        assertThat(progress.path("topics").isArray()).as("Progress response: %s", progress).isTrue();
        assertThat(progress.path("topics")).as("Progress response: %s", progress).hasSize(1);
        assertThat(progress.path("topics").get(0).path("accuracy").asDouble()).isEqualTo(30.0);
        assertThat(progress.path("topics").get(0).path("recentQuizCount").asInt()).isEqualTo(1);

        var plan = post("/api/plans", token, planRequest(UUID.randomUUID(), today(), 2, 45));
        assertThat(plan.getStatusCode().value()).isEqualTo(201);
        assertThat(plan.getBody().path("unallocatedMinutes").asInt()).isZero();
        var session = plan.getBody().path("sessions").get(0);
        String completeUrl = "/api/sessions/" + session.path("id").asText() + "/complete";
        var completed = post(completeUrl, token, Map.of());
        assertThat(completed.getBody().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(post(completeUrl, token, Map.of()).getBody()).isEqualTo(completed.getBody());
        assertThat(get("/api/progress", token).getBody().path("completedMinutes").asInt())
                .isEqualTo(session.path("minutes").asInt());
    }

    @Test
    void storesOnlyHashedCredentialsAndRejectsExpiredOrRevokedTokens() {
        String email = UUID.randomUUID() + "@example.com";
        var credentials = Map.of("email", email, "password", "correct-horse-123");
        String token = post("/api/auth/register", null, credentials).getBody().path("accessToken").asText();
        assertThat(jdbc.sql("SELECT password_hash FROM app_user").query(String.class).single())
                .startsWith("$2").isNotEqualTo("correct-horse-123");
        assertThat(jdbc.sql("SELECT token_hash FROM access_token").query(String.class).single())
                .hasSize(64).isNotEqualTo(token);
        var login = post("/api/auth/login", null, credentials);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String secondToken = login.getBody().path("accessToken").asText();
        assertThat(post("/api/auth/logout", token, Map.of()).getStatusCode().value()).isEqualTo(204);
        assertThat(get("/api/topics", token).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/topics", secondToken).getStatusCode().value()).isEqualTo(200);
        jdbc.sql("UPDATE access_token SET expires_at = now() - interval '1 hour'").update();
        assertThat(get("/api/topics", secondToken).getStatusCode().value()).isEqualTo(401);
        assertThat(post("/api/auth/login", null, Map.of("email", email, "password", "incorrect-password"))
                .getStatusCode().value()).isEqualTo(401);
        assertThat(post("/api/auth/register", null, credentials).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void usersCannotReadOrModifyEachOthersDataThroughRestOrMcp() {
        String alice = register();
        String bob = register();
        var topic = topic(alice, "Private topic", 120);
        var planResponse = post("/api/plans", alice, planRequest(UUID.randomUUID(), today(), 1, 30));
        assertThat(planResponse.getStatusCode().value()).as("Plan response: %s", planResponse.getBody()).isEqualTo(201);
        var plan = planResponse.getBody();
        assertThat(get("/api/topics", bob).getBody()).isEmpty();
        assertThat(get("/api/plans/" + plan.path("id").asText(), bob).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/plans/" + plan.path("id").asText() + "/cancel", bob, Map.of()).getStatusCode().value())
                .isEqualTo(404);
        assertThat(post("/api/sessions/" + plan.path("sessions").get(0).path("id").asText() + "/complete", bob, Map.of())
                .getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/topics/" + topic.path("id").asText() + "/quiz-results", bob,
                Map.of("requestId", UUID.randomUUID(), "correctAnswers", 1, "totalQuestions", 2))
                .getStatusCode().value()).isEqualTo(404);
        var mcp = callTool(bob, "get_study_plan", Map.of("planId", plan.path("id").asText()));
        assertThat(mcp.path("result").path("isError").asBoolean()).isTrue();
        assertThat(mcp.toString()).doesNotContain("Private topic");
        var spoof = callTool(bob, "get_progress", Map.of("userId", jdbc.sql("SELECT id FROM app_user ORDER BY created_at LIMIT 1")
                .query(UUID.class).single().toString()));
        assertThat(spoof.path("result").path("isError").asBoolean()).isTrue();
    }

    @Test
    void validationRejectsInvalidScoresDatesBudgetsAndUnknownFields() {
        String token = register();
        String id = topic(token, "Validation", 60).path("id").asText();
        assertThat(post("/api/topics/" + id + "/quiz-results", token,
                Map.of("requestId", UUID.randomUUID(), "correctAnswers", 11, "totalQuestions", 10))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/topics/" + id + "/quiz-results", token,
                Map.of("requestId", UUID.randomUUID(), "totalQuestions", 10)).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today().minusDays(1), 1, 30))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today(), 15, 30))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today(), 1, 0))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/topics?page=-1", token).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/topics", token, Map.of("subject", "Java", "name", "Other", "priority", 3,
                "estimatedMinutes", 60, "ownerId", UUID.randomUUID())).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void concurrentRetriesCreateOnePlanAndChangedRequestsConflict() throws Exception {
        String token = register();
        topic(token, "Concurrency", 600);
        UUID requestId = UUID.randomUUID();
        var request = planRequest(requestId, today(), 3, 60);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<ResponseEntity<JsonNode>>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                jobs.add(() -> post("/api/plans", token, request));
            }
            List<String> ids = new ArrayList<>();
            for (var future : executor.invokeAll(jobs)) {
                var response = future.get();
                assertThat(response.getStatusCode().value()).as("Plan response: %s", response.getBody()).isEqualTo(201);
                ids.add(response.getBody().path("id").asText());
            }
            assertThat(ids).containsOnly(ids.get(0));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM study_plan").query(Integer.class).single()).isEqualTo(1);
        assertThat(post("/api/plans", token, planRequest(requestId, today(), 3, 45)).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today(), 3, 60))
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void cancellationPreservesCompletedWorkAndRejectsFutureOrSkippedCompletions() {
        String token = register();
        topic(token, "Sessions", 300);
        var planResponse = post("/api/plans", token, planRequest(UUID.randomUUID(), today(), 2, 60));
        assertThat(planResponse.getStatusCode().value()).isEqualTo(201);
        var plan = planResponse.getBody();
        String completedId = plan.path("sessions").get(0).path("id").asText();
        assertThat(post("/api/sessions/" + completedId + "/complete", token, Map.of()).getStatusCode().value()).isEqualTo(200);
        String sessionId = plan.path("sessions").get(2).path("id").asText();
        assertThat(post("/api/sessions/" + sessionId + "/complete", token, Map.of()).getStatusCode().value()).isEqualTo(409);
        var cancelled = post("/api/plans/" + plan.path("id").asText() + "/cancel", token, Map.of());
        assertThat(cancelled.getBody().path("sessions")).anyMatch(s -> s.path("status").asText().equals("COMPLETED"));
        assertThat(cancelled.getBody().path("sessions")).filteredOn(s -> !s.path("id").asText().equals(completedId))
                .allMatch(s -> s.path("status").asText().equals("SKIPPED"));
        assertThat(get("/api/progress", token).getBody().path("completedMinutes").asInt()).isEqualTo(30);
        assertThat(post("/api/sessions/" + sessionId + "/complete", token, Map.of()).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today().plusDays(1), 2, 60))
                .getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void mcpSupportsDiscoveryAndAnEntirePlanningWorkflow() {
        String token = register();
        var initialized = rpc(token, "initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "integration-test", "version", "1.0")));
        assertThat(initialized.path("result").path("serverInfo").path("name").asText()).isEqualTo("study-planner");
        var tools = rpc(token, "tools/list", Map.of()).path("result").path("tools");
        assertThat(tools).hasSize(12);
        assertThat(tools).anyMatch(t -> t.path("name").asText().equals("create_study_plan"));
        var topicResult = callTool(token, "create_topic", Map.of("subject", "Java", "name", "Collections",
                "priority", 4, "estimatedMinutes", 90));
        assertThat(topicResult.path("result").path("isError").asBoolean()).isFalse();
        String topicId = topicResult.path("result").path("structuredContent").path("data").path("id").asText();
        assertThat(topicId).isNotBlank();
        var quiz = callTool(token, "record_quiz_result", Map.of("topicId", topicId,
                "result", Map.of("requestId", UUID.randomUUID(), "correctAnswers", 4, "totalQuestions", 10)));
        assertThat(quiz.path("result").path("isError").asBoolean()).isFalse();
        var progress = callTool(token, "get_progress", Map.of()).path("result").path("structuredContent").path("data");
        assertThat(progress.path("topics")).as("MCP progress: %s", progress).hasSize(1);
        assertThat(progress.path("topics").get(0).path("accuracy").asDouble()).isEqualTo(40.0);
        var plan = callTool(token, "create_study_plan", planRequest(UUID.randomUUID(), today(), 2, 45));
        assertThat(plan.path("result").path("isError").asBoolean()).isFalse();
        var data = plan.path("result").path("structuredContent").path("data");
        assertThat(data.path("sessions")).hasSize(4);
        var completed = callTool(token, "complete_study_session", Map.of("sessionId", data.path("sessions").get(0).path("id").asText()));
        assertThat(completed.path("result").path("structuredContent").path("data").path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void mcpRejectsBadArgumentsUnauthenticatedRequestsAndUntrustedOrigins() {
        String token = register();
        var invalid = callTool(token, "create_study_plan", planRequest(UUID.randomUUID(), today(), 999, -1));
        assertThat(invalid.path("result").path("isError").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM study_plan").query(Integer.class).single()).isZero();
        var headers = headers(null);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        assertThat(http.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(Map.of("jsonrpc", "2.0", "id", 1,
                "method", "tools/list"), headers), String.class).getStatusCode().value()).isEqualTo(401);
        headers = headers(token);
        headers.setOrigin("https://untrusted.example");
        assertThat(http.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(Map.of(), headers), String.class)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void latestFiveQuizzesUseQuestionWeightedAccuracyAndRejectChangedRetries() {
        String token = register();
        String topicId = topic(token, "Quiz history", 120).path("id").asText();
        for (int index = 0; index < 6; index++) {
            int total = index == 5 ? 50 : 10;
            int correct = index == 0 ? 10 : index == 5 ? 50 : 0;
            var result = post("/api/topics/" + topicId + "/quiz-results", token,
                    Map.of("requestId", UUID.randomUUID(), "correctAnswers", correct, "totalQuestions", total));
            assertThat(result.getStatusCode().value()).isEqualTo(201);
        }
        var progress = get("/api/progress", token).getBody().path("topics").get(0);
        assertThat(progress.path("recentQuizCount").asInt()).isEqualTo(5);
        assertThat(progress.path("accuracy").asDouble()).isEqualTo(55.56);
        UUID id = UUID.randomUUID();
        assertThat(post("/api/topics/" + topicId + "/quiz-results", token,
                Map.of("requestId", id, "correctAnswers", 1, "totalQuestions", 2)).getStatusCode().value()).isEqualTo(201);
        assertThat(post("/api/topics/" + topicId + "/quiz-results", token,
                Map.of("requestId", id, "correctAnswers", 2, "totalQuestions", 2)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void disjointPlansStillReserveTheSameFiniteWorkload() {
        String token = register();
        topic(token, "Finite work", 90);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today(), 1, 60))
                .getStatusCode().value()).isEqualTo(201);
        var second = post("/api/plans", token, planRequest(UUID.randomUUID(), today().plusDays(1), 1, 60));
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody().path("unallocatedMinutes").asInt()).isEqualTo(30);
        assertThat(post("/api/plans", token, planRequest(UUID.randomUUID(), today().plusDays(2), 1, 60))
                .getStatusCode().value()).isEqualTo(409);
    }

    private String register() {
        var result = post("/api/auth/register", null,
                Map.of("email", UUID.randomUUID() + "@example.com", "password", "correct-horse-123"));
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        return result.getBody().path("accessToken").asText();
    }

    private JsonNode topic(String token, String name, int minutes) {
        var response = post("/api/topics", token,
                Map.of("subject", "Java", "name", name, "priority", 3, "estimatedMinutes", minutes));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody();
    }

    private Map<String, Object> planRequest(UUID id, LocalDate start, int days, int minutes) {
        return Map.of("requestId", id, "startDate", start.toString(), "days", days, "dailyMinutes", minutes);
    }

    private LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }

    private ResponseEntity<JsonNode> post(String url, String token, Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String url, String token) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) { headers.setBearerAuth(token); }
        return headers;
    }

    private JsonNode callTool(String token, String name, Object arguments) {
        return rpc(token, "tools/call", Map.of("name", name, "arguments", arguments));
    }

    private JsonNode rpc(String token, String method, Object params) {
        var headers = headers(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        var response = http.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(Map.of("jsonrpc", "2.0", "id", 1,
                "method", method, "params", params), headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
}
