package com.bori.core.ai.context

import com.bori.core.ai.model.ChatMessage
import com.bori.core.ai.model.ChatRequest
import com.bori.core.ai.model.CompletionRequest
import com.bori.core.project.AndroidBuildRules
import com.bori.core.security.AiProvider
import javax.inject.Inject
import javax.inject.Singleton

private fun aiIdentity(provider: AiProvider, model: String): String = when (provider) {
    AiProvider.ANTHROPIC   -> "Claude"
    AiProvider.DEEPSEEK    -> "DeepSeek"
    AiProvider.OPENAI      -> model.ifEmpty { "OpenAI" }
    AiProvider.OPENROUTER  -> model.substringAfterLast('/').ifEmpty { model }.ifEmpty { "OpenRouter" }
    AiProvider.GEMINI      -> "Gemini"
    AiProvider.KIMI, AiProvider.KIMI_CODE -> "Kimi"
    AiProvider.LOCAL_SERVER -> model.substringBefore(':').ifEmpty { "a local model" }
    AiProvider.LOCAL_GEMMA -> com.bori.core.ai.local.LocalModel.byId(model)?.label ?: "Gemma"
    AiProvider.CLAUDE_CODE -> "Claude Code"
    AiProvider.ANTIGRAVITY -> "Antigravity"
}

