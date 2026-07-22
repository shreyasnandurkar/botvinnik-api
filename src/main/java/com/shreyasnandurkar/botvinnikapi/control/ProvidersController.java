package com.shreyasnandurkar.botvinnikapi.control;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.AliasRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.PoolRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.control.dto.ControlDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import com.shreyasnandurkar.botvinnikapi.core.error.NotFoundException;
import com.shreyasnandurkar.botvinnikapi.security.KeyCrypto;
import com.shreyasnandurkar.botvinnikapi.security.ssrf.SsrfBlockedException;
import com.shreyasnandurkar.botvinnikapi.security.ssrf.SsrfPolicy;
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
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;

@RestController
public class ProvidersController {

    private final ProviderRepository providers;
    private final AliasRepository aliases;
    private final PoolRepository pools;
    private final ConfigSnapshotService snapshots;
    private final KeyCrypto crypto;
    private final SsrfPolicy ssrfPolicy;

    public ProvidersController(ProviderRepository providers, AliasRepository aliases,
                               PoolRepository pools, ConfigSnapshotService snapshots,
                               KeyCrypto crypto, SsrfPolicy ssrfPolicy) {
        this.providers = providers;
        this.aliases = aliases;
        this.pools = pools;
        this.snapshots = snapshots;
        this.crypto = crypto;
        this.ssrfPolicy = ssrfPolicy;
    }

    @PostMapping("/v1/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlDtos.ProviderResponse> register(@RequestBody ControlDtos.RegisterProviderRequest body) {
        body.validate();
        KeyCrypto.Encrypted enc = body.apiKey() == null ? null : crypto.encrypt(body.apiKey());
        return providers.findByName(body.name())
                .flatMap(existing -> Mono.<ProviderEntity>error(new InvalidRequestException(
                        "A provider named '" + body.name() + "' already exists.", "name")))
                .switchIfEmpty(Mono.defer(() -> checkBaseUrl(body.baseUrl())
                        .then(resolvePoolId(body.pool()))
                        .flatMap(poolId -> providers.save(new ProviderEntity(
                                null, body.name(), body.type(), body.baseUrl(),
                                enc == null ? null : enc.ciphertext(), enc == null ? null : enc.nonce(),
                                body.streamIdleTimeoutMs(), unwrap(poolId), "active", null)))))
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
        KeyCrypto.Encrypted enc = body.apiKey() == null ? null : crypto.encrypt(body.apiKey());
        return providers.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Provider", id.toString())))
                .flatMap(existing -> checkBaseUrl(body.baseUrl())
                        .then(resolvePoolId(body.pool()))
                        .map(poolId -> new ProviderEntity(
                                existing.id(), existing.name(), existing.type(),
                                body.baseUrl() != null ? body.baseUrl() : existing.baseUrl(),
                                enc != null ? enc.ciphertext() : existing.encryptedApiKey(),
                                enc != null ? enc.nonce() : existing.nonce(),
                                body.streamIdleTimeoutMs() != null ? body.streamIdleTimeoutMs()
                                        : existing.streamIdleTimeoutMs(),
                                body.pool() == null ? existing.poolId() : unwrap(poolId),
                                body.status() != null ? body.status() : existing.status(),
                                existing.createdAt())))
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

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Marks "no pool" so reactive operators never have to carry a null. */
    private static final UUID NO_POOL = new UUID(0, 0);

    private static UUID unwrap(UUID poolId) {
        return NO_POOL.equals(poolId) ? null : poolId;
    }

    /**
     * Registration-time SSRF check (§11). The resolver guard re-validates at every
     * connect — DNS changes — so this is the early, friendly rejection, not the defense.
     */
    private Mono<Void> checkBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Mono.empty();
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InvalidRequestException("'base_url' is not a valid URL.", "base_url"));
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            return Mono.error(new InvalidRequestException(
                    "'base_url' must use http or https.", "base_url"));
        }
        if (uri.getHost() == null) {
            return Mono.error(new InvalidRequestException("'base_url' must include a host.", "base_url"));
        }
        return Mono.fromCallable(() -> InetAddress.getAllByName(uri.getHost()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(addresses -> {
                    for (InetAddress address : addresses) {
                        ssrfPolicy.validate(address);
                    }
                })
                .onErrorMap(SsrfBlockedException.class, e -> new InvalidRequestException(
                        "'base_url' is rejected: " + e.getMessage(), "base_url"))
                .onErrorMap(UnknownHostException.class, e -> new InvalidRequestException(
                        "'base_url' host '" + uri.getHost() + "' cannot be resolved.", "base_url"))
                .then();
    }

    /** null or "" → NO_POOL; a name must resolve or the request is rejected. */
    private Mono<UUID> resolvePoolId(String pool) {
        if (pool == null || pool.isBlank()) {
            return Mono.just(NO_POOL);
        }
        return pools.findByName(pool)
                .map(p -> p.id())
                .switchIfEmpty(Mono.error(new InvalidRequestException(
                        "'pool' references unknown pool '" + pool + "'.", "pool")));
    }
}
