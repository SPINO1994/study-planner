package dev.studyplanner.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class BearerTokenFilter extends OncePerRequestFilter {
    private final AuthService auth;

    BearerTokenFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            auth.authenticate(header.substring(7)).ifPresent(userId -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
