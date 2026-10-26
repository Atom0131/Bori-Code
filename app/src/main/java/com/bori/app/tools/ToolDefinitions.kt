package com.bori.app.tools

import com.bori.core.ai.model.ToolDefinition

/**
 * The tools that the AI agent may call during a chat session.
 *
 * Each definition is sent to the model in every ChatRequest so it knows what
 * actions are available. The actual execution happens in [ToolExecutor].
 */
object ToolDefinitions {

    val READ_FILE = ToolDefinition(
        name = "read_file",
        description = "Read the full text content of a file. " +
            "Use this to understand existing code before making changes.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Absolute path to the file, e.g. /sdcard/Bori/MyApp/app/src/main/MainActivity.kt"
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    val READ_FILES = ToolDefinition(
        name = "read_files",
        description = "Read multiple files in a single operation. " +
            "Use this whenever you need to understand several files at once — " +
            "e.g. a ViewModel and its Repository, or all files flagged by the compiler. " +
            "Prefer this over calling read_file repeatedly.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "paths": {
                  "type": "array",
                  "description": "List of absolute file paths to read.",
                  "items": {
                    "type": "string",
                    "description": "Absolute path, e.g. /sdcard/Bori/MyApp/app/src/main/java/Foo.kt"
                  }
                }
              },
              "required": ["paths"]
            }
        """.trimIndent(),
    )

    val WRITE_FILE = ToolDefinition(
        name = "write_file",
        description = "Write (or overwrite) a file with the given content. " +
            "Parent directories are created automatically. " +
            "Use this to create new files or apply code changes.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Absolute path to the file to write."
                },
                "content": {
                  "type": "string",
                  "description": "Full text content to write to the file."
                }
              },
              "required": ["path", "content"]
            }
        """.trimIndent(),
    )

    val LIST_DIRECTORY = ToolDefinition(
        name = "list_directory",
        description = "List the files and subdirectories at a path. " +
            "Use this to explore the project structure before reading or writing files.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Absolute path to the directory to list."
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    val CREATE_DIRECTORY = ToolDefinition(
        name = "create_directory",
        description = "Create a directory (and any missing parent directories).",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Absolute path of the directory to create."
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    val RUN_SHELL = ToolDefinition(
        name = "run_shell",
        description = "Execute a shell command in the project root directory and return its output. " +
            "Available commands include: ls, find, cat, grep, mkdir, cp, mv, touch, rm. " +
            "Gradle builds are not supported on-device; use the other file tools instead.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Shell command to execute, e.g. \"grep -r 'TODO' /sdcard/Bori/MyApp/\""
                }
              },
              "required": ["command"]
            }
        """.trimIndent(),
    )

    val GET_EDITOR_CONTEXT = ToolDefinition(
        name = "get_editor_context",
        description = "Returns the current editor state: active file path, open tabs with dirty " +
            "flags, and cursor offset. Use this before reading or editing to understand what " +
            "is currently open in the IDE.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
    )

    val APPLY_EDIT = ToolDefinition(
        name = "apply_edit",
        description = "Applies a find-and-replace edit to a file. " +
            "Replaces the first occurrence of 'old_text' with 'new_text'. " +
            "The change is saved to disk and reflected live in the editor if the file is open. " +
            "If 'old_text' is an empty string \"\", the entire file is overwritten with 'new_text' — " +
            "use this when writing a whole new implementation. " +
            "For targeted edits, read the file first to get the exact text; fails if old_text is not found.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "file_path": {
                  "type": "string",
                  "description": "Absolute path to the file, e.g. /sdcard/Bori/MyApp/app/src/main/java/Foo.kt"
                },
                "old_text": {
                  "type": "string",
                  "description": "Exact text to find and replace (including whitespace). Pass an empty string to overwrite the entire file."
                },
                "new_text": {
                  "type": "string",
                  "description": "Replacement text, or the full new file content when old_text is empty."
                }
              },
              "required": ["file_path", "old_text", "new_text"]
            }
        """.trimIndent(),
    )

    val GET_BUILD_LOGS = ToolDefinition(
        name = "get_build_logs",
        description = "Returns the status and full log output of the most recent build. " +
            "Status is one of: none, building, success, failed.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
    )

    val GET_DIAGNOSTICS = ToolDefinition(
        name = "get_diagnostics",
        description = "Returns all structured diagnostics (errors and warnings) from the most " +
            "recent build, grouped by file. Each entry includes the file path, line, column, " +
            "severity, and the compiler message. Returns an empty list if no build has been run " +
            "or the last build had no diagnostics.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
    )

    val OPEN_FILE = ToolDefinition(
        name = "open_file",
        description = "Opens a file in the editor. If the file is already open, switches to its tab. " +
            "Optionally scrolls to a specific 1-based line number. " +
            "Use this after writing or editing a file so the user can see the result.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Absolute path to the file to open, e.g. /sdcard/Bori/MyApp/app/src/main/java/Foo.kt"
                },
                "line": {
                  "type": "integer",
                  "description": "1-based line number to scroll to after opening (optional)."
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    val GET_PROJECT_INFO = ToolDefinition(
        name = "get_project_info",
        description = "Returns a complete summary of the active project: package name, minSdk, " +
            "targetSdk, all declared dependencies from build.gradle.kts, " +
            "AndroidManifest permissions, and registered activities/services. " +
            "Call this first before any task so you know what already exists " +
            "and avoid duplicating dependencies or permissions.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
    )

    val ADD_DEPENDENCY = ToolDefinition(
        name = "add_dependency",
        description = "Adds a single dependency line to app/build.gradle.kts. " +
            "Handles the gradle syntax correctly and syncs the open editor. " +
            "Does nothing if the coordinate is already present. " +
            "Prefer this over apply_edit for any build.gradle change.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "configuration": {
                  "type": "string",
                  "description": "Gradle configuration: implementation, kapt, testImplementation, androidTestImplementation"
                },
                "coordinate": {
                  "type": "string",
                  "description": "Maven coordinate, e.g. \"androidx.room:room-runtime:2.6.1\""
                }
              },
              "required": ["configuration", "coordinate"]
            }
        """.trimIndent(),
    )

    val ADD_MANIFEST_PERMISSION = ToolDefinition(
        name = "add_manifest_permission",
        description = "Adds a <uses-permission> entry to AndroidManifest.xml. " +
            "Inserts it in the correct location (before <application>) and " +
            "does nothing if the permission is already declared.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "permission": {
                  "type": "string",
                  "description": "Full Android permission name, e.g. \"android.permission.INTERNET\""
                }
              },
              "required": ["permission"]
            }
        """.trimIndent(),
    )

    val TRIGGER_BUILD = ToolDefinition(
        name = "trigger_build",
        description = "Triggers a full on-device build of the active Android project and returns " +
            "the build status (success/failed) and the last 20 log lines. " +
            "Use this when the user asks you to build the project, or after writing code changes " +
            "to verify they compile. Do not call this repeatedly in a tight loop — fix errors first.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
    )

    val INSTALL_APK = ToolDefinition(
        name = "install_apk",
        description = "Opens the system installer for the most recently built APK. " +
            "The user will see an install dialog and must tap Install to confirm. " +
            "Only call this after a successful build. " +
            "If the 'Install unknown apps' permission has not been granted, this will open the " +
            "settings screen and ask the user to grant it first.",
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
    )

    val LAUNCH_APP = ToolDefinition(
        name = "launch_app",
        description = "Launches the installed app. The app must have been installed first. " +
            "Pass package_name to launch a specific app, or omit it to launch the active project's app.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "package_name": {
                  "type": "string",
                  "description": "Package name, e.g. com.example.myapp. Omit to use the active project."
                }
              },
              "required": []
            }
        """.trimIndent(),
    )

    val WRITE_FILES = ToolDefinition(
        name = "write_files",
        description = "Writes multiple files in a single operation. " +
            "Use this to scaffold a feature — e.g. create a Room Entity, Dao, Database, " +
            "Repository, and ViewModel all at once instead of one file per tool call. " +
            "Parent directories are created automatically. " +
            "Existing files are overwritten.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "files": {
                  "type": "array",
                  "description": "List of files to write.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "path": {
                        "type": "string",
                        "description": "Absolute path, e.g. /sdcard/Bori/MyApp/app/src/main/java/com/example/UserDao.kt"
                      },
                      "content": {
                        "type": "string",
                        "description": "Full file content to write."
                      }
                    },
                    "required": ["path", "content"]
                  }
                }
              },
              "required": ["files"]
            }
        """.trimIndent(),
    )

    val WEB_SEARCH = ToolDefinition(
        name = "web_search",
        description = "Search the web and get back a list of relevant results (title, URL, and a " +
            "content snippet), plus a short synthesized answer when available. " +
            "Use this to research APIs, libraries, error messages, or current best practices " +
            "before writing code. Follow up with web_fetch to read a specific result in full.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "The search query, e.g. \"Room database migration kotlin example\""
                },
                "max_results": {
                  "type": "integer",
                  "description": "Number of results to return (1-10, default 5)."
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    val WEB_FETCH = ToolDefinition(
        name = "web_fetch",
        description = "Fetch a single web page (or raw text/JSON resource) by URL and return its " +
            "readable text content with HTML markup stripped. " +
            "Use this to read documentation, API references, or a search result in detail.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The full http(s) URL to fetch, e.g. https://developer.android.com/..."
                }
              },
              "required": ["url"]
            }
        """.trimIndent(),
    )

    val DOWNLOAD_IMAGE = ToolDefinition(
        name = "download_image",
        description = "Download an image from a URL and save it into the active project. " +
            "By default it is saved into app/src/main/res/drawable/ using the given file name. " +
            "Use this to add icons, illustrations, or sample assets. The file extension is " +
            "inferred from the response when not given. Only image responses are accepted.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The full http(s) URL of the image to download."
                },
                "file_name": {
                  "type": "string",
                  "description": "Resource file name without path, e.g. hero_banner or icon.png. Must be a valid Android drawable name (lowercase letters, digits, underscores)."
                },
                "path": {
                  "type": "string",
                  "description": "Optional absolute destination path inside the project sandbox. Overrides the default res/drawable/ location when provided."
                }
              },
              "required": ["url", "file_name"]
            }
        """.trimIndent(),
    )

    val WRITE_PLAN = ToolDefinition(
        name = "write_plan",
        description = "Submit your implementation plan to the user for review. " +
            "Call this ONCE after exploring the codebase. The agent loop stops immediately after.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "summary": {
                  "type": "string",
                  "description": "One or two sentences summarising what will be done."
                },
                "plan": {
                  "type": "string",
                  "description": "Full markdown implementation plan shown to the user."
                }
              },
              "required": ["summary", "plan"]
            }
        """.trimIndent(),
    )

    // NOTE (declaration order): ASK_QUESTIONS, EXIT_PLAN_MODE and COMPACT_CONVERSATION must be
    // declared BEFORE any list val (planModeTools/askModeTools/all) that references them. Object
    // `val`s initialize top-to-bottom, so a list that forward-references a later val would capture
    // null at init time.

    /**
     * Batched clarifying questions (1–4 at once), each with an optional short header and tappable
     * options. Used inline in any mode — no special "Ask Mode" needed — and intercepted by the
     * agent loop (it never executes in [ToolExecutor]). Replaces the one-question-per-call ask_user.
     */
    val ASK_QUESTIONS = ToolDefinition(
        name = "ask_questions",
        description = "Ask the user 1 to 4 clarifying questions at once and pause the loop until " +
            "they answer. Use this INLINE in normal mode whenever the user says 'ask me' / 'ask me " +
            "[N] questions' / 'follow up with questions' / 'before you start', or whenever a specific " +
            "clarification would change what you build — you do NOT need any special mode to ask. " +
            "If the user names a count (e.g. 'ask me three questions'), provide exactly that many. " +
            "Whenever there are sensible predefined answers, supply them in 'options' so the user can " +
            "tap instead of type. The user can always pick 'Other' and type a custom answer.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "questions": {
                  "type": "array",
                  "description": "Between 1 and 4 questions to ask the user at once.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "question": {
                        "type": "string",
                        "description": "The clarifying question to ask."
                      },
                      "header": {
                        "type": "string",
                        "description": "A very short label for this question (1-3 words), e.g. \"Database\" or \"Auth method\"."
                      },
                      "options": {
                        "type": "array",
                        "description": "Optional suggested answers shown as tappable choices. Omit if the question is open-ended.",
                        "items": { "type": "string" }
                      },
                      "allow_multiple": {
                        "type": "boolean",
                        "description": "If true, the user may select more than one option. Defaults to false."
                      }
                    },
                    "required": ["question"]
                  }
                }
              },
              "required": ["questions"]
            }
        """.trimIndent(),
    )

    /**
     * Graceful escape from Plan Mode: requests to stop planning and start implementing. Intercepted
     * by the agent loop, which shows an Approve/Reject card; on approval the agent gains the full
     * tool set. Gives Plan Mode a one-tap exit instead of being a one-way trap.
     */
    val EXIT_PLAN_MODE = ToolDefinition(
        name = "exit_plan_mode",
        description = "Request to leave Plan Mode and start implementing directly. " +
            "Call this when your plan is ready, or when the user signals they want to proceed " +
            "(\"go\", \"do it\", \"build it\", \"looks good\"). The agent loop pauses and shows the user " +
            "an Approve/Reject card; on approval, Plan Mode ends and you gain the full tool set to " +
            "make changes. NEVER stay stuck repeating plans or claim you cannot act because you are " +
            "in Plan Mode — call this tool instead. (Alternatively, use write_plan to present a plan " +
            "for formal review first.)",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "reason": {
                  "type": "string",
                  "description": "One sentence explaining what you are ready to implement."
                }
              },
              "required": ["reason"]
            }
        """.trimIndent(),
    )

    // Restricted tool list for Plan Mode — read-only + clarify + write_plan + exit.
    // Added ASK_QUESTIONS (inline clarifying) and EXIT_PLAN_MODE (graceful escape) here.
    val planModeTools: List<ToolDefinition> = listOf(
        GET_PROJECT_INFO,
        GET_EDITOR_CONTEXT,
        READ_FILE,
        READ_FILES,
        LIST_DIRECTORY,
        ASK_QUESTIONS,
        WRITE_PLAN,
        EXIT_PLAN_MODE,
    )

    val ASK_USER = ToolDefinition(
        name = "ask_user",
        description = "Ask the user one question and pause the loop until they answer. " +
            "Use this when the user says 'ask me' anywhere in their message, or when you need " +
            "a specific clarification to proceed. Ask ONE question per call — for multiple questions, " +
            "call ask_user once per question in sequence. " +
            "In Ask Mode, ask at most 4 questions before calling write_plan.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "question": {
                  "type": "string",
                  "description": "The clarifying question to ask the user."
                },
                "context": {
                  "type": "string",
                  "description": "Optional: one sentence explaining why you need this information."
                }
              },
              "required": ["question"]
            }
        """.trimIndent(),
    )

    /** Tool list for Ask Mode — read-only tools + ask_questions + write_plan. */
    val askModeTools: List<ToolDefinition> = listOf(
        GET_PROJECT_INFO,
        GET_EDITOR_CONTEXT,
        READ_FILE,
        READ_FILES,
        LIST_DIRECTORY,
        ASK_QUESTIONS,
        WRITE_PLAN,
    )

    /**
     * Compresses the conversation into a structured summary to free context space, keeping recent
     * turns verbatim. Intercepted by the agent loop (which compacts then pauses); never executed in
     * [ToolExecutor].
     */
    val COMPACT_CONVERSATION = ToolDefinition(
        name = "compact_conversation",
        description = "Compress the conversation so far into a concise structured summary to free " +
            "up context space, keeping the most recent turns verbatim. Call this when the " +
            "conversation has become very long and older details are no longer needed in full. " +
            "The loop pauses after compacting; the user continues from the summarized state.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "focus": {
                  "type": "string",
                  "description": "Optional: what to prioritise keeping in the summary, e.g. \"the Room migration plan\"."
                }
              },
              "required": []
            }
        """.trimIndent(),
    )

    val SUGGEST_PLAN_MODE = ToolDefinition(
        name = "suggest_plan_mode",
        description = "Suggest switching to Plan Mode when the user's request is asking to create a plan, " +
            "design a feature, or structure work before implementation. " +
            "The agent loop will pause and show the user a confirmation card. " +
            "Do NOT call this for normal coding requests — only when the user explicitly asks for a plan.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "reason": {
                  "type": "string",
                  "description": "One sentence explaining why Plan Mode would help with this request."
                }
              },
              "required": ["reason"]
            }
        """.trimIndent(),
    )

    // All tools sent with every agent chat request.
    // Updated: ASK_USER → ASK_QUESTIONS (batched inline clarifying) and COMPACT_CONVERSATION added.
    val all: List<ToolDefinition> = listOf(
        GET_PROJECT_INFO,
        GET_EDITOR_CONTEXT,
        READ_FILE,
        READ_FILES,
        LIST_DIRECTORY,
        WRITE_FILE,
        WRITE_FILES,
        APPLY_EDIT,
        CREATE_DIRECTORY,
        ADD_DEPENDENCY,
        ADD_MANIFEST_PERMISSION,
        OPEN_FILE,
        WEB_SEARCH,
        WEB_FETCH,
        DOWNLOAD_IMAGE,
        SUGGEST_PLAN_MODE,
        ASK_QUESTIONS,
        COMPACT_CONVERSATION,
    )
}
