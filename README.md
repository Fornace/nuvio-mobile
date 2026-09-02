<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Android releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest) · [Apple AI port](https://github.com/Fornace/nuvio-mobile/issues/1) · [Upstream project](https://github.com/NuvioMedia/NuvioMobile)

</div>

## Get Nuvio Mobile

- Android APKs: [NuvioMobile 0.4.13](https://github.com/NuvioMedia/NuvioMobile/releases/tag/0.4.13).
- iOS: build from source. The previous public TestFlight link has expired and the upstream GitHub
  IPA is unsigned, so neither is presented here as an installable beta.
- Apple Silicon Mac: the iOS target can run as Designed for iPad/iPhone from Xcode. A native macOS
  target and the Apple AI provider host are tracked in [issue #1](https://github.com/Fornace/nuvio-mobile/issues/1).

The Android TV build with the working AI Media Provider Center is published separately as the
[Nuvio AI developer preview](https://github.com/Fornace/nuvio-ai/releases/tag/ai-dev-2026.09.03).

## Build from source

```bash
git clone https://github.com/Fornace/nuvio-mobile.git
cd nuvio-mobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

### iOS

iOS development requires macOS and Xcode.

```bash
./scripts/prepare-ios-dependencies.sh
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Verified with Xcode 27.0 and Swift 6.4 on 2026-09-03. For a connected iPhone or an Apple Silicon
Mac destination, select that destination in Xcode and let automatic development signing use your
team.

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)
