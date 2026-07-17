package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

/**
 * Every client-visible failure becomes an OpenAI-style {"error": {...}}
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<OpenAiDtos.ErrorBody> gateway(GatewayException e) {
        return ResponseEntity.status(e.status())
                .body(new OpenAiDtos.ErrorBody(
                        new OpenAiDtos.ErrorDetail(e.getMessage(), e.type(), e.param(), e.code())));
    }

    /** Malformed JSON / undeserializable body. */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<OpenAiDtos.ErrorBody> badInput(ServerWebInputException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new OpenAiDtos.ErrorBody(new OpenAiDtos.ErrorDetail(
                        "Could not parse the request body as JSON.", "invalid_request_error", null, null)));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<OpenAiDtos.ErrorBody> unexpected(Throwable e) {
        log.error("Unexpected error handling request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new OpenAiDtos.ErrorBody(new OpenAiDtos.ErrorDetail(
                        "Internal gateway error.", "api_error", null, "internal_error")));
    }
}