package dev.studyplanner.quiz;

import static dev.studyplanner.quiz.QuizModels.*;

import dev.studyplanner.common.ApiException;
import dev.studyplanner.topic.TopicModels.QuizRequest;
import dev.studyplanner.topic.TopicRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizService {
    private final QuizStore store;
    private final OpenAiQuizGenerator generator;
    private final TopicRepository topics;
    private final Clock clock;

    public QuizService(QuizStore store, OpenAiQuizGenerator generator, TopicRepository topics, Clock clock) {
        this.store = store;
        this.generator = generator;
        this.topics = topics;
        this.clock = clock;
    }

    public AiStatus status() { return generator.status(); }

    public Quiz generate(UUID userId, GenerateRequest request) {
        if (!generator.status().configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI quizzes need an OPENAI_API_KEY on the server. Manual results are still available.");
        }
        var reservation = store.reserve(userId, request);
        var quiz = reservation.quiz();
        if (!reservation.created()) return view(quiz);
        try {
            // No database transaction is held while waiting for the model.
            var content = generator.generate(quiz.subject(), quiz.topicName(), quiz.questionCount(), quiz.difficulty());
            return view(store.ready(userId, quiz.id(), content));
        } catch (RuntimeException exception) {
            store.failed(userId, quiz.id());
            throw exception;
        }
    }

    public Quiz get(UUID userId, UUID quizId) { return view(store.get(userId, quizId)); }
    public List<Summary> list(UUID userId, int page) { return store.list(userId, page); }
    public List<ManualResult> manualResults(UUID userId, int page) { return store.manualResults(userId, page); }

    @Transactional
    public Quiz submit(UUID userId, UUID quizId, SubmitRequest request) {
        topics.lockUser(userId);
        var quiz = store.get(userId, quizId);
        if (quiz.status().equals("SUBMITTED")) {
            if (!quiz.answers().equals(request.answers())) {
                throw new ApiException(HttpStatus.CONFLICT, "This quiz was already submitted with different answers");
            }
            return view(quiz);
        }
        if (!quiz.status().equals("READY")) throw new ApiException(HttpStatus.CONFLICT, "This quiz is not ready for submission");
        if (request.answers().size() != quiz.questionCount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Answer every question before submitting");
        }
        var feedback = grade(quiz.content(), request.answers());
        topics.insertQuiz(quiz.topicId(), new QuizRequest(quiz.id(), feedback.correctAnswers(),
                quiz.questionCount(), "AI quiz — " + quiz.difficulty().name().toLowerCase(java.util.Locale.ROOT), ""), clock.instant());
        store.submitted(userId, quizId, request.answers());
        return view(store.get(userId, quizId));
    }

    private Quiz view(QuizStore.StoredQuiz quiz) {
        List<Question> questions = new ArrayList<>();
        if (quiz.content() != null) {
            for (int index = 0; index < quiz.content().questions().size(); index++) {
                var question = quiz.content().questions().get(index);
                questions.add(new Question(index + 1, question.prompt(), question.options()));
            }
        }
        var feedback = quiz.status().equals("SUBMITTED") ? grade(quiz.content(), quiz.answers()) : null;
        return new Quiz(quiz.id(), quiz.topicId(), quiz.topicName(), quiz.questionCount(), quiz.difficulty(),
                quiz.status(), quiz.createdAt(), List.copyOf(questions), feedback);
    }

    static Feedback grade(GeneratedContent content, List<Integer> answers) {
        var review = new ArrayList<Review>();
        var groups = new TreeMap<String, int[]>(String.CASE_INSENSITIVE_ORDER);
        int correct = 0;
        for (int index = 0; index < content.questions().size(); index++) {
            var question = content.questions().get(index);
            boolean matched = question.correctOption().equals(answers.get(index));
            if (matched) correct++;
            var counts = groups.computeIfAbsent(question.subtopic().strip(), key -> new int[2]);
            counts[0] += matched ? 1 : 0;
            counts[1]++;
            review.add(new Review(index + 1, question.prompt(), question.options(), answers.get(index),
                    question.correctOption(), question.subtopic(), question.explanation()));
        }
        var weakPoints = groups.entrySet().stream().filter(entry -> entry.getValue()[0] < entry.getValue()[1])
                .map(entry -> new WeakPoint(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                        percentage(entry.getValue()[0], entry.getValue()[1])))
                .sorted(Comparator.comparingDouble(WeakPoint::accuracy).thenComparing(WeakPoint::subtopic)).toList();
        return new Feedback(correct, answers.size(), percentage(correct, answers.size()), weakPoints, List.copyOf(review));
    }

    private static double percentage(int correct, int total) { return Math.round(10000.0 * correct / total) / 100.0; }
}
