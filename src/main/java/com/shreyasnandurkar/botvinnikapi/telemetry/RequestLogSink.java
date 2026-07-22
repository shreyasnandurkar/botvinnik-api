package com.shreyasnandurkar.botvinnikapi.telemetry;

import com.shreyasnandurkar.botvinnikapi.config.ConfigSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §13's bounded telemetry queue: fast producer, slow database. 10K cap,
 * drop-oldest, batched drain — never block or grow on the hot path.
 */
@Component
public class RequestLogSink implements RequestTelemetry {

    private static final Logger log = LoggerFactory.getLogger(RequestLogSink.class);
    private static final int CAPACITY = 10_000;
    private static final int MAX_BATCH = 500;

    private final ArrayBlockingQueue<PricedEntry> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final DatabaseClient db;
    private final ConfigSnapshotService snapshots;
    private final PriceBook priceBook;
    private final SpendTracker spendTracker;
    private final Disposable drain;

    private record PricedEntry(RequestLogEntry entry, BigDecimal costUsd) {
    }

    public RequestLogSink(DatabaseClient db, ConfigSnapshotService snapshots,
                          PriceBook priceBook, SpendTracker spendTracker) {
        this.db = db;
        this.snapshots = snapshots;
        this.priceBook = priceBook;
        this.spendTracker = spendTracker;
        this.drain = Mono.defer(this::drainBatch)
                .then(Mono.delay(Duration.ofSeconds(1)))
                .repeat()
                .subscribe();
    }

    @Override
    public void record(RequestLogEntry entry) {
        BigDecimal cost = priceBook.cost(entry.model(), entry.promptTokens(), entry.completionTokens());
        if (entry.apiKeyId() != null) {
            // Spend must be current at the next request's ingress check, not at DB-drain time.
            spendTracker.add(entry.apiKeyId(), cost);
        }
        PricedEntry priced = new PricedEntry(entry, cost);
        while (!queue.offer(priced)) {
            queue.poll();
            dropped.incrementAndGet();
        }
    }

    public long dropped() {
        return dropped.get();
    }

    private Mono<Void> drainBatch() {
        List<PricedEntry> batch = new ArrayList<>();
        queue.drainTo(batch, MAX_BATCH);
        if (batch.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(batch)
                .concatMap(this::insert)
                .then()
                .onErrorResume(e -> {
                    log.warn("Dropping {} request_logs rows: {}", batch.size(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> insert(PricedEntry priced) {
        RequestLogEntry e = priced.entry();
        UUID providerId = snapshots.providerId(e.provider());
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO request_logs
                          (api_key_id, provider_id, model, ts, latency_ms, ttft_ms,
                           prompt_tokens, completion_tokens, outcome, error_code,
                           cost_usd, prompt_excerpt, response_excerpt)
                        VALUES (:apiKeyId, :providerId, :model, :ts, :latencyMs, :ttftMs,
                                :promptTokens, :completionTokens, :outcome, :errorCode,
                                :costUsd, :promptExcerpt, :responseExcerpt)
                        """)
                .bind("ts", e.ts())
                .bind("latencyMs", e.latencyMs())
                .bind("costUsd", priced.costUsd());
        spec = bindNullable(spec, "apiKeyId", e.apiKeyId(), UUID.class);
        spec = bindNullable(spec, "providerId", providerId, UUID.class);
        spec = bindNullable(spec, "model", e.model(), String.class);
        spec = bindNullable(spec, "ttftMs", e.ttftMs(), Long.class);
        spec = bindNullable(spec, "promptTokens", e.promptTokens(), Integer.class);
        spec = bindNullable(spec, "completionTokens", e.completionTokens(), Integer.class);
        spec = bindNullable(spec, "outcome", e.outcome(), String.class);
        spec = bindNullable(spec, "errorCode", e.errorCode(), String.class);
        spec = bindNullable(spec, "promptExcerpt", e.promptExcerpt(), String.class);
        spec = bindNullable(spec, "responseExcerpt", e.responseExcerpt(), String.class);
        return spec.then();
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
