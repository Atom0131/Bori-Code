# AI Subsystem

The AI subsystem is a **multi-provider coding assistant with transparent offline
fallback**. Code lives in `core/ai/` (clients, routing, models, local Gemma) and the
agent/tool layer in `app/tools/` + `app/ui/chat/`.

## Request flow
```
ChatScreen → ChatViewModel
  → ContextManager.buildChatRequest()   core/ai/.../context/ContextManager.kt
  → ModelRouter (pick provider/model)   core/ai/.../router/ModelRouter.kt
  → AiProviderClient.chat(request)      core/ai/.../provider/AiProviderClient.kt
  → Flow<ChatChunk> (SSE stream)        → ChatViewModel renders + runs agent loop
```

- **ContextManager** builds the system prompt (normal / Plan Mode / Ask Mode
  variants), injects code context (open file ≤10k chars, repo map, symbol summary,
  project language), trims history to a ~16k-token budget, and sets the per-turn
  output budget `MAX_RESPONSE_TOKENS = 32_768`.
- **ModelRouter** is the single decision point for which provider+model handles a
  request: it honors the saved preference (with key present), falls back through a
  priority list, and **transparently routes to on-device Gemma when offline** (or
  when an online call fails and Gemma is ready).

## Providers
All implement `AiProviderClient` (`provider/AiProviderClient.kt`):
`chat()→Flow<ChatChunk>`, `complete()→Flow<CompletionChunk>`, `supportsToolUse()`,
`maxContextTokens()`, optional `fetchBalance()`.

| Provider | Client | Auth | Endpoint | Tools | Vision | Notes |
|---|---|---|---|---|---|---|
| Anthropic (Claude) | `AnthropicClient.kt` | `x-api-key` + `anthropic-version` | `api.anthropic.com/v1/messages` | ✅ | ✅ | system is a top-level field; `content_block_*` SSE |
| OpenAI | `OpenAiClient.kt` (extends `OpenAiCompatClient`) | Bearer | `api.openai.com/v1/chat/completions` | ✅ | ✅ | uses `max_completion_tokens`; o-series reasoning: omit `temperature`, `system`→`developer` |
| DeepSeek | `DeepSeekClient.kt` (compat) | Bearer | `api.deepseek.com` | ✅ | ❌ (stripped w/ note) | echoes `reasoning_content` back for multi-turn thinking; `/user/balance` |
| OpenRouter | `OpenRouterClient.kt` (compat) | Bearer + `HTTP-Referer`/`X-Title` | `openrouter.ai/api` | ✅ | model-dependent | free-text model field; `/api/v1/credits` |
| Gemini | `GeminiClient.kt` | `x-goog-api-key` | `generativelanguage.googleapis.com/v1beta/models/{m}:streamGenerateContent?alt=sse` | ✅ | ✅ | `thinkingConfig`, `friendlyError()`, first-functionCall-only |
| Local Gemma | `local/LocalGemmaClient.kt` | — (on-device) | LiteRT-LM inference | ❌ | ❌ | GPU→CPU fallback; offline; foreground service during inference |

`OpenAiCompatClient` (`provider/OpenAiCompatClient.kt`) is the shared base for the
three OpenAI-shaped APIs (OpenAI / DeepSeek / OpenRouter): it handles the
`/chat/completions` + `/completions` request shaping, SSE parsing, streaming tool
calls, a catalog-driven output-token clamp (`cappedMaxTokens`), and the
`isReasoningModel` hook (overridden by `OpenAiClient` to match `^o\d`).

## ModelCatalog — single source of truth
`model/ModelCatalog.kt` is the **only** place model IDs/defaults live. Each `Model`
carries `id`, `freeTierQuota`, `note` (shown in pickers), and `maxOutput` (output
clamp). Pickers (`SettingsScreen`, `ChatScreen`), `ModelRouter`, and ViewModels all
read from it — **never hardcode model IDs elsewhere.** Stale saved IDs are dropped
by `UserPreferencesRepository.sanitizeModel()`.