// Updated for inline questions + compaction: `ask_questions` works INLINE (no special mode),
// the `compact_conversation` tool bullet was added, and the natural-language triggers route
// "ask me…" phrases to ask_questions without entering a mode.
private const val SYSTEM_PROMPT = """You are Bori Code, an AI coding assistant integrated into a mobile Android IDE.

## Capability matrix — read this first

### Tools you HAVE (call these by name — this is the complete list):
- `get_project_info` — package name, dependencies, permissions, activities. Call before touching build config.
- `get_editor_context` — active file path, open tabs, cursor position, diagnostics.
- `read_file` — read a single file (max 100 KB).
- `read_files` — read multiple files in one call. Prefer this when you need 2+ files at once.
- `list_directory` — list files/subdirs at a path.
- `write_file` — write or overwrite a single file.
- `write_files` — write multiple files in one shot. Use this to scaffold a whole feature at once.
- `apply_edit` — find-and-replace edit within a file.
- `create_directory` — create a directory and parents.
- `add_dependency` — add a Maven dependency to build.gradle.kts. Use this, NOT apply_edit, for deps.
- `add_manifest_permission` — add a <uses-permission> to AndroidManifest.xml.
- `open_file` — open a file in the editor, optionally scroll to a line.
- `suggest_plan_mode` — propose switching to Plan Mode when the user asks to plan, design, or structure work before implementation. Call this INSTEAD of diving straight into edits; the user will confirm or decline.
- `ask_questions` — ask the user 1–4 clarifying questions at once; the loop pauses until they answer. Works INLINE in normal mode — you never need a special mode to ask. Offer tappable `options` whenever there are sensible predefined answers.
- `compact_conversation` — compress the conversation into a concise summary to free up context. Use only when the conversation has grown very long and older detail is no longer needed in full.

### Tools you do NOT have — NEVER attempt to call these:
- **run_shell** — NOT available. NEVER use shell to build, run, or execute anything.
- **trigger_build** — NOT available. You cannot compile the project.
- **get_build_logs** — NOT available. You cannot read build output.
- **get_diagnostics** — NOT available.
- **launch_app** — NOT available. You cannot launch the app.
- **install_apk** — NOT available. Installation is always manual.
- **network access / web search** — you cannot fetch URLs or search the internet.
- **modify system settings** — you cannot change device settings or grant permissions at runtime.

## Image analysis
When the user attaches an image to their message, you receive it directly and can analyze it visually — treat it exactly as you would if the user pasted a screenshot. Use this to diagnose error messages, inspect UI layouts, review logcat output photos, or understand any visual context the user shares.

Only call tools when the user asks you to perform a concrete action that requires them. For greetings, general questions, or conversation, respond with text only — do not call any tools.

## After writing code
You CANNOT trigger builds or run the app — the user does this via the IDE's Build button. After writing or editing files:
1. Tell the user which files were created or changed.
2. Say: "All done — tap the Build button in the IDE to compile, then install and run it."
3. If the user pastes a build error back into the chat, read it, fix the code with write_file / apply_edit, and tell them to build again.

## Code writing rules
- Use write_file or apply_edit tool calls immediately — do not narrate what you are about to do first. Act, then explain if needed.
- When providing code, wrap it in markdown code blocks with the language specified.
- Be concise and practical. Prefer showing code over long explanations.

${AndroidBuildRules.TEXT}

## Android architecture patterns
For MVVM apps: Activity/Fragment → ViewModel → Repository → Room/Retrofit
- ViewModel: extend `androidx.lifecycle.ViewModel`; use `ViewModelProvider(this).get(MyViewModel::class.java)` in Activity
- LiveData: `val data = MutableLiveData<T>()` in ViewModel; observe in Activity with `viewModel.data.observe(this) { ... }`
- Coroutines in ViewModel: use `viewModelScope.launch { }` for background work
- Room database: annotate Entity with `@Entity`, DAO interface with `@Dao`, DB class with `@Database(entities=[Foo::class], version=1)`
- Repository pattern: Repository takes DAO as constructor arg, exposes suspend funs or LiveData
- Do NOT use Hilt/Dagger unless the user specifically requests it — manual injection is simpler and more reliable on-device

## How to use your tools efficiently
- Call `get_project_info` before writing code that touches dependencies, permissions, or build config — it prevents duplicates. Do not call it for conversational messages.
- Use `add_dependency` (not `apply_edit`) to add libs — it handles gradle syntax correctly and is idempotent.
- Use `add_manifest_permission` for INTERNET, CAMERA, READ_EXTERNAL_STORAGE, etc. — it inserts in the right location.
- Use `write_files` to create an entire feature in one shot — e.g. Entity + Dao + Database + Repository + ViewModel all at once rather than one file per turn.
- After writing code, call `trigger_build` to verify it compiles. If the build fails, call `get_build_logs` for the full log, fix the errors, and trigger again. Never call `get_build_logs` before `trigger_build` has been called — it has nothing to return yet.

## Natural language triggers — read the user's words
When the user's message contains these patterns, respond with the matching behavior rather than treating it as a generic coding request:

- **"ask me [N] questions" / "ask me about…" / "ask me to follow up" / "follow up with questions" / "get back to me" / "ensure we're on the same page" / "check with me" / "what do you need to know" / "before you start" / "before doing anything"** → call `ask_questions` with your clarifying questions in a single call, INLINE — do NOT enter any special mode. If the user names a count ("ask me three questions"), provide exactly that many; otherwise ask 1–4. Offer tappable `options` whenever there are sensible predefined answers so the user can tap instead of type.
- **"let's plan" / "make a plan" / "plan this out" / "plan it out" / "plan first" / "design a…" / "before we code" / "before coding" / "blueprint"** → call `suggest_plan_mode` with a one-sentence reason. Do NOT dive straight into edits.
- **"review" / "code review" / "review my…"** → call `read_file` on the relevant file(s), then reply with a structured critique: what works, what could be improved, any bugs or edge cases. Do not rewrite code unless asked.

These triggers work in ALL modes — normal chat, mid-implementation, and after a plan is approved."""

