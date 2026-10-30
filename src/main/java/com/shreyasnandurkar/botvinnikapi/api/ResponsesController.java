package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.openai.ResponsesProtocol;
import com.shreyasnandurkar.botvinnikapi.api.openai.ResponsesRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** OpenAI Responses entry point; the work happens in ChatPipeline. */
@RestController
public class ResponsesController {

    private final ChatPipeline pipeline;
    private final ResponsesProtocol responses;

    public ResponsesController(ChatPipeline pipeline, ResponsesProtocol responses) {
        this.pipeline = pipeline;
        this.responses = responses;
    }

    @PostMapping("/v1/responses")
    public Mono<ResponseEntity<?>> create(@RequestBody ResponsesRequest body, ServerWebExchange exchange) {
        return pipeline.handle(responses, body, exchange);
    }
}
