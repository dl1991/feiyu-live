# 大肥鱼直播 (FeiyuLive)

Android live-streaming assistant app, package `com.dl1991.feiyu`.

## Build

CI (GitHub Actions, `.github/workflows/android.yml`) builds a debug APK on every push to `main`
and uploads it as the `feiyu-live-debug-apk` artifact.

Locally: JDK 17 + Android SDK (platform 36, build-tools 35.0.0), then

```
./gradlew :app:assembleDebug
```

## Agent: 直播专家组 (v0.4)

大肥鱼 streams on her own and grows from it. Every few seconds (and on new comments) one orchestrator
tick runs a crew of small experts on the same model (a different provider per expert is optional):
弹幕运营 (picks comments, spots regulars) → 研究员 (page → talking points) → 导演 (inner thought, goal,
≤3 JSON tool calls) → 编剧 (her lines) → 安全审核 (rule filter always on + model review) → 主播 speaks
(TTS + caption, or the 真人接管 approval queue). After each segment / stream the 复盘官 scores engagement
per topic, updates her goals, interests, skills and regulars, writes a diary, and proposes the next 节目单
(editable in 「她的成长」). Any expert can be switched off (rules take over); repeated 导演 failures or
the tokens/hour cap fall back to the simple engine. Code: `FyCrew.kt`, `FyAgentTools.kt`, `FyLifeState.kt`,
`FyAgent.kt`; JVM tests in `FyAgentToolsTest` / `FyCrewTest` (scripted mini-stream with a fake model).

## Avatar

Default avatar: 「二次元大肥鱼」 sprite set (`app/src/main/assets/dafeiyu/avatar/`, `manifest.json`
maps expressions → PNGs, plus blink / mouth overlay patches). Original AI-generated art; 大肥鱼 is a
community DeepSeek fan persona, not official DeepSeek art (see `THIRD_PARTY_LICENSES.md`).
Alternatives in 直播内容 › 主播形象: 「简笔鱼」 (procedural) or 「自己的立绘」 (import a .zip with the
same manifest + PNG names).

## License

GPL-3.0 (see `LICENSE`). Third-party notices: `THIRD_PARTY_LICENSES.md`.
