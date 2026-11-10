package dev.studyplanner.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {
    @Test
    void limitsAuthenticationRequestsAndAllowsThemAgainAfterOneMinute() throws Exception {
        var clock = new MutableClock();
        var filter = new AuthRateLimitFilter(clock, 2);
        assertThat(request(filter).getStatus()).isEqualTo(204);
        assertThat(request(filter).getStatus()).isEqualTo(204);
        var blocked = request(filter);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        clock.now = clock.now.plusSeconds(60);
        assertThat(request(filter).getStatus()).isEqualTo(204);
    }

    private MockHttpServletResponse request(AuthRateLimitFilter filter) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        return response;
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-16T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
