package com.aura.assistant

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

enum class ActionType {
    LAUNCH_APP,
    WAIT_FOR_APP,
    FIND_AND_CLICK,
    ENTER_TEXT,
    SUBMIT_SEARCH,
    READ_FIRST_RESULT,
    READ_LATEST_MESSAGE,
    TOGGLE_SETTING
}

data class AssistantAction(
    val type: ActionType,
    val target: String,
    val extra: String? = null,
    val timeoutMs: Long = 4000L
)

data class ActionPlan(
    val rawUtterance: String,
    val actions: List<AssistantAction>,
    val confirmationPrompt: String? = null
)

data class AppEntry(val key: String, val packageName: String, val aliases: List<String>)

/**
 * Multi-step natural language command planner for Android accessibility / voice automation.
 * Handles English, Telugu, and Hinglish compounding, alias resolution, and context carryover.
 */
class AuraCommandPlanner(
    apps: List<AppEntry> = DEFAULT_APPS,
    private val waitForAppTimeoutMs: Long = 4000L
) {

    private val aliasIndex: List<Pair<String, AppEntry>> = apps
        .flatMap { app -> app.aliases.map { alias -> normalizeForMatch(alias) to app } }
        .filter { it.first.isNotEmpty() }
        .distinctBy { it.first }
        .sortedByDescending { it.first.length }

    private val appByAlias: Map<String, AppEntry> = aliasIndex.toMap()

    private val latinAliases = alternation(aliasIndex.map { it.first }.filter { it.isAscii() })
    private val otherAliases = alternation(aliasIndex.map { it.first }.filterNot { it.isAscii() })

    private val appTrailing = Regex(
        """\s+(?:on|in|at|using|via|from)\s+(?:the\s+)?(${latinAliases})(?:\s+app)?$""",
        RegexOption.IGNORE_CASE
    )

    private val appLeading = Regex(
        """^(${latinAliases})\s+(?:for|par|pe|mein|me)\s+""",
        RegexOption.IGNORE_CASE
    )

    private val appLocative = Regex("""(${otherAliases})\s*(?:లో|లొ|ల)""", RegexOption.IGNORE_CASE)

    private val appMentionPatterns = listOf(appTrailing, appLeading, appLocative)

    fun plan(spokenUtterance: String): ActionPlan {
        val cleaned = cleanUtterance(spokenUtterance)
        if (cleaned.isEmpty()) {
            return ActionPlan(spokenUtterance, emptyList(), PROMPT_NOT_UNDERSTOOD)
        }

        val ctx = Ctx()
        val unrecognized = ArrayList<String>()

        for (clauseText in splitIntoClauses(cleaned)) {
            val clause = Clause(LOOK_UP.replace(clauseText, "search"))
            if (!parseClause(clause, ctx)) {
                unrecognized.add(clauseText)
            }
        }

        val actions = insertLaunchWaits(ctx.out)
        val prompt = when {
            actions.isEmpty() -> PROMPT_NOT_UNDERSTOOD
            unrecognized.isNotEmpty() -> {
                val skipped = unrecognized.joinToString("\" and \"")
                "I couldn't understand \"$skipped\". Should I carry on with the rest?"
            }
            else -> null
        }
        return ActionPlan(spokenUtterance, actions, prompt)
    }

    private class AppRef(val key: String, val launchTarget: String) {
        override fun equals(other: Any?) =
            other is AppRef && other.key == key && other.launchTarget == launchTarget

        override fun hashCode() = 31 * key.hashCode() + launchTarget.hashCode()
    }

    private class Ctx {
        var app: AppRef? = null
        val out = ArrayList<AssistantAction>()
    }

    private class Clause(val text: String) {
        val tokens: List<String> = text.split(' ').filter { it.isNotEmpty() }
        val lowerTokens: List<String> = tokens.map { it.lowercase().trim(',', '.', '"', ';') }
        val lower: String = normalizeForMatch(text)

        fun hasAny(words: Set<String>): Boolean = lowerTokens.any { it in words }
        fun hasTerm(terms: List<String>): Boolean = terms.any { containsTerm(lower, it) }
    }

    private fun AppEntry.toRef() = AppRef(key, packageName)

    private fun parseClause(c: Clause, ctx: Ctx): Boolean =
        tryReadLatestMessage(c, ctx) ||
            tryOpenLatestChat(c, ctx) ||
            tryReadFirstResult(c, ctx) ||
            trySearch(c, ctx) ||
            tryToggle(c, ctx) ||
            tryOpenApp(c, ctx) ||
            tryClick(c, ctx)

    private fun tryReadLatestMessage(c: Clause, ctx: Ctx): Boolean {
        if (!c.hasAny(READ_VERBS) || !c.hasTerm(MESSAGE_TERMS)) return false
        val target = resolveReadTarget(c, ctx)
        ctx.out.add(AssistantAction(ActionType.READ_LATEST_MESSAGE, target))
        return true
    }

    private fun tryOpenLatestChat(c: Clause, ctx: Ctx): Boolean {
        if (!c.hasAny(OPEN_VERBS) || !c.hasTerm(LATEST_TERMS)) return false
        if (!c.hasTerm(CONVERSATION_TERMS) && !c.hasTerm(MESSAGE_TERMS)) return false
        findApp(c.lower)?.let { ensureLaunched(it.toRef(), ctx) }
        ctx.out.add(AssistantAction(ActionType.FIND_AND_CLICK, "latest_chat"))
        return true
    }

    private fun tryReadFirstResult(c: Clause, ctx: Ctx): Boolean {
        if (!c.hasAny(READ_VERBS) || !c.hasTerm(FIRST_TERMS) || !c.hasTerm(RESULT_TERMS)) return false
        val target = resolveReadTarget(c, ctx)
        ctx.out.add(AssistantAction(ActionType.READ_FIRST_RESULT, target))
        return true
    }

    private fun trySearch(c: Clause, ctx: Ctx): Boolean {
        val verbIndex = c.lowerTokens.indexOfFirst { it in SEARCH_VERBS }
        if (verbIndex < 0) return false

        val before = c.tokens.subList(0, verbIndex)
        val afterRaw = c.tokens.subList(verbIndex + 1, c.tokens.size)
        val after = trimFillers(afterRaw)

        val queryFromAfter = after.isNotEmpty()
        val query = joinTokens(if (queryFromAfter) after else trimFillers(before))
        val rest = if (queryFromAfter) before else afterRaw

        val (withoutApp, queryApp) = extractAppMention(query)
        val queryTokens = trimFillers(withoutApp.split(' ').filter { it.isNotEmpty() })
        if (queryTokens.isEmpty()) return false

        val app = queryApp ?: findApp(normalizeForMatch(joinTokens(rest)))
        if (app != null) ensureLaunched(app.toRef(), ctx)

        ctx.out.add(AssistantAction(ActionType.FIND_AND_CLICK, SEARCH_TARGET))
        ctx.out.add(AssistantAction(ActionType.ENTER_TEXT, SEARCH_TARGET, joinTokens(queryTokens)))
        ctx.out.add(AssistantAction(ActionType.SUBMIT_SEARCH, SEARCH_TARGET))
        return true
    }

    private fun tryToggle(c: Clause, ctx: Ctx): Boolean {
        val settingKey = SETTING_INDEX
            .firstOrNull { (alias, _) -> containsTerm(c.lower, alias) }
            ?.second
            ?: return false

        val stateIndex = c.lowerTokens.indexOfLast { it in ON_WORDS || it in OFF_WORDS }
        val state = when {
            stateIndex >= 0 -> if (c.lowerTokens[stateIndex] in ON_WORDS) "on" else "off"
            c.hasAny(TOGGLE_VERBS) -> "toggle"
            else -> return false
        }

        ctx.out.add(AssistantAction(ActionType.TOGGLE_SETTING, settingKey, state))
        return true
    }

    private fun tryOpenApp(c: Clause, ctx: Ctx): Boolean {
        if (!c.hasAny(OPEN_VERBS)) return false
        val app = findApp(c.lower)?.toRef() ?: unknownApp(c) ?: return false
        ctx.out.add(AssistantAction(ActionType.LAUNCH_APP, app.launchTarget))
        ctx.app = app
        return true
    }

    private fun tryClick(c: Clause, ctx: Ctx): Boolean {
        if (!c.hasAny(CLICK_VERBS)) return false
        val targetTokens = c.tokens.filter { token ->
            val lower = token.lowercase().trim(',', '.', '"', ';')
            lower.isNotEmpty() && lower !in CLICK_VERBS && lower !in CLICK_FILLERS
        }
        if (targetTokens.isEmpty()) return false
        ctx.out.add(AssistantAction(ActionType.FIND_AND_CLICK, joinTokens(targetTokens)))
        return true
    }

    private fun findApp(lowerText: String): AppEntry? =
        aliasIndex.firstOrNull { (alias, _) -> containsTerm(lowerText, alias) }?.second

    private fun unknownApp(c: Clause): AppRef? {
        val aliasTokens = c.lowerTokens.filter {
            it.isNotEmpty() && it !in OPEN_VERBS && it !in FILLERS
        }
        if (aliasTokens.isEmpty() || aliasTokens.size > 3) return null
        if (aliasTokens.any { it in UI_ELEMENT_WORDS }) return null
        val alias = aliasTokens.joinToString(" ")
        return AppRef(alias, alias)
    }

    private fun ensureLaunched(app: AppRef, ctx: Ctx) {
        if (ctx.app != app) {
            ctx.out.add(AssistantAction(ActionType.LAUNCH_APP, app.launchTarget))
            ctx.app = app
        }
    }

    private fun resolveReadTarget(c: Clause, ctx: Ctx): String {
        val mentioned = findApp(c.lower)
        if (mentioned != null) {
            ensureLaunched(mentioned.toRef(), ctx)
            return mentioned.key
        }
        return ctx.app?.key ?: CURRENT_APP
    }

    private fun extractAppMention(query: String): Pair<String, AppEntry?> {
        for (pattern in appMentionPatterns) {
            val match = pattern.find(query) ?: continue
            val app = appByAlias[normalizeForMatch(match.groupValues[1])] ?: continue
            val cleanedQuery = query.removeRange(match.range)
                .trim { it.isWhitespace() || it == '్' || it == ',' }
            return cleanedQuery to app
        }
        return query to null
    }

    private fun insertLaunchWaits(actions: List<AssistantAction>): List<AssistantAction> {
        val result = ArrayList<AssistantAction>(actions.size + 2)
        actions.forEachIndexed { index, action ->
            result.add(action)
            val next = actions.getOrNull(index + 1)
            if (action.type == ActionType.LAUNCH_APP && next?.type != ActionType.TOGGLE_SETTING) {
                result.add(
                    AssistantAction(ActionType.WAIT_FOR_APP, action.target, timeoutMs = waitForAppTimeoutMs)
                )
            }
        }
        return result
    }

    private fun splitIntoClauses(text: String): List<String> {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val clauses = ArrayList<String>()
        val current = ArrayList<String>()

        fun flush() {
            if (current.isNotEmpty()) {
                clauses.add(joinTokens(current))
                current.clear()
            }
        }

        var i = 0
        while (i < tokens.size) {
            val run = matchDelimiterRun(tokens, i)
            if (run == null) {
                current.add(tokens[i])
                i++
                continue
            }
            val next = i + run.length
            val right = tokens.subList(next, tokens.size)
            val isBoundary = when {
                current.isEmpty() || right.isEmpty() -> true
                run.hard -> true
                else -> endsWithCommand(current) || startsWithCommand(right)
            }
            if (isBoundary) flush() else current.addAll(tokens.subList(i, next))
            i = next
        }
        flush()
        return clauses
    }

    companion object {
        private const val SEARCH_TARGET = "search"
        private const val CURRENT_APP = "current"
        private const val PROMPT_NOT_UNDERSTOOD = "Sorry, I didn't catch that. What would you like me to do?"

        val DEFAULT_APPS: List<AppEntry> = listOf(
            AppEntry("youtube", "com.google.android.youtube", listOf("youtube", "you tube", "యూట్యూబ్")),
            AppEntry(
                "youtube_music", "com.google.android.apps.youtube.music",
                listOf("youtube music", "yt music", "యూట్యూబ్ మ్యూజిక్")
            ),
            AppEntry("whatsapp", "com.whatsapp", listOf("whatsapp", "whats app", "వాట్సాప్", "వాట్సప్")),
            AppEntry("settings", "com.android.settings", listOf("settings", "సెట్టింగ్స్", "సెట్టింగులు")),
            AppEntry("chrome", "com.android.chrome", listOf("chrome", "google chrome", "క్రోమ్")),
            AppEntry("gmail", "com.google.android.gm", listOf("gmail", "జీమెయిల్", "జిమెయిల్")),
            AppEntry("maps", "com.google.android.apps.maps", listOf("google maps", "maps", "మ్యాప్స్")),
            AppEntry("instagram", "com.instagram.android", listOf("instagram", "ఇన్స్టాగ్రామ్")),
            AppEntry("facebook", "com.facebook.katana", listOf("facebook", "ఫేస్బుక్")),
            AppEntry("telegram", "org.telegram.messenger", listOf("telegram", "టెలిగ్రామ్")),
            AppEntry("play_store", "com.android.vending", listOf("play store", "google play", "ప్లే స్టోర్")),
            AppEntry("spotify", "com.spotify.music", listOf("spotify", "స్పాటిఫై")),
            AppEntry("photos", "com.google.android.apps.photos", listOf("google photos", "photos", "ఫోటోలు"))
        )
    }
}

