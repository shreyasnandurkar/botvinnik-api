<div align="center">
  <img src="src/images/botvinnik-logo.png" alt="BotvinnikAPI" width="420"/>

  <h3>A self-hosted LLM gateway for the JVM. One endpoint, any client SDK, any backend.</h3>

  <p>
    <img alt="Java 25" src="https://img.shields.io/badge/Java-25-b07219">
    <img alt="Spring Boot 4.1" src="https://img.shields.io/badge/Spring%20Boot-4.1-6db33f">
    <img alt="WebFlux" src="https://img.shields.io/badge/reactive-WebFlux%20%2B%20Netty-6db33f">
    <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-R2DBC%20%2B%20Flyway-336791">
    
  </p>

  [Overview](#overview) · [Features](#features) · [Getting started](#getting-started) · [Using it](#using-it) · [How routing works](#how-routing-works) · [API reference](#api-reference) · [Design notes](#design-notes)
</div>

---

## Overview

**BotvinnikAPI** sits between your applications and your LLM backends: a GPU box running **Ollama** down the hall, **Gemini** or **Anthropic** in the cloud, **Groq**, **vLLM**, or anything else that speaks the OpenAI wire format. Apps call one endpoint with one API key. The gateway handles routing, load balancing, failover, streaming, rate limits, and spend tracking.

Apps keep the SDK they already use. The OpenAI SDK, the Anthropic SDK, the OpenAI Agents SDK, the `ollama` library, LangChain, Open WebUI, Cursor, and Claude Code all work **unmodified**: change the base URL and you're done.

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:4000/v1", api_key="sk_live_...")

client.chat.completions.create(
    model="assistant",          # an alias, retargeted server-side
    messages=[{"role": "user", "content": "hello"}],
    stream=True,
)
```

`model="assistant"` is an **alias**. The operator maps it to, say, `office-gpu/qwen3:4b` with a fallback to `google/gemini-2.5-flash`. Moving every client from a local model to a cloud one (or back) is a single `PATCH`, with no code changes and no redeploys.

```
          client protocols                                      provider adapters
┌───────────────────────────────────────┐                  ┌────────────────────────────────┐
│ OpenAI Chat       /v1/chat/completions│                  │ ollama     NDJSON              │
│ OpenAI Responses  /v1/responses       │  ┌────────────┐  │ gemini     SSE                 │
│ Anthropic         /v1/messages        │─▶│BotvinnikAPI│─▶│ anthropic  named SSE events    │
│ Ollama            /api/chat ...       │  └────────────┘  │ openai     OpenAI, Groq, vLLM  │
└───────────────────────────────────────┘                  └────────────────────────────────┘
```

Any client protocol can reach any backend. A Claude Code session can be served by a local Qwen model; an Ollama client can be served by Gemini.

## Features

| | |
|---|---|
| **Four client protocols** | OpenAI Chat Completions, OpenAI Responses (stateless), Anthropic Messages, and the Ollama API. Streaming, tool calls, and reasoning/thinking content on all of them. |
| **Embeddings** | `/v1/embeddings` and `/api/embed`, served by Ollama, OpenAI-compatible, and Gemini backends through the same routing and logging as chat. |
| **Native provider adapters** | Each adapter speaks its backend's real wire format over plain HTTP (no vendor SDKs) and normalizes it into one common stream. |
| **Aliases, pools, and failover** | Aliases map to a primary model plus a fallback chain. Pools group identical backends. Connect-time failures move to the next candidate automatically. |
| **Load-aware balancing** | Power-of-two-choices (P2C), least-connections, or round-robin, keyed on **live in-flight requests** rather than request counts. |
| **Circuit breakers** | A provider failing half of its recent calls is skipped for 15 s, then probed back to life with a single test call. |
| **TTFT-based health** | Each provider is `HEALTHY`, `DEGRADED`, `OPEN`, or `DOWN`, based on a moving average of time-to-first-token. Slowdowns show up before hard failures. |
| **Keys, limits, and spend caps** | Hashed gateway keys, per-key rate limits, USD spend caps, and max-token caps. Provider credentials are encrypted at rest. |
| **SSRF protection** | Backend URLs are checked at connect time, so DNS rebinding can't reach cloud metadata endpoints. LAN ranges are allowlisted by config. |
| **Telemetry and dashboard** | Request logs with per-key cost attribution, usage and latency endpoints, and a read-only dashboard at `/dashboard`. |

<div align="center">
  <img src="src/images/dashboard.png" alt="BotvinnikAPI dashboard showing provider health, TTFT percentiles, routing table, spend and failures" width="100%"/>
</div>

## Getting started

### Prerequisites

- **JDK 25**
- **Docker**, for Postgres
- Optionally, [Ollama](https://ollama.com) with a model pulled, or an API key for a cloud provider

### Run the gateway

```bash
# 1. Start Postgres
docker run -d --name botvinnik-pg \
  -e POSTGRES_USER=botvinnik -e POSTGRES_PASSWORD=botvinnik -e POSTGRES_DB=botvinnik \
  -p 5432:5432 postgres:17-alpine

# 2. Set the environment
export GEMINI_API_KEY=your-gemini-key                        # any placeholder works if you only use local models
export GATEWAY_ENCRYPTION_KEY=$(openssl rand -base64 32)     # encrypts provider keys at rest

# 3. Start it on port 4000. Flyway creates the schema; providers in application.yaml are seeded.
./mvnw spring-boot:run
```

> [!TIP]
> For quick local experiments, `AUTH_ENABLED=false ./mvnw spring-boot:run` skips API key checks on the data plane.

### Make your first request

```bash
# Create an API key. It is shown once, so save it.
curl -X POST localhost:4000/v1/apikeys -H 'Content-Type: application/json' \
  -d '{"name":"my-app","rate_limit_rpm":120}'

# Chat through the gateway, pinned to a specific provider
curl localhost:4000/v1/chat/completions \
  -H "Authorization: Bearer sk_live_..." -H 'Content-Type: application/json' \
  -d '{"model":"local-ollama/qwen3:1.7b","messages":[{"role":"user","content":"hello"}]}'
```

Then open **http://localhost:4000/dashboard**.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `botvinnik`, `botvinnik`, `botvinnik` | Postgres connection |
| `GATEWAY_ENCRYPTION_KEY` | dev fallback | Base64 AES key for provider credentials |
| `AUTH_ENABLED` | `true` | Require gateway API keys on the data plane |
| `GEMINI_API_KEY` | required | Key for the `google` provider seeded from `application.yaml` |

Seed providers, model prices, and allowed LAN ranges live in [`application.yaml`](src/main/resources/application.yaml). Everything else is managed at runtime through the control-plane API.

## Using it

### Register a backend

```bash
# Any OpenAI-compatible server: OpenAI, Groq, vLLM, LM Studio, OpenRouter...
curl -X POST localhost:4000/v1/providers -H 'Content-Type: application/json' \
  -d '{"name":"groq","type":"openai","base_url":"https://api.groq.com/openai/v1","api_key":"gsk_..."}'
```

Supported `type` values: `ollama`, `gemini`, `anthropic`, `openai`.

### Create an alias with fallbacks

```bash
curl -X POST localhost:4000/v1/aliases -H 'Content-Type: application/json' \
  -d '{"alias":"assistant","primary":"local-ollama/qwen3:1.7b","fallbacks":["google/gemini-2.5-flash"]}'
```

### Point your SDK at it

| Client | Base URL |
|---|---|
| OpenAI SDK, LangChain, Cursor | `http://localhost:4000/v1` |
| OpenAI Agents SDK (Responses) | `http://localhost:4000/v1` |
| Anthropic SDK, Claude Code | `http://localhost:4000` |
| `ollama` library, Open WebUI | `http://localhost:4000` |

Authenticate with `Authorization: Bearer sk_live_...` or `x-api-key: sk_live_...`.

```python
import anthropic

client = anthropic.Anthropic(base_url="http://localhost:4000", api_key="sk_live_...")
client.messages.create(
    model="groq/openai/gpt-oss-20b",   # an Anthropic client, served by Groq
    max_tokens=256,
    messages=[{"role": "user", "content": "hello"}],
)
```

## How routing works

The `model` field decides where a request goes:

| You send | What happens |
|---|---|
| `assistant` (an alias) | Primary, then each fallback in order. Each step is widened to its pool. |
| `groq/openai/gpt-oss-20b` | Pinned to provider `groq`, because `groq` is registered. If the prefix isn't a provider name, the whole string is treated as a model id. |
| `qwen3:1.7b` (a bare name) | Sent to any provider whose discovered model list includes it, balanced across them. `404` if no provider has it. |

A background job polls every provider's model list, so bare names work without configuration.

```
request ─▶ auth ─▶ rate limit + spend cap ─▶ resolve route ─▶ balance ─▶ circuit breaker ─▶ adapter ─▶ backend
           └──────────── all in memory, no database calls on the request path ────────────┘
```

> [!IMPORTANT]
> Failover only happens **before the first byte is sent**. Once a stream has started, HTTP can't take bytes back, so a backend that dies mid-stream produces an in-band error event and a clean end of stream instead of a retry.

## API reference

**Data plane** (requires an API key):

```
POST /v1/chat/completions        OpenAI Chat, stream: true | false
POST /v1/responses               OpenAI Responses (stateless: previous_response_id is rejected)
POST /v1/messages                Anthropic Messages, stream: true | false
POST /v1/embeddings              OpenAI embeddings, encoding_format: float | base64
POST /api/chat, /api/generate    Ollama, NDJSON streaming (stream defaults to true)
POST /api/embed                  Ollama embeddings
GET  /v1/models, /api/tags       Models across all providers (GET /api/version is open)
```

**Control plane** (operator):

```
POST/GET/PATCH/DELETE  /v1/providers      register local or cloud backends
POST/GET               /v1/pools          group providers for load balancing
POST/GET/PATCH/DELETE  /v1/aliases        alias → primary + fallbacks
POST/GET/DELETE        /v1/apikeys        keys with rate limits, spend caps, token caps
GET                    /v1/health         status, circuit, in-flight, TTFT per provider
GET                    /v1/logs           request history, filterable
GET                    /v1/usage          spend per key / provider / model
GET                    /v1/stats          req/min, error rate, TTFT p50/p95/p99
```

> [!WARNING]
> The control plane is intentionally unauthenticated. Keep it on localhost or a trusted network.

### Failure behavior

| Failure | Result |
|---|---|
| Backend unreachable at connect | Next candidate in the chain; `503` if none are left |
| Backend dies mid-stream | In-band error event, then a clean end of stream |
| Client disconnects | Upstream request is cancelled, so token spend stops |
| Backend goes silent between chunks | Idle timeout, `504 stream_idle_timeout` |
| Upstream `429` or `5xx` | Counted by the circuit breaker, eligible for failover |
| Model refuses or a safety filter triggers | `200 OK`, passed through. A refusal is a valid answer, not an outage. |
| Rate limit or spend cap reached | `429` with `Retry-After`, before any routing work |

## Design notes

- **No database on the request path.** Config lives in an in-memory snapshot that is rebuilt and swapped atomically after every admin write. Request logs go to a bounded queue drained in batches, so a slow database degrades logging, never requests.
- **Balance on in-flight load, not request counts.** LLM calls take anywhere from 200 ms to several minutes, so two requests are rarely the same cost. P2C picks the less busy of two random backends and avoids everyone piling onto the same "least loaded" one.
- **Idle timeouts, not total timeouts.** A long generation is normal. Silence between chunks is the real failure signal.
- **Cancellation propagates.** When a client disconnects, the reactive chain cancels and the upstream connection closes.
- **Credentials done properly.** Gateway keys are stored as SHA-256 hashes and shown once. Provider keys are encrypted with AES-GCM using a fresh nonce per record.

Deeper rationale is in [`docs/HLD.md`](docs/HLD.md).

## Performance

Measured with the in-repo harness ([`bench/`](bench/README.md)) against a stub backend fast enough that only the gateway is measured. Each run is paired with a direct call at the same concurrency, and the table shows the **difference**: what the gateway itself adds.

| Concurrency | Throughput | Added p50 | Added p99 | Errors |
|---|---|---|---|---|
| 10 | 20,300 req/s | 0.39 ms | 0.71 ms | 0 |
| 50 | 33,100 req/s | 0.44 ms | 13.6 ms | 0 |
| 200 | 33,800 req/s | 0.66 ms | 34.6 ms | 0 |

Dev laptop (Ryzen AI 7 350, 16 threads), full auth enabled, load generator on the same machine. Next to an LLM's 200 to 3,000 ms response time, about 0.4 ms of overhead is negligible. The p99 at high concurrency comes from garbage-collection pauses, not routing work.

```powershell
.\bench\run-bench.ps1            # non-streaming
.\bench\run-bench.ps1 -Stream    # streaming, measured as time to first token
```

## Testing

```bash
./mvnw test
```

178 tests, most of them failure-injection tests. WireMock plays each backend and returns errors or garbage; raw Netty servers die mid-body or hang forever; cancellation is checked with connection-disposal latches; circuit-breaker trips are verified at the wire level. Control-plane tests run against real Postgres via Testcontainers, so Docker is required.

> [!NOTE]
> BotvinnikAPI is built for a single operator. There is no multi-tenancy, RBAC, moderation, or semantic caching. It routes requests; it does not run models.
