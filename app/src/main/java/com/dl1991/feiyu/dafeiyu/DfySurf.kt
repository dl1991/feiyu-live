package com.dl1991.feiyu.dafeiyu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dl1991.feiyu.net.NetworkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PageLink(val text: String, val url: String)
data class PageView(val url: String, val title: String, val text: String, val desc: String, val links: List<PageLink>, val blocked: String? = null)

/** Where she can start a search. Users can turn sources off in settings. */
object SurfSources {
    val ALL = listOf("bing", "ddg", "wiki", "baike", "bili", "github", "news")
    fun label(id: String) = when (id) {
        "bing" -> "必应"; "ddg" -> "DuckDuckGo"; "wiki" -> "维基百科"; "baike" -> "百度百科"; "bili" -> "B站"
        "github" -> "GitHub"; "news" -> "新闻"; else -> id
    }
    fun searchUrl(id: String, q: String): String {
        val e = URLEncoder.encode(q, "UTF-8")
        return when (id) {
            "ddg" -> "https://html.duckduckgo.com/html/?q=$e"
            "wiki" -> "https://zh.wikipedia.org/w/index.php?search=$e&variant=zh-cn"
            "baike" -> "https://baike.baidu.com/search?word=$e"
            "bili" -> "https://search.bilibili.com/all?keyword=$e"
            "github" -> "https://github.com/search?q=$e&type=repositories"
            "news" -> "https://cn.bing.com/news/search?q=$e"
            else -> "https://cn.bing.com/search?q=$e"
        }
    }
}

/**
 * Readability-ish DOM extraction, run inside the page. Drops nav/aside/footer/script, prefers
 * <article>/<main>, else the element with the most paragraph text; returns title, meta
 * description, main text (≤ 7000 chars) and up to 60 visible links with their anchor text.
 */
internal const val EXTRACT_JS = """
(function(){try{
 var drop=['script','style','noscript','nav','footer','aside','header','form','iframe','svg'];
 var root=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=main]');
 if(!root){var best=null,score=0;document.querySelectorAll('div,section').forEach(function(d){var t=0;d.querySelectorAll(':scope > p, :scope > ul, :scope > ol').forEach(function(p){t+=(p.innerText||'').length});if(t>score){score=t;best=d}});root=best||document.body;}
 var clone=root.cloneNode(true);drop.forEach(function(tag){clone.querySelectorAll(tag).forEach(function(n){n.remove()})});
 var text=(clone.innerText||clone.textContent||'').replace(/\n{3,}/g,'\n\n').trim().slice(0,7000);
 var md=document.querySelector('meta[name=description]');
 var links=[],seen={};document.querySelectorAll('a[href]').forEach(function(a){if(links.length>=60)return;var h=a.href,t=(a.innerText||a.title||'').trim().replace(/\s+/g,' ');
  if(!h||h.indexOf('http')!==0||t.length<2||t.length>80||seen[h])return;var r=a.getBoundingClientRect();if(r.width<2||r.height<2)return;seen[h]=1;links.push({t:t,u:h});});
 return JSON.stringify({u:location.href,ti:document.title||'',d:md?md.content:'',x:text,l:links});
}catch(e){return JSON.stringify({u:location.href,ti:document.title||'',d:'',x:'',l:[],err:String(e)})}})()
"""

/** Blocks form submission inside pages unless the user allowed forms. */
internal const val NO_FORMS_JS = """
(function(){if(window.__dfyNoForms)return;window.__dfyNoForms=1;
 document.addEventListener('submit',function(e){e.preventDefault();e.stopPropagation();},true);
 HTMLFormElement.prototype.submit=function(){};})()
"""

/**
 * The one WebView she browses with. Owned here (application context, main thread) so the
 * 直播间 screen, the floating window and the stream compositor can all show the same page.
 * Hardening: no file/content access, no geolocation, no popups/new windows, no downloads,
 * non-http(s) schemes refused, and — unless forms are allowed — every non-GET request is
 * answered locally with an empty 204 so nothing can be posted, bought or submitted.
 */
@SuppressLint("SetJavaScriptEnabled")
object DfyBrowser {
    private const val TAG = "DafeiyuWeb"
    @Volatile var webView: WebView? = null; private set
    val currentUrl = MutableStateFlow("")
    val loading = MutableStateFlow(false)
    @Volatile var allowForms = false
    @Volatile var allowLogin = false
    private var pageDone: CompletableDeferred<Boolean>? = null

