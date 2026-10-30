package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.ollama.OllamaChatProtocol;
import com.shreyasnandurkar.botvinnikapi.api.ollama.OllamaChatRequest;
import com.shreyasnandurkar.botvinnikapi.api.ollama.OllamaDtos;
import com.shreyasnandurkar.botvinnikapi.api.ollama.OllamaEmbedRequest;
import com.shreyasnandurkar.botvinnikapi.api.ollama.OllamaGenerateProtocol;
import com.shreyasnandurkar.botvinnikapi.api.ollama.OllamaGenerateRequest;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.core.model.ModelInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** The Ollama API surface, so apps pointed at an Ollama server can use the gateway unchanged. */
@RestController
public class OllamaController {

    /** Clients gate features on the server version; this is the Ollama API level the gateway speaks. */
    private static final String API_VERSION = "0.12.0";

    private final ChatPipeline pipeline;
    private final OllamaChatProtocol chat;
    private final OllamaGenerateProtocol generate;
    private final ConfigSnapshotService snapshots;

    public OllamaController(ChatPipeline pipeline, OllamaChatProtocol chat, OllamaGenerateProtocol generate,
                            ConfigSnapshotService snapshots) {
        this.pipeline = pipeline;
        this.chat = chat;
        this.generate = generate;
        this.snapshots = snapshots;
    }

    @PostMapping("/api/chat")
    public Mono<ResponseEntity<?>> chat(@RequestBody OllamaChatRequest body, ServerWebExchange exchange) {
        return pipeline.handle(chat, body, exchange);
    }

    @PostMapping("/api/generate")
    public Mono<ResponseEntity<?>> generate(@RequestBody OllamaGenerateRequest body, ServerWebExchange exchange) {
        return pipeline.handle(generate, body, exchange);
    }

    @PostMapping("/api/embed")
    public Mono<OllamaDtos.EmbedResponse> embed(@RequestBody OllamaEmbedRequest body) {
        body.validate();
        return pipeline.embed(body.model, body.promptChars(), body::toEmbeddingRequest)
                .map(resp -> new OllamaDtos.EmbedResponse(resp.model(), resp.embeddings(),
                        resp.usage() == null ? 0 : resp.usage().promptTokens()));
    }

    /** The same union of discovered models as /v1/models, one entry per name. */
    @GetMapping("/api/tags")
    public Mono<OllamaDtos.TagList> tags() {
        return Flux.fromIterable(snapshots.registry().all())
                .flatMap(provider -> provider.listModels().onErrorReturn(List.of()))
                .flatMapIterable(models -> models)
                .map(ModelInfo::id)
                .distinct()
                .map(OllamaDtos.Tag::of)
                .collectList()
                .map(OllamaDtos.TagList::new);
    }

    @GetMapping("/api/version")
    public OllamaDtos.Version version() {
        return new OllamaDtos.Version(API_VERSION);
    }
}
