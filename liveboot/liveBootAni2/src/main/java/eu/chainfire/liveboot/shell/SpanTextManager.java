package eu.chainfire.liveboot.shell;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import eu.chainfire.libcfsurface.gl.GLHelper;
import eu.chainfire.libcfsurface.gl.GLPicture;
import eu.chainfire.libcfsurface.gl.GLTextureManager;

/**
 * Text renderer for the overlay. Replaces libcfsurface's GLTextManager, which could only color a
 * whole line and which placed each line's baseline from that line's own glyph bounds.
 *
 * <p>Differences that matter:
 * <ul>
 *   <li>Lines are lists of colored spans, so single words can have their own color.</li>
 *   <li>The baseline comes from the font's metrics, so every line sits at the same height for any
 *       font, and the text size is reduced if a font's ascent+descent would not fit in a line.</li>
 *   <li>Wrapping breaks at spaces when possible.</li>
 *   <li>GL textures are only created and deleted on the render thread (draw()).</li>
 * </ul>
 */
final class SpanTextManager {
    private static final class Line {
        final List<TextSpan> spans;
        Bitmap bitmap;
        GLPicture picture;

        Line(List<TextSpan> spans) {
            this.spans = spans;
        }
    }

    private final GLTextureManager mTextureManager;
    private final GLHelper mHelper;
    private final Paint mPaint = new Paint();
    private final ReentrantLock mLock = new ReentrantLock(true);
    private final List<Line> mLines = new ArrayList<>();
    /** Pictures/bitmaps released by add()/resize(); freed on the GL thread in draw(). */
    private final List<Line> mGarbage = new ArrayList<>();

    private Typeface mTypeface;
    private int mRequestedTextSize;
    private int mWidth;
    private int mHeight;
    private int mLineHeight;
    private int mLineCount;
    private int mHorizontalPadding;
    private float mBaseline;
    private boolean mWordWrap = true;

    SpanTextManager(GLTextureManager textureManager, GLHelper helper, int width, int height,
                    int lineHeight, Typeface typeface, int textSize) {
        mTextureManager = textureManager;
        mHelper = helper;
        mTypeface = typeface == null ? Typeface.MONOSPACE : typeface;
        mRequestedTextSize = textSize;
        mPaint.setAntiAlias(true);
        mPaint.setSubpixelText(true);
        resize(width, height, lineHeight);
    }

    /**
     * Line height needed for a given pixel text size in the given font, so that a requested
     * text_size is honored for tall fonts as well as for the built-in monospace one.
     */
    static int lineHeightFor(Typeface typeface, int textSize) {
        Paint paint = new Paint();
        paint.setTypeface(typeface == null ? Typeface.MONOSPACE : typeface);
        paint.setTextSize(Math.max(1, textSize));
        Paint.FontMetrics fm = paint.getFontMetrics();
        int content = (int) Math.ceil(fm.descent - fm.ascent);
        return Math.max(1, content + Math.max(2, (int) Math.ceil(textSize * 0.1f)));
    }

    void resize(int width, int height, int lineHeight) {
        mLock.lock();
        try {
            if (width >= 0) mWidth = width;
            if (height >= 0) mHeight = height;
            if (lineHeight > 0) mLineHeight = lineHeight;
            mLineHeight = Math.max(1, mLineHeight);
            mLineCount = Math.max(1, (int) Math.ceil((double) mHeight / mLineHeight));
            applyTextSettings();
            for (Line line : mLines) release(line); // bitmaps are regenerated lazily at the new size
            trim();
        } finally {
            mLock.unlock();
        }
    }

    private void applyTextSettings() {
        mPaint.setTypeface(mTypeface);
        // Same automatic size as the original renderer unless a size was requested.
        int size = mRequestedTextSize > 0 ? mRequestedTextSize : (int) Math.ceil(mLineHeight / 1.2f);
        size = Math.max(1, size);
        mPaint.setTextSize(size);
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        float content = fm.descent - fm.ascent;
        if (content > mLineHeight && content > 0) {
            // The font is taller than the line: scale down until ascent+descent fits.
            size = Math.max(1, (int) Math.floor(size * (mLineHeight / content)));
            mPaint.setTextSize(size);
            fm = mPaint.getFontMetrics();
            content = fm.descent - fm.ascent;
        }
        // Constant baseline for every line, balanced vertically within the line box.
        mBaseline = (mLineHeight - content) / 2.0f - fm.ascent;
        mHorizontalPadding = Math.max(1, mLineHeight / 4);
        mPaint.setColor(Color_WHITE);
        mPaint.setShadowLayer(1.0f, 1.0f, 1.0f, 0xFF000000);
    }

    private static final int Color_WHITE = 0xFFFFFFFF;

