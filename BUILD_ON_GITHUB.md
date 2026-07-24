# Build with GitHub Actions

1. Create an empty GitHub repository.
2. Extract this archive and upload all files, including `.github/workflows/build-android.yml`.
3. Open **Actions → Build OpenCode Go Agent APK → Run workflow**.
4. Download the `OpenCode-Go-Agent-v11.0` artifact after the workflow completes.

The artifact contains two debug APK variants, SHA-256 files, lint reports, and a verified source archive.

- `app-sideload-debug.apk`: Android 8+ sideload build.
- `app-modern-debug.apk`: target-SDK-37 build with a separate application ID suffix.

Third-party ARM64/Bionic runtime binaries for PHP, Node.js, Python, MariaDB and PostgreSQL are not bundled in this source archive. Static hosting and SQLite work without them; server runtimes require compatible runtime packs.
