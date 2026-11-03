# 大肥鱼直播 (FeiyuLive)

Android live-streaming assistant app, package `com.dl1991.feiyu`.

## Build

CI (GitHub Actions, `.github/workflows/android.yml`) builds a debug APK on every push to `main`
and uploads it as the `feiyu-live-debug-apk` artifact.

Locally: JDK 17 + Android SDK (platform 36, build-tools 35.0.0), then

```
./gradlew :app:assembleDebug
```

## Avatar

Default avatar: 「二次元大肥鱼」 sprite set (`app/src/main/assets/dafeiyu/avatar/`, `manifest.json`
maps expressions → PNGs, plus blink / mouth overlay patches). Original AI-generated art; 大肥鱼 is a
community DeepSeek fan persona, not official DeepSeek art (see `THIRD_PARTY_LICENSES.md`).
Alternatives in 直播内容 › 主播形象: 「简笔鱼」 (procedural) or 「自己的立绘」 (import a .zip with the
same manifest + PNG names).

## License

GPL-3.0 (see `LICENSE`). Third-party notices: `THIRD_PARTY_LICENSES.md`.
