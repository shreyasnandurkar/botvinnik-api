package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/** 504 — silence between stream chunks, never total request time (§10). */
public class StreamIdleTimeoutException extends GatewayException {

    public StreamIdleTimeoutException(String provider, Duration idleTimeout) {
        super("Provider '" + provider + "' sent no data for " + idleTimeout.toMillis()
                        + "ms during an active stream.",
                HttpStatus.GATEWAY_TIMEOUT, "api_error", "stream_idle_timeout", null);
    }
}
