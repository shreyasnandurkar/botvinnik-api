package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiChatCompletionRequest;
import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.routing.RequestRouter;
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

    private final ConfigSnapshotService snapshots;
    private final RequestRouter router;
    private final OpenAiSseEncoder sseEncoder;

    public ChatCompletionsController(ConfigSnapshotService snapshots, RequestRouter router,
                                     OpenAiSseEncoder sseEncoder) {
        this.snapshots = snapshots;
        this.router = router;
        this.sseEncoder = sseEncoder;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> complete(@RequestBody OpenAiChatCompletionRequest body) {
        body.validate();
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
}