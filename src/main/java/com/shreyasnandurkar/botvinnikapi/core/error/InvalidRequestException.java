package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

/** 400 - the client sent something malformed, unknown, or unsupported. */
public class InvalidRequestException extends GatewayException {

    public InvalidRequestException(String message, String param) {
        super(message, HttpStatus.BAD_REQUEST, "invalid_request_error", null, param);
    }
}