    suspend fun ensure(ctx: Context): WebView = withContext(Dispatchers.Main) {
        webView ?: WebView(ctx.applicationContext).also { w ->
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = userAgentString.replace("; wv", "")
                loadsImagesAutomatically = true
            }
            w.setDownloadListener { _, _, _, _, _ -> /* downloads are never started */ }
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    return DfySafety.urlBlock(u, allowLogin) != null // block → stay on page
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!allowForms && !request.method.equals("GET", true) && !request.method.equals("HEAD", true) && !request.method.equals("OPTIONS", true)) {
                        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading.value = true; currentUrl.value = url }
                override fun onPageFinished(view: WebView, url: String) {
                    loading.value = false; currentUrl.value = url
                    if (!allowForms) view.evaluateJavascript(NO_FORMS_JS, null)
                    pageDone?.complete(true)
                }
            }
            webView = w
        }
    }

    /** Load [url], wait for it to settle, extract readable text + links. */
    suspend fun read(ctx: Context, url: String, timeoutMs: Long = 20_000): PageView {
        val w = ensure(ctx)
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { pageDone = done; w.loadUrl(url) }
        withTimeoutOrNull(timeoutMs) { done.await() }
        delay(1200) // let client-side rendering finish
        val raw = withContext(Dispatchers.Main) {
            val r = CompletableDeferred<String?>()
            w.evaluateJavascript(EXTRACT_JS) { r.complete(it) }
            r
        }.let { withTimeoutOrNull(6000) { it.await() } }
        return parse(url, raw)
    }

    /** evaluateJavascript returns a JSON-encoded string literal. */
    internal fun parse(fallbackUrl: String, raw: String?): PageView {
        val inner = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull() ?: return PageView(fallbackUrl, "", "", "", emptyList(), "页面读取失败")
        val o = runCatching { JSONObject(inner) }.getOrNull() ?: return PageView(fallbackUrl, "", "", "", emptyList(), "页面读取失败")
        val links = o.optJSONArray("l")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { PageLink(clean(it.optString("t"), 80), it.optString("u")) } } ?: emptyList()
        return PageView(o.optString("u", fallbackUrl), clean(o.optString("ti"), 120), o.optString("x").take(7000), clean(o.optString("d"), 240), links)
    }

    suspend fun stop() = withContext(Dispatchers.Main) { webView?.stopLoading(); loading.value = false }

    suspend fun destroy() = withContext(Dispatchers.Main) {
        webView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        webView = null
    }
}

