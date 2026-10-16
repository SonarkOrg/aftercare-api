package com.aftercare.aftercareapi.config.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** A bounded LRU map of token buckets: the least recently used key is dropped beyond {@code maxKeys}. */
public final class BucketMap<K> {

    private final Map<K, TokenBucket> map;
    private final Supplier<TokenBucket> factory;

    public BucketMap(int maxKeys, Supplier<TokenBucket> factory) {
        this.factory = factory;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, TokenBucket> eldest) {
                return size() > maxKeys;
            }
        };
    }

    public synchronized TokenBucket get(K key) {
        return map.computeIfAbsent(key, k -> factory.get());
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized boolean contains(K key) {
        return map.containsKey(key);
    }
}
