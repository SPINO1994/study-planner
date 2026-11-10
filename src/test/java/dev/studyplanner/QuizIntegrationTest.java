package dev.studyplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth-requests-per-minute=1000", "spring.datasource.password=", "app.quiz.api-key=test-key",
        "app.quiz.generations-per-hour=2"})
class QuizIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicReference<String> mode = new AtomicReference<>("NORMAL");
    private static final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;
    private static final HttpServer provider = startProvider();
    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.configure(registry);
        registry.add("app.quiz.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE app_user CASCADE").update();
        calls.set(0);
        mode.set("NORMAL");
        lastRequest.set(null);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    @AfterAll
    static void stopProvider() { provider.stop(0); }

    @Test
    void generatesAtMostThirtyQuestionsWithoutExposingTheAnswerKey() {
        String token = register();
        String topic = topic(token);
        var response = generate(token, topic, UUID.randomUUID(), 30);
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
        var quiz = response.getBody();
        assertThat(quiz.path("questions")).hasSize(30).allMatch(q -> q.path("options").size() == 4);
        assertThat(quiz.path("feedback").isNull()).isTrue();
        assertThat(quiz.toString()).doesNotContain("correctOption", "explanation", "subtopic");
        assertThat(get("/api/quizzes/generated/" + quiz.path("id").asText(), token).getBody()).isEqualTo(quiz);
        assertThat(lastRequest.get().path("store").asBoolean()).isFalse();
        assertThat(lastRequest.get().path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(lastRequest.get().path("text").path("format").path("schema").path("properties")
                .path("questions").path("maxItems").asInt()).isEqualTo(30);
        assertThat(generate(token, topic, UUID.randomUUID(), 31).getStatusCode().value()).isEqualTo(400);
        assertThat(generate(token, topic, UUID.randomUUID(), 0).getStatusCode().value()).isEqualTo(400);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void gradesOnTheServerGroupsMissesAndUpdatesStudyProgressOnce() throws Exception {
        String token = register();
        var quiz = generate(token, topic(token), UUID.randomUUID(), 4).getBody();
        String url = "/api/quizzes/generated/" + quiz.path("id").asText() + "/submit";
        var body = Map.of("answers", List.of(0, 0, 0, 0));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var responses = executor.invokeAll(List.of(() -> post(url, token, body), () -> post(url, token, body)));
            for (var future : responses) {
                var response = (ResponseEntity<?>) future.get();
                assertThat(response.getStatusCode().value()).isEqualTo(200);
            }
        }
        var graded = post(url, token, body).getBody();
        var feedback = graded.path("feedback");
        assertThat(feedback.path("correctAnswers").asInt()).isEqualTo(1);
        assertThat(feedback.path("accuracy").asDouble()).isEqualTo(25);
        assertThat(feedback.path("weakPoints").get(0).path("subtopic").asText()).isEqualTo("Generics");
        assertThat(feedback.path("weakPoints").get(0).path("accuracy").asDouble()).isZero();
        assertThat(feedback.path("review")).hasSize(4);
        assertThat(feedback.path("review").get(1).path("correctOption").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_result").query(Integer.class).single()).isEqualTo(1);
        assertThat(get("/api/progress", token).getBody().path("topics").get(0).path("accuracy").asDouble()).isEqualTo(25);
        assertThat(get("/api/quizzes/manual", token).getBody()).isEmpty();
        assertThat(post(url, token, Map.of("answers", List.of(0, 1, 2, 3))).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void correctAnswersProduceNoFalseWeakPoints() {
        String token = register();
        var quiz = generate(token, topic(token), UUID.randomUUID(), 3).getBody();
        var feedback = post("/api/quizzes/generated/" + quiz.path("id").asText() + "/submit", token,
                Map.of("answers", List.of(0, 1, 2))).getBody().path("feedback");
        assertThat(feedback.path("accuracy").asDouble()).isEqualTo(100);
        assertThat(feedback.path("weakPoints")).isEmpty();
    }

    @Test
    void quizOwnershipAndSubmissionValidationCannotBeBypassed() {
        String alice = register();
        String bob = register();
        String topic = topic(alice);
        var quiz = generate(alice, topic, UUID.randomUUID(), 2).getBody();
        String url = "/api/quizzes/generated/" + quiz.path("id").asText();
        assertThat(get(url, bob).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/quizzes/generated", bob).getBody()).isEmpty();
        assertThat(post(url + "/submit", bob, Map.of("answers", List.of(0, 1))).getStatusCode().value()).isEqualTo(404);
        assertThat(generate(bob, topic, UUID.randomUUID(), 2).getStatusCode().value()).isEqualTo(404);
        assertThat(post(url + "/submit", alice, Map.of("answers", List.of(0))).getStatusCode().value()).isEqualTo(400);
        assertThat(post(url + "/submit", alice, Map.of("answers", List.of(0, 4))).getStatusCode().value()).isEqualTo(400);
        assertThat(post(url + "/submit", alice, Map.of("answers", List.of(0, 1), "score", 100)).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/topics/" + topic + "/quiz-results", alice,
                Map.of("requestId", quiz.path("id").asText(), "correctAnswers", 2, "totalQuestions", 2))
                .getStatusCode().value()).isEqualTo(409);
        assertThat(get(url, null).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_result").query(Integer.class).single()).isZero();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void concurrentGenerationRetriesDoNotChargeForAnotherModelCall() throws Exception {
        String token = register();
        String topic = topic(token);
        UUID request = UUID.randomUUID();
        mode.set("BLOCK");
        var first = CompletableFuture.supplyAsync(() -> generate(token, topic, request, 2));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(generate(token, topic, request, 2).getStatusCode().value()).isEqualTo(409);
            assertThat(generate(token, topic, UUID.randomUUID(), 2).getStatusCode().value()).isEqualTo(409);
            assertThat(get("/api/progress", token).getStatusCode().value()).isEqualTo(200);
        } finally { release.countDown(); }
        var result = first.get(10, TimeUnit.SECONDS);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(generate(token, topic, request, 2).getBody()).isEqualTo(result.getBody());
        assertThat(generate(token, topic, request, 3).getStatusCode().value()).isEqualTo(409);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void providerFailuresAreSafeAndDoNotCreateScoresOrPartialQuizzes() {
        String token = register();
        String topic = topic(token);
        mode.set("UNAUTHORIZED");
        var response = generate(token, topic, UUID.randomUUID(), 2);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).doesNotContain("provider-secret");
        assertThat(get("/api/quizzes/generated", token).getBody().get(0).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_result").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM generated_quiz WHERE questions IS NOT NULL").query(Integer.class).single()).isZero();
    }

    @Test
    void invalidOrIncompleteProviderContentIsRejected() {
        String token = register();
        String topic = topic(token);
        mode.set("DUPLICATE");
        assertThat(generate(token, topic, UUID.randomUUID(), 2).getStatusCode().value()).isEqualTo(502);
        mode.set("INCOMPLETE");
        assertThat(generate(token, topic, UUID.randomUUID(), 2).getStatusCode().value()).isEqualTo(502);
        assertThat(jdbc.sql("SELECT count(*) FROM generated_quiz WHERE status = 'READY'").query(Integer.class).single()).isZero();
    }

    @Test
    void refusalsAndProviderRateLimitsHaveClearErrors() {
        String token = register();
        String topic = topic(token);
        mode.set("REFUSAL");
        assertThat(generate(token, topic, UUID.randomUUID(), 2).getStatusCode().value()).isEqualTo(422);
        mode.set("RATE_LIMIT");
        assertThat(generate(token, topic, UUID.randomUUID(), 2).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void hourlyLimitPreventsFurtherPaidCalls() {
        String token = register();
        String topic = topic(token);
        assertThat(generate(token, topic, UUID.randomUUID(), 1).getStatusCode().value()).isEqualTo(200);
        assertThat(generate(token, topic, UUID.randomUUID(), 1).getStatusCode().value()).isEqualTo(200);
        assertThat(generate(token, topic, UUID.randomUUID(), 1).getStatusCode().value()).isEqualTo(429);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void exhaustedBillingQuotaIsDistinguishedFromTemporaryRateLimits() {
        String token = register();
        mode.set("NO_QUOTA");
        var response = generate(token, topic(token), UUID.randomUUID(), 2);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().path("detail").asText()).contains("no available quota", "billing");
    }

    @Test
    void manualHistoryKeepsTitlesAndNotesSeparateFromAiAttempts() {
        String token = register();
        String topic = topic(token);
        var result = Map.of("requestId", UUID.randomUUID(), "title", "Class practice test", "notes", "Review wildcard bounds",
                "correctAnswers", 7, "totalQuestions", 10);
        assertThat(post("/api/topics/" + topic + "/quiz-results", token, result).getStatusCode().value()).isEqualTo(201);
        var manual = get("/api/quizzes/manual", token).getBody();
        assertThat(manual).hasSize(1);
        assertThat(manual.get(0).path("title").asText()).isEqualTo("Class practice test");
        assertThat(manual.get(0).path("notes").asText()).isEqualTo("Review wildcard bounds");
        assertThat(get("/api/quizzes/manual", register()).getBody()).isEmpty();
        assertThat(get("/api/quizzes/manual?page=-1", token).getStatusCode().value()).isEqualTo(400);
        assertThat(get("/api/quizzes/generated", token).getBody()).isEmpty();
    }

    @Test
    void mcpCanGenerateAndGradeTheSameQuizWithoutLeakingAnswers() {
        String token = register();
        String topic = topic(token);
        var generated = tool(token, "generate_quiz", Map.of("requestId", UUID.randomUUID(), "topicId", topic,
                "questionCount", 2, "difficulty", "BEGINNER"));
        assertThat(generated.path("isError").asBoolean()).isFalse();
        String id = generated.path("structuredContent").path("data").path("id").asText();
        var read = tool(token, "get_quiz", Map.of("quizId", id));
        assertThat(read.toString()).doesNotContain("correctOption");
        var graded = tool(token, "submit_quiz", Map.of("quizId", id, "submission", Map.of("answers", List.of(0, 1))));
        assertThat(graded.path("isError").asBoolean()).isFalse();
        assertThat(graded.path("structuredContent").path("data").path("feedback").path("accuracy").asDouble()).isEqualTo(100);
    }

    private String register() {
        var response = post("/api/auth/register", null, Map.of("email", UUID.randomUUID() + "@example.com", "password", "correct-horse-123"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().path("accessToken").asText();
    }

    private String topic(String token) {
        var response = post("/api/topics", token, Map.of("subject", "Java", "name", "Collections", "priority", 3, "estimatedMinutes", 120));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().path("id").asText();
    }

    private ResponseEntity<JsonNode> generate(String token, String topic, UUID request, int count) {
        return post("/api/quizzes/generated", token, Map.of("requestId", request, "topicId", topic,
                "questionCount", count, "difficulty", "INTERMEDIATE"));
    }

    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<JsonNode> post(String url, String token, Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String url, String token) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private JsonNode tool(String token, String name, Object arguments) {
        var headers = headers(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        var response = http.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(Map.of("jsonrpc", "2.0", "id", 1,
                "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)), headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("result");
    }

    private static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", exchange -> {
                try {
                    calls.incrementAndGet();
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    lastRequest.set(request);
                    String behavior = mode.get();
                    if (behavior.equals("BLOCK")) { entered.countDown(); release.await(8, TimeUnit.SECONDS); }
                    int status = behavior.equals("UNAUTHORIZED") ? 401 : behavior.equals("RATE_LIMIT") || behavior.equals("NO_QUOTA") ? 429 : 200;
                    int count = JSON.readTree(request.path("input").asText()).path("questionCount").asInt();
                    List<Map<String, Object>> questions = new ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        questions.add(Map.of("prompt", "Question " + index,
                                "options", behavior.equals("DUPLICATE") ? List.of("Same", "Same", "C", "D") : List.of("A", "B", "C", "D"),
                                "correctOption", index % 4, "subtopic", index % 2 == 0 ? "Collections" : "Generics", "explanation", "Explanation " + index));
                    }
                    Object output = behavior.equals("REFUSAL") ? Map.of("type", "refusal", "refusal", "Cannot comply")
                            : Map.of("type", "output_text", "text", JSON.writeValueAsString(Map.of("questions", questions)));
                    byte[] body = JSON.writeValueAsString(status != 200 ? Map.of("error", behavior.equals("NO_QUOTA")
                                    ? Map.of("code", "insufficient_quota") : "provider-secret")
                            : Map.of("status", behavior.equals("INCOMPLETE") ? "incomplete" : "completed",
                                    "output", List.of(Map.of("type", "message", "content", List.of(output)))))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (Exception exception) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
