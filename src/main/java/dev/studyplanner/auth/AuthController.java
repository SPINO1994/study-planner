package dev.studyplanner.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.TokenResponse register(@Valid @RequestBody AuthService.Credentials request) {
        return auth.register(request);
    }

    @PostMapping("/login")
    AuthService.TokenResponse login(@Valid @RequestBody AuthService.Credentials request) {
        return auth.login(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader("Authorization") String authorization) {
        auth.logout(authorization.substring(7));
    }
}
