package eu.chainfire.liveboot.shell;

import java.util.Arrays;
import java.util.List;

public final class ParserSmokeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<ScriptParser.ScriptLine> script = ScriptParser.parse(Arrays.asList(
                "[wait=125]",
                "[pause=75]",
                "Online",
                "[replace]",
                "",
                "[color=green]",
                "Ready",
                "[replace \"ERROR\" with \"OK\"]",
                "[progress=130]",
                "[image=wallpaper.webp,front]",
                "[clear]",
                "[fill]"
        ));

        check(script.size() == 7, "unexpected parsed action count: " + script.size());
        check("Online".equals(script.get(0).text), "normal text was not parsed");
        check(script.get(0).wait == 125L && script.get(0).pause == 75L, "wait/pause timing failed");
        check("Ready".equals(script.get(1).text), "replace-next text missing");
        check(script.get(1).replacePrevious, "bare [replace] did not mark the next line");
        check(script.get(1).spans.get(0).color == 0xFF00FF00, "line color did not persist");
        check(script.get(2).isReplace, "parameterized replace was not parsed");
        check("ERROR".equals(script.get(2).oldText) && "OK".equals(script.get(2).newText), "replace arguments wrong");
        check(script.get(3).isProgress && script.get(3).progressValue == 100, "progress clamp failed");
        check(script.get(4).isImage && "wallpaper.webp".equals(script.get(4).imageName)
                && script.get(4).imageLayer == 1, "image front-layer directive failed");
        check(script.get(5).isClear, "clear directive failed");
        check(script.get(6).isFill, "fill directive failed");

        List<ScriptParser.ScriptLine> colored = ScriptParser.parse(Arrays.asList(
                "Plain [w=red]ERROR[/w]",
                "[highlight \"FAILED\" yellow]",
                "Service FAILED"
        ));
        check("Plain ERROR".equals(colored.get(0).text), "inline color tags leaked into text");
        check(colored.get(0).spans.size() == 2, "inline color spans were not separated");
        check(colored.get(0).spans.get(1).color == 0xFFFF0000, "inline word color was incorrect");
        check(colored.get(1).spans.get(1).color == 0xFFFFFF00, "highlight rule did not color its word");

        List<TextSpan> replaced = TextSpan.replace(colored.get(1).spans, "FAILED", "OK");
        check(replaced != null && "Service OK".equals(TextSpan.plain(replaced)), "literal span replacement failed");
        check(replaced.get(1).color == 0xFFFFFF00, "replacement did not preserve the original matched color");

        System.out.println("PASS: parser timing, bare replace, parameterized replace, progress, image layers, clear/fill, inline colors, highlights, and color-preserving text replacement");
    }
}