private val INVISIBLE = Regex("[\\u200B-\\u200D\\uFEFF]")
private val WHITESPACE = Regex("\\s+")
private val SENTENCE_END = Regex("""[.!?।;]+(?=\s|$)""")

private val WAKE_WORD = Regex(
    """^(?:(?:hey|hi|hello|ok|okay)[\s,]+)?aura(?=[\s,]|$)[\s,]*""" +
        """|^(?:(?:హే|హలో|ఓకే)[\s,]+)?ఆరా(?=[\s,]|$)[\s,]*""",
    RegexOption.IGNORE_CASE
)

private val LOOK_UP = Regex("""\blook\s+up\b""", RegexOption.IGNORE_CASE)

private fun String.isAscii(): Boolean = all { it.code < 128 }

private fun alternation(items: List<String>): String =
    if (items.isEmpty()) "(?!)" else items.joinToString("|") { Regex.escape(it) }

private fun normalizeForMatch(text: String): String {
    val folded = INVISIBLE.replace(Normalizer.normalize(text, Normalizer.Form.NFC), "")
        .lowercase()
        .replace('-', ' ')
    return WHITESPACE.replace(folded, " ").trim()
}

private fun joinTokens(tokens: List<String>): String = tokens.joinToString(" ").replace(" ,", ",")

