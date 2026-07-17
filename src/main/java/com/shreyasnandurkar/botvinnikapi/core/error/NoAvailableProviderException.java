package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

/** 503 — every candidate in the routing chain is down or circuit-open (§10). */
public class NoAvailableProviderException extends GatewayException {

    public NoAvailableProviderException(String model, Throwable cause) {
        super("No provider is currently available to serve '" + model + "'.",
                HttpStatus.SERVICE_UNAVAILABLE, "api_error", "provider_unavailable", null);
        if (cause != null) {
            initCause(cause);
        }
    }
}
