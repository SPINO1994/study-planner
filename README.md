# Study Planner

Java backend for tracking study topics, quiz results, and daily study sessions. An AI assistant can use the same features through twelve authenticated MCP tools.

Java 21, Spring Boot, Spring AI, PostgreSQL, Spring JDBC, Flyway, and Spring Security.

## Run

```sh
cp .env.example .env
# Set a database password in .env.
docker compose up --build
```

UI: `http://localhost:8080` · API: `/api` · Swagger UI: `/docs` · Health: `/actuator/health`.

The UI supports account access, topic editing, quiz results, and study sessions. The MCP check tab verifies tool discovery and an authenticated progress call. Browser sessions last within the current tab; passwords are not saved by the app.

For local development, start only the database with `docker compose up -d db`, then run `./mvnw spring-boot:run` (`.\mvnw.cmd spring-boot:run` on Windows). The application reads the project-root `.env` as a simple Java properties file; use unquoted `NAME=value` lines. Environment variables take precedence.

## Quizzes

Open the Quizzes page for manual results or AI practice quizzes. Manual results accept a title, topic, score, and optional notes. Existing scores appear in the same history.

For AI generation, put `OPENAI_API_KEY=your-key` in the ignored local `.env` and restart. `OPENAI_QUIZ_MODEL` defaults to `gpt-4.1-mini`. The backend uses the [Responses API with structured output](https://developers.openai.com/api/docs/guides/structured-outputs); the browser never receives the key. Only the subject and topic name are sent. Generation is a paid API call, limited to ten requests per user per hour by default (`APP_QUIZ_GENERATIONS_PER_HOUR`).

Choose 1–30 MCQs and a difficulty. Quizzes are saved and can be reopened from history. Answer keys stay on the server until submission; Java grades the answers and groups mistakes by subtopic. Each submission records one result in study progress. Reports identify areas missed in that attempt, not a complete measure of mastery. AI-written questions and explanations may contain mistakes.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/quizzes/ai-status` | Check whether generation is configured |
| GET | `/api/quizzes/manual` | List manual results |
| GET, POST | `/api/quizzes/generated` | List or generate saved quizzes |
| GET | `/api/quizzes/generated/{id}` | Take or review a quiz |
| POST | `/api/quizzes/generated/{id}/submit` | Submit zero-based answer indexes |

Generation needs `requestId`, `topicId`, `questionCount`, and `difficulty` (`BEGINNER`, `INTERMEDIATE`, or `ADVANCED`). Reuse the request ID for retries to avoid another model call. Submission is `{"answers":[0,2,1]}` in question order. Identical submission retries return the saved result; changed answers conflict. Manual scores cannot replace generated scores.

## Try it

Register through Swagger UI or:

```sh
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"student@example.com","password":"choose-a-long-password"}'
```

Use the returned `accessToken` in `Authorization: Bearer <token>`. Tokens expire after 24 hours; login issues a new token and logout revokes the current one.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login`, `/api/auth/logout` | Account access |
| GET, POST | `/api/topics` | List or create topics |
| PUT | `/api/topics/{id}` | Update a topic |
| POST | `/api/topics/{id}/quiz-results` | Record a quiz score |
| GET | `/api/progress` | Per-topic accuracy and completed study time |
| GET, POST | `/api/plans` | List or create plans |
| GET | `/api/plans/{id}` | Read a plan and its sessions |
| POST | `/api/plans/{id}/cancel` | Skip remaining sessions |
| POST | `/api/sessions/{id}/complete` | Complete a session |

Topic example:

```json
{"subject":"Java","name":"Collections","priority":4,"estimatedMinutes":180,"examDate":null}
```

Quiz results need `requestId` (a UUID), `correctAnswers`, and `totalQuestions`. Plans need `requestId`, `startDate` (`YYYY-MM-DD`), `days` (1–14), and `dailyMinutes` (15–480). Reuse a request ID when retrying; changing its payload returns a conflict. Topic lists use 50 items per page and plan lists use 20, with `?page=0` as the first page.

On Windows, `./scripts/demo.ps1` creates a sample account and exercises the REST and MCP endpoints. It returns a session object; assign it to a variable to retain the access token locally.

## Connect an AI assistant

Configure an MCP client that supports Streamable HTTP with custom bearer headers:

```json
{
  "mcpServers": {
    "study-planner": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": {"Authorization": "Bearer <your-access-token>"}
    }
  }
}
```

The exact configuration keys depend on the client. This server uses stateless Streamable HTTP and requires authentication on every request. It does not provide OAuth discovery; clients that require OAuth-only connections need an authorization layer. Keep real tokens out of committed configuration.

Available tools: `create_topic`, `list_topics`, `get_progress`, `record_quiz_result`, `create_study_plan`, `get_study_plan`, `list_study_plans`, `complete_study_session`, `cancel_study_plan`, `generate_quiz`, `get_quiz`, and `submit_quiz`.

Example: “Review my quiz results and make a three-day study plan starting today, with 60 minutes per day. Explain which topics need the most work.”

An AI assistant can run in your connected client. Planning and the MCP connection check need no model API key. Quiz generation uses the server's OpenAI key. Java calculates schedules and quiz scores.

## Planning rules

- Accuracy is question-weighted across the latest five quizzes per topic. Unassessed topics use 50% for ranking but return `null` accuracy in the API.
- Ranking combines weakness, user priority, and exam proximity. Allocations made earlier in the day lower that topic's rank to spread work across topics.
- Sessions last up to 30 minutes. Completed work and work reserved by pending sessions are subtracted from the estimate. Daily budgets and remaining work are never exceeded. The plan reports unused minutes when there is insufficient eligible work.
- All planning dates use UTC. Topics are eligible through their exam date. Past exams and finished topics are excluded.
- Pending sessions cannot overlap a new plan's date range. Cancel the old plan to replace it. Future sessions cannot be completed; completed sessions are counted once.
- Writes that affect planning lock the user's database row, so concurrent requests for that user cannot create duplicate or conflicting work.

## Test

```sh
./mvnw verify
```

Integration tests start PostgreSQL 17 with Testcontainers and require Docker. To use an existing disposable PostgreSQL database, set `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD`. Its database name must end in `_test`; tests clear its application tables. Scheduler and rate limiter tests run without a database using `./mvnw -Dtest=StudySchedulerTest,AuthRateLimitFilterTest test`.

The suite covers real HTTP REST/MCP workflows, ownership, token expiry/revocation, validation, concurrent retries, scheduling budgets, and session transitions. Quiz tests use a local simulated provider, so they make no paid calls. They check answer secrecy, grading, failure handling, and generation limits. GitHub Actions runs the suite on Java 21.

## Deployment notes

The Compose setup binds to localhost. A public deployment needs HTTPS, database backups, and an explicit `APP_ALLOWED_ORIGINS` list. Passwords use BCrypt; access tokens are random and only their SHA-256 hashes are stored. Authentication has a per-process IP limit of 20 requests per minute; use a shared gateway limiter when running multiple instances. Adjust token lifetime with `APP_TOKEN_LIFETIME` (ISO-8601 duration). Account recovery and email verification are outside this version's scope.
