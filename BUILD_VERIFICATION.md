# Build verification

Date: 2026-07-24
Version: 10.1.0 (`versionCode` 1010)
Variants: sideload target 28; modern target 37

## Executed in this environment

- XML parse: pass.
- JSON parse: pass.
- Java lexical/delimiter verification: pass.
- Whole-tree `javac` parser pass (Android symbols intentionally unresolved): pass.
- Full Java source semantic compilation against local Android/JSON verification stubs: pass.
- Required module inventory: pass.
- `LocalWebServer` pure JVM compilation: pass.
- HTTP 200/content test: pass.
- HTTP byte range 206 test: pass.
- encoded path traversal rejection: pass.
- `ZipUtil` pure JVM compilation: pass.
- ZIP pack/extract round trip: pass.
- Zip Slip rejection: pass.
- SQL permission classifier: multi-statement bypass rejection: pass.
- SQL permission classifier: write-CTE bypass rejection: pass.
- SQL destructive classifier string-literal false-positive test: pass.
- AI database read channel uses DB-enforced read-only mode: source verified.

## Android APK build

The active execution container contains only an empty SDK placeholder and no downloadable Android platform/Gradle artifacts and cannot perform a trustworthy Android APK compile locally. The included GitHub Actions workflow performs source checks, JVM smoke tests, Android lint and `assembleSideloadDebug`/`assembleModernDebug` in a configured Android environment.

A source check is not presented as proof that the APK has been built or installed on a real device.
