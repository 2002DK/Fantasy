package com.fantasy.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TtlCacheTest {

    /** A clock the test moves forward. */
    private static final class TestClock extends Clock {

        private Instant now = Instant.parse("2026-10-20T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(10), clock);
    private final AtomicInteger loads = new AtomicInteger();

    @Test
    void reusesAValueUntilItExpires() {
        assertThat(cache.get("a", loads::incrementAndGet)).isEqualTo(1);
        clock.advance(Duration.ofMinutes(9));
        assertThat(cache.get("a", loads::incrementAndGet)).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.get("a", loads::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void expiredEntriesAreDroppedWhenAnotherValueLoads() {
        AtomicInteger loadsOfA = new AtomicInteger();
        cache.get("a", loadsOfA::incrementAndGet);
        clock.advance(Duration.ofMinutes(11));
        cache.get("b", () -> 0);
        clock.advance(Duration.ofSeconds(-120)); // "a" would still be fresh at this time had it been kept

        assertThat(cache.get("a", loadsOfA::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void aFailedLoadIsNotCached() {
        assertThatThrownBy(() -> cache.get("a", () -> {
            throw new IllegalStateException("down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cache.get("a", loads::incrementAndGet)).isEqualTo(1);
    }
}
