---
inclusion: always
---

# OmniSearch — Project Steering Guide

OmniSearch is a Perplexity AI-style answer engine: it takes a user query, performs a web search via the Tavily API, builds context, streams a synthesized Markdown answer from an LLM, and generates follow-up questions. Built with Spring AI on a reactive (WebFlux/Reactor) pipeline.

> **Note:** `OMNISEARCH_SETUP.md` is outdated. It references endpoints (`/search`, `/search/stream`), a `ContextEngineeringAgent`, and a `SupabaseService` that no longer exist in the current code. Use this file as the source of truth.

## Tech Stack

- **Language/Runtime:** Java 21
- **Framework:** Spring Boot 3.5.5
- **AI:** Spring AI 1.1.0 — currently wired to **Google GenAI (Gemini `gemini-3.5-flash-lite`)**. Ollama support is present but commented out in `pom.xml` and `application.properties`.
- **Reactive stack:** spring-boot-starter-webflux (Project Reactor `Mono`/`Flux`). The app serves a Spring MVC controller layer over a reactive pipeline.
- **Build:** Maven (`./mvnw`), managed by `pom.xml`
- **Boilerplate:** Lombok (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`)
- **Chat memory:** `MessageWindowChatMemory` (max 20 messages), applied via `MessageChatMemoryAdvisor`

## Build & Run

```bash
# Build
./mvnw clean install

# Run (requires GOOGLE_GENAI_API_KEY or the key set in application.properties)
./mvnw spring-boot:run
```

The app starts on the default port (8080). The static frontend is served from `src/main/resources/static/index.html`.

## Architecture & Package Layout

Base package: `com.ojashwa.springai.omnisearch`

```
controller/   REST endpoints + request/response DTOs
service/      Orchestration and external API integration
agent/        LLM chat clients (response generation, follow-ups)
config/       Spring beans (ChatMemory, WebClient)
model/        Domain/transport POJOs (Tavily, SearchContext, etc.)
```

### Request flow (streaming conversation)

1. `OmnisearchController` receives `POST /api/omnisearch/conversation` → calls `OmnisearchOrchestrator.searchWithStreaming(query, conversationId)`.
2. `WebSearchService` calls the Tavily API (returns `Mono<TavilyResponse>`). On 429/any error it returns a fallback simulated result so the pipeline never crashes.
3. `OmnisearchOrchestrator.buildContextDirectly()` assembles a `SearchContext` with numbered sources and aggregated content — **no LLM call** (fast, deterministic).
4. A `<!--SOURCES:...-->` JSON header chunk is prepended to the stream so the frontend can render source cards before the answer completes.
5. `ResponseGenerationAgent.generateResponse()` streams the answer (`Flux<String>`) using the system + user prompt templates, with chat-memory scoped to `conversationId`.

### Follow-up questions (separate, synchronous)

Follow-ups are **not** part of the streaming pipeline. The frontend collects the full streamed answer, then calls `POST /api/omnisearch/followups` which invokes `FollowUpQuestionsAgent.generateFollowUps()` (a blocking `.call()` that returns structured JSON via `responseMimeType("application/json")`).

## API Endpoints

| Method | Path | Returns | Notes |
|--------|------|---------|-------|
| POST | `/api/omnisearch/conversation` | `Flux<String>` | Streams answer chunks; first chunk is a `<!--SOURCES:...-->` header |
| POST | `/api/omnisearch/conversation/stream` | `Flux<ServerSentEvent<String>>` | Same as above wrapped in SSE |
| POST | `/api/omnisearch/followups` | `FollowUpResponse` (JSON) | Generates 4–5 follow-up questions |
| GET | `/api/omnisearch/health` | `Mono<String>` | Health check |

## Code Conventions

- **Dependency injection:** constructor injection only (no `@Autowired`). Controllers use Lombok `@RequiredArgsConstructor`.
- **Models:** Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` for POJOs. DTOs with no logic use Java `record` types (`FollowUpRequest`, `FollowUpResponse`).
- **Logging:** Lombok `@Slf4j`. Log at entry points and key pipeline stages; keep messages concise.
- **Reactive return types:** Services return `Mono`/`Flux`. Controllers expose `Flux` for streaming endpoints. Do not call `.block()` in the reactive pipeline.
- **External HTTP:** use `WebClient` (configured in `WebClientConfig`) for the Tavily API. Always provide an `onErrorResume` fallback so failures degrade gracefully.
- **Prompt templates:** stored as `.st` StringTemplate files in `src/main/resources/promptTemplates/`. Loaded via `@Value("classpath:promptTemplates/...")` as a `Resource`, then referenced in `chatClient.prompt().system(...)` / `.user(...)` with `.param(name, value)` substitution.
- **LLM options:** set per-agent via `GoogleGenAiChatOptions.builder()`. Response generation uses `temperature(0.7)`; follow-ups use `temperature(0.6)` and force JSON output.
- **Chat memory:** `MessageChatMemoryAdvisor` added only to `ResponseGenerationAgent`. Conversation scope is keyed by `conversationId` passed from the client.
- **Cross-origin:** `@CrossOrigin(origins = "*")` is set on the controller.

## Prompt Templates

| File | Used by | Purpose |
|------|---------|---------|
| `systemPromptTemplate.st` | ResponseGenerationAgent | Defines the "Omisearch" persona: answer-first, factual, inline `[n]` citations, mandatory Summary table + Sources list |
| `userPromptTemplate.st` | ResponseGenerationAgent | Injects `{query}`, `{context}`, `{sources}` |
| `followupSystemPromptTemplate.st` | FollowUpQuestionsAgent | Rules for 4–5 concise, diverse follow-up questions; JSON output |
| `followupUserPromptTemplate.st` | FollowUpQuestionsAgent | Injects `{originalQuery}`, `{aiResponse}` |

When editing prompts, preserve the inline-citation and Summary-Overview formatting rules — the frontend relies on that structure.

## Known Issues & Gotchas

- **Unused constructor param:** `OmnisearchOrchestrator` injects `FollowUpQuestionsAgent` but never stores or uses it. Follow-ups are handled directly by the controller. This is harmless but will show as an unused parameter.
- **Setup doc drift:** `OMNISEARCH_SETUP.md` describes endpoints (`/search`, `/search/stream`), a `ContextEngineeringAgent`, and Supabase persistence that are **not** in the codebase. PostgreSQL/JDBC dependencies are commented out in `pom.xml`. Treat the setup doc as historical.
- **Secrets:** `application.properties` currently hardcodes the Google GenAI API key. Prefer an environment variable (e.g., `spring.ai.google.genai.api-key=${GOOGLE_GENAI_API_KEY}`) and avoid committing real keys.
- **Long LLM calls:** netty connection timeout is set to 300s and MVC async request timeout to 120s to accommodate slow model responses.

## When Making Changes

- Keep the controller layer thin; orchestration logic belongs in `service/`, LLM interaction in `agent/`.
- New endpoints go under `/api/omnisearch` in `OmnisearchController`.
- New prompt content → add a `.st` file in `promptTemplates/` and load it as a `Resource`.
- New transport POJOs → add to `model/` with the Lombok four-annotation set; prefer `record` for simple immutable DTOs in `controller/`.
- Verify reactive chains don't swallow errors silently — add `.doOnError`/`onErrorResume` with logging.