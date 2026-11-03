# 大肥鱼人生 (DaFeiYu) — integration note

All module code lives in `src/android/app/src/main/java/com/openminis/app/mibot/dafeiyu/` (28 files) and
`src/android/app/src/main/assets/mibot/dafeiyu/` (`persona_default.md`, `NOTICE.md`). Nothing outside those
two directories was edited, committed or pushed. The edits below are what the main tree needs. Each one is small.

Headline screen = **直播间** (`DfyRoutes.LIVE = "mibot/dafeiyu"`): off-air it is the 开播台 (mode 冲浪/学知识,
topic, comment sources, voice). On-air it is the room (WebView + trail, avatar + subtitles, chat/event feed, viewer input,
test-event chips). The interaction engine is `DfyLive` (DfyLiveEngine.kt). Run it with **模拟观众** for local testing.

---

## 1. Gradle — RootEncoder (RTMP)  `src/android/app/build.gradle.kts`

```kotlin
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // [fork/mibot-dafeiyu] RTMP push (pedroSG94/RootEncoder, Apache-2.0). Pinned to 2.6.1:
    // built with Kotlin 2.1.20, which this app's Kotlin 2.1.0 compiler can read. 2.6.4+ use
    // Kotlin 2.2.x and 2.8.x uses 2.4.x (metadata too new for this compiler).
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.1")
```
`settings.gradle.kts` already lists `maven { url = uri("https://jitpack.io") }`, and the jitpack POM for 2.6.1 answers HTTP 200.
Only `DfyRtmpPush.kt` imports `com.pedro.*`. If you need to ship without the dependency, delete that one file and the
two `DfyRtmp.*` call sites in `DfyLiveScreen.kt` (the 推流 page). Release/R8: RootEncoder has no reflection, so no keep rules
are expected. Re-check if minify complains.

## 2. Manifest  `src/android/app/src/main/AndroidManifest.xml`

The permissions already present (INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, SYSTEM_ALERT_WINDOW,
POST_NOTIFICATIONS) cover most of it. Add:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<!-- [fork/mibot-dafeiyu] 抖音 RTMP push (canvas = mediaPlayback; screen-capture fallback = mediaProjection) -->
<service
    android:name="com.openminis.app.mibot.dafeiyu.DfyStreamService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback|mediaProjection" />
<!-- [fork/mibot-dafeiyu] floating whale / mini live window (TYPE_APPLICATION_OVERLAY) -->
<service
    android:name="com.openminis.app.mibot.dafeiyu.DfyOverlayService"
    android:exported="false" />
```
WorkManager needs no manifest entry because the app uses the default initializer and no custom `WorkerFactory` was found.

## 3. Navigation  `ui/navigation/AppNavigation.kt`

Add the import `import com.openminis.app.mibot.dafeiyu.dafeiyuGraph`, then inside the `NavHost { … }` builder, next to
the plugin-market routes:
```kotlin
        // [fork/mibot-dafeiyu] 大肥鱼人生: 直播间 (headline) + prelive/home/timeline/memory/knowledge/viewers/chat/settings/pending/bili-login
        dafeiyuGraph(navController)
```
The graph uses `navController.navigate(route) { launchSingleTop = true }` and `popBackStack()` directly, not `safeNavigate`.

Settings action wiring (where `plugins = go(com.openminis.app.mibot.plugins.PluginRoutes.MARKET)` is):
```kotlin
                        dafeiyu = go(com.openminis.app.mibot.dafeiyu.DfyRoutes.LIVE),
```

## 4. Settings entry  `mibot/settings/MibotSettingsScreen.kt`

```kotlin
// MibotSettingsActions: add
    val dafeiyu: () -> Unit = {},
// apps grid: add after 插件市场
                    MibotGridItem(t("大肥鱼", "DaFeiYu"), Icons.Outlined.LiveTv, MibotTokens.Sky, a.dafeiyu),
```
(`Icons.Outlined.LiveTv` comes from material-icons-extended, which is already a dependency. If `MibotIcons.lineFor` has
no mapping it falls back to the Material glyph.) You can also add a row under "Bots":
`MibotRow(t("大肥鱼 · 直播间", "DaFeiYu live room"), t("虚拟主播冲浪、学知识、和弹幕互动", "Virtual streamer: surf, learn, chat"), Icons.Outlined.LiveTv, MibotTokens.Sky, onClick = a.dafeiyu, showDivider = true)`.

## 5. Deep links  `deeplink/DeepLinkHandler.kt`

In the host `when` (next to `"settings" -> parseSettingsPath(uri)`):
```kotlin
            "dafeiyu" -> com.openminis.app.mibot.dafeiyu.DfyRoutes.routeFor(uri)
                ?.let { DeepLinkAction.OpenSettingsScreen(it) } ?: DeepLinkAction.Unknown
```
Notifications and the overlay use `minis://dafeiyu/live|home|chat|pending` (knowledge and settings also map).
Optional alias inside `parseSettingsPath`: `"dafeiyu" -> DeepLinkAction.OpenSettingsScreen(com.openminis.app.mibot.dafeiyu.DfyRoutes.LIVE)`.

## 6. App start  `MinisApp.onCreate()`