private fun cleanUtterance(input: String): String {
    var text = Normalizer.normalize(input, Normalizer.Form.NFC)
    text = INVISIBLE.replace(text, "")
    text = SENTENCE_END.replace(text, " , ")
    text = text.replace(",", " , ").replace("\"", " ")
    text = WHITESPACE.replace(text, " ").trim()
    while (true) {
        val stripped = WAKE_WORD.replaceFirst(text, "")
        if (stripped == text) break
        text = stripped.trim()
    }
    return text.trim { it == ',' || it.isWhitespace() }
}

private val termRegexes = ConcurrentHashMap<String, Regex>()

private fun containsTerm(text: String, term: String): Boolean {
    if (!term.isAscii()) return text.contains(term)
    val regex = termRegexes.getOrPut(term) {
        Regex("(?<![a-z0-9])${Regex.escape(term)}(?:s|es)?(?![a-z0-9])")
    }
    return regex.containsMatchIn(text)
}

private fun words(vararg w: String): Set<String> = w.map { normalizeForMatch(it) }.toSet()
private fun terms(vararg t: String): List<String> = t.map { normalizeForMatch(it) }

private val OPEN_VERBS = words(
    "open", "launch", "start",
    "ఓపెన్", "తెరవండి", "తెరవు", "తెరువు", "లాంచ్", "ప్రారంభించండి",
    "kholo", "khol", "kholiye"
)
private val SEARCH_VERBS = words(
    "search", "find",
    "వెతకండి", "వెతకు", "వెతుకు", "సెర్చ్", "శోధించండి",
    "dhundho", "dhundo", "khojo"
)
private val READ_VERBS = words("read", "చదవండి", "చదువు", "వినిపించండి", "padho", "padhiye")
private val ON_WORDS = words("on", "enable", "activate", "ఆన్", "chalu")
private val OFF_WORDS = words("off", "disable", "deactivate", "ఆఫ్", "band")
private val CLICK_VERBS = words("click", "tap", "press", "select", "క్లిక్", "నొక్కండి", "ట్యాప్")
private val TOGGLE_VERBS = words("turn", "switch", "toggle")

