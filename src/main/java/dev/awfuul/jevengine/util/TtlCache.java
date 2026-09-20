package dev.awfuul.jevengine.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded least-recently-used cache with a time limit per entry.
 *
 * <p>Chat repeats itself constantly, so the same short lines are judged over and
 * over. Holding recent verdicts keeps those off the network entirely. Access is
 * synchronised because entries are read from chat threads and written from the
 * virtual threads that run requests, and the map is small enough that lock
 * contention never shows up next to a network round trip.
 */
public final class TtlCache<K, V> {

    private record Slot<V>(V value, long expiresAt) {
    }

    private final int maxSize;
    private final long ttlMillis;
    private final LinkedHashMap<K, Slot<V>> map;

    private long hits;
    private long misses;

    public TtlCache(int maxSize, long ttlMillis) {
        this.maxSize = Math.max(1, maxSize);
        this.ttlMillis = ttlMillis;
        this.map = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Slot<V>> eldest) {
                return size() > TtlCache.this.maxSize;
            }
        };
    }

    public synchronized V get(K key) {
        Slot<V> entry = map.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            map.remove(key);
            misses++;
            return null;
        }
        hits++;
        return entry.value();
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Slot<>(value, System.currentTimeMillis() + ttlMillis));
    }

    public synchronized void clear() {
        map.clear();
        hits = 0;
        misses = 0;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0D : (double) hits / total;
    }
}
