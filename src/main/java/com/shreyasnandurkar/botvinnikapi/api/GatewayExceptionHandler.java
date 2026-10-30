package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.core.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Every client-visible failure becomes an error body in the protocol of the request's path.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final ClientProtocols protocols;

    public GatewayExceptionHandler(ClientProtocols protocols) {
        this.protocols = protocols;
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Object> gateway(GatewayException e, ServerWebExchange exchange) {
        return ResponseEntity.status(e.status())
                .body(protocolOf(exchange).encodeError(
                        e.status().value(), e.getMessage(), e.type(), e.param(), e.code()));
    }

    /** Malformed JSON / undeserializable body. */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Object> badInput(ServerWebInputException e, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(protocolOf(exchange).encodeError(400,
                        "Could not parse the request body as JSON.", "invalid_request_error", null, null));
    }

    /** Spring's own rejections (415, 405, 406...) keep their status rather than becoming a 500. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> status(ResponseStatusException e, ServerWebExchange exchange) {
        int status = e.getStatusCode().value();
        String message = e.getReason() != null ? e.getReason() : HttpStatus.valueOf(status).getReasonPhrase();
        return ResponseEntity.status(status)
                .body(protocolOf(exchange).encodeError(status, message, "invalid_request_error", null, null));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Object> unexpected(Throwable e, ServerWebExchange exchange) {
        log.error("Unexpected error handling request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(protocolOf(exchange).encodeError(500,
                        "Internal gateway error.", "api_error", null, "internal_error"));
    }

    private ClientProtocol<?> protocolOf(ServerWebExchange exchange) {
        return protocols.forPath(exchange.getRequest().getPath().value());
    }
}
