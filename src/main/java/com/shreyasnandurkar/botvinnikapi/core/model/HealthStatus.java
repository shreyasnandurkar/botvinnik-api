package com.shreyasnandurkar.botvinnikapi.core.model;

public record HealthStatus(State state, long latencyMs, String detail) {

    public enum State {HEALTHY, DEGRADED, OPEN}

    public static HealthStatus healthy(long latencyMs) {
        return new HealthStatus(State.HEALTHY, latencyMs, null);
    }

    public static HealthStatus down(String detail) {
        return new HealthStatus(State.OPEN, -1, detail);
    }
}