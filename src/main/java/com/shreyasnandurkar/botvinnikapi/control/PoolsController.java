package com.shreyasnandurkar.botvinnikapi.control;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import com.shreyasnandurkar.botvinnikapi.control.db.PoolEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.PoolRepository;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderEntity;
import com.shreyasnandurkar.botvinnikapi.control.db.ProviderRepository;
import com.shreyasnandurkar.botvinnikapi.control.dto.ControlDtos;
import com.shreyasnandurkar.botvinnikapi.core.error.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
public class PoolsController {

    private final PoolRepository pools;
    private final ProviderRepository providers;
    private final ConfigSnapshotService snapshots;

    public PoolsController(PoolRepository pools, ProviderRepository providers,
                           ConfigSnapshotService snapshots) {
        this.pools = pools;
        this.providers = providers;
        this.snapshots = snapshots;
    }

    @PostMapping("/v1/pools")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlDtos.PoolResponse> create(@RequestBody ControlDtos.CreatePoolRequest body) {
        body.validate();
        return pools.findByName(body.name())
                .flatMap(existing -> Mono.<PoolEntity>error(new InvalidRequestException(
                        "A pool named '" + body.name() + "' already exists.", "name")))
                .switchIfEmpty(pools.save(new PoolEntity(null, body.name(), body.strategyOrDefault())))
                .flatMap(saved -> snapshots.rebuild().thenReturn(saved))
                .map(saved -> new ControlDtos.PoolResponse(saved.id(), saved.name(), saved.strategy(), List.of()));
    }

    @GetMapping("/v1/pools")
    public Mono<List<ControlDtos.PoolResponse>> list() {
        return pools.findAll()
                .flatMap(pool -> providers.findAllByOrderByCreatedAt()
                        .filter(p -> pool.id().equals(p.poolId()))
                        .map(ProviderEntity::name)
                        .collectList()
                        .map(members -> new ControlDtos.PoolResponse(
                                pool.id(), pool.name(), pool.strategy(), members)))
                .collectList();
    }
}
