package eu.chainfire.liveboot.shell;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the small, line-oriented message language used by the LiveBoot module.
 *
 * <p>Color can be set for a whole line ({@code [color=...]} on its own line, persistent) and for
 * individual words inside a line ({@code [w=...]word[/w]}, or persistent {@code [highlight ...]}
 * rules that color every matching word in the lines that follow).
 */
public final class ScriptParser {
    private static final long MAX_DELAY_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final Pattern TIMING = Pattern.compile("^\\[(wait|delay|pause)=(-?\\d+)\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE = Pattern.compile("^\\[replace\\s+\"([^\"]*)\"\\s+with\\s+\"([^\"]*)\"\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE_PREVIOUS = Pattern.compile("^\\[replace\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROGRESS = Pattern.compile("^\\[progress(?:\\s+|=)(-?\\d+)\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE = Pattern.compile("^\\[image=(.*?)\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR = Pattern.compile("^\\[color=([^\\]]*)\\]$", Pattern.CASE_INSENSITIVE);
    /** [highlight "word" color]  /  [highlight "word"=color]  /  [highlight "word" with color] */
    private static final Pattern HIGHLIGHT = Pattern.compile(
            "^\\[highlight\\s+\"([^\"]+)\"\\s*(?:=|with\\s+)?\\s*([^\\]\\s\"]+)\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIGHLIGHT_CLEAR = Pattern.compile(
            "^\\[highlight(?:=|\\s+)(?:clear|none|off)\\]$", Pattern.CASE_INSENSITIVE);
    /** Inline word color tags: [w=red] / [word=#FF8800] to open, [/w] / [/word] to close. */
    private static final Pattern INLINE_TAG = Pattern.compile(
            "\\[(?:(?:w|word)=([^\\]]*)|/(?:w|word))\\]", Pattern.CASE_INSENSITIVE);

    private static final int MAX_HIGHLIGHT_RULES = 64;

    public static final class ScriptLine {
        /** Plain text of the line (no color tags). */
        public final String text;
        /** The line as colored runs; never null. Empty for blank lines and non-text actions. */
        public final List<TextSpan> spans;
        public final long wait;
        public final long pause;
        /** The line color in effect (the base color of the spans). */
        public final int color;
        public final boolean isClear;
        public final boolean isFill;
        public final boolean isReplace;
        public final String oldText;
        public final String newText;
        public final boolean replacePrevious;
        public final boolean isProgress;
        public final int progressValue;
        public final boolean isImage;
        public final String imageName;
        /** -1 = use settings.conf, 0 = behind text, 1 = in front of text. */
        public final int imageLayer;

        private ScriptLine(List<TextSpan> spans, long wait, long pause, int color, boolean isClear,
                           boolean isFill, boolean isReplace, String oldText,
                           String newText, boolean replacePrevious, boolean isProgress, int progressValue,
                           boolean isImage, String imageName, int imageLayer) {
            this.spans = spans == null ? new ArrayList<TextSpan>() : spans;
            this.text = TextSpan.plain(this.spans);
            this.wait = wait;
            this.pause = pause;
            this.color = color;
            this.isClear = isClear;
            this.isFill = isFill;
            this.isReplace = isReplace;
            this.oldText = oldText == null ? "" : oldText;
            this.newText = newText == null ? "" : newText;
            this.replacePrevious = replacePrevious;
            this.isProgress = isProgress;
            this.progressValue = progressValue;
            this.isImage = isImage;
            this.imageName = imageName == null ? "" : imageName;
            this.imageLayer = imageLayer;
        }
    }

    private static final class HighlightRule {
        final String word;
        final int color;
        HighlightRule(String word, int color) {
            this.word = word;
            this.color = color;
        }
    }

    private ScriptParser() { }

    public static List<ScriptLine> parse(List<String> lines) {
        List<ScriptLine> output = new ArrayList<>();
        long pendingWait = 0L;
        long pendingPause = 0L;
        int currentColor = Color.WHITE;
        boolean replaceNextLine = false;
        List<HighlightRule> rules = new ArrayList<>();

        if (lines == null) return output;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            // Blank separators do not consume a pending replace-next-line directive.
            if (replaceNextLine && line.length() == 0) continue;
            Matcher timing = TIMING.matcher(line);
            if (timing.matches()) {
                long duration = parseDuration(timing.group(2));
                String kind = timing.group(1).toLowerCase(Locale.ROOT);
                if ("pause".equals(kind)) pendingPause = duration;
                else pendingWait = duration; // [delay=] is an alias for [wait=]
                continue;
            }

            Matcher image = IMAGE.matcher(line);
            if (image.matches()) {
                String imageSpec = image.group(1).trim();
                int imageLayer = -1;
                int layerSeparator = imageSpec.lastIndexOf(',');
                if (layerSeparator >= 0) {
                    String layer = imageSpec.substring(layerSeparator + 1).trim();
                    if ("front".equalsIgnoreCase(layer)) {
                        imageLayer = 1;
                        imageSpec = imageSpec.substring(0, layerSeparator).trim();
                    } else if ("behind".equalsIgnoreCase(layer)) {
                        imageLayer = 0;
                        imageSpec = imageSpec.substring(0, layerSeparator).trim();
                    }
                }
                output.add(new ScriptLine(null, pendingWait, pendingPause, currentColor, false, false,
                        false, "", "", false, false, 0, true, imageSpec, imageLayer));
                pendingWait = pendingPause = 0L;
                continue;
            }

            // Whole-line color: persists until the next [color=...]. Unchanged behavior.
            Matcher color = COLOR.matcher(line);
            if (color.matches()) {
                int parsed = parseColor(color.group(1));
                if (parsed != Integer.MIN_VALUE) currentColor = parsed;
                continue;
            }

            if (HIGHLIGHT_CLEAR.matcher(line).matches()) {
                rules.clear();
                continue;
            }
            Matcher highlight = HIGHLIGHT.matcher(line);
            if (highlight.matches()) {
                String word = highlight.group(1);
                String colorText = highlight.group(2);
                // Drop any earlier rule for the same word; "none"/"off"/"clear" only removes it.
                for (int i = rules.size() - 1; i >= 0; i--) {
                    if (rules.get(i).word.equals(word)) rules.remove(i);
                }
                String lower = colorText.toLowerCase(Locale.ROOT);
                if (!("none".equals(lower) || "off".equals(lower) || "clear".equals(lower))) {
                    int parsed = parseColor(colorText);
                    if (parsed != Integer.MIN_VALUE && rules.size() < MAX_HIGHLIGHT_RULES) {
                        rules.add(new HighlightRule(word, parsed));
                    }
                }
                continue;
            }

            if ("[clear]".equalsIgnoreCase(line)) {
                output.add(make(null, pendingWait, pendingPause, currentColor, true, false,
                        false, "", "", false, false, 0));
                pendingWait = pendingPause = 0L;
                replaceNextLine = false;
                continue;
            }

            if ("[fill]".equalsIgnoreCase(line)) {
                output.add(make(null, pendingWait, pendingPause, currentColor, false, true,
                        false, "", "", false, false, 0));
                pendingWait = pendingPause = 0L;
                replaceNextLine = false;
                continue;
            }

            if (REPLACE_PREVIOUS.matcher(line).matches()) {
                // The next visible text/progress line replaces the latest displayed script line.
                replaceNextLine = true;
                continue;
            }

            Matcher replace = REPLACE.matcher(line);
            if (replace.matches()) {
                output.add(make(null, pendingWait, pendingPause, currentColor, false, false,
                        true, replace.group(1), replace.group(2), false, false, 0));
                pendingWait = pendingPause = 0L;
                replaceNextLine = false;
                continue;
            }

            Matcher progress = PROGRESS.matcher(line);
            if (progress.matches()) {
                int value;
                try {
                    value = Integer.parseInt(progress.group(1));
                } catch (NumberFormatException ignored) {
                    value = 0;
                }
                value = Math.max(0, Math.min(100, value));
                output.add(make(null, pendingWait, pendingPause, currentColor, false, false,
                        false, "", "", replaceNextLine, true, value));
                pendingWait = pendingPause = 0L;
                replaceNextLine = false;
                continue;
            }

            output.add(make(parseSpans(line, currentColor, rules), pendingWait, pendingPause,
                    currentColor, false, false, false, "", "", replaceNextLine, false, 0));
            pendingWait = pendingPause = 0L;
            replaceNextLine = false;
        }
        return output;
    }

    /**
     * Turn a text line into colored runs. Inline {@code [w=COLOR]..[/w]} tags take priority; the
     * persistent highlight rules then color matching words that were not colored explicitly.
     */
    static List<TextSpan> parseSpans(String line, int baseColor, List<HighlightRule> rules) {
        StringBuilder text = new StringBuilder(line.length());
        List<Integer> colorList = new ArrayList<>(line.length());
        List<Boolean> explicitList = new ArrayList<>(line.length());
        List<Integer> stack = new ArrayList<>();

        Matcher tag = INLINE_TAG.matcher(line);
        int last = 0;
        while (tag.find()) {
            appendRun(line, last, tag.start(), baseColor, stack, text, colorList, explicitList);
            String openValue = tag.group(1);
            if (openValue != null) {
                int parsed = parseColor(openValue);
                if (parsed != Integer.MIN_VALUE) {
                    stack.add(parsed);
                } else { // unknown color: keep the tag visible so the mistake is easy to spot
                    appendRun(line, tag.start(), tag.end(), baseColor, stack, text, colorList, explicitList);
                }
            } else if (!stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            } else {
                appendRun(line, tag.start(), tag.end(), baseColor, stack, text, colorList, explicitList);
            }
            last = tag.end();
        }
        appendRun(line, last, line.length(), baseColor, stack, text, colorList, explicitList);

        int length = text.length();
        int[] colors = new int[length];
        boolean[] explicit = new boolean[length];
        for (int i = 0; i < length; i++) {
            colors[i] = colorList.get(i);
            explicit[i] = explicitList.get(i);
        }

        if (rules != null && !rules.isEmpty() && length > 0) {
            String plain = text.toString();
            for (HighlightRule rule : rules) {
                applyRule(plain, rule, colors, explicit);
            }
        }
        return TextSpan.fromChars(text, colors, 0, length);
    }

    private static void appendRun(String source, int from, int to, int baseColor, List<Integer> stack,
                                  StringBuilder text, List<Integer> colors, List<Boolean> explicit) {
        if (to <= from) return;
        boolean isExplicit = !stack.isEmpty();
        int color = isExplicit ? stack.get(stack.size() - 1) : baseColor;
        for (int i = from; i < to; i++) {
            text.append(source.charAt(i));
            colors.add(color);
            explicit.add(isExplicit);
        }
    }

    /** Color every occurrence of the rule's word; whole-word where the word's edges are letters/digits. */
    private static void applyRule(String plain, HighlightRule rule, int[] colors, boolean[] explicit) {
        String word = rule.word;
        if (word.length() == 0) return;
        boolean needStartBoundary = Character.isLetterOrDigit(word.charAt(0));
        boolean needEndBoundary = Character.isLetterOrDigit(word.charAt(word.length() - 1));
        int from = 0;
        while (from <= plain.length() - word.length()) {
            int at = plain.indexOf(word, from);
            if (at < 0) break;
            int end = at + word.length();
            boolean startOk = !needStartBoundary || at == 0 || !Character.isLetterOrDigit(plain.charAt(at - 1));
            boolean endOk = !needEndBoundary || end == plain.length() || !Character.isLetterOrDigit(plain.charAt(end));
            if (startOk && endOk) {
                for (int i = at; i < end; i++) {
                    if (!explicit[i]) colors[i] = rule.color;
                }
                from = end;
            } else {
                from = at + 1;
            }
        }
    }

    private static ScriptLine make(List<TextSpan> spans, long wait, long pause, int color,
                                   boolean clear, boolean fill, boolean replace,
                                   String oldText, String newText, boolean replacePrevious,
                                   boolean progress, int progressValue) {
        return new ScriptLine(spans, wait, pause, color, clear, fill,
                replace, oldText, newText, replacePrevious, progress, progressValue,
                false, "", -1);
    }

    private static long parseDuration(String value) {
        try {
            return Math.max(0L, Math.min(MAX_DELAY_MS, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int parseColor(String value) {
        String color = value == null ? "" : value.trim();
        if (color.length() == 0) return Integer.MIN_VALUE;
        try {
            return Color.parseColor(color);
        } catch (IllegalArgumentException ignored) {
            return Integer.MIN_VALUE;
        }
    }
}
