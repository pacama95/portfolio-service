package com.portfolio.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

public class RedisTestResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> redis;

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
        return Map.of(
                "quarkus.redis.hosts",
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Override
    public void stop() {
        if (redis != null) {
            redis.stop();
        }
    }
}
