# Build

## Local

Requirements:

- JDK 17;
- Android SDK Platform 37 and Build Tools 36.0.0;
- Gradle 9.5;
- network access to Google's and Maven's artifact repositories for the first build.

Run:

```bash
./build-local.sh
```

Default local output (sideload build):

```text
app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
app/build/outputs/apk/sideload/debug/app-sideload-debug.apk.sha256
```

Build the modern variant separately with:

```bash
gradle :app:assembleModernDebug :app:lintModernDebug
```

## GitHub Actions

`.github/workflows/build-android.yml` performs:

1. source/XML/JSON verification;
2. pure JVM localhost and ZIP smoke tests;
3. full Java source compile against local verification stubs;
4. Android SDK installation;
5. Android lint for both distributions;
6. sideload and modern debug APK builds;
7. APK and source SHA-256 generation;
8. artifact upload.

A production release must use a private release keystore and should ship verified runtime packs separately or as trusted app assets after license review.
