# Gateway overhead benchmark

Answers one question: **how much latency does BotvinnikAPI add to a call that would
otherwise go straight to the provider?**

```powershell
.\bench\run-bench.ps1                          # non-streaming, 10/50/200 concurrent
.\bench\run-bench.ps1 -Stream                  # streaming, latency measured as TTFT
.\bench\run-bench.ps1 -Levels 10,50 -Duration 60
```

The script starts Postgres, the stub, and the gateway; provisions a provider and an
unthrottled API key; runs both load phases; tears down what it started. Results land in
`bench/results-nonstreaming.md` / `bench/results-streaming.md`.

## Method

**Measure the delta, not the absolute.** Every run is a pair at the same concurrency:

1. **direct to stub** — the load generator posts Ollama-shaped `/api/chat` requests to the
   stub, exactly the request the gateway would have made.
2. **through gateway** — the load generator posts OpenAI-shaped `/v1/chat/completions` to
   the gateway with a real bearer token, which routes to that same stub.

Gateway overhead is (2) − (1) at the same percentile. Loopback TCP, JVM scheduling, the OS,
and the load generator's own cost sit in both numbers and cancel; an absolute number would
be mostly those things.

**The backend must not be the bottleneck.** `StubProvider.java` is a hand-rolled HTTP/1.1
server serving canned Ollama responses — no framework, no allocation per request beyond the
read buffer. It sustains ~170,000 req/s at 0.05 ms p50 on the dev laptop, an order of
magnitude past what the gateway does, so what the benchmark measures is the gateway.
`com.sun.net.httpserver` was not good enough here: it saturates in the same range as the
thing under test and would have quietly become the thing under test.

**The load generator must not be the bottleneck either.** `LoadGen.java` uses raw sockets
with one keep-alive connection pinned per worker — no HTTP client library, because a pooling
client's internal queueing lands inside the number we are trying to measure. It parses just
enough of the response to time it exactly (`Content-Length` and `chunked`), and stamps the
arrival of the first response byte separately from completion.

**Full auth is on.** SHA-256 bearer lookup, snapshot resolution, spend-cap check and
telemetry all run — the point is to price the real hot path, not a stripped one. The bench
key carries no rate limit and no spend cap, so what is measured is the gateway working, not
Bucket4j rejecting.

## What the numbers mean

- **Non-streaming latency** is full request/response completion.
- **Streaming latency** (`-Stream`) is time to the first response byte — the gateway's
  contribution to TTFT, which is what a user perceives. The stub emits 8 NDJSON deltas plus
  a terminal usage chunk.
- **The streaming delta is only trustworthy at low concurrency.** Ten flushed chunks per
  response cost ten syscalls on each side, and above ~10 concurrent the raw-socket baseline
  hits that wall before the gateway does — Netty batches the same bytes better than the
  generator reads them. The direct column stops being a floor and the delta goes negative,
  which means "the control saturated", not "the gateway is free". Read streaming overhead
  off the concurrency-10 row; read the higher rows as absolute gateway TTFT only.
- **Closed loop.** N workers each hold one request in flight, like `wrk`/`hey` without
  latency correction. These are service times at a given concurrency, not the response
  times of an open-loop arrival process; under saturation the two diverge (coordinated
  omission), so read the 200-concurrency row as "what the box does when hammered", not as
  a service-level promise.
- **The load generator shares a laptop with the gateway and the stub.** Contention inflates
  both sides of the pair, so the delta stays meaningful while the absolutes are pessimistic.
  Treat the numbers as a floor on performance, not a ceiling.

## Where the tail comes from

The p50 barely moves with concurrency; the p99 does. Running the gateway with
`-Xlog:gc:file=target/gc.log:time,uptime` under the 50-concurrent load names the cause:

```
Pause Young (Normal) (G1 Evacuation Pause) 600M->36M(948M) 2.4ms   <- every ~150ms
```

G1 young collections every ~150 ms, 2–3 ms each, occasionally 11–17 ms. At ~35,000 req/s
that is roughly 4 GB/s of garbage, ~110 KB per request. Each pause freezes every request
then in flight — 50 of them — and the burst that resumes behind it is what shows up at p99.
So the tail is allocation, not routing: the gateway's own logic is the flat ~0.4 ms, and
everything above p90 is the JVM reclaiming what the request path allocated. Reducing
per-request allocation is the lever on the tail; the p50 is already near the floor.

## Notes

- `--delay-micros N` on the stub simulates a slower backend if you want to see the gateway's
  overhead against realistic inference latency rather than against zero.
- The route used is `bench-stub/stub-model` — a pinned `provider/model` route, which does not
  widen to a pool, so balancer choice never varies between runs.
- Telemetry is deliberately left on. At these rates the bounded 10K queue drops rows, which
  is exactly the designed behaviour and part of the hot-path cost being measured.
