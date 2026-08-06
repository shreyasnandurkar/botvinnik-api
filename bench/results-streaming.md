# Gateway overhead benchmark

- mode: **streaming (SSE)**
- latency: time to first response byte (TTFT)
- measure: 30s per run, 10s warmup discarded
- host: Windows 11, 16 logical CPUs, JDK 25.0.3

## Concurrency 10

| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|---|
| direct to stub | 14,636 req/s | 0.09 ms | 0.14 ms | 0.17 ms | 0.25 ms | 0.75 ms | 11.11 ms | 0 |
| through gateway | 8,357 req/s | 0.64 ms | 0.95 ms | 1.08 ms | 1.42 ms | 3.07 ms | 5.68 ms | 0 |

## Concurrency 50

| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|---|
| direct to stub | 18,333 req/s | 1.54 ms | 4.11 ms | 5.73 ms | 12.31 ms | 32.18 ms | 85.11 ms | 0 |
| through gateway | 11,409 req/s | 3.16 ms | 4.02 ms | 4.80 ms | 14.27 ms | 39.36 ms | 98.81 ms | 0 |

## Concurrency 200

| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|---|
| direct to stub | 18,239 req/s | 9.61 ms | 19.97 ms | 25.90 ms | 43.64 ms | 95.24 ms | 233.86 ms | 0 |
| through gateway | 11,547 req/s | 13.28 ms | 27.08 ms | 35.44 ms | 64.08 ms | 132.23 ms | 453.17 ms | 0 |

## Added latency (gateway − direct, same concurrency)

| Concurrency | p50 | p90 | p95 | p99 | p99.9 |
|---|---|---|---|---|---|
| 10 | 0.55 ms | 0.81 ms | 0.92 ms | 1.17 ms | 2.32 ms |
| 50 | 1.62 ms | -0.08 ms | -0.93 ms | 1.96 ms | 7.18 ms |
| 200 | 3.67 ms | 7.12 ms | 9.54 ms | 20.44 ms | 36.99 ms |