/** Text-only reader for background (no WebView) runs: GET + crude HTML strip. */
object HttpReader {
    private val base by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build() }
    private fun client() = runCatching { NetworkHttp.client(NetworkHttp.DEFAULT_TAG, base) }.getOrDefault(base)
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36 FeiyuLive/0.1"

    suspend fun read(url: String): PageView = withContext(Dispatchers.IO) {
        runCatching {
            client().newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "zh-CN,zh;q=0.9").build()).execute().use { r ->
                val ct = r.header("Content-Type").orEmpty()
                if (!ct.contains("html") && !ct.contains("text")) return@withContext PageView(url, "", "", "", emptyList(), "不是网页")
                val html = r.body?.string().orEmpty().take(600_000)
                val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1).orEmpty()
                val desc = Regex("<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()
                val baseUri = Uri.parse(r.request.url.toString())
                val links = Regex("<a[^>]+href=[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)
                    .mapNotNull { m ->
                        val t = strip(m.groupValues[2]).trim(); val h = m.groupValues[1]
                        val abs = when { h.startsWith("http") -> h; h.startsWith("//") -> "https:$h"; h.startsWith("/") -> "${baseUri.scheme}://${baseUri.host}$h"; else -> null }
                        if (abs == null || t.length !in 2..80) null else PageLink(t, abs)
                    }.distinctBy { it.url }.take(60).toList()
                val body = html.replace(Regex("<(script|style|noscript|nav|footer|header|aside)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                PageView(r.request.url.toString(), clean(strip(title), 120), strip(body).replace(Regex("\\s{2,}"), "\n").take(7000), clean(desc, 240), links)
            }
        }.getOrElse { PageView(url, "", "", "", emptyList(), "打不开：" + clean(it.message, 60)) }
    }

    private fun strip(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
}

/** robots.txt (User-agent: *) + per-host pacing. Cached for the process lifetime. */
object Politeness {
    private val rules = ConcurrentHashMap<String, List<String>>()
    private val lastHit = ConcurrentHashMap<String, Long>()
    private const val HOST_GAP_MS = 5000L
    private val base by lazy { OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build() }

    suspend fun allowed(url: String): Boolean = withContext(Dispatchers.IO) {
        val u = Uri.parse(url); val host = u.host ?: return@withContext false
        val dis = rules.getOrPut(host) {
            runCatching {
                val c = runCatching { NetworkHttp.client(NetworkHttp.DEFAULT_TAG, base) }.getOrDefault(base)
                c.newCall(Request.Builder().url("${u.scheme}://$host/robots.txt").build()).execute().use { r ->
                    if (!r.isSuccessful) emptyList() else parseRobots(r.body?.string().orEmpty().take(100_000))
                }
            }.getOrDefault(emptyList())
        }
        val path = (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "")
        dis.none { it.isNotEmpty() && path.startsWith(it) }
    }

    internal fun parseRobots(txt: String): List<String> {
        val out = mutableListOf<String>(); var mine = false
        for (line in txt.lines()) {
            val l = line.substringBefore('#').trim(); if (l.isEmpty()) continue
            val k = l.substringBefore(':').trim().lowercase(); val v = l.substringAfter(':', "").trim()
            when (k) { "user-agent" -> mine = v == "*"; "disallow" -> if (mine && v.isNotEmpty()) out += v.substringBefore('*').substringBefore('$') }
        }
        return out
    }

    /** Wait until this host may be hit again. */
    suspend fun pace(url: String) {
        val host = Uri.parse(url).host ?: return
        val wait = (lastHit[host] ?: 0) + HOST_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastHit[host] = System.currentTimeMillis()
    }
}

/** Knowledge-base operations (学知识). */
object DfyKnowledge {
    fun newGoal(title: String, why: String, by: String) = LearnGoal("g-" + java.util.UUID.randomUUID().toString().take(10), clean(title, 60), clean(why, 160), System.currentTimeMillis(), by)

    suspend fun addNotes(ctx: Context, goalId: String, page: PageView, points: List<String>) {
        if (points.isEmpty()) return
        val now = System.currentTimeMillis()
        DfyStore.update(ctx) { s -> s.copy(knowledge = s.knowledge.map { g ->
            if (g.id != goalId) g else g.deepCopy().also { c ->
                if (c.sources.none { it.url == page.url }) c.sources.add(KSource(page.url, page.title, now))
                points.map { clean(it, 280) }.filter { it.length >= 6 && c.notes.none { n -> n.text == it } }
                    .forEach { c.notes.add(KNote("n-" + java.util.UUID.randomUUID().toString().take(8), it, page.url, page.title, now)) }
                c.updatedAt = now
            }
        }) }
    }

    /** Close a goal: summary + self-quiz results; distils an Insight into long-term memory. */
    suspend fun finish(ctx: Context, goalId: String, summary: String, quiz: List<KQuiz>) {
        val now = System.currentTimeMillis()
        DfyStore.update(ctx) { s ->
            val g = s.knowledge.find { it.id == goalId } ?: return@update s
            val ins = Insight("i-" + java.util.UUID.randomUUID().toString().take(10),
                "我学了「${g.title}」：${clean(summary, 160)}", "以后聊到${g.title}，我可以用学到的东西回答，也会说明出处",
                "interest", 0.6, now, tags = listOf(g.title, "学知识"), evidence = g.sources.take(5).map { it.url })
            s.copy(
                knowledge = s.knowledge.map { if (it.id != goalId) it else it.deepCopy().also { c -> c.summary = clean(summary, 600); c.quiz.addAll(quiz); c.status = "learned"; c.insightId = ins.id; c.updatedAt = now } },
                insights = s.insights + ins,
                interests = DfyInterests.record(s.interests, "goal:$goalId", g.title + " " + summary, now),
            )
        }
    }

    /** Notes relevant to a question (for quizzes and viewer questions). */
    fun lookup(s: DfyState, q: String, limit: Int = 6): List<KNote> {
        fun bg(t: String) = t.lowercase().replace(Regex("\\s+"), "").windowed(2).toSet()
        val qb = bg(q)
        return s.knowledge.flatMap { it.notes }.map { it to bg(it.text).intersect(qb).size }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
