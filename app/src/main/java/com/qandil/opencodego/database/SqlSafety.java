package com.qandil.opencodego.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative SQL permission classifier. It is not a substitute for DB-native roles. */
public final class SqlSafety {
    private static final Pattern MUTATION = Pattern.compile(
            "(?s).*\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|REPLACE|CREATE|ALTER|DROP|TRUNCATE|"
                    + "VACUUM|REINDEX|ANALYZE|ATTACH|DETACH|GRANT|REVOKE|CALL|DO|COPY|LOAD)\\b.*");
    private static final Pattern SIDE_EFFECT_SELECT = Pattern.compile(
            "(?s).*(\\bINTO\\s+(OUTFILE|DUMPFILE)\\b|\\bFOR\\s+UPDATE\\b|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b).*" );
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?s).*(\\bDROP\\s+(TABLE|DATABASE|SCHEMA|INDEX|VIEW|TRIGGER|USER|ROLE)\\b|"
                    + "\\bTRUNCATE(?:\\s+TABLE)?\\b|\\bDELETE\\s+FROM\\b|"
                    + "\\bALTER\\s+TABLE\\b.*\\bDROP\\b|\\bVACUUM\\s+INTO\\b|"
                    + "\\bREPLACE\\s+INTO\\b).*" );

    private SqlSafety() {}

    public static boolean isWrite(String sql) {
        List<String> statements = statements(sql);
        if (statements.isEmpty()) return false;
        for (String statement : statements) if (!isReadOnlyStatement(statement)) return true;
        return false;
    }

    public static boolean isSingleReadQuery(String sql) {
        List<String> statements = statements(sql);
        return statements.size() == 1 && isReadOnlyStatement(statements.get(0));
    }

    public static boolean isDestructive(String sql) {
        for (String statement : statements(sql)) {
            String normalized = normalize(statement);
            String outsideStrings = maskStrings(normalized);
            if (DESTRUCTIVE.matcher(outsideStrings).matches()) return true;
            if (outsideStrings.startsWith("UPDATE ") && !containsWord(outsideStrings, "WHERE")) return true;
        }
        return false;
    }

    public static List<String> statements(String sql) {
        List<String> output = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) return output;
        StringBuilder current = new StringBuilder();
        boolean single = false, dual = false, backtick = false, lineComment = false, blockComment = false;
        String dollarTag = null;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') { lineComment = false; current.append(' '); }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; current.append(' '); }
                continue;
            }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, i)) {
                    current.append(dollarTag); i += dollarTag.length() - 1; dollarTag = null;
                } else current.append(c);
                continue;
            }
            if (!single && !dual && !backtick && c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (!single && !dual && !backtick && c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (!single && !dual && !backtick && c == '$') {
                int end = sql.indexOf('$', i + 1);
                if (end > i && sql.substring(i + 1, end).matches("[A-Za-z0-9_]*")) {
                    dollarTag = sql.substring(i, end + 1); current.append(dollarTag); i = end; continue;
                }
            }
            if (single) {
                current.append(c);
                if (c == '\'' && next == '\'') { current.append(next); i++; }
                else if (c == '\'' && !escaped(sql, i)) single = false;
                continue;
            }
            if (dual) {
                current.append(c);
                if (c == '"' && next == '"') { current.append(next); i++; }
                else if (c == '"' && !escaped(sql, i)) dual = false;
                continue;
            }
            if (backtick) {
                current.append(c);
                if (c == '`' && next == '`') { current.append(next); i++; }
                else if (c == '`') backtick = false;
                continue;
            }
            if (c == '\'') { single = true; current.append(c); continue; }
            if (c == '"') { dual = true; current.append(c); continue; }
            if (c == '`') { backtick = true; current.append(c); continue; }
            if (c == ';') {
                add(output, current); current.setLength(0);
            } else current.append(c);
        }
        add(output, current);
        return output;
    }

    private static boolean isReadOnlyStatement(String statement) {
        String normalized = normalize(statement);
        if (normalized.isEmpty()) return true;
        boolean candidate = normalized.startsWith("SELECT ") || normalized.equals("SELECT")
                || normalized.startsWith("WITH ") || normalized.startsWith("VALUES ")
                || normalized.startsWith("SHOW ") || normalized.startsWith("DESCRIBE ")
                || normalized.startsWith("DESC ") || normalized.startsWith("TABLE ")
                || normalized.startsWith("EXPLAIN ");
        if (!candidate) return false;
        String outsideStrings = maskStrings(normalized);
        return !MUTATION.matcher(outsideStrings).matches()
                && !SIDE_EFFECT_SELECT.matcher(outsideStrings).matches();
    }

    private static String normalize(String statement) {
        return statement == null ? "" : statement.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private static String maskStrings(String input) {
        StringBuilder output = new StringBuilder(input.length());
        boolean single = false, dual = false, backtick = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char next = i + 1 < input.length() ? input.charAt(i + 1) : '\0';
            if (single) {
                output.append(' ');
                if (c == '\'' && next == '\'') { output.append(' '); i++; }
                else if (c == '\'' && !escaped(input, i)) single = false;
            } else if (dual) {
                output.append(c);
                if (c == '"' && next == '"') { output.append(next); i++; }
                else if (c == '"' && !escaped(input, i)) dual = false;
            } else if (backtick) {
                output.append(c);
                if (c == '`' && next == '`') { output.append(next); i++; }
                else if (c == '`') backtick = false;
            } else if (c == '\'') { single = true; output.append(' '); }
            else if (c == '"') { dual = true; output.append(c); }
            else if (c == '`') { backtick = true; output.append(c); }
            else output.append(c);
        }
        return output.toString();
    }

    private static boolean escaped(String value, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && value.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) == 1;
    }

    private static void add(List<String> output, StringBuilder value) {
        String statement = value.toString().trim();
        if (!statement.isEmpty()) output.add(statement);
    }

    private static boolean containsWord(String value, String word) {
        return Pattern.compile("(?s).*\\b" + Pattern.quote(word) + "\\b.*").matcher(value).matches();
    }
}
