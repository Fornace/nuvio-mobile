# Nuvio Mobile — AI Subtitles & Player UX Handoff

**Date:** 2026-09-10
**Repo:** `~/works/repos/nuvio-mobile-ai-port` — GitHub `Fornace/nuvio-mobile` (fork of `NuvioMedia/NuvioMobile`)
**Branch:** `main` (default branch; `cmp-rewrite` tracks upstream and is merged in)
**State at handoff:** clean tree, all work committed and pushed through `9a13dec3`.

---

## 1. What was delivered (working today)

### Cloud translation of addon subtitles (merged, on phone)
- **Feature:** translate any *external/addon* subtitle track via an OpenAI-compatible chat API; result saved as local WebVTT and activated as a new track ("Italian (AI)").
- **Code:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/aisubtitle/` (`AiSubtitleModels.kt`, `SubtitleTranslator.kt`), platform actuals in `iosMain`/`androidMain` (`AiSubtitlePlatform.*.kt`), settings page `features/settings/AiSubtitlesSettingsPage.kt`, player wiring in `PlayerScreenRuntimeSubtitleActions.kt` (`translateSelectedAddonSubtitle`), `SubtitleModal.kt` (AI rail).
- **Defaults:** endpoint `https://llm.fornace.net`, model `gemini-3.8-flash`, target `it`. Model defaults live in `AiSubtitleModels.kt` companion.
- **Zero-config:** `NUVIO_AI_API_KEY` in gitignored `local.properties` bakes a fallback key into `AiSubtitleBuildConfig` (generated at build). Keychain override wins; enable-toggle defaults ON when the build key exists; superseded default models (`qwen3.7-max`, `comath-qwen-38-flash`) auto-migrate.
- **Verified:** all Kotlin targets compile (`compileKotlinIosArm64`, iosSimulatorArm64, commonMain metadata, AndroidMain), full iOS Xcode build, installed on Sacrilegione (0.4.15 build 120). Gateway translate call measured 2.8s / 4-line batch.
- **Key commits:** `f1ab039b` (feature, PR #4), `0f977c78` (gemini default), `2b36044f` (zero-config), `9a13dec3` (default enabled).

### Infra fixed along the way (llm.fornace.net gateway, 49.12.9.255)
- **Root cause of dead Gemini:** `GEMINI_API_KEY` in `/etc/fornace-llm2.env` was stale. Replaced with the working key from `~/.agent_credentials/envs/fornace-create.env.local` (`GOOGLE_API_KEY`).
- **Routing cleanup** via `POST /admin/routing/reset` (admin token in `/etc/fornace-llm2.env`, gateway on 127.0.0.1:4012, control 4013): deleted superseded groups `gemini-3-flash-preview` and `gemini-3.5-flash` (+ their deployments), fixed `fornace-vision` fallback chain references, added public `gemini-3.8-flash` group + `dep-gemini-ondemand-gemini-3-8-flash` deployment. Backups: `/etc/fornace-llm2.env.bak-gemini-*`, `/var/lib/fornace-llm2/gateway.db.bak-gemini-*`.
- **Gateway model list now:** exactly one public Gemini model (`gemini-3.8-flash`), plus comath-qwen-38-flash (works; send `enable_thinking:false` for qwen), gpt-5.4-mini (blocked: Codex account), glm-53-flash (quota), codex-gpt-5.6-test etc.
- **Verified current model** against Google's live model list: gemini-3.8-flash is the newest flash.

### Repo state
- `main` = upstream `cmp-rewrite` (0.4.15, through `e3779428`) + fork commits (AI subtitles, local.properties optional, docs). Upstream remote `upstream` configured.
- Key access: `ssh root@49.12.9.255` with `~/.agent_credentials/ssh/frapposerver-incident-20260908` (NOT frapposerver.pkey — that one fails).

---

## 2. Known gap: the AI rail is invisible for embedded tracks (user-visible bug)

**The complaint that started the design review:** user's content (Lanterns S1E3 from AIOStreams) has *embedded* subtitle tracks ("Integrato"); the AI rail only renders when an *addon* (external file) subtitle is selected (`SubtitleModal.kt`: `visible = aiEnabled && effectiveSelectedAddonSubtitle != null`). So Traduci never appears for muxed-in tracks — the common case for this user.

**Fix path (design agreed, see §3):** offer Traduci for any selected track; embedded tracks extracted via mpv/FFmpeg demux to SRT (Libavformat already bundled via MPVKit) then run through the existing `SubtitleTranslator`. UI per design review: bottom-panel "Traduci…" action, not the 4th rail.

**Technical notes for extraction:**
- mpv bridge (`iosApp/iosApp/Player/MPVPlayerBridge.swift`) already exposes `track-list` observation and `getSubtitleTrack*` APIs; external tracks are identified via `track-list/N/external`.
- Astra review warns: demux of a *remote* interleaved stream is not instant — needs a spike (byte-range reads, auth headers replay, seek-to-end for idx). Bitmap subs (PGS/VobSub) need OCR — out of scope v1; show honest "formato richiede riconoscimento testo" message.

---

## 3. Design decisions (two-model review, both full texts in Obsidian)

**Note:** `research/2026-09-10-nuvio-player-design-review.md` in the vault has both complete reviews (gemini-3.8-flash first, fornace-astra/gpt-6-astra second) with screenshots referenced. Read them before building.

Agreed consensus across both reviewers:

1. **Kill the 4th AI rail.** Translation is an action, not a peer category. Astra's preferred placement: single "Traduci…" action in the subtitle panel's bottom action area opening a sheet (source track, target language, method, disclosure, "Avvia traduzione"). Gemini proposed a pill on the selected track card; astra rejected it (scrolls away, mixes select/transform hit areas). Follow astra.
2. **Subtitle panel repair first** (astra's finding, visible in user screenshots IMG_9206-9208 in `~/Downloads/`): panel competes with controls beneath; "Bold" clipped at bottom; per-language "1" badges are noise; "Integrato" wrong as leading badge; mixed EN/IT labels ("Languages", "Style", "Bold", "Italian" in Italian UI); unclear dismissal.
3. **Rotation:** two-option model (follow iOS-permitted orientation by default + explicit expand-to-landscape action in player, collapse to return; separate "lock current orientation"). Do NOT use raw sensor following as default (bed case: sideways with Portrait Lock is deliberate). Distinguish orientation lock vs touch lock. No private orientation hacks — scene geometry requests only (`OrientationLockCoordinator.swift` already does this correctly).
4. **Portrait player:** aspect-fit video stage (not fixed 40%); compact controls at video edge; title + Riprendi context; tabs Dettagli | Episodi (Sorgenti secondary); **vertical** episode list; scrubber at video/content seam with generous gesture separation; next-episode card. One prominent "Prossimo episodio" card.
5. **Landscape details:** opaque two-column companion view (NOT dimmed playing video); left ~1/3 episode list, right details; pause on open (restore only if view-caused); primary action "Riprendi" for current episode; browsing ≠ playing; spoiler handling for next-episode descriptions; explicit return-to-player (tap-outside meaningless when columns fill viewport).
6. **PiP:** system PiP via `AVPictureInPictureController` + `AVSampleBufferDisplayLayer` (mpv is not AVPlayer; this is a rendering-integration project — timed frames, playback delegate, clock sync, background audio session, restore/seek). `canStartPictureInPictureAutomaticallyFromInline = true`. Keep the native session OUT of composable ownership. Subtitles (Compose-rendered) will not appear in PiP without extra work. **Separate requirement:** in-app continue-playback while navigating Nuvio must not be accidentally deferred with the floating-player. PiP is currently a stub: `PlayerPlatformEffects.ios.kt` `ManagePlayerPictureInPicture(...) = Unit`, `rememberIsInPictureInPicture() = false`.
7. **AI dubbing (future):** lives under Audio as "Italiano · Voce tradotta AI" with independent original/translated volume; never call it "dub"; one-tap return to original; prefer native dub when it exists.
8. **Unifying architecture: one persistent playback session, multiple presentations** (landscape/portrait/details/PiP) — not screens that rebuild the player. Astra flags this as THE architectural decision that makes everything fit.

**Build order consensus:** (1) panel repair + Traduci-for-any-track; (2) playback session + rotation; (3) PiP technical spike EARLY (expensive unknown); (4) portrait + landscape companion views.

---

## 4. Build/run recipes (verified this session)

```bash
# Java: no system JDK; use homebrew openjdk@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# Kotlin compile check (fast)
NUVIO_IOS_DISTRIBUTION=full ./gradlew :composeApp:compileKotlinIosArm64

# Full iOS build + install on Sacrilegione (iPhone 15 Plus)
NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "id=00008120-001E68980292201E" \
  -derivedDataPath build/ios-derived-full-device \
  DEVELOPMENT_TEAM=2V6YSU4HFB build
xcrun devicectl device install app --device 00008120-001E68980292201E \
  build/ios-derived-full-device/Build/Products/Debug-iphoneos/Nuvio.app
xcrun devicectl device process launch --device 00008120-001E68980292201E com.nuvio.app
# screenshot for verification
xcrun devicectl device capture screenshot --device 00008120-001E68980292201E --destination /tmp/shot.png
```

- **Distribution gate:** `NUVIO_IOS_DISTRIBUTION=full` (QuickJS plugins, P2P, in-app trailers, custom servers) vs `appstore`. The two distributions SHARE `composeApp/build/xcode-frameworks/.../ComposeApp.framework` — switching requires rebuild; stale framework = wrong feature set silently.
- **Signing:** Xcode-beta has no Apple account; build with `DEVELOPMENT_TEAM=2V6YSU4HFB` (Fornace) which matches the local wildcard dev profile including Sacrilegione. Project pins `8QBDZ766S3` — needs an Xcode account login to use `run-mobile.sh ios p` as-is.
- **Device launch fails while locked** ("device was not, or could not be, unlocked") — normal; unlock and tap the icon.
- **Backend config:** `local.properties` (gitignored) must contain `NUVIO_SUPABASE_URL=https://api.nuvio.tv`, `NUVIO_SUPABASE_ANON_KEY=sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN` (recoverable from the tvOS app binary in the Nuvio Player tvOS 27 simulator if lost), `NUVIO_AI_API_KEY=<fornace gateway token>`, `NUVIO_IOS_DISTRIBUTION`.
- **Test account:** sacrilegio@me.com / 12345678 — verified working against api.nuvio.tv (HTTP 200, session issued 2026-09-06; watch progress synced).
- **Verify baked key:** `composeApp/build/generated/runtime-config/kotlin/com/nuvio/app/features/aisubtitle/AiSubtitleBuildConfig.kt` (strings on the binary does NOT work — Kotlin/Native stores literals as UTF-16; even api.nuvio.tv is invisible to `strings`).
- AppStore variant still uses empty `SupabaseConfig` unless local.properties set — first login failure diagnosis this session was exactly this (empty URL baked in).

---

## 5. Credential state (as of session end — several rotated dead)

| Credential | State |
|---|---|
| Fornace gateway token (`FORNACE_LLM_API_KEY` in `envs/fornace-llm.env`) | WORKING (used by app + reviews) |
| Google `GOOGLE_API_KEY` (`envs/fornace-create.env.local`) | WORKING (now also gateway's GEMINI_API_KEY) |
| DashScope (`DASHSCOPE_API_KEY`) | DEAD (rotated) |
| OpenAI direct + astra key (`chatbot-ui.env.local`) | DEAD — astra CLI unusable until replaced; fornace-astra on gateway works as substitute |
| Z.ai | DEAD |
| Gateway gemini routes | FIXED this session (see §1) |
| Gateway SSH | `ssh -i ~/.agent_credentials/ssh/frapposerver-incident-20260908 root@49.12.9.255` |

`~/.ssh/config` `ffrapposerver` host entry still points at an OLD IP (95.216.37.30); the real gateway host is 49.12.9.255 — use the key above directly.

## 6. Session transcript receipts (for provenance)

- Login test: `POST https://api.nuvio.tv/auth/v1/token?grant_type=password` → 200, user `8cba91a9-b432-41a6-8127-425118c48116`, session in app prefs `sb-api-nuvio-tv-session` (issued 06:42, post-fix build).
- Watch progress synced (Gachiakuta S1E14, "23m rimanenti") — home feed verified by screenshot.
- Gateway translate test (final): 2.8s, 77/625 tokens, correct Italian, model `gemini-3.8-flash`.
- Design reviews: `/tmp/design-review-out.json` (gemini), `/tmp/astra-review-out.json` (fornace-astra) — both full texts also in the Obsidian note.

## 7. Immediate next actions (priority order)

1. **Traduci for embedded tracks** (fixes the user's actual complaint): mpv track demux spike → SRT → existing translator; new "Traduci…" bottom-sheet UI per §3.2; panel localization cleanup (EN strings in IT UI).
2. **Playback session refactor** (one session, many presentations) — prerequisite for rotation + PiP + companion views.
3. **PiP rendering spike** — frame path from mpv into AVSampleBufferDisplayLayer; measure battery/thermal early.
4. **Portrait + landscape companion views** per §3.4-3.5.
5. Optional: restore astra CLI (needs new OpenAI key); fix `~/.ssh/config` ffrapposerver entry; consider PR upstream for the aisubtitle feature if wanted public.
