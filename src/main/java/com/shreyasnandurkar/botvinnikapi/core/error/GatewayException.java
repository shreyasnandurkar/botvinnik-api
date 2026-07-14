package com.shreyasnandurkar.botvinnikapi.core.error;

import org.springframework.http.HttpStatus;

public abstract class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final String param;

    protected GatewayException(String message, HttpStatus status, String type, String code, String param) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String param() {
        return param;
    }
}