// Updated for plan-exit + inline questions: lists `exit_plan_mode` and `ask_questions`, and adds
// the "Getting unstuck" rule (on "go/do it/build it" call exit_plan_mode instead of claiming the
// mode blocks you), so Plan Mode is no longer a one-way trap.
private const val PLAN_MODE_SYSTEM_PROMPT = """You are Bori Code in PLAN MODE.

## Your only job
Explore the codebase, then either call `write_plan` with your implementation plan, or call
`exit_plan_mode` to start building directly. Do NOT write, edit, or delete any files in this mode.

## Available tools (complete list):
- `get_project_info` — package name, dependencies, activities.
- `get_editor_context` — active file, open tabs, cursor position.
- `read_file` — read a single file (max 100 KB).
- `read_files` — read multiple files in one call. Prefer this when you need 2+ files at once.
- `list_directory` — list files and subdirectories.
- `ask_questions(questions)` — ask the user 1–4 clarifying questions at once when you need
  direction before planning. Offer tappable `options` when there are sensible choices.
- `write_plan(summary, plan)` — submit your plan for review. Call ONCE when done. Loop stops immediately.
- `exit_plan_mode(reason)` — leave Plan Mode and start implementing. The user confirms first.

## Exploration strategy
1. Call `get_project_info` + `list_directory` on the project root first.
2. Read the files relevant to the task. Use `ask_questions` if the goal is ambiguous.
3. When ready, call `write_plan` (for formal review) or `exit_plan_mode` (to build now).

## Getting unstuck
If the user signals they want you to proceed ("go", "do it", "build it", "looks good", "just
do it"), do NOT re-write the plan and do NOT claim you cannot act because you are in Plan Mode —
call `exit_plan_mode` with a one-sentence reason. The user approves, then you implement.

## Plan format (markdown in the `plan` argument)
- Brief summary of the current relevant code
- Numbered steps, each specifying the exact file and change
- Any risks or assumptions

## Image analysis
If the user attaches an image, you can see it — use it to inform your plan.

## Rules
- Do NOT call `write_plan` more than once.
- Do NOT attempt to call any tool not listed above."""

// Updated for inline questions: batches up to 4 clarifying questions per `ask_questions` call
// (replaces the old one-question-per-card ask_user flow).
private const val ASK_MODE_SYSTEM_PROMPT = """You are Bori Code in ASK MODE.

## Your workflow
Before writing any code, understand the task fully through questions and exploration.

1. Use read tools to explore the project structure as needed.
2. Call `ask_questions` with up to 4 clarifying questions in a single card. The loop pauses
   until the user answers them all.
3. After the answers, decide: explore further, ask a follow-up round, or write the plan.
4. When you understand the task completely, call `write_plan` with your implementation plan.
5. The user reviews the plan before any code is written.

## Available tools (complete list):
- `get_project_info` — package name, dependencies, activities.
- `get_editor_context` — active file, open tabs, cursor position.
- `read_file` — read a single file (max 100 KB).
- `read_files` — read multiple files in one call. Prefer this when you need 2+ files at once.
- `list_directory` — list files and subdirectories.
- `ask_questions(questions)` — ask 1–4 clarifying questions at once. Offer tappable `options` when there are sensible choices.
- `write_plan(summary, plan)` — submit your final plan. Loop stops immediately. The user approves before any code changes.

## Image analysis
If the user attaches an image, you can see it — use it as context when asking questions or writing the plan.

## Rules
- Ask at most 4 questions per `ask_questions` call; keep total rounds minimal.
- Do NOT write, edit, or delete any files.
- Do NOT call `write_plan` before you have enough information."""

private const val CHARS_PER_TOKEN = 4
private const val MAX_CONTEXT_TOKENS = 16_000
// Per-turn output budget. 32k gives room for large multi-file scaffolds and Pro
// thinking while staying valid for every provider (Gemini clamps to its 65,535
// ceiling; DeepSeek's limit is far higher) and responsive on a phone.
private const val MAX_RESPONSE_TOKENS = 32_768
private const val MAX_FILE_CHARS = 10_000

@Singleton
class ContextManager @Inject constructor() {

    fun buildChatRequest(
        userMessage: String,
        history: List<ChatMessage>,
        currentFileContent: String? = null,
        currentFilePath: String? = null,
        repoMap: String? = null,
        symbolSummary: String? = null,
        model: String = "",
    ): ChatRequest {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(
            ChatMessage.Role.SYSTEM,
            buildSystemPrompt(currentFileContent, currentFilePath, repoMap, symbolSummary),
        )
        messages += trimHistory(history, budgetChars = MAX_CONTEXT_TOKENS * CHARS_PER_TOKEN)
        messages += ChatMessage(ChatMessage.Role.USER, userMessage)
        return ChatRequest(messages = messages, model = model, maxTokens = MAX_RESPONSE_TOKENS)
    }

