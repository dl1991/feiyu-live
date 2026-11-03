# 大肥鱼直播 (FeiyuLive)

Android live-streaming assistant app, package `com.dl1991.feiyu`.

## Build

CI (GitHub Actions, `.github/workflows/android.yml`) builds a debug APK on every push to `main`
and uploads it as the `feiyu-live-debug-apk` artifact.

Locally: JDK 17 + Android SDK (platform 36, build-tools 35.0.0), then

```
./gradlew :app:assembleDebug
```

## License

GPL-3.0 (see `LICENSE`). Third-party notices: `THIRD_PARTY_LICENSES.md`.
