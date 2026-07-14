package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiChatCompletionRequest;
import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The data-plane entry point (§5). Validate → resolve → dispatch → adapt back.
 * No database anywhere on this path.
 */
@RestController
public class ChatCompletionsController {

    private final ProviderRegistry registry;

    public ChatCompletionsController(ProviderRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<OpenAiDtos.ChatCompletionResponse> complete(@RequestBody OpenAiChatCompletionRequest body) {
        body.validate();
        if (Boolean.TRUE.equals(body.stream)) {
            // Build order §19: streaming is step 3. Reject explicitly rather than
            // pretending by buffering — a batching gateway that claims to stream is worse.
            throw new InvalidRequestException("Streaming (stream: true) is not supported yet.", "stream");
        }
        ProviderRegistry.Resolution resolution = registry.resolve(body.model);
        return resolution.provider()
                .chat(body.toChatRequest(resolution.model()))
                .map(OpenAiDtos.ChatCompletionResponse::from);
    }
}
