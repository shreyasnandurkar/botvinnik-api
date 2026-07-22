package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiChatCompletionRequest;
import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.routing.RequestRouter;
import com.shreyasnandurkar.botvinnikapi.security.ApiKeyAuthFilter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
Validate → resolve → dispatch → adapt back.
 */
@RestController
public class ChatCompletionsController {

    private final ConfigSnapshotService snapshots;
    private final RequestRouter router;
    private final OpenAiSseEncoder sseEncoder;
    private final GatewayProperties properties;

    public ChatCompletionsController(ConfigSnapshotService snapshots, RequestRouter router,
                                     OpenAiSseEncoder sseEncoder, GatewayProperties properties) {
        this.snapshots = snapshots;
        this.router = router;
        this.sseEncoder = sseEncoder;
        this.properties = properties;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> complete(@RequestBody OpenAiChatCompletionRequest body,
                                            ServerWebExchange exchange) {
        body.validate();
        enforceLimits(body, exchange.getAttribute(ApiKeyAuthFilter.KEY_ATTR));
        // Snapshot read only — the hot path never touches the DB (§4).
        ProviderRegistry.RoutePlan plan = snapshots.registry().resolveRoute(body.model);

        if (Boolean.TRUE.equals(body.stream)) {
            Flux<String> frames = sseEncoder.encode(
                    router.stream(plan, body::toChatRequest),
                    plan.primary().model(), body.includeStreamUsage());
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(frames));
        }

        return router.chat(plan, body::toChatRequest)
                .map(resp -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(OpenAiDtos.ChatCompletionResponse.from(resp)));
    }

    /** §20 resource protection: reject oversized prompts, clamp max_tokens to the key's cap. */
    private void enforceLimits(OpenAiChatCompletionRequest body, ApiKeyEntity key) {
        int maxPromptChars = properties.limits().maxPromptChars();
        if (body.promptChars() > maxPromptChars) {
            throw new InvalidRequestException(
                    "Prompt exceeds the gateway limit of " + maxPromptChars + " characters.", "messages");
        }
        Integer cap = key == null ? null : key.maxTokensCap();
        if (cap == null) {
            return;
        }
        Integer requested = body.maxTokens != null ? body.maxTokens : body.maxCompletionTokens;
        body.maxTokens = requested == null ? cap : Math.min(requested, cap);
    }
}
