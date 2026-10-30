package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.anthropic.AnthropicMessagesRequest;
import com.shreyasnandurkar.botvinnikapi.api.anthropic.AnthropicProtocol;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Anthropic Messages entry point; the work happens in ChatPipeline. */
@RestController
public class MessagesController {

    private final ChatPipeline pipeline;
    private final AnthropicProtocol anthropic;

    public MessagesController(ChatPipeline pipeline, AnthropicProtocol anthropic) {
        this.pipeline = pipeline;
        this.anthropic = anthropic;
    }

    @PostMapping("/v1/messages")
    public Mono<ResponseEntity<?>> create(@RequestBody AnthropicMessagesRequest body,
                                          ServerWebExchange exchange) {
        return pipeline.handle(anthropic, body, exchange);
    }
}
