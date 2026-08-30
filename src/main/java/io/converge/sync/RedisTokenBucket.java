package io.converge.sync;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisTokenBucket {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local values = redis.call('HMGET', KEYS[1], 'tokens', 'last')
            local tokens = tonumber(values[1]) or tonumber(ARGV[1])
            local last = tonumber(values[2]) or tonumber(ARGV[3])
            local now = tonumber(ARGV[3])
            local elapsed = math.max(0, now - last) / 1000
            tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]))
            local allowed = 0
            if tokens >= 1 then tokens = tokens - 1 allowed = 1 end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last', now)
            redis.call('PEXPIRE', KEYS[1], 120000)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int capacity;
    private final int refillPerSecond;

    RedisTokenBucket(StringRedisTemplate redis,
            @Value("${sync.rate-limit.capacity}") int capacity,
            @Value("${sync.rate-limit.refill-per-second}") int refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    boolean tryAcquire(String system) {
        Long result = redis.execute(SCRIPT, List.of("sync:tokens:" + system),
                Integer.toString(capacity), Integer.toString(refillPerSecond),
                Long.toString(System.currentTimeMillis()));
        return Long.valueOf(1).equals(result);
    }
}

