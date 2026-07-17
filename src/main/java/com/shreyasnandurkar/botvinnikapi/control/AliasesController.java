package com.shreyasnandurkar.botvinnikapi.control;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.control.dto.ControlDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.error.NotFoundException;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@RestController
public class AliasesController {

    private final AliasRepository aliases;
    private final ProviderRepository providers;
    private final ConfigSnapshotService snapshots;
    private final ObjectMapper mapper;

    public AliasesController(AliasRepository aliases, ProviderRepository providers,
                             ConfigSnapshotService snapshots, ObjectMapper mapper) {
        this.aliases = aliases;
        this.providers = providers;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    @PostMapping("/v1/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlDtos.AliasResponse> create(@RequestBody ControlDtos.CreateAliasRequest body) {
        body.validate();
        String providerName = providerPart(body.primary());
        String model = modelPart(body.primary());
        return aliases.findByAlias(body.alias())
                .flatMap(existing -> Mono.<ProviderEntity>error(new InvalidRequestException(
                        "An alias named '" + body.alias() + "' already exists.", "alias")))
                .switchIfEmpty(requireProvider(providerName, "primary"))
                .flatMap(target -> requireFallbackProviders(body.fallbacks())
                        .then(aliases.save(new AliasEntity(
                                null, body.alias(), target.id(), model, toJson(body.fallbacks()))))
                        .flatMap(saved -> snapshots.rebuild().thenReturn(saved))
                        .map(saved -> ControlDtos.AliasResponse.from(
                                saved, target.name(), fallbacksOf(saved))));
    }

    @GetMapping("/v1/aliases")
    public Mono<List<ControlDtos.AliasResponse>> list() {
        return aliases.findAll()
                .flatMap(a -> providers.findById(a.targetProviderId())
                        .map(p -> ControlDtos.AliasResponse.from(a, p.name(), fallbacksOf(a))))
                .collectList();
    }

    @PatchMapping("/v1/aliases/{id}")
    public Mono<ControlDtos.AliasResponse> update(@PathVariable UUID id,
                                                  @RequestBody ControlDtos.UpdateAliasRequest body) {
        body.validate();
        return aliases.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Alias", id.toString())))
                .flatMap(existing -> {
                    Mono<ProviderEntity> target = body.primary() != null
                            ? requireProvider(providerPart(body.primary()), "primary")
                            : providers.findById(existing.targetProviderId());
                    return target.flatMap(provider -> requireFallbackProviders(body.fallbacks())
                            .then(aliases.save(new AliasEntity(
                                    existing.id(), existing.alias(), provider.id(),
                                    body.primary() != null ? modelPart(body.primary()) : existing.targetModel(),
                                    body.fallbacks() != null ? toJson(body.fallbacks()) : existing.fallbackChain())))
                            .flatMap(saved -> snapshots.rebuild().thenReturn(saved))
                            .map(saved -> ControlDtos.AliasResponse.from(
                                    saved, provider.name(), fallbacksOf(saved))));
                });
    }

    @DeleteMapping("/v1/aliases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return aliases.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Alias", id.toString())))
                .flatMap(existing -> aliases.deleteById(id))
                .then(snapshots.rebuild());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Mono<ProviderEntity> requireProvider(String name, String param) {
        return providers.findByName(name)
                .switchIfEmpty(Mono.error(new InvalidRequestException(
                        "'" + param + "' references unknown provider '" + name + "'.", param)));
    }

    private Mono<Void> requireFallbackProviders(List<String> fallbacks) {
        if (fallbacks == null || fallbacks.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(fallbacks)
                .concatMap(f -> requireProvider(providerPart(f), "fallbacks"))
                .then();
    }

    private static String providerPart(String providerSlashModel) {
        return providerSlashModel.substring(0, providerSlashModel.indexOf('/'));
    }

    private static String modelPart(String providerSlashModel) {
        return providerSlashModel.substring(providerSlashModel.indexOf('/') + 1);
    }

    private Json toJson(List<String> fallbacks) {
        if (fallbacks == null || fallbacks.isEmpty()) {
            return null;
        }
        return Json.of(mapper.writeValueAsString(fallbacks));
    }

    private List<String> fallbacksOf(AliasEntity e) {
        if (e.fallbackChain() == null) {
            return List.of();
        }
        return mapper.readValue(e.fallbackChain().asString(), new TypeReference<List<String>>() {
        });
    }
}
