package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.openai.EmbeddingsRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** OpenAI embeddings entry point; the work happens in ChatPipeline. */
@RestController
public class EmbeddingsController {

    private final ChatPipeline pipeline;

    public EmbeddingsController(ChatPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/v1/embeddings")
    public Mono<ResponseEntity<?>> create(@RequestBody EmbeddingsRequest body) {
        return pipeline.embed(body);
    }
}
