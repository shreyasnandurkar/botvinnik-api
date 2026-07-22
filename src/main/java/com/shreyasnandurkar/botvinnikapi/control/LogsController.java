package com.shreyasnandurkar.botvinnikapi.control;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only telemetry views (§15) — what the step 8 dashboard will render. */
@RestController
public class LogsController {

    private final DatabaseClient db;

    public LogsController(DatabaseClient db) {
        this.db = db;
    }

    public record LogRow(
            UUID id,
            Instant ts,
            @JsonProperty("api_key") String apiKey,
            String provider,
            String model,
            @JsonProperty("latency_ms") Long latencyMs,
            @JsonProperty("ttft_ms") Long ttftMs,
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            String outcome,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("cost_usd") BigDecimal costUsd,
            @JsonProperty("prompt_excerpt") String promptExcerpt,
            @JsonProperty("response_excerpt") String responseExcerpt) {
    }

    public record StatsView(
            @JsonProperty("window_minutes") int windowMinutes,
            long requests,
            @JsonProperty("requests_per_min") double requestsPerMin,
            @JsonProperty("error_rate") double errorRate,
            @JsonProperty("ttft_p50_ms") Double ttftP50Ms,
            @JsonProperty("ttft_p95_ms") Double ttftP95Ms,
            @JsonProperty("ttft_p99_ms") Double ttftP99Ms) {
    }

    public record UsageRow(
            @JsonProperty("api_key") String apiKey,
            String provider,
            String model,
            long requests,
            @JsonProperty("prompt_tokens") Long promptTokens,
            @JsonProperty("completion_tokens") Long completionTokens,
            @JsonProperty("cost_usd") BigDecimal costUsd) {
    }

    @GetMapping("/v1/logs")
    public Mono<List<LogRow>> logs(@RequestParam(required = false) String provider,
                                   @RequestParam(required = false) String model,
                                   @RequestParam(required = false) String outcome,
                                   @RequestParam(name = "api_key_id", required = false) UUID apiKeyId,
                                   @RequestParam(defaultValue = "50") int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT rl.id, rl.ts, k.name AS api_key, p.name AS provider, rl.model,
                       rl.latency_ms, rl.ttft_ms, rl.prompt_tokens, rl.completion_tokens,
                       rl.outcome, rl.error_code, rl.cost_usd, rl.prompt_excerpt, rl.response_excerpt
                FROM request_logs rl
                LEFT JOIN providers p ON p.id = rl.provider_id
                LEFT JOIN api_keys k ON k.id = rl.api_key_id
                WHERE 1 = 1
                """);
        List<Object[]> binds = new ArrayList<>();
        if (provider != null) {
            sql.append(" AND p.name = :provider");
            binds.add(new Object[]{"provider", provider});
        }
        if (model != null) {
            sql.append(" AND rl.model = :model");
            binds.add(new Object[]{"model", model});
        }
        if (outcome != null) {
            sql.append(" AND rl.outcome = :outcome");
            binds.add(new Object[]{"outcome", outcome});
        }
        if (apiKeyId != null) {
            sql.append(" AND rl.api_key_id = :apiKeyId");
            binds.add(new Object[]{"apiKeyId", apiKeyId});
        }
        sql.append(" ORDER BY rl.ts DESC LIMIT :limit");
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString())
                .bind("limit", Math.clamp(limit, 1, 500));
        for (Object[] bind : binds) {
            spec = spec.bind((String) bind[0], bind[1]);
        }
        return spec.map(LogsController::logRow).all().collectList();
    }

    /** §16's traffic row: rate, error share, TTFT percentiles over a recent window. */
    @GetMapping("/v1/stats")
    public Mono<StatsView> stats(@RequestParam(name = "window_minutes", defaultValue = "15") int windowMinutes) {
        int window = Math.clamp(windowMinutes, 1, 24 * 60);
        return db.sql("""
                        SELECT COUNT(*) AS requests,
                               COUNT(*) FILTER (WHERE outcome IN ('error', 'partial')) AS failures,
                               percentile_cont(0.5)  WITHIN GROUP (ORDER BY ttft_ms) AS p50,
                               percentile_cont(0.95) WITHIN GROUP (ORDER BY ttft_ms) AS p95,
                               percentile_cont(0.99) WITHIN GROUP (ORDER BY ttft_ms) AS p99
                        FROM request_logs
                        WHERE ts > now() - make_interval(mins => :window)
                        """)
                .bind("window", window)
                .map((row, meta) -> {
                    long requests = longOrZero(row, "requests");
                    long failures = longOrZero(row, "failures");
                    return new StatsView(window, requests,
                            Math.round(requests * 100.0 / window) / 100.0,
                            requests == 0 ? 0 : Math.round(failures * 1000.0 / requests) / 1000.0,
                            row.get("p50", Double.class),
                            row.get("p95", Double.class),
                            row.get("p99", Double.class));
                })
                .one();
    }

    @GetMapping("/v1/usage")
    public Mono<List<UsageRow>> usage() {
        return db.sql("""
                        SELECT k.name AS api_key, p.name AS provider, rl.model,
                               COUNT(*) AS requests,
                               SUM(rl.prompt_tokens) AS prompt_tokens,
                               SUM(rl.completion_tokens) AS completion_tokens,
                               SUM(rl.cost_usd) AS cost_usd
                        FROM request_logs rl
                        LEFT JOIN providers p ON p.id = rl.provider_id
                        LEFT JOIN api_keys k ON k.id = rl.api_key_id
                        GROUP BY k.name, p.name, rl.model
                        ORDER BY requests DESC
                        """)
                .map((row, meta) -> new UsageRow(
                        row.get("api_key", String.class),
                        row.get("provider", String.class),
                        row.get("model", String.class),
                        longOrZero(row, "requests"),
                        row.get("prompt_tokens", Long.class),
                        row.get("completion_tokens", Long.class),
                        row.get("cost_usd", BigDecimal.class)))
                .all().collectList();
    }

    private static LogRow logRow(Readable row) {
        return new LogRow(
                row.get("id", UUID.class),
                row.get("ts", Instant.class),
                row.get("api_key", String.class),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("latency_ms", Long.class),
                row.get("ttft_ms", Long.class),
                row.get("prompt_tokens", Integer.class),
                row.get("completion_tokens", Integer.class),
                row.get("outcome", String.class),
                row.get("error_code", String.class),
                row.get("cost_usd", BigDecimal.class),
                row.get("prompt_excerpt", String.class),
                row.get("response_excerpt", String.class));
    }

    private static long longOrZero(Readable row, String column) {
        Long value = row.get(column, Long.class);
        return value == null ? 0 : value;
    }
}
