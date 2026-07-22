package com.shreyasnandurkar.botvinnikapi.telemetry;

import java.time.Instant;
import java.util.UUID;

public record RequestLogEntry(
        UUID apiKeyId,
        String provider,
        String model,
        Instant ts,
        long latencyMs,
        Long ttftMs,
        Integer promptTokens,
        Integer completionTokens,
        String outcome,
        String errorCode,
        String promptExcerpt,
        String responseExcerpt) {
}
