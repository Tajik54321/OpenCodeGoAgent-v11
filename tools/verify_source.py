#!/usr/bin/env python3
from __future__ import annotations
import json, os, re, subprocess, sys, tempfile, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
errors: list[str] = []
warnings: list[str] = []

for path in ROOT.rglob("*.xml"):
    if any(part in {"build", ".gradle"} for part in path.parts):
        continue
    try: ET.parse(path)
    except Exception as exc: errors.append(f"XML {path.relative_to(ROOT)}: {exc}")

for path in ROOT.rglob("*.json"):
    if any(part in {"build", ".gradle"} for part in path.parts):
        continue
    try: json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc: errors.append(f"JSON {path.relative_to(ROOT)}: {exc}")

required = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/qandil/opencodego/MainActivity.java",
    "app/src/main/java/com/qandil/opencodego/ai/AgentEngine.java",
    "app/src/main/java/com/qandil/opencodego/security/SecureStore.java",
    "app/src/main/java/com/qandil/opencodego/server/ServerManager.java",
    "app/src/main/java/com/qandil/opencodego/database/DatabaseManager.java",
    "app/src/main/java/com/qandil/opencodego/database/DatabaseServerManager.java",
    "app/src/main/java/com/qandil/opencodego/database/SqlSafety.java",
    "tools/run_android_stub_compile.sh",
    ".github/workflows/build-android.yml",
]
for name in required:
    if not (ROOT / name).is_file(): errors.append(f"Missing required file: {name}")

# Lexer-like delimiter pass that ignores strings and comments.
def check_java(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    stack: list[tuple[str,int]] = []
    pairs = {')':'(', ']':'[', '}':'{'}
    state = "code"
    line = 1
    i = 0
    while i < len(text):
        c = text[i]
        n = text[i+1] if i+1 < len(text) else ''
        if c == '\n': line += 1
        if state == "line":
            if c == '\n': state = "code"
        elif state == "block":
            if c == '*' and n == '/': state = "code"; i += 1
        elif state == "string":
            if c == '\\': i += 1
            elif c == '"': state = "code"
        elif state == "char":
            if c == '\\': i += 1
            elif c == "'": state = "code"
        else:
            if c == '/' and n == '/': state = "line"; i += 1
            elif c == '/' and n == '*': state = "block"; i += 1
            elif c == '"': state = "string"
            elif c == "'": state = "char"
            elif c in '([{': stack.append((c,line))
            elif c in ')]}':
                if not stack or stack[-1][0] != pairs[c]:
                    errors.append(f"Java delimiter {path.relative_to(ROOT)}:{line}: unexpected {c}")
                    return
                stack.pop()
        i += 1
    if state in {"string", "char", "block"}: errors.append(f"Java lexical state {path.relative_to(ROOT)}: unterminated {state}")
    if stack:
        c, ln = stack[-1]
        errors.append(f"Java delimiter {path.relative_to(ROOT)}:{ln}: unclosed {c}")

for path in (ROOT / "app/src/main/java").rglob("*.java"):
    check_java(path)
    text = path.read_text(encoding="utf-8")
    if "TODO" in text or "FIXME" in text: warnings.append(f"Marker in {path.relative_to(ROOT)}")


# Let javac parse the complete source tree. Android symbols are intentionally absent
# in this lightweight verifier; only parser-level diagnostics are treated as failures.
java_files = [str(path) for path in (ROOT / "app/src/main/java").rglob("*.java")]
if java_files:
    with tempfile.TemporaryDirectory(prefix="opencode-javac-") as output_dir:
        process = subprocess.run(
            ["javac", "-Xmaxerrs", "5000", "-proc:none", "-d", output_dir, *java_files],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
        parser_patterns = (
            "unclosed string literal", "unclosed character literal", "illegal line end in character literal",
            "reached end of file while parsing", "illegal start of expression",
            "illegal start of type", "';' expected", "')' expected",
            "'}' expected", "not a statement", "class, interface, enum, or record expected",
            "orphaned case", "'else' without 'if'", "'try' without 'catch'",
            "invalid method declaration; return type required",
        )
        lines = process.stderr.splitlines()
        for index, line in enumerate(lines):
            if "error:" in line and any(pattern in line for pattern in parser_patterns):
                context = " | ".join(lines[index:index + 3])
                errors.append("Java parser: " + context)

# Resolve all project-internal imports.
java_root = ROOT / "app/src/main/java"
for path in java_root.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    for imported in re.findall(r"^import\s+(com\.qandil\.opencodego(?:\.[A-Za-z0-9_]+)+);", text, re.M):
        candidate = java_root / (imported.replace('.', '/') + ".java")
        if not candidate.is_file() and not imported.endswith(".R"):
            errors.append(f"Unresolved internal import in {path.relative_to(ROOT)}: {imported}")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if 'android:allowBackup="false"' not in manifest: warnings.append("Application backup is not disabled")
for activity in re.findall(r'<activity\s+android:name="([^"]+)"', manifest):
    if activity.startswith('.'):
        candidate = java_root / ("com/qandil/opencodego/" + activity[1:] + ".java")
        if not candidate.is_file(): errors.append(f"Manifest activity is missing: {activity}")

for path in java_root.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    if re.search(r"(?:sk-|AIza|ghp_|xai-)[A-Za-z0-9_-]{16,}", text):
        errors.append(f"Possible hard-coded credential: {path.relative_to(ROOT)}")
if "SecureStore" not in (ROOT / "app/src/main/java/com/qandil/opencodego/ai/ProviderStore.java").read_text(encoding="utf-8"):
    errors.append("ProviderStore does not use SecureStore")

print(f"SOURCE_FILES={sum(1 for _ in ROOT.rglob('*') if _.is_file())}")
print(f"JAVA_FILES={sum(1 for _ in (ROOT/'app/src/main/java').rglob('*.java'))}")
for warning in warnings: print("WARNING:", warning)
for error in errors: print("ERROR:", error)
if errors: sys.exit(1)
print("SOURCE_VERIFICATION=PASS")
