package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends GatewayException {

    public NotFoundException(String what, String id) {
        super(what + " '" + id + "' does not exist.",
                HttpStatus.NOT_FOUND, "invalid_request_error", "not_found", null);
    }
}
