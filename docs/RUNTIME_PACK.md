# ARM64 Runtime execution

Android 10 introduced a W^X restriction: apps targeting API 29+ cannot call `execve()` on binaries stored in their writable app home directory. The project therefore builds two distributions.

## Sideload build

- variant: `sideloadDebug` / `sideloadRelease`;
- application ID: `com.qandil.opencodego`;
- target SDK: 28;
- intended for direct APK installation;
- accepts manifest- and SHA-256-verified ZIP runtime packs in app-private storage;
- executes Android Bionic `arm64-v8a` binaries without requiring the Termux app.

The minimum device version remains Android 8.0 / API 26. This variant is not intended for Google Play submission because of its legacy target SDK.

## Modern build

- variant: `modernDebug` / `modernRelease`;
- application ID: `com.qandil.opencodego.modern`;
- target SDK: 37;
- downloaded executables are rejected;
- native runtime executables and every executable shared library must be embedded in the APK under `app/src/main/jniLibs/arm64-v8a`.

Embedded executable naming convention:

```text
liboc_<runtime>_<executable>.so
```

Examples:

```text
liboc_php_php.so
liboc_node_node.so
liboc_python_python3.so
liboc_mariadb_mariadbd.so
liboc_mariadb_mariadb.so
liboc_postgres_postgres.so
liboc_postgres_psql.so
liboc_postgres_initdb.so
liboc_postgres_pg_dump.so
```

The files may be PIE executables despite the `.so` packaging suffix. The Android build is configured to extract and not strip `liboc_*.so`. All runtime dependencies needed as executable code must also be trusted APK assets/native libraries; writable downloaded `.so` files are not a valid modern-runtime solution.

Official Android restriction reference: <https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission>

# ZIP runtime pack format (sideload build)

A runtime pack is a ZIP archive containing `runtime.json`, `bin/` and optional `lib/`, `etc/`, `share/` directories.

Example:

```text
runtime.json
bin/php
lib/libcrypto.so
lib/libssl.so
etc/php.ini
```

Example `runtime.json`:

```json
{
  "format": 1,
  "name": "php",
  "version": "8.x",
  "abi": "arm64-v8a",
  "minSdk": 26,
  "executables": {
    "php": "bin/php"
  },
  "sha256": {
    "bin/php": "<64-character SHA-256>"
  }
}
```

Supported pack names:

`php`, `node`, `python`, `git`, `composer`, `npm`, `mariadb`, `postgres`, `redis`, `ssh`.

Requirements:

- executables must target Android Bionic, not glibc;
- ABI must be `arm64-v8a` or `aarch64`;
- minimum SDK must not exceed the device API level;
- every entry listed in `sha256` is verified before activation;
- paths are canonicalized and cannot escape the staging directory;
- executable bits are applied only to files declared in `executables`.

MariaDB and PostgreSQL need the additional CLI tools listed in [DATABASES.md](DATABASES.md).