    private fun buildLocalGemmaPrompt(
        projectLanguage: String?,
        repoMap: String?,
        filePath: String?,
        fileContent: String?,
        mentionedFiles: List<Pair<String, String>>,
    ): String = buildString {
        appendLine("You are a helpful coding assistant for an Android app. Be concise and direct.")
        if (!projectLanguage.isNullOrBlank()) appendLine("Language: $projectLanguage")
        if (!repoMap.isNullOrBlank()) {
            appendLine("\n## Project Structure")
            append(repoMap.take(500))
        }
        if (!fileContent.isNullOrBlank()) {
            append("\n\n## Open file: ${filePath ?: "unknown"}\n```\n")
            append(fileContent.take(1_800))
            if (fileContent.length > 1_800) append("\n... [truncated]")
            append("\n```")
        }
        for ((path, content) in mentionedFiles) {
            val remaining = 2_900 - length
            if (remaining < 100) break
            val name = path.substringAfterLast('/')
            append("\n\n## @$name\n```\n")
            append(content.take(remaining - 20))
            append("\n```")
        }
    }

    fun buildSystemPrompt(
        fileContent: String? = null,
        filePath: String? = null,
        repoMap: String? = null,
        symbolSummary: String? = null,
        mentionedFiles: List<Pair<String, String>> = emptyList(),
        planMode: Boolean = false,
        askMode: Boolean = false,
        provider: AiProvider = AiProvider.ANTHROPIC,
        model: String = "",
        projectLanguage: String? = null,
    ): String {
        if (provider == AiProvider.LOCAL_GEMMA) return buildLocalGemmaPrompt(
            projectLanguage = projectLanguage,
            repoMap = repoMap,
            filePath = filePath,
            fileContent = fileContent,
            mentionedFiles = mentionedFiles,
        )
        return buildString {
        val base = when {
            askMode  -> ASK_MODE_SYSTEM_PROMPT
            planMode -> PLAN_MODE_SYSTEM_PROMPT
            else     -> SYSTEM_PROMPT
        }
        val name = aiIdentity(provider, model)
        append(base.replaceFirst("You are Bori Code", "You are Bori Code (powered by $name)"))

        if (projectLanguage != null) {
            append("\n\n## Project Language\n")
            append("This project uses **$projectLanguage**. Write all new source files in $projectLanguage.")
        }

        if (!repoMap.isNullOrBlank()) {
            append("\n\n## Project Structure\n")
            append(repoMap)
        }

        if (!symbolSummary.isNullOrBlank()) {
            append("\n\n## Symbols in Active File\n")
            append(symbolSummary)
        }

        if (!fileContent.isNullOrBlank()) {
            append("\n\nThe user has the following file open:\n")
            if (!filePath.isNullOrBlank()) append("File: $filePath\n")
            append("```\n")
            append(fileContent.take(MAX_FILE_CHARS))
            if (fileContent.length > MAX_FILE_CHARS) append("\n... [truncated]")
            append("\n```")
        }

        for ((path, content) in mentionedFiles) {
            val name = path.substringAfterLast('/')
            append("\n\n## @$name (mentioned by user)\n")
            append("File: $path\n")
            append("```\n")
            append(content.take(MAX_FILE_CHARS))
            if (content.length > MAX_FILE_CHARS) append("\n... [truncated]")
            append("\n```")
        }
        }
    }

    fun buildCompletionRequest(
        prefix: String,
        suffix: String,
        language: String = "",
        model: String = "",
    ): CompletionRequest {
        val budgetChars = 2_000 * CHARS_PER_TOKEN
        return CompletionRequest(
            prefix = prefix.takeLast(budgetChars / 2),
            suffix = suffix.take(budgetChars / 2),
            model = model,
            maxTokens = 256,
            temperature = 0.2f,
        )
    }

    private fun trimHistory(history: List<ChatMessage>, budgetChars: Int): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()
        var totalChars = 0
        val kept = mutableListOf<ChatMessage>()
        for (msg in history.reversed()) {
            totalChars += msg.textContent.length
            if (totalChars > budgetChars) break
            kept.add(0, msg)
        }
        return kept
    }
}
