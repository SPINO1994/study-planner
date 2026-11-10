package dev.studyplanner.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

final class AuthRateLimitFilter extends OncePerRequestFilter {
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int maxRequests;

    AuthRateLimitFilter(Clock clock, int maxRequests) {
        this.clock = clock;
        this.maxRequests = maxRequests;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getServletPath().equals("/api/auth/login")
                || request.getServletPath().equals("/api/auth/register"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allow(request.getRemoteAddr())) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Try again in one minute\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String address) {
        long now = clock.millis();
        windows.values().removeIf(window -> now - window.start >= 60_000);
        if (!windows.containsKey(address) && windows.size() >= 10_000) {
            return false;
        }
        var window = windows.computeIfAbsent(address, key -> new Window(now));
        return ++window.requests <= maxRequests;
    }

    private static final class Window {
        final long start;
        int requests;

        Window(long start) {
            this.start = start;
        }
    }
}
