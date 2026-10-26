# Bori Code — Change Notes (June 2026)

Four features shipped this cycle. Each section below gives the problem, the fix, the files
touched, and before/after snippets of the key changes. A recurring **integrity invariant** runs
through the agent-loop work: every `MessageContent.ToolUse` appended to `agentHistory` must be
followed by exactly one matching `MessageContent.ToolResult` (same `toolUseId`) before the loop
resumes, or the provider API rejects the request with an orphaned-tool error.

---

## 1. IME nav-bar fix — lockstep collapsing bottom bar

**Problem.** The chat input bar hopped/jumped and left a static gap above the keyboard. Root
cause: the 5-tab `NavigationBar` was hidden with `if (!WindowInsets.isImeVisible)`, which flips at
the *start* of the IME animation — removing the bar instantly (~80dp jump) while `imePadding()`
animated smoothly. Two clocks → hop + gap. The Scaffold `bottomBar` re-measure also broke on the
Samsung Fold.

**Fix.** Remove the nav bar from `Scaffold.bottomBar`; render it as the last child of a plain
content `Column` inside a new `CollapsingBottomBar` that reads the IME inset **in the measure
pass** (the same mechanism `imePadding()` uses), so the bar's reserved height shrinks frame-for-
frame in lockstep with the input rising. No hop, no gap.

**Files:** `app/.../ui/NavHost.kt`.

```kotlin
// BEFORE — boolean toggle in Scaffold.bottomBar (instant 80dp jump = the hop)
Scaffold(
    bottomBar = { if (!imeVisible) { Column { NavigationBar { … } } } },
) { innerPadding -> NavHost(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) { … } }

// AFTER — nav bar in plain content; height collapses via IME inset read in the measure pass
Scaffold(contentWindowInsets = WindowInsets(0)) { innerPadding ->
    Column(Modifier.fillMaxSize().padding(innerPadding)) {
        NavHost(Modifier.weight(1f).fillMaxSize()) { … }
        CollapsingBottomBar { NavigationBar { … } }   // last child, collapses under the keyboard
    }
}

@Composable
private fun CollapsingBottomBar(content: @Composable () -> Unit) {
    val imeInsets = WindowInsets.ime           // captured in composition (getter is @Composable)
    Column(
        Modifier.clipToBounds().layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val reduce = imeInsets.getBottom(this).coerceAtMost(p.height)  // read in MEASURE pass
            layout(p.width, p.height - reduce) { p.place(0, 0) }           // top-anchored; clip bottom
        },
    ) { content() }
}
```

**Note.** `WindowInsets.ime` must be captured in composition and only `getBottom()` read inside the
`layout` lambda — that snapshot read re-triggers measurement without recomposition.

---

## 2. Inline clarifying questions + Plan-Mode exit

**Problem.** (a) Saying "ask me questions" forced a sticky Ask Mode + a confirmation dialog and
asked one question at a time. (b) Plan Mode was a one-way trap — the only graceful exit was a
`write_plan` approval, so the model could get stuck repeating plans.

**Fix.** New `ask_questions` tool (1–4 questions at once, with tappable `options`) usable **inline
in normal mode**; new `exit_plan_mode` tool that surfaces an Approve/Reject card. Dropped ASK
auto-detection. Added a per-build re-arm so a session-persistent Plan Mode re-engages after each
exit.

**Files:** `app/.../tools/ToolDefinitions.kt`, `app/.../tools/ToolExecutor.kt`,
`core/ai/.../context/ContextManager.kt`, `app/.../ui/chat/ChatViewModel.kt`,
`app/.../ui/chat/ChatScreen.kt`.

```kotlin
// ToolDefinitions — ask_user (single) replaced by ask_questions (batched) in `all` & askModeTools;
// exit_plan_mode added to planModeTools. NOTE: these vals must be declared BEFORE the list vals
// that reference them (object vals init top-to-bottom; a forward ref would be null).
val ASK_QUESTIONS = ToolDefinition(name = "ask_questions", /* 1–4 {question, header, options[], allow_multiple} */ …)
val EXIT_PLAN_MODE = ToolDefinition(name = "exit_plan_mode", /* {reason} */ …)
```