private val START_VERBS = words(
    "open", "launch", "start", "search", "find", "lookup", "look", "read", "turn", "switch",
    "enable", "disable", "toggle", "click", "tap", "press", "select", "type", "enter", "go",
    "play", "pause", "stop", "close", "call", "send", "scroll", "show", "tell"
)
private val LEAD_IN_WORDS = words("please", "also", "now", "just")

private val HINGLISH_FINAL = words(
    "kholo", "khol", "kholiye", "dhundho", "dhundo", "khojo", "padho", "padhiye",
    "chalu", "band", "karo", "kijiye"
)

private val VERB_FINAL: Set<String> =
    (OPEN_VERBS + SEARCH_VERBS + READ_VERBS + CLICK_VERBS + ON_WORDS + OFF_WORDS)
        .filter { w -> !w.isAscii() || w in HINGLISH_FINAL }
        .toSet() + words("చేయండి", "చేయి", "karo", "kijiye")

private val FILLERS = words(
    "my", "the", "a", "an", "app", "please", "to", "on", "in", "at", "for", "me",
    "చేయండి", "చేయి", "దయచేసి", "karo", "kijiye", "kar", "do"
)
private val CLICK_FILLERS = FILLERS + words("button")
private val LEADING_FILLERS = words("for", "about", "please", "దయచేసి")
private val TRAILING_FILLERS = words(
    "please", "for", "me", "కోసం", "ke", "liye", "karo", "kijiye", "kar", "చేయండి", "చేయి", "దయచేసి"
)

