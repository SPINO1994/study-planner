package dev.studyplanner.plan;

import static dev.studyplanner.plan.PlanModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PlanController {
    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    Plan create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody PlanRequest request) {
        return plans.create(userId, request);
    }

    @GetMapping("/plans/{planId}")
    Plan get(@AuthenticationPrincipal UUID userId, @PathVariable UUID planId) {
        return plans.get(userId, planId);
    }

    @GetMapping("/plans")
    List<PlanSummary> list(@AuthenticationPrincipal UUID userId, @RequestParam(defaultValue = "0") int page) {
        return plans.list(userId, page);
    }

    @PostMapping("/plans/{planId}/cancel")
    Plan cancel(@AuthenticationPrincipal UUID userId, @PathVariable UUID planId) {
        return plans.cancel(userId, planId);
    }

    @PostMapping("/sessions/{sessionId}/complete")
    Session complete(@AuthenticationPrincipal UUID userId, @PathVariable UUID sessionId) {
        return plans.complete(userId, sessionId);
    }
}
