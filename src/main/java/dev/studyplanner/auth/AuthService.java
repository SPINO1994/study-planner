package dev.studyplanner.auth;

import dev.studyplanner.common.ApiException;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Duration tokenLifetime;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwords, Clock clock,
            @Value("${app.token-lifetime}") Duration tokenLifetime) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
        this.tokenLifetime = tokenLifetime;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record Credentials(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {}

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {}

    @Transactional
    public TokenResponse register(Credentials request) {
        validatePassword(request.password());
        var userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id, email, password_hash, created_at) VALUES (?, ?, ?, ?)")
                .params(userId, normalize(request.email()), passwords.encode(request.password()), Timestamp.from(clock.instant()))
                .update();
        return issueToken(userId);
    }

    @Transactional
    public TokenResponse login(Credentials request) {
        validatePassword(request.password());
        var user = jdbc.sql("SELECT id, password_hash FROM app_user WHERE email = ?")
                .param(normalize(request.email()))
                .query((rs, row) -> new StoredUser(rs.getObject("id", UUID.class), rs.getString("password_hash")))
                .optional();
        boolean valid = passwords.matches(request.password(), user.map(StoredUser::passwordHash).orElse(dummyHash));
        if (!valid || user.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        var id = user.get().id();
        jdbc.sql("SELECT id FROM app_user WHERE id = ? FOR UPDATE").param(id).query(UUID.class).single();
        jdbc.sql("DELETE FROM access_token WHERE user_id = ? AND expires_at <= ?")
                .params(id, Timestamp.from(clock.instant())).update();
        int count = jdbc.sql("SELECT count(*) FROM access_token WHERE user_id = ?")
                .param(id).query(Integer.class).single();
        if (count >= 10) {
            throw new ApiException(HttpStatus.CONFLICT, "Sign out of an existing session before signing in again");
        }
        return issueToken(id);
    }

    public Optional<UUID> authenticate(String token) {
        if (token.length() != 43) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT user_id FROM access_token WHERE token_hash = ? AND expires_at > ?")
                .params(hash(token), Timestamp.from(clock.instant())).query(UUID.class).optional();
    }

    public void logout(String token) {
        jdbc.sql("DELETE FROM access_token WHERE token_hash = ?").param(hash(token)).update();
    }

    private TokenResponse issueToken(UUID userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry = clock.instant().plus(tokenLifetime);
        jdbc.sql("INSERT INTO access_token(token_hash, user_id, expires_at) VALUES (?, ?, ?)")
                .params(hash(token), userId, Timestamp.from(expiry)).update();
        return new TokenResponse(token, "Bearer", expiry);
    }

    private void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must fit within 72 UTF-8 bytes");
        }
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record StoredUser(UUID id, String passwordHash) {}
}
