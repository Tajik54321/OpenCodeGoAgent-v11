# Final source status — 11.0.0

Date: 2026-07-24

This archive is the completed source candidate for OpenCode Go Agent Server Studio. It is not represented as an already-built or device-tested APK.

## Completed in source

- Native Android application shell and all declared activities/services.
- Project creation/import/export, file manager, editor, terminal and process supervision.
- Static localhost hosting plus PHP/Node/Python launch adapters.
- SQLite Database Center and runtime-backed MariaDB/PostgreSQL management, CRUD, backup and restore.
- AI provider hub for OpenAI-compatible, Anthropic and Gemini APIs.
- Tool-calling agent with project-scoped permissions, encrypted secrets, audit timeline and checkpoints.
- Conservative SQL permission classifier plus database-enforced read-only AI query channels.
- Sideload and modern Android product flavors reflecting Android executable-code restrictions.
- GitHub Actions build, lint, verification, APK checksum and source packaging workflow.

## Binary dependencies intentionally not fabricated

The source archive does not contain third-party PHP, Node.js, Python, MariaDB or PostgreSQL ARM64/Bionic binaries. The sideload flavor accepts manifest/SHA-256 verified runtime packs; the modern flavor requires reviewed native executables to be packaged inside the APK. Static hosting and SQLite do not require these packs.

## Verification boundary

The available execution container has no usable Android platform/build-tools installation and cannot download it. Therefore no APK is claimed as compiled, signed, installed or tested on a physical Android device in this environment. The included CI workflow is configured to perform the real Android build on a runner with SDK access.