private val UI_ELEMENT_WORDS = words(
    "result", "results", "link", "links", "button", "tab", "menu", "option", "video",
    "post", "icon", "message", "messages", "chat", "search"
)

private val MESSAGE_TERMS = terms("message", "msg", "sms", "text", "మెసేజ్", "సందేశ")
private val LATEST_TERMS = terms(
    "latest", "last", "newest", "most recent", "recent", "new",
    "చివరి", "తాజా", "కొత్త", "లేటెస్ట్", "ఆఖరి"
)
private val CONVERSATION_TERMS = terms("conversation", "chat", "thread", "సంభాషణ", "చాట్")
private val FIRST_TERMS = terms("first", "top", "1st", "మొదటి", "ఫస్ట్", "టాప్")
private val RESULT_TERMS = terms("result", "video", "link", "item", "one", "ఫలిత", "రిజల్ట్", "వీడియో", "లింక్")

private val SETTING_INDEX: List<Pair<String, String>> = listOf(
    "bluetooth" to listOf("bluetooth", "blue tooth", "బ్లూటూత్"),
    "wifi" to listOf("wifi", "wi fi", "వైఫై", "వై ఫై"),
    "mobile_data" to listOf("mobile data", "cellular data", "మొబైల్ డేటా"),
    "airplane_mode" to listOf("airplane mode", "aeroplane mode", "flight mode", "ఎయిర్ప్లేన్ మోడ్", "ఫ్లైట్ మోడ్"),
    "flashlight" to listOf("flashlight", "flash light", "torch", "ఫ్లాష్లైట్", "టార్చ్"),
    "location" to listOf("location", "gps", "లొకేషన్"),
    "do_not_disturb" to listOf("do not disturb", "dnd", "డు నాట్ డిస్టర్బ్"),
    "hotspot" to listOf("hotspot", "hot spot", "హాట్స్పాట్"),
    "nfc" to listOf("nfc"),
    "auto_rotate" to listOf("auto rotate", "auto rotation", "screen rotation", "ఆటో రొటేట్"),
    "dark_mode" to listOf("dark mode", "dark theme", "డార్క్ మోడ్")
).flatMap { (key, aliases) -> aliases.map { alias -> normalizeForMatch(alias) to key } }
    .sortedByDescending { it.first.length }