Defaults (June 2026): Anthropic `claude-sonnet-4-6`; OpenAI `gpt-4.1-mini` (chat),
`gpt-4.1-nano` (completion); DeepSeek `deepseek-v4-pro` / `deepseek-v4-flash`;
Gemini `gemini-2.5-flash`.

## ChatChunk streaming model
`model/ChatModels.kt`. Collectors use **exhaustive `when`** — adding a variant means
updating every collector.
- `TextDelta(text)` — streamed assistant text
- `ReasoningDelta(text)` — DeepSeek thinking tokens (must be echoed back next turn)
- `ToolCallStart(id, name)` / `ToolCallDelta(argsJson)` / `ToolCallEnd(id)`
- `Usage(provider, promptTokens, completionTokens)`
- `Done(stopReason)`
Related content types (`MessageContent`): `Text`, `Image`, `ToolUse`, `ToolResult`,
`Reasoning`.

## Tools / function-calling & the agent loop
- **`app/tools/ToolDefinitions.kt`** — the tool catalog. Each `ToolDefinition` has
  `name`, `description`, and a JSON-Schema `inputSchema`. Tools include:
  `get_project_info`, `get_editor_context`, `read_file`, `read_files`,
  `list_directory`, `write_file`, `write_files`, `apply_edit`, `create_directory`,
  `add_dependency`, `add_manifest_permission`, `open_file`, `suggest_plan_mode`,
  `ask_questions`, `write_plan`, `exit_plan_mode`, `compact_conversation`.
- **`app/tools/ToolExecutor.kt`** — executes a requested tool (file I/O, build
  triggers, log retrieval), gated by the `aiWorkTurns` budget.
- **Agent loop** (in `ChatViewModel`): answers **one tool call per turn**, appends
  the `ToolResult`, and re-invokes the model until no tool is requested or the budget
  is reached. (Gemini's `parseChunk` deliberately emits only the first functionCall
  so the call/response counts stay in lockstep — parallel calls would 400.)
- **Plan Mode / Ask Mode**: `suggest_plan_mode` / `write_plan` / `exit_plan_mode`
  gate edits behind an approved plan; `ask_questions` shows a multi-question card
  inline.

## Security
- **`core/security/KeyVault.kt`** — `EncryptedSharedPreferences` backed by an Android
  Keystore master key; per-provider keys + `svc_`-prefixed service keys (e.g. Tavily).
- **`provider/RedactingLoggingInterceptor.kt`** — strips API keys from HTTP logs.

## Per-model facts (June 2026) — maintenance reference
> All IDs/caps below are configured in `ModelCatalog.kt`. See also the memory notes
> `reference-ai-provider-clients` and `project-model-currency-2026-06-21`.

- **Output token caps** (why the catalog `maxOutput` clamp exists): old gpt-4o =
  16,384 (caused real `400 max_token is too large: 32768`); gpt-4.1 family = 32,768;
  o3-mini ≈ 100k; DeepSeek v4 up to 384k; Claude 4.x ≈ 64k+; Gemini 2.5/3.x ceiling
  65,535.
- **Gemini free tier:** `gemini-2.5-flash`/`-flash-lite` have real free quota; all
  **3.x + Pro** models have **no API free tier** (429 on free keys) — need a paid or
  Google AI Pro/Ultra key. Default is kept at `gemini-2.5-flash` so a fresh free key
  doesn't 429 on first message.
- **Gemini thinking:** 2.5 → `generationConfig.thinkingConfig.thinkingBudget`
  (flash 0 = off, flash-lite omit, pro 128 = min); 3.x → `thinkingLevel`
  (completion `minimal`, except `pro` which only allows low/medium/high). Never send
  both fields (400).
- **OpenAI o-series:** reject `temperature` and the `system` role (use `developer`),
  require `max_completion_tokens` — handled via `isReasoningModel`.
- **DeepSeek:** must echo `reasoning_content` verbatim on multi-turn / tool calls.
- **Anthropic note:** Claude **Fable 5 / Mythos 5 are suspended** (US export
  directive, 2026-06-12) — do not add. Opus 4.8 / Sonnet 4.6 / Haiku 4.5 are current.
