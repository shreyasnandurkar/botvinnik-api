package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiChatCompletionRequest;
import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
Validate → resolve → dispatch → adapt back.
 */
@RestController
public class ChatCompletionsController {

    private final ProviderRegistry registry;
    private final OpenAiSseEncoder sseEncoder;

    public ChatCompletionsController(ProviderRegistry registry, OpenAiSseEncoder sseEncoder) {
        this.registry = registry;
        this.sseEncoder = sseEncoder;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> complete(@RequestBody OpenAiChatCompletionRequest body) {
        body.validate();
        ProviderRegistry.Resolution resolution = registry.resolve(body.model);
        ChatRequest request = body.toChatRequest(resolution.model());

        if (Boolean.TRUE.equals(body.stream)) {
            Flux<String> frames = sseEncoder.encode(
                    resolution.provider().stream(request), resolution.model(), body.includeStreamUsage());
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(frames));
        }

        return resolution.provider()
                .chat(request)
                .map(resp -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(OpenAiDtos.ChatCompletionResponse.from(resp)));
    }
}