private class Delimiter(val words: List<String>, val hard: Boolean)

private fun hard(vararg w: String) = Delimiter(w.map { normalizeForMatch(it) }, true)
private fun soft(vararg w: String) = Delimiter(w.map { normalizeForMatch(it) }, false)

private val DELIMITERS: List<Delimiter> = listOf(
    hard("and", "then"), hard("after", "that"), hard("aur", "phir"),
    hard("మరియు", "తర్వాత"), hard("ఆ", "తర్వాత"),
    hard("then"), hard("afterwards"), hard("తర్వాత"), hard("తరువాత"), hard("చేసి"),
    soft("and"), soft("మరియు"), soft("aur"), soft(",")
).sortedByDescending { it.words.size }

private class DelimiterRun(val length: Int, val hard: Boolean)

private fun matchDelimiter(tokens: List<String>, pos: Int): Delimiter? =
    DELIMITERS.firstOrNull { d ->
        pos + d.words.size <= tokens.size &&
            d.words.indices.all { k -> normalizeForMatch(tokens[pos + k]) == d.words[k] }
    }

private fun matchDelimiterRun(tokens: List<String>, start: Int): DelimiterRun? {
    var pos = start
    var isHard = false
    while (pos < tokens.size) {
        val delimiter = matchDelimiter(tokens, pos) ?: break
        pos += delimiter.words.size
        isHard = isHard || delimiter.hard
    }
    return if (pos == start) null else DelimiterRun(pos - start, isHard)
}

private fun startsWithCommand(tokens: List<String>): Boolean {
    val first = tokens.firstOrNull { normalizeForMatch(it) !in LEAD_IN_WORDS } ?: return false
    val cleaned = normalizeForMatch(first).trim(',', '.', '"', ';')
    return cleaned in START_VERBS
}

private fun endsWithCommand(tokens: List<String>): Boolean {
    val last = tokens.lastOrNull() ?: return false
    val cleaned = normalizeForMatch(last).trim(',', '.', '"', ';')
    return cleaned in VERB_FINAL
}

private fun trimFillers(tokens: List<String>): List<String> {
    var start = 0
    var end = tokens.size
    while (start < end && normalizeForMatch(tokens[start]) in LEADING_FILLERS) start++
    while (end > start && normalizeForMatch(tokens[end - 1]) in TRAILING_FILLERS) end--
    return tokens.subList(start, end)
}