```kotlin
// ChatViewModel.runAgentLoop — interception sets pending state; the ToolResult is supplied by the
// resumer (submitAnswers / approve|declinePlanExit) so the ToolUse never orphans.
} else if (toolUse != null && (toolUse.name == "ask_questions" || toolUse.name == "ask_user")) {
    val items = parseQuestionItems(toolUse.name, toolUse.inputJson)
    _pendingQuestions.value = PendingQuestions(toolUseId = toolUse.id, items = items); setLoading(false); return
} else if (toolUse != null && toolUse.name == "exit_plan_mode") {
    _pendingPlanExitRequest.value = PendingPlanModeRequest(toolUse.id, reason); setLoading(false); return
}
```

```kotlin
// Per-build re-arm: exiting a session-persistent plan returns to Plan Mode on the next send,
// so each new build re-plans and re-asks to exit (the "new build = new get-out call" behavior).
fun approvePlanExit() {
    _isPlanMode.value = false
    if (_planModeSessionPersistent.value) _rearmPlanModeNextSend.value = true
    /* append ToolResult "User approved exiting Plan Mode…" then resume loop */
}
// sendMessage(): if (_rearmPlanModeNextSend.value) { _rearmPlanModeNextSend.value = false; _isPlanMode.value = true }
```

UI: `MultiQuestionCard` (header chip + `FilterChip` options + "Other" field, one Submit) replaced
`AskModeQuestionCard`; `PlanExitConfirmationCard` ("Exit Plan Mode and start building?").

---

## 3. Compaction fix (+ `compact_conversation` tool) & AI usage view

### 3a. `/compact` was a no-op
**Problem.** `compactConversation()` read the in-memory `agentHistory`, which is empty until a
message seeds it and is never restored on app open (`activeSessionId` starts -1). So `/compact`
hit `size < 3` → "Not enough history." The fixed 60% cut could also split a tool pair, and the
summary was a weak "3–5 sentences."

**Fix.** Seed from the DB before compacting; tool-atomic cut; structured summary; plus a
`compact_conversation` tool the model can call itself.

```kotlin
// Seed-from-DB + guards (was: if (agentHistory.size < 3) … return  // always empty on a fresh VM)
if (activeSessionId == -1L) { /* "No conversation to compact yet" */ return }
if (agentHistory.isEmpty()) seedAgentHistoryFromDb()           // <-- the actual fix
if (agentHistory.size < 3) { _snackbarEvent.emit("Not enough history to compact"); return@launch }

// Tool-atomic cut: land on a clean USER text turn so a ToolUse is never split from its ToolResult.
private fun computeCompactionCut(history: List<ChatMessage>): Int {
    var cut = (history.size * 6 / 10).coerceIn(2, history.size - 1)
    while (cut < history.size) {
        val first = history[cut]
        val startsWithToolResult = first.content.any { it is MessageContent.ToolResult }
        val prevHasToolUse = cut > 0 && history[cut - 1].content.any { it is MessageContent.ToolUse }
        if (first.role == ChatMessage.Role.USER && !startsWithToolResult && !prevHasToolUse) break
        cut++
    }
    return cut.coerceIn(1, history.size - 1)
}
```

The `compact_conversation` interception appends the ToolResult **before** compacting (so the pair
stays atomic — it gets summarized away cleanly), then runs `compactConversation()`.

**Files:** `app/.../ui/chat/ChatViewModel.kt`, `app/.../tools/ToolDefinitions.kt`,
`app/.../tools/ToolExecutor.kt`, `core/ai/.../context/ContextManager.kt`.

### 3b. Usage / limits view
**Problem.** The streaming layer captured **no token usage**, and only some providers expose a
quota endpoint.

**Fix.** New `ChatChunk.Usage` emitted by every cloud client; live balance for OpenRouter/DeepSeek;
app-tracked tokens (new DataStore `UsageRepository`) for the rest; a Settings "Usage" section.

