package com.shreyasnandurkar.botvinnikapi.api;

import com.shreyasnandurkar.botvinnikapi.api.dto.OpenAiDtos;
import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GET /v1/models — the union of listModels() across every registered provider.
 */
@RestController
public class ModelsController {

    private final ConfigSnapshotService snapshots;

    public ModelsController(ConfigSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/v1/models")
    public Mono<OpenAiDtos.ModelList> listModels() {
        return Flux.fromIterable(snapshots.registry().all())
                .flatMap(provider -> provider.listModels()
                        .onErrorReturn(java.util.List.of()))
                .flatMapIterable(models -> models)
                .collectList()
                .map(OpenAiDtos.ModelList::from);
    }
}