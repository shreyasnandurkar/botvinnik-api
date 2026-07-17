package com.shreyasnandurkar.botvinnikapi.control;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.control.dto.ControlDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.error.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
public class ProvidersController {

    private final ProviderRepository providers;
    private final AliasRepository aliases;
    private final ConfigSnapshotService snapshots;

    public ProvidersController(ProviderRepository providers, AliasRepository aliases,
                               ConfigSnapshotService snapshots) {
        this.providers = providers;
        this.aliases = aliases;
        this.snapshots = snapshots;
    }

    @PostMapping("/v1/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlDtos.ProviderResponse> register(@RequestBody ControlDtos.RegisterProviderRequest body) {
        body.validate();
        return providers.findByName(body.name())
                .flatMap(existing -> Mono.<ProviderEntity>error(new InvalidRequestException(
                        "A provider named '" + body.name() + "' already exists.", "name")))
                .switchIfEmpty(providers.save(new ProviderEntity(
                        null, body.name(), body.type(), body.baseUrl(), body.apiKey(),
                        body.streamIdleTimeoutMs(), null, "active", null)))
                .flatMap(saved -> snapshots.rebuild().thenReturn(saved))
                .map(ControlDtos.ProviderResponse::from);
    }

    @GetMapping("/v1/providers")
    public Mono<List<ControlDtos.ProviderResponse>> list() {
        return providers.findAllByOrderByCreatedAt()
                .map(ControlDtos.ProviderResponse::from)
                .collectList();
    }

    @PatchMapping("/v1/providers/{id}")
    public Mono<ControlDtos.ProviderResponse> update(@PathVariable UUID id,
                                                     @RequestBody ControlDtos.UpdateProviderRequest body) {
        body.validate();
        return providers.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Provider", id.toString())))
                .map(existing -> new ProviderEntity(
                        existing.id(), existing.name(), existing.type(),
                        body.baseUrl() != null ? body.baseUrl() : existing.baseUrl(),
                        body.apiKey() != null ? body.apiKey() : existing.apiKey(),
                        body.streamIdleTimeoutMs() != null ? body.streamIdleTimeoutMs()
                                : existing.streamIdleTimeoutMs(),
                        existing.poolId(),
                        body.status() != null ? body.status() : existing.status(),
                        existing.createdAt()))
                .flatMap(providers::save)
                .flatMap(saved -> snapshots.rebuild().thenReturn(saved))
                .map(ControlDtos.ProviderResponse::from);
    }

    @DeleteMapping("/v1/providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return providers.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Provider", id.toString())))
                .flatMap(existing -> aliases.findAllByTargetProviderId(id).collectList()
                        .flatMap(referencing -> {
                            if (!referencing.isEmpty()) {
                                return Mono.error(new InvalidRequestException(
                                        "Provider '" + existing.name() + "' is the target of "
                                                + referencing.size() + " alias(es); delete them first.", null));
                            }
                            return providers.deleteById(id);
                        }))
                .then(snapshots.rebuild());
    }
}