```kotlin
// ChatModels — new sealed subtype. WARNING: adding a ChatChunk subtype makes exhaustive `when`s
// non-exhaustive — the runAgentLoop collect needed a new `is ChatChunk.Usage ->` branch.
data class Usage(val provider: AiProvider, val promptTokens: Int, val completionTokens: Int) : ChatChunk()
```

```kotlin
// OpenAiCompatClient — request the final usage chunk, then parse it (choices is empty there).
if (request.stream) put("stream_options", JSONObject().put("include_usage", true))
val usageObj = obj.optJSONObject("usage")
if (usageObj != null && usageObj.has("prompt_tokens") && (choicesArr == null || choicesArr.length() == 0))
    return listOf(ChatChunk.Usage(provider, usageObj.optInt("prompt_tokens"), usageObj.optInt("completion_tokens")))

// AnthropicClient — accrue input from message_start, output from message_delta; emit before Done.
// GeminiClient — emit from usageMetadata ONLY on the finishReason chunk (cumulative → single emit
//                avoids double counting).
```

```kotlin
// Balance: AiProviderClient gains `suspend fun fetchBalance(): ProviderBalance? = null`.
// OpenRouter → /api/v1/credits (total_credits=limit, total_usage=used); DeepSeek → /user/balance.
// ChatViewModel records each Usage chunk:
is ChatChunk.Usage -> usageRepository.record(chunk.provider, chunk.promptTokens, chunk.completionTokens)
```

`UsageRepository` is DataStore-backed (per-provider cumulative tokens; **no Room migration**).
Settings shows a per-provider row: tokens in/out/total + a live balance line or a "No quota API —
app-tracked tokens only" note, refreshed on screen open.

**Files (3b):** `core/ai/.../model/ChatModels.kt`, new `core/ai/.../model/UsageModels.kt`,
`core/ai/.../provider/{AiProviderClient,OpenAiCompatClient,AnthropicClient,GeminiClient,OpenRouterClient,DeepSeekClient}.kt`,
`core/ai/.../router/ModelRouter.kt`, new `app/data/usage/UsageRepository.kt`,
`app/.../ui/chat/ChatViewModel.kt`, `app/.../ui/settings/SettingsViewModel.kt`,
`app/.../ui/settings/SettingsScreen.kt`.

---

## 4. Chat bubble readability — copy-icon contrast + color swatches

**Problem.** The copy icon on the blue user bubble was tinted `SubtleGray @ 60%` — gray-on-blue,
invisible. Also no way to customize bubble color.

**Fix.** White copy icon on the user bubble (theme-aware `onSurfaceVariant` on the assistant
bubble), plus a preset-swatch picker in Settings persisted as `chatBubbleColor`.

```kotlin
// UserBubble copy icon — BEFORE: tint = SubtleGray.copy(alpha = 0.6f)  // invisible on blue
//                        AFTER:  tint = Color.White.copy(alpha = 0.7f)
// AssistantBubble copy icon AFTER: tint = MaterialTheme.colorScheme.onSurfaceVariant

// Persisted pref + applied color
data class UserPreferences(/* … */ val chatBubbleColor: Long = 0xFF1A56DB)   // ARGB
val bubbleColor = Color(userPrefs.chatBubbleColor)   // threaded into UserBubble + PillInputBar send button
```

`ChatAppearanceSection` renders 8 circular swatches (all dark enough to keep white text readable);
the active one is ringed and checked.

**Files:** `app/.../ui/chat/ChatScreen.kt`, `app/.../data/prefs/UserPreferences.kt`,
`app/.../data/prefs/UserPreferencesRepository.kt`, `app/.../ui/settings/SettingsViewModel.kt`,
`app/.../ui/settings/SettingsScreen.kt`.

---

## Build
All four features build clean with `assembleDebug` + `bundleRelease` (foreground; never `gradlew
clean` — that destroys `patched_kotlinc_embeddable.jar`). Local Gemma is offline/free: it emits no
usage and supports no tools, so questions/usage apply to cloud providers only.