```kotlin
        // [fork/mibot-dafeiyu] re-arm the B站 life loop (no-op when off) and the floating whale (if enabled)
        runCatching { com.openminis.app.mibot.dafeiyu.DfyWorker.sync(this) }
        runCatching { com.openminis.app.mibot.dafeiyu.DfyOverlay.refresh(this) }
```
`DfyWorker.sync` reads JSON from filesDir synchronously, which is a few KB. Move it into the existing background init
block if there is one.

## 7. Agent tool (main chat)

`tools/AgentTools.kt`, inside `if (!isHelper) { … }`:
```kotlin
            add(com.openminis.app.mibot.dafeiyu.DfyTools.definition())
```
`ui/chat/ChatViewModel.kt`, `executeTool(...)` `when (name)` block:
```kotlin
            com.openminis.app.mibot.dafeiyu.DfyTools.NAME ->
                return com.openminis.app.mibot.dafeiyu.DfyTools.execute(context, argsJson)
```
Tool `dafeiyu_life` actions: status, today, recall, knowledge, add_todo (search/note/live/learn), ask, live_say, run_now.

## 8. 抖音 comment reader (accessibility hook)  `accessibility/MinisAccessibilityService.kt`

At the end of `onAccessibilityEvent(event)`:
```kotlin
        // [fork/mibot-dafeiyu] read the 抖音 live comment list only while the user enabled that source in the 直播间
        if (com.openminis.app.mibot.dafeiyu.DouyinA11yReader.enabled &&
            event.packageName?.toString() == com.openminis.app.mibot.dafeiyu.DouyinA11yReader.PKG &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            runCatching { com.openminis.app.mibot.dafeiyu.DouyinA11yReader.onWindow(rootInActiveWindow) }
        }
```
The existing config (`typeAllMask`, `canRetrieveWindowContent=true`) is enough. Throttle it if 抖音 floods events
(e.g. at most one walk per 400 ms).

## 9. Licences  `THIRD_PARTY_LICENSES.md` (Android section)

```markdown
| [dsh-dafeiyu-life](https://github.com/minedalang/dsh-dafeiyu-life) | ideas + ported logic (v2.6.0, 2026) | **MIT** — © 2026 c1overzz | 大肥鱼人生 module (`mibot/dafeiyu`): default persona prompt (adapted), mood model (8 moods, 18 h half-life, effect table), interest stages & topic table, memory decay stages/formula, reflection quality rules. Re-implemented in Kotlin; full MIT text in `assets/mibot/dafeiyu/NOTICE.md` |
| [RootEncoder](https://github.com/pedroSG94/RootEncoder) | 2.6.1 (JitPack) | **Apache-2.0** | RTMP push (H.264/AAC) for 大肥鱼 live streaming (`DfyRtmpPush.kt`) |
```
Edge "Read aloud" TTS is a remote service, not bundled code. Mention it in the privacy/about text as
"optional, unofficial Microsoft Edge speech endpoint". The avatar is drawn procedurally, so there are no art assets to license.

## 10. CI smoke (`.github/ci/smoke.sh`), screenshots

After the deep-link edit (§5) lands:
```bash
# 大肥鱼: 直播间 off-air (开播台), then a 20 s local broadcast with simulated viewers, then home/settings
adb shell am start -W -a android.intent.action.VIEW -d "minis://dafeiyu/live" $PKG >/dev/null; zshot dfy-live-offair 4
adb shell am start -W -a android.intent.action.VIEW -d "minis://dafeiyu/home" $PKG >/dev/null; zshot dfy-home 3
adb shell am start -W -a android.intent.action.VIEW -d "minis://dafeiyu/settings" $PKG >/dev/null; zshot dfy-settings 3
adb shell am start -W -a android.intent.action.VIEW -d "minis://dafeiyu/knowledge" $PKG >/dev/null; zshot dfy-knowledge 3
```
Going on-air from CI needs a tap on 开播 (CI demo keys are fake, so she would use the canned fallback lines and system TTS).
If you want an automated on-air shot, add an intent extra (e.g. `--ez ci_dafeiyu_live true` → `DfyLive.start(ctx, "surf", "鲸鱼", setOf("sim"))`
in MainActivity's CI hook) and then `zshot dfy-live-onair 20`. Grep logcat for `DafeiyuLive|DafeiyuWeb|DafeiyuTts` and expect 0 FATAL.

---

## Data & privacy facts (for review)
- State: `filesDir/dafeiyu/*.json` (settings, mood, episodes, pending, insights, interests, picks, live, todos, chat,
  actions, sessions, knowledge, viewers, meta). Writes are atomic (tmp + rename) and sizes are capped. Backup export/import is
  in 设置 › 备份. Backups never contain secrets, and an import turns write permissions off.
- Secrets: B站 cookie (`mibot_dafeiyu_bili`), 抖音 推流地址/推流码 and the TTS API key (`mibot_dafeiyu_secrets`) are stored in
  EncryptedSharedPreferences (AES-256, Keystore). If the keystore fails, nothing is persisted.
- Browsing WebView: JS on, file/content access off, no geolocation, no popups, no downloads, and non-http schemes refused.
  Every non-GET request gets a local 204 unless 设置 › 允许网页表单提交 is on. Login/checkout/payment paths are skipped,
  adult/gambling hosts are blocked, robots.txt is honoured and each host is limited to one hit per 5 s. Captcha pages
  are detected and abandoned, never solved.
- No 录播: RTMP refuses to start unless she is live and stops by itself when she goes off-air.
