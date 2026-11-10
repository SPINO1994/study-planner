package dev.studyplanner.plan;

import static dev.studyplanner.plan.PlanModels.*;

import dev.studyplanner.common.ApiException;
import dev.studyplanner.topic.TopicRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PlanService {
    private final PlanRepository plans;
    private final TopicRepository topics;
    private final StudyScheduler scheduler;
    private final Clock clock;

    public PlanService(PlanRepository plans, TopicRepository topics, StudyScheduler scheduler, Clock clock) {
        this.plans = plans;
        this.topics = topics;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Transactional
    public Plan create(UUID userId, PlanRequest request) {
        topics.lockUser(userId);
        var existing = plans.findByRequest(userId, request.requestId());
        if (existing.isPresent()) {
            var plan = existing.get();
            if (!plan.startDate().equals(request.startDate()) || plan.days() != request.days()
                    || plan.dailyMinutes() != request.dailyMinutes()) {
                throw new ApiException(HttpStatus.CONFLICT, "Request ID was already used with different plan settings");
            }
            return plan;
        }
        LocalDate today = LocalDate.now(clock);
        if (request.startDate().isBefore(today) || request.startDate().isAfter(today.plusDays(90))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start date must be within the next 90 days, including today (UTC)");
        }
        if (plans.hasPendingSessions(userId, request.startDate(), request.startDate().plusDays(request.days() - 1))) {
            throw new ApiException(HttpStatus.CONFLICT, "Cancel or complete the existing sessions in this date range first");
        }
        var allocations = scheduler.allocate(topics.progress(userId), request);
        if (allocations.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No unfinished topics are available within this date range");
        }
        UUID id = plans.insert(userId, request, allocations, clock.instant());
        return get(userId, id);
    }

    public Plan get(UUID userId, UUID planId) {
        return plans.find(userId, planId).orElseThrow(() -> ApiException.notFound("Plan"));
    }

    public List<PlanSummary> list(UUID userId, int page) {
        if (page < 0 || page > 10000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be between 0 and 10000");
        }
        return plans.list(userId, page);
    }

    @Transactional
    public Session complete(UUID userId, UUID sessionId) {
        topics.lockUser(userId);
        var session = plans.findSession(userId, sessionId).orElseThrow(() -> ApiException.notFound("Session"));
        if (session.status().equals("COMPLETED")) {
            return session;
        }
        if (session.status().equals("SKIPPED")) {
            throw new ApiException(HttpStatus.CONFLICT, "A skipped session cannot be completed");
        }
        if (session.studyDate().isAfter(LocalDate.now(clock))) {
            throw new ApiException(HttpStatus.CONFLICT, "A future session cannot be completed");
        }
        plans.complete(sessionId, clock.instant());
        return plans.findSession(userId, sessionId).orElseThrow();
    }

    @Transactional
    public Plan cancel(UUID userId, UUID planId) {
        topics.lockUser(userId);
        get(userId, planId);
        plans.cancel(planId);
        return get(userId, planId);
    }
}
