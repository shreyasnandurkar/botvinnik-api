package com.shreyasnandurkar.botvinnikapi.control;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ApiKeyRepository;
import com.shreyasnandurkar.botvinnikapi.control.dto.ControlDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.NotFoundException;
import com.shreyasnandurkar.botvinnikapi.security.Sha256;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
public class ApiKeysController {

    private final ApiKeyRepository apiKeys;
    private final ConfigSnapshotService snapshots;
    private final SecureRandom random = new SecureRandom();

    public ApiKeysController(ApiKeyRepository apiKeys, ConfigSnapshotService snapshots) {
        this.apiKeys = apiKeys;
        this.snapshots = snapshots;
    }

    @PostMapping("/v1/apikeys")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlDtos.ApiKeyCreatedResponse> create(@RequestBody ControlDtos.CreateApiKeyRequest body) {
        body.validate();
        String key = generateKey();
        return apiKeys.save(new ApiKeyEntity(
                        null, Sha256.hex(key), body.name(), body.rateLimitRpm(), body.spendCapUsd(),
                        Boolean.TRUE.equals(body.logContent()), body.maxTokensCap(), null, null))
                .flatMap(saved -> snapshots.rebuild().thenReturn(saved))
                .map(saved -> new ControlDtos.ApiKeyCreatedResponse(
                        saved.id(), key, saved.name(), saved.rateLimitRpm(), saved.spendCapUsd(),
                        saved.logContent(), saved.maxTokensCap()));
    }

    @GetMapping("/v1/apikeys")
    public Mono<List<ControlDtos.ApiKeyResponse>> list() {
        return apiKeys.findAllByOrderByCreatedAt()
                .map(ControlDtos.ApiKeyResponse::from)
                .collectList();
    }

    @DeleteMapping("/v1/apikeys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return apiKeys.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("API key", id.toString())))
                .flatMap(existing -> apiKeys.deleteById(id))
                .then(snapshots.rebuild());
    }

    /** 32 bytes of entropy — the reason plain SHA-256 is a sound store (§11). */
    private String generateKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "sk_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
