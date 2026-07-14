package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

/**
 * 502 - could not connect to the upstream provider at all.
 */
public class ProviderUnreachableException extends GatewayException {

    public ProviderUnreachableException(String provider, Throwable cause) {
        super("Provider '" + provider + "' is unreachable: " + cause.getMessage(),
                HttpStatus.BAD_GATEWAY, "api_error", "provider_unreachable", null);
        initCause(cause);
    }
}