    void setWordWrap(boolean wordWrap) {
        mLock.lock();
        try {
            mWordWrap = wordWrap;
        } finally {
            mLock.unlock();
        }
    }

    // ---- adding text ----

    void add(String text, int color, boolean wordWrap) {
        add(TextSpan.single(text == null ? "" : text, color), wordWrap);
    }

    /** Add one logical line (which may wrap into several display lines). */
    void add(List<TextSpan> spans, boolean wordWrap) {
        if (spans == null) return;
        mLock.lock();
        try {
            String plain = TextSpan.plain(spans);
            if (plain.length() == 0) {
                mLines.add(new Line(new ArrayList<TextSpan>()));
            } else {
                int[] colors = TextSpan.colorPerChar(spans);
                wrapInto(plain, colors, wordWrap);
            }
            trim();
        } finally {
            mLock.unlock();
        }
    }

    private void wrapInto(String plain, int[] colors, boolean wordWrap) {
        float available = Math.max(1.0f, mWidth - (mHorizontalPadding * 2));
        int n = plain.length();
        float[] widths = new float[n];
        mPaint.getTextWidths(plain, 0, n, widths);

        int start = 0;
        while (start <= n) {
            // explicit newline inside the text ends the display line
            int newline = plain.indexOf('\n', start);
            int limit = newline >= 0 ? newline : n;

            int end = start;
            float used = 0.0f;
            while (end < limit && (used + widths[end] <= available || end == start)) {
                used += widths[end];
                end++;
            }
            int next;
            if (end < limit) { // overflow: need to wrap or truncate
                int cut = end;
                if (wordWrap) {
                    int space = -1;
                    for (int i = end; i > start; i--) {
                        if (plain.charAt(i - 1) == ' ') { space = i - 1; break; }
                    }
                    if (space > start) cut = space;
                }
                mLines.add(new Line(TextSpan.fromChars(plain, colors, start, cut)));
                if (!wordWrap) return; // original behavior: truncate instead of wrapping
                next = cut;
                while (next < limit && plain.charAt(next) == ' ') next++; // swallow the break space
            } else {
                mLines.add(new Line(TextSpan.fromChars(plain, colors, start, end)));
                if (newline < 0) return;
                next = newline + 1;
                if (next >= n) return;
            }
            start = next;
            if (start >= n) return;
        }
    }

    private void trim() {
        while (mLines.size() > mLineCount) release(mLines.remove(0));
    }

    /** Drop the GL/bitmap resources of a line; actual freeing happens on the render thread. */
    private void release(Line line) {
        if (line.bitmap != null || line.picture != null) mGarbage.add(copyResources(line));
        line.bitmap = null;
        line.picture = null;
    }

    private static Line copyResources(Line line) {
        Line holder = new Line(null);
        holder.bitmap = line.bitmap;
        holder.picture = line.picture;
        return holder;
    }

    private void freeGarbage() {
        for (Line garbage : mGarbage) {
            if (garbage.picture != null) garbage.picture.destroy();
            if (garbage.bitmap != null && !garbage.bitmap.isRecycled()) garbage.bitmap.recycle();
        }
        mGarbage.clear();
    }

    // ---- drawing (GL thread) ----

    void draw() {
        draw(1.0f);
    }

    void draw(float alpha) {
        mLock.lock();
        try {
            freeGarbage();
            int top = mHeight - mLineHeight;
            for (int i = mLines.size() - 1; i >= 0; i--) {
                Line line = mLines.get(i);
                if (line.spans != null && line.spans.size() > 0) {
                    if (line.bitmap == null) line.bitmap = render(line);
                    if (line.picture == null) line.picture = new GLPicture(mTextureManager, line.bitmap);
                    mHelper.draw(line.picture, 0, top, mWidth, mLineHeight, GLPicture.AlphaType.IMAGE, alpha);
                }
                top -= mLineHeight;
            }
        } finally {
            mLock.unlock();
        }
    }

    private Bitmap render(Line line) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, mWidth), mLineHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float[] spanWidths = new float[line.spans.size()];
        for (int i = 0; i < spanWidths.length; i++) {
            spanWidths[i] = mPaint.measureText(line.spans.get(i).text);
        }
        float x = mHorizontalPadding;

        for (int i = 0; i < spanWidths.length; i++) {
            TextSpan span = line.spans.get(i);
            mPaint.setColor(span.color);
            canvas.drawText(span.text, x, mBaseline, mPaint);
            x += spanWidths[i];
        }
        mPaint.setColor(Color_WHITE);
        return bitmap;
    }

    void destroy() {
        mLock.lock();
        try {
            for (Line line : mLines) release(line);
            mLines.clear();
            freeGarbage();
        } finally {
            mLock.unlock();
        }
    }
}
