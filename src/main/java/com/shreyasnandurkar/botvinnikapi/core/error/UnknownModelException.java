package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

/** 404 — the requested model/provider cannot be resolved to a destination. */
public class UnknownModelException extends GatewayException {

    public UnknownModelException(String model) {
        super("The model '" + model + "' does not exist or no provider is configured for it.",
                HttpStatus.NOT_FOUND, "invalid_request_error", "model_not_found", "model");
    }
}
