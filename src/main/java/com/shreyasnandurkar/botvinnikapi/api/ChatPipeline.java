package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import com.shreyasnandurkar.botvinnikapi.core.ProviderRegistry;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.model.ChatRequest;
import com.shreyasnandurkar.botvinnikapi.core.routing.ModelCatalog;
import com.shreyasnandurkar.botvinnikapi.core.routing.RequestRouter;
import com.shreyasnandurkar.botvinnikapi.security.ApiKeyAuthFilter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/** Validate → limit → resolve → dispatch → encode, shared by every client protocol. */
@Component
public class ChatPipeline {

    private final ConfigSnapshotService snapshots;
    private final RequestRouter router;
    private final GatewayProperties properties;
    private final ModelCatalog catalog;

    public ChatPipeline(ConfigSnapshotService snapshots, RequestRouter router,
                        GatewayProperties properties, ModelCatalog catalog) {
        this.snapshots = snapshots;
        this.router = router;
        this.properties = properties;
        this.catalog = catalog;
    }

    public <B> Mono<ResponseEntity<?>> handle(ClientProtocol<B> protocol, B body, ServerWebExchange exchange) {
        ClientProtocol.InboundChat inbound = protocol.toChatRequest(body);
        Function<String, ChatRequest> forModel =
                enforceLimits(inbound, exchange.getAttribute(ApiKeyAuthFilter.KEY_ATTR));
        // Snapshot read only — the hot path never touches the DB (§4).
        ProviderRegistry.RoutePlan plan = snapshots.registry().resolveRoute(inbound.model(), catalog);

        if (inbound.stream()) {
            Flux<?> frames = protocol.encodeStream(
                    router.stream(plan, forModel), body, plan.primary().model());
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(frames));
        }

        return router.chat(plan, forModel)
                .map(resp -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(protocol.encodeResponse(resp)));
    }

    /** §20 resource protection: reject oversized prompts, clamp max_tokens to the key's cap. */
    private Function<String, ChatRequest> enforceLimits(ClientProtocol.InboundChat inbound, ApiKeyEntity key) {
        int maxPromptChars = properties.limits().maxPromptChars();
        if (inbound.promptChars() > maxPromptChars) {
            throw new InvalidRequestException(
                    "Prompt exceeds the gateway limit of " + maxPromptChars + " characters.", "messages");
        }
        Integer cap = key == null ? null : key.maxTokensCap();
        if (cap == null) {
            return inbound.forModel();
        }
        return model -> {
            ChatRequest request = inbound.forModel().apply(model);
            Integer requested = request.maxTokens();
            return request.withMaxTokens(requested == null ? cap : Math.min(requested, cap));
        };
    }
}
