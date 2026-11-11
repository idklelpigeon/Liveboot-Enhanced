package eu.chainfire.liveboot.shell;

import java.util.ArrayList;
import java.util.List;

/** A run of text drawn in a single color. A displayed line is a list of spans. */
public final class TextSpan {
    public final String text;
    public final int color;

    public TextSpan(String text, int color) {
        this.text = text == null ? "" : text;
        this.color = color;
    }

    /** Concatenate the text of all spans. */
    public static String plain(List<TextSpan> spans) {
        if (spans == null) return "";
        StringBuilder out = new StringBuilder();
        for (TextSpan span : spans) out.append(span.text);
        return out.toString();
    }

    /** Single-span convenience list. */
    public static List<TextSpan> single(String text, int color) {
        List<TextSpan> out = new ArrayList<>(1);
        out.add(new TextSpan(text, color));
        return out;
    }

    /** Expand to one color per UTF-16 char of {@link #plain}. */
    public static int[] colorPerChar(List<TextSpan> spans) {
        int total = 0;
        for (TextSpan span : spans) total += span.text.length();
        int[] colors = new int[total];
        int at = 0;
        for (TextSpan span : spans) {
            for (int i = 0; i < span.text.length(); i++) colors[at++] = span.color;
        }
        return colors;
    }

    /** Merge adjacent chars of equal color back into spans. */
    public static List<TextSpan> fromChars(CharSequence text, int[] colors, int start, int end) {
        List<TextSpan> out = new ArrayList<>();
        int runStart = start;
        for (int i = start; i < end; i++) {
            boolean last = i == end - 1;
            if (last || colors[i + 1] != colors[i]) {
                out.add(new TextSpan(text.subSequence(runStart, i + 1).toString(), colors[i]));
                runStart = i + 1;
            }
        }
        return out;
    }

    /**
     * Literal, case-sensitive replace across a span list. Replacement text takes the color of the
     * first character it replaces, so recolored words stay recolored. Returns null if nothing matched.
     */
    public static List<TextSpan> replace(List<TextSpan> spans, String oldText, String newText) {
        if (oldText == null || oldText.length() == 0) return null;
        String plain = plain(spans);
        if (plain.indexOf(oldText) < 0) return null;
        int[] colors = colorPerChar(spans);
        StringBuilder outText = new StringBuilder();
        List<Integer> outColors = new ArrayList<>();
        int i = 0;
        while (i < plain.length()) {
            if (plain.startsWith(oldText, i)) {
                int color = colors[i];
                for (int k = 0; k < newText.length(); k++) {
                    outText.append(newText.charAt(k));
                    outColors.add(color);
                }
                i += oldText.length();
            } else {
                outText.append(plain.charAt(i));
                outColors.add(colors[i]);
                i++;
            }
        }
        int[] merged = new int[outColors.size()];
        for (int k = 0; k < merged.length; k++) merged[k] = outColors.get(k);
        return fromChars(outText, merged, 0, merged.length);
    }
}
