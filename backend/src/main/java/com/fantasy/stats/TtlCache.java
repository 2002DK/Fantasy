package com.fantasy.stats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Minimal time-based cache. A loader that throws caches nothing, so the next call
 * retries. Concurrent misses may load twice, which is fine for idempotent GETs.
 */
final class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    TtlCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    V get(K key, Supplier<V> loader) {
        Instant now = clock.instant();
        Entry<V> entry = entries.get(key);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.value();
        }
        V value = loader.get();
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        return value;
    }
}
