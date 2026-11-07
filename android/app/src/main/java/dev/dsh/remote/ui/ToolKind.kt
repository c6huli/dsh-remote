package dev.dsh.remote.ui

import androidx.compose.ui.graphics.Color

/**
 * Tool-result categories, coloured with the dsh Web UI's own palette.
 *
 * The hex values are copied from the host frontend's design tokens so the phone
 * matches the Web UI instead of inventing a second palette:
 *
 *   green-500            #22C55E   … --dsw-static-green-500
 *   file-type violet     #8B76F6   … --dsh-file-type-violet (rgb(139,118,246))
 *   blue-500             #3B82F6   … --dsw-static-blue-500
 *   deepseek-450         #5686FE   … --dsw-static-deepseek-450
 *   amber-500            #F59E0B   … --dsw-static-amber-500
 *   blue-400             #60A5FA   … --dsw-static-blue-400
 *   neutral-bluish-300   #CFD3D6   … --dsw-static-neutral-bluish-300
 *   red-600              #EC1313   … --dsw-static-red-600
 */
enum class ToolKind(val color: Color) {
    /** Terminal work: pwsh, bash, exec … */
    Shell(Color(0xFF22C55E)),

    /** Writing/editing files, and the deliverables card. */
    Edit(Color(0xFF8B76F6)),

    /** Reading and searching: read, glob, grep, list. */
    Read(Color(0xFF3B82F6)),

    /** Network lookups: web search / fetch. */
    Web(Color(0xFF5686FE)),

    /** Planning and agents: todo, plan, subagent. */
    Task(Color(0xFFF59E0B)),

    /** Images and screenshots. */
    Media(Color(0xFF60A5FA)),

    /** Everything else (MCP tools, plugins). */
    Other(Color(0xFFCFD3D6));

    /** Card fill: the same hue, faint enough to sit behind text in both themes. */
    val tint: Color get() = color.copy(alpha = 0.14f)
}

/** A red that matches the Web UI's error colour. */
val ToolErrorColor = Color(0xFFEC1313)

/**
 * Classify a tool by its name.
 *
 * The host ships one card renderer per tool, so it knows the kind directly; the
 * App only receives names, which are grouped here the same way. Web tools are
 * matched before reading so `web_search` does not fall into the search group.
 */
fun toolKindOf(name: String): ToolKind {
    val n = name.lowercase()
    fun has(vararg keys: String) = keys.any { n.contains(it) }
    return when {
        has("pwsh", "powershell", "bash", "shell", "exec", "cmd", "terminal") -> ToolKind.Shell
        has("web_search", "web_fetch", "webfetch", "fetch", "http", "browser") -> ToolKind.Web
        // Before edit/read: `todo_write` is planning work, not a file write, and
        // `list_agents` is agent work, not a directory listing.
        has("todo", "plan", "subagent", "task", "goal", "agent", "job", "workflow") -> ToolKind.Task
        has("image", "screen", "ocr", "vision", "photo", "render", "camera") -> ToolKind.Media
        has("edit", "write", "patch", "replace", "notebook", "present", "create_file", "insert") -> ToolKind.Edit
        has("read", "glob", "grep", "list", "search", "history", "ls_", "dir") -> ToolKind.Read
        else -> ToolKind.Other
    }
}
/**
 * 工具类别 → 语义色调。界面取色一律走 [DshSemanticColors.tool],不在业务代码里
 * 写死颜色:同一张 `bash` 行在六套配色下必须长得一样,但底色的深浅要跟主题走。
 */
fun toolToneOf(kind: ToolKind): dev.dsh.remote.ui.theme.ToolTone = when (kind) {
    ToolKind.Shell -> dev.dsh.remote.ui.theme.ToolTone.Terminal
    ToolKind.Edit -> dev.dsh.remote.ui.theme.ToolTone.Edit
    ToolKind.Read -> dev.dsh.remote.ui.theme.ToolTone.Read
    ToolKind.Web -> dev.dsh.remote.ui.theme.ToolTone.Web
    ToolKind.Task -> dev.dsh.remote.ui.theme.ToolTone.Plan
    ToolKind.Media -> dev.dsh.remote.ui.theme.ToolTone.Search
    ToolKind.Other -> dev.dsh.remote.ui.theme.ToolTone.Neutral
}