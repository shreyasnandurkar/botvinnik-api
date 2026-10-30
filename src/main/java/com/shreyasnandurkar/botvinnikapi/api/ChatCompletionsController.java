package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.openai.OpenAiChatCompletionRequest;
import com.shreyasnandurkar.botvinnikapi.api.openai.OpenAiProtocol;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** OpenAI Chat Completions entry point; the work happens in ChatPipeline. */
@RestController
public class ChatCompletionsController {

    private final ChatPipeline pipeline;
    private final OpenAiProtocol openAi;

    public ChatCompletionsController(ChatPipeline pipeline, OpenAiProtocol openAi) {
        this.pipeline = pipeline;
        this.openAi = openAi;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> complete(@RequestBody OpenAiChatCompletionRequest body,
                                            ServerWebExchange exchange) {
        return pipeline.handle(openAi, body, exchange);
    }
}
