package com.qandil.opencodego.diff;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Memory-bounded line diff with unified output. */
public final class LineDiff {
    public enum Kind { EQUAL, ADD, REMOVE }
    public static final class Entry {
        public final Kind kind;
        public final String text;
        Entry(Kind kind, String text) { this.kind = kind; this.text = text; }
    }

    private LineDiff() {}

    public static List<Entry> diff(String before, String after) {
        String[] a = split(before), b = split(after);
        long cells = (long) (a.length + 1) * (b.length + 1);
        if (cells > 4_000_000L) return patienceFallback(a, b);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1]
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Entry> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                result.add(new Entry(Kind.EQUAL, a[i++])); j++;
            } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                result.add(new Entry(Kind.ADD, b[j++]));
            } else result.add(new Entry(Kind.REMOVE, a[i++]));
        }
        return result;
    }

    public static String unified(String name, String before, String after, int context) {
        List<Entry> entries = diff(before, after);
        StringBuilder output = new StringBuilder("--- a/").append(name).append('\n')
                .append("+++ b/").append(name).append('\n');
        int oldLine = 1, newLine = 1;
        int safeContext = Math.max(0, Math.min(20, context));
        int index = 0;
        while (index < entries.size()) {
            int change = index;
            while (change < entries.size() && entries.get(change).kind == Kind.EQUAL) change++;
            if (change >= entries.size()) break;
            int start = Math.max(index, change - safeContext);
            int end = change;
            int equalTail = 0;
            while (end < entries.size()) {
                if (entries.get(end).kind == Kind.EQUAL) {
                    equalTail++;
                    if (equalTail > safeContext) break;
                } else equalTail = 0;
                end++;
            }
            int oldStart = lineAt(entries, start, true);
            int newStart = lineAt(entries, start, false);
            int oldCount = count(entries, start, end, true);
            int newCount = count(entries, start, end, false);
            output.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (int k = start; k < end; k++) {
                Entry entry = entries.get(k);
                output.append(entry.kind == Kind.ADD ? '+' : entry.kind == Kind.REMOVE ? '-' : ' ')
                        .append(entry.text).append('\n');
            }
            index = end;
        }
        return output.toString();
    }

    public static JSONArray json(String before, String after) {
        JSONArray array = new JSONArray();
        for (Entry entry : diff(before, after)) {
            array.put(new JSONObject().put("kind", entry.kind.name().toLowerCase()).put("text", entry.text));
        }
        return array;
    }

    private static int lineAt(List<Entry> entries, int end, boolean oldSide) {
        int line = 1;
        for (int i = 0; i < end; i++) {
            Kind kind = entries.get(i).kind;
            if (kind == Kind.EQUAL || (oldSide ? kind == Kind.REMOVE : kind == Kind.ADD)) line++;
        }
        return line;
    }

    private static int count(List<Entry> entries, int start, int end, boolean oldSide) {
        int count = 0;
        for (int i = start; i < end; i++) {
            Kind kind = entries.get(i).kind;
            if (kind == Kind.EQUAL || (oldSide ? kind == Kind.REMOVE : kind == Kind.ADD)) count++;
        }
        return count;
    }

    private static List<Entry> patienceFallback(String[] a, String[] b) {
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) prefix++;
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
                && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) suffix++;
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < prefix; i++) result.add(new Entry(Kind.EQUAL, a[i]));
        for (int i = prefix; i < a.length - suffix; i++) result.add(new Entry(Kind.REMOVE, a[i]));
        for (int i = prefix; i < b.length - suffix; i++) result.add(new Entry(Kind.ADD, b[i]));
        for (int i = suffix; i > 0; i--) result.add(new Entry(Kind.EQUAL, a[a.length - i]));
        return result;
    }

    private static String[] split(String value) { return (value == null ? "" : value).split("\\r?\\n", -1); }
}
