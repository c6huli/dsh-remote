package dev.dsh.remote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "dsh_settings")

class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val draftsKey = stringPreferencesKey("drafts")
    private val notifyDoneKey = booleanPreferencesKey("notify_done")
    private val notifyPromptKey = booleanPreferencesKey("notify_prompt")
    private val deepseekApiKeyKey = stringPreferencesKey("deepseek_api_key")
    private val themePreferenceKey = stringPreferencesKey("theme_preference")
    private val languageKey = stringPreferencesKey("language")
    private val verboseKey = booleanPreferencesKey("verbose_steps")
    private val downloadDirKey = stringPreferencesKey("download_dir")
    private val downloadTreeKey = stringPreferencesKey("download_tree_uri")

    /**
     * Folder picked through the system file manager (SAF).
     *
     * Empty means "not chosen": files then fall back to 下载/<downloadDir>/.
     * The URI carries a persisted read/write grant, so it survives restarts.
     */
    val downloadTree: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[downloadTreeKey] ?: ""
    }

    suspend fun setDownloadTree(uri: String) {
        context.dataStore.edit { prefs -> prefs[downloadTreeKey] = uri }
    }

    /**
     * Folder under the public Downloads directory that files land in.
     *
     * Kept as a relative folder name (not an absolute path) so it stays writable
     * through MediaStore on Android 10+ without storage permissions.
     */
    val downloadDir: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[downloadDirKey]?.takeIf { it.isNotBlank() } ?: "DSHRemote"
    }

    suspend fun setDownloadDir(v: String) {
        val clean = v.trim().trim('/').trim('\\').ifBlank { "DSHRemote" }
        context.dataStore.edit { prefs -> prefs[downloadDirKey] = clean }
    }

    /**
     * Detailed vs concise conversation rendering.
     *
     * Off keeps the current compact view; on expands the reasoning/tool steps the
     * desktop shows, which is what "安卓端显示内容没 web 端多" is about.
     */
    val verbose: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[verboseKey] ?: false }

    suspend fun setVerbose(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[verboseKey] = v }
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[serverUrlKey] ?: DEFAULT_URL
    }

    /** UI appearance: "system" | "light" | "dark". Defaults to follow system. */
    val themePreference: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[themePreferenceKey] ?: "system"
    }

    /** UI language: "zh" | "en". Defaults to Chinese. */
    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[languageKey] ?: "zh"
    }

    /** DeepSeek platform API key for balance/usage queries. */
    val deepseekApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[deepseekApiKeyKey] ?: ""
    }

    /** Task-completion notification (with sound). Defaults to on. */
    val notifyDone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[notifyDoneKey] ?: true
    }

    /** Question / approval-request notifications. Defaults to on. */
    val notifyPrompt: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[notifyPromptKey] ?: true
    }

    suspend fun setServerUrl(url: String) {
        // Tolerate a bare host:port (very common when typing a Tailscale address):
        // default to http:// because the personal proxy speaks plain HTTP over the overlay.
        var value = url.trim()
        if (value.isNotEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        context.dataStore.edit { prefs -> prefs[serverUrlKey] = value.trimEnd('/') }
    }

    suspend fun setNotifyDone(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[notifyDoneKey] = v }
    }

    suspend fun setNotifyPrompt(v: Boolean) {
        context.dataStore.edit { prefs -> prefs[notifyPromptKey] = v }
    }

    suspend fun setDeepseekApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[deepseekApiKeyKey] = key.trim() }
    }

    suspend fun setThemePreference(v: String) {
        context.dataStore.edit { prefs -> prefs[themePreferenceKey] = v }
    }

    suspend fun setLanguage(v: String) {
        context.dataStore.edit { prefs -> prefs[languageKey] = v }
    }

    /** Persist per-session composer drafts as a JSON map (WeChat-style drafts). */
    suspend fun saveDrafts(map: Map<String, String>) {
        val json = JSONObject()
        for ((k, v) in map) if (v.isNotEmpty()) json.put(k, v)
        context.dataStore.edit { prefs -> prefs[draftsKey] = json.toString() }
    }

    suspend fun loadDrafts(): Map<String, String> {
        val json = context.dataStore.data.first()[draftsKey] ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotEmpty()) map[k] = v
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        /**
         * Default endpoint. The MagicDNS suffix is NOT stable across tailnet
         * rebuilds (a stale name fails to resolve with ENOTFOUND and the app
         * looks permanently offline), so the Tailscale IP is the primary —
         * it needs no DNS at all and works as soon as the VPN is up.
         */
        const val DEFAULT_URL = "http://100.117.219.91:8080"

        /**
         * Reachability candidates probed in order. First one that answers wins,
         * so a renamed tailnet or a changed node IP still connects without the
         * user editing anything.
         */
        val CANDIDATE_URLS = listOf(
            "http://100.117.219.91:8080",
            "http://desktop-4nfn26o.taileae4a8.ts.net:8080",
            "http://desktop-4nfn26o.tail21c67d.ts.net:8080",
            "http://127.0.0.1:8080",
        )
    }
}
