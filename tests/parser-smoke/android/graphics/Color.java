package android.graphics;

public final class Color {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int RED = 0xFFFF0000;
    public static final int GREEN = 0xFF00FF00;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int CYAN = 0xFF00FFFF;

    private Color() { }

    public static int parseColor(String value) {
        if (value == null) throw new IllegalArgumentException("null color");
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "white": return WHITE;
            case "red": return RED;
            case "green": return GREEN;
            case "yellow": return YELLOW;
            case "cyan": return CYAN;
            case "black": return 0xFF000000;
            case "blue": return 0xFF0000FF;
            default:
                if (!v.startsWith("#")) throw new IllegalArgumentException("unknown color");
                long parsed = Long.parseLong(v.substring(1), 16);
                if (v.length() == 7) return (int) (0xFF000000L | parsed);
                if (v.length() == 9) return (int) parsed;
                throw new IllegalArgumentException("bad color length");
        }
    }
}
