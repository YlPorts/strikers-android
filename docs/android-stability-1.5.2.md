# Android 1.5.2 stability pass

Base: `android-v1.5.1-touch-compat` at `9f225d8530c57cf0e3f997db865b50c21d81925e`.

This pass preserves the existing engine, renderer, internal resolution defaults,
audio buffering, game data loading, package ID and game logic.

## Changes

- Touch controls remain visible by default. The launcher offers an explicit option
  to hide them when a physical controller is detected, retaining controller support
  without trusting every device's gamepad classification.
- Button centers respect cutouts, visible system bars and mandatory gesture areas.
  Stored positions remain proportional to the full screen and are clamped only
  for display/hit testing, so a temporary rotation does not overwrite the layout.
- Touch input clears on focus loss, pause, resize, inset changes, cancel and detach.
  Overlay recovery is event driven with bounded retries; callbacks stop on pause
  and teardown. An SDL library-load failure no longer continues into touch JNI.
- The settings button uses an explicit visibility flag. Its timeout cannot remove
  the editor's OK button. Settings scroll on small screens and dismiss on teardown.
- Touch preferences migrate once to a file owned by the game process, avoiding
  overwrites from the launcher's separate SharedPreferences cache.
- The native bridge publishes the entire input state in one lock-free 64-bit atomic
  instead of seven independent atomics. Repeated identical input does not call JNI.
- Font metrics are reused during drawing, redundant overlay layout requests are
  removed, and release builds no longer inject per-trigger cinematic tracing.
- The exact random-dialogue bounds correction already applied by the 1.5.1 workflow
  now lives in CharacterAudio.cpp. Other build paths receive the same Wario/CHAR_CLAP
  fix without a source mutation in CI.
- Duplicate launcher taps are ignored while a launch is pending. A completed
  bootstrap does not start the game after its Activity has been closed.

## Verification

`android-native` runs the following gates before packaging the release APK:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=undefined \
  android/tests/touch_state_test.cpp -o /tmp/touch-state-test
/tmp/touch-state-test
gradle --no-daemon -p android :app:testReleaseUnitTest
gradle --no-daemon -p android :app:assembleRelease
```

Robolectric tests run the actual touch View on API 26 and 35. They cover concurrent
joystick/button input, independent release, cancellation, focus loss, resize,
detach, safe bounds on four screen sizes, settings teardown, editor save visibility,
preference migration and unchanged-input suppression. Native tests cover every
signed axis value, trigger/button bounds and concurrent snapshot publication.
The separate ARM64 native scan also remains enabled.

Compilation and simulated Android tests do not certify GPU-driver behavior or
long-session stability on physical phones. Device checks still needed: opening a
match, Wario/Mario introductions, repeated background/resume, rotating in both
landscape directions, controller connect/disconnect with each visibility setting,
and saved custom layouts. No measured FPS improvement is claimed.

## Signing and upgrade compatibility

The CI APK uses the inherited debug signing configuration. The delivered APK is
re-signed offline with the existing StrikersAndroid 1.5 release key (never stored
in this repository). Release certificate SHA-256:

`e505aded7ce3985c69e4005cf489d33641a7ad13d4f55c7a62a64e57141a9471`

The saved 1.5 APK has this same certificate and versionCode 150. This build uses
versionCode 152 so Android can accept it as an update. The delivered 1.5.1 APK
instead used a temporary Android Debug certificate:

`2b123c8a842685e135175f50cff793584b72d69da95561b4dd7b36db462f685e`

The 1.5 release key cannot update that differently signed 1.5.1 installation in
place. Keep the installed app and its saves if Android rejects the update; do not
uninstall just to bypass the signature check.
