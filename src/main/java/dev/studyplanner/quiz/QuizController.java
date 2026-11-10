package dev.studyplanner.quiz;

import static dev.studyplanner.quiz.QuizModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {
    private final QuizService quizzes;

    public QuizController(QuizService quizzes) { this.quizzes = quizzes; }

    @GetMapping("/ai-status")
    AiStatus status() { return quizzes.status(); }

    @GetMapping("/manual")
    List<ManualResult> manual(@AuthenticationPrincipal UUID userId, @RequestParam(defaultValue = "0") int page) {
        return quizzes.manualResults(userId, page);
    }

    @PostMapping("/generated")
    Quiz generate(@AuthenticationPrincipal UUID userId, @Valid @RequestBody GenerateRequest request) {
        return quizzes.generate(userId, request);
    }

    @GetMapping("/generated")
    List<Summary> list(@AuthenticationPrincipal UUID userId, @RequestParam(defaultValue = "0") int page) {
        return quizzes.list(userId, page);
    }

    @GetMapping("/generated/{quizId}")
    Quiz get(@AuthenticationPrincipal UUID userId, @PathVariable UUID quizId) { return quizzes.get(userId, quizId); }

    @PostMapping("/generated/{quizId}/submit")
    Quiz submit(@AuthenticationPrincipal UUID userId, @PathVariable UUID quizId, @Valid @RequestBody SubmitRequest request) {
        return quizzes.submit(userId, quizId, request);
    }
}
