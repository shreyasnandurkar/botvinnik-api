# Gateway overhead benchmark

- mode: **non-streaming**
- latency: full request/response completion
- measure: 30s per run, 10s warmup discarded
- host: Windows 11, 16 logical CPUs, JDK 25.0.3

## Concurrency 10

| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|---|
| direct to stub | 118,697 req/s | 0.07 ms | 0.15 ms | 0.18 ms | 0.26 ms | 0.40 ms | 4.34 ms | 0 |
| through gateway | 20,336 req/s | 0.46 ms | 0.66 ms | 0.74 ms | 0.98 ms | 2.82 ms | 16.49 ms | 0 |

## Concurrency 50

| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|---|
| direct to stub | 140,664 req/s | 0.36 ms | 0.63 ms | 0.72 ms | 0.95 ms | 1.31 ms | 27.97 ms | 0 |
| through gateway | 33,051 req/s | 0.80 ms | 2.48 ms | 4.27 ms | 14.57 ms | 43.60 ms | 141.43 ms | 0 |

## Concurrency 200

| Path | Throughput | p50 | p90 | p95 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|---|
| direct to stub | 116,002 req/s | 1.82 ms | 2.90 ms | 3.25 ms | 4.02 ms | 5.47 ms | 24.52 ms | 0 |
| through gateway | 33,780 req/s | 2.48 ms | 15.68 ms | 21.89 ms | 38.60 ms | 65.67 ms | 228.79 ms | 0 |

## Added latency (gateway − direct, same concurrency)

| Concurrency | p50 | p90 | p95 | p99 | p99.9 |
|---|---|---|---|---|---|
| 10 | 0.39 ms | 0.51 ms | 0.56 ms | 0.71 ms | 2.43 ms |
| 50 | 0.44 ms | 1.86 ms | 3.55 ms | 13.62 ms | 42.29 ms |
| 200 | 0.66 ms | 12.79 ms | 18.63 ms | 34.58 ms | 60.20 ms |
