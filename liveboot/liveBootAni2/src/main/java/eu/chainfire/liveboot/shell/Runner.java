/* Copyright (C) 2011-2024 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.chainfire.liveboot.shell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.libcfsurface.SurfaceHost;
import eu.chainfire.libcfsurface.gl.GLHelper;
import eu.chainfire.libcfsurface.gl.GLPicture;
import eu.chainfire.libcfsurface.gl.GLTextureManager;
import eu.chainfire.librootjavadaemon.RootDaemon;
import eu.chainfire.libsuperuser.Debug;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.Toolbox;
import eu.chainfire.liveboot.BuildConfig;

public class
    Runner
extends
    SurfaceHost
implements
    OnLineListener,
    SurfaceHost.IGLRenderCallback
{
    public static void main(String[] args) {
        Logger.setLogTag("LiveBootSurface");
        Logger.setDebugLogging(BuildConfig.DEBUG);
        Debug.setDebug(BuildConfig.DEBUG);
        Debug.setLogTypeEnabled(Debug.LOG_GENERAL | Debug.LOG_COMMAND, true);
        Debug.setLogTypeEnabled(Debug.LOG_OUTPUT, false);
        Debug.setSanityChecksEnabled(false); // don't complain about calls on the main thread

        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Logger.dp("EXCEPTION", "%s", throwable.getClass().getName());
                if (oldHandler != null) {
                    oldHandler.uncaughtException(thread, throwable);
                } else {
                    System.exit(1);
                }
            }
        });

        // app_process starts this entry point without ActivityThread's normal application bind.
        // On Android releases using lazy Typeface initialization, that leaves Typeface.DEFAULT
        // and the generic font family constants unset. Typeface.Builder and
        // Typeface.createFromFile() eventually dereference that missing system fallback.
        sTypefaceSystemMapStatus = initializeTypefaceSystemMap();
        Logger.d("FONT: system typeface map initialization: %s", sTypefaceSystemMapStatus);

        new Runner().run(args);
    }

    /**
     * Initialize Android's preinstalled typeface map before any Runner instance reads
     * Typeface.MONOSPACE or attempts to construct a custom Typeface. This is normally
     * performed during application binding; the standalone app_process launch skips that
     * lifecycle step. The method is hidden from the public SDK, so resolve it reflectively.
     */
    private static String initializeTypefaceSystemMap() {
        try {
            Method initializer = Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
            initializer.invoke(null);

            if (Typeface.DEFAULT == null || Typeface.MONOSPACE == null) {
                throw new IllegalStateException(
                        "loadPreinstalledSystemFontMap returned without populating Typeface defaults");
            }
            return "OK: loadPreinstalledSystemFontMap populated Typeface.DEFAULT and MONOSPACE";
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return "FAILED: " + describeFontThrowable(cause);
        }
    }

    public static final String LIVEBOOT_ABORT_FILE = "/dev/.liveboot_exit";

    private static final int TEST_TIME = 5000;
    private static final int LEAD_TIME = 200;

    private boolean mTest = false;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mLines = 80;
    private boolean mWordWrap = false;
    private boolean mTransparent = false;
    private boolean mDark = false;
    private boolean mLogcatColor = true;
    private static final String LOG_NAME = "/cache/liveboot.log";
    private boolean mLogSave = false;
    private OutputStream mLogStream = null;
    private ReentrantLock mLogLock = new ReentrantLock(true);
    private static final String SCRIPT_NAME_SYSTEM = "/system/su.d/0000liveboot.script";
    private static final String SCRIPT_NAME_SU = "/su/su.d/0000liveboot.script";
    private static final String SCRIPT_NAME_SBIN = "/sbin/supersu/su.d/0000liveboot.script";
    //TODO where to put the script for Magisk?
    private String mRunScript = null;

    private GLTextureManager mTextureManager = null;
    private GLHelper mHelper = null;
    private volatile SpanTextManager mTextManager = null;

    private Logcat mLogcat = null;
    private Dmesg mDmesg = null;
    private Script mScript = null;

    private HandlerThread mHandlerThread = null;
    private Handler mHandler = null;

    private long mFirstLine = 0;
    private int mLinesPassed = 0;

    private volatile long mComplete = 0;

    private static final String MODULE_ROOT = "/data/adb/modules/liveboot_custom";
    private static final String CONFIG_PATH = MODULE_ROOT + "/config/settings.conf";
    private static final String MESSAGE_ROOT = MODULE_ROOT + "/messages";
    private static final String FONT_ROOT = MODULE_ROOT + "/fonts";
    private static final String STAGED_FONT_ROOT = "/data/local/tmp/liveboot-font";
    private static String sTypefaceSystemMapStatus = "not attempted";
    private static final String IMAGE_ROOT = MODULE_ROOT + "/images";
    private static final long MAX_MESSAGE_FILE_BYTES = 1024L * 1024L;
    private static final long MAX_IMAGE_FILE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_DECODED_IMAGE_PIXELS = 8L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 4096;

    private String mMessageFileName = "default.txt";
    private File mMessageFile = null;
    private boolean mUseCustomMessage = false;
    private int mBackgroundOpacity = 75;
    private int mFinishHoldMs = 1500;
    private int mImageFit = 1; // 0=contain, 1=cover, 2=stretch
    private int mImageOpacity = 100;
    private boolean mConfiguredImageOnTop = false;
    private volatile boolean mImageOnTop = false;
    private int mConfiguredTextSize = 0;
    private boolean mLineBackground = false;
    private Typeface mConfiguredTypeface = Typeface.MONOSPACE;
    private volatile boolean mMessagePlaybackFinished = true;
    private Thread mMessagePlaybackThread = null;
    private final List<DisplayLine> mDisplayedLines = new ArrayList<>();

    // Bitmaps are decoded by the script worker, but uploaded/deleted only on the GL render thread.
    private final Object mPendingImageLock = new Object();
    private Bitmap mPendingImageBitmap = null;
    private boolean mImageChangePending = false;
    private int mImageTextureId = 0;
    private int mImageTextureWidth = 0;
    private int mImageTextureHeight = 0;
    private int mImageProgram = 0;
    private int mImagePositionLocation = -1;
    private int mImageTexCoordLocation = -1;
    private int mImageScaleLocation = -1;
    private int mImageOpacityLocation = -1;
    private int mImageSamplerLocation = -1;
    private FloatBuffer mImageVertexBuffer = null;

    private static final class DisplayLine {
        List<TextSpan> spans;
        DisplayLine(List<TextSpan> spans) {
            this.spans = spans;
        }
    }

    /** Pixel height of one text line for the current configuration. */
    private int computeLineHeight() {
        if (mConfiguredTextSize > 0) {
            return SpanTextManager.lineHeightFor(mConfiguredTypeface, mConfiguredTextSize);
        }
        return Math.max(1, mHeight / Math.max(1, mLines));
    }

    /** With a fixed text_size the number of visible lines follows from the screen height. */
    private void updateLineCount() {
        if (mConfiguredTextSize > 0 && mHeight > 0) {
            mLines = Math.max(1, Math.min(500, mHeight / Math.max(1, computeLineHeight())));
        }
        mLines = Math.max(1, mLines);
    }

    private boolean isBootAnimationRunning() {
        List<String> ps = Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps") + " | /system/bin/" + Toolbox.command("grep") + " bootanim"+  " | /system/bin/" + Toolbox.command("grep") + " -v grep",
                "/system/bin/" + Toolbox.command("ps") + " -A | /system/bin/" + Toolbox.command("grep") + " bootanim" + " | /system/bin/" + Toolbox.command("grep") + " -v grep"
        });
        if (ps != null) {
            for (String line : ps) {
                if (line.contains("bootanim")) {
                    return true;
                }
            }
        }
        return SystemProperties.get("init.svc.bootanim", "stopped").equals("running");
    }

    private void killBootAnimation() {
        List<String> ps = Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps") + " | /system/bin/" + Toolbox.command("grep") + " bootanim"+  " | /system/bin/" + Toolbox.command("grep") + " -v grep",
                "/system/bin/" + Toolbox.command("ps") + " -A | /system/bin/" + Toolbox.command("grep") + " bootanim" + " | /system/bin/" + Toolbox.command("grep") + " -v grep"
        });
        if (ps != null) {
            for (String line : ps) {
                String[] parts = line.split(" +");
                if (parts.length >= 2) {
                    Shell.run("sh", new String[] { "/system/bin/" + Toolbox.command("kill") + " -9 " + parts[1] }, null, false);
                }
            }
        }
    }

    private void infanticide() { // children
        String pid = String.valueOf(android.os.Process.myPid());
        List<String> ps = Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps") + " | /system/bin/" + Toolbox.command("grep") + " " + pid + " | /system/bin/" + Toolbox.command("grep") + " -v grep",
                "/system/bin/" + Toolbox.command("ps") + " -A | /system/bin/" + Toolbox.command("grep") + " " + pid + " | /system/bin/" + Toolbox.command("grep") + " -v grep"
        });
        if (ps != null) {
            for (String line : ps) {
                String[] parts = line.split(" +");
                if (parts.length >= 3) {
                    if (!parts[1].equals(pid) && parts[2].equals(pid)) {
                        Shell.run("sh", new String[] { "/system/bin/" + Toolbox.command("kill") + " -9 " + parts[1] }, null, false);
                    }
                }
            }
        }
    }

    private void suicide() { // self
        Shell.SH.run(new String[] { "/system/bin/" + Toolbox.command("kill") + " -9 " + String.valueOf(android.os.Process.myPid()) });
    }

    @Override
    protected void onSize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    @Override
    protected void onResize(int width, int height) {
        mWidth = width;
        mHeight = height;
        updateLineCount();
        if (mTextManager != null) mTextManager.resize(width, height, computeLineHeight());
        if (mHelper != null) mHelper.resize(width, height);
    }

    @Override
    protected void onInit(String[] args) {
        RootDaemon.daemonize(BuildConfig.APPLICATION_ID, 0, false, null);

        Toolbox.init();

        // parse options
        String logcatLevelOpts = null;
        String logcatBufferOpts = null;
        String logcatFormatOpt = null;
        String dmesgOpts = null;

        for (String arg : args) {
            try {
                if (arg.equals("test")) {
                    mTest = true;
                    Logger.dp("OPTS", "test==1");
                } else if (arg.equals("transparent")) {
                    mTransparent = true;
                    Logger.dp("OPTS", "transparent==1");
                } else if (arg.equals("wordwrap")) {
                    mWordWrap = true;
                    Logger.dp("OPTS", "wordwrap==1");
                } else if (arg.equals("save")) {
                    mLogSave = true;
                    Logger.dp("OPTS", "save==1");
                } else if (arg.equals("dark")) {
                    mDark = true;
                    Logger.dp("OPTS", "dark==1");
                } else if (arg.equals("logcatnocolors")) {
                    mLogcatColor = false;
                    Logger.dp("OPTS", "logcatnocolors==1");
                } else if (arg.contains("=")) {
                    String key = arg.substring(0, arg.indexOf('='));
                    String value = arg.substring(arg.indexOf('=') + 1);

                    if (key.equals("fallbackwidth")) {
                        fallbackWidth = Integer.valueOf(value, 10);
                        Logger.dp("OPTS", "fallbackWidth==%d", fallbackWidth);
                    } else if (key.equals("fallbackheight")) {
                        fallbackHeight = Integer.valueOf(value, 10);
                        Logger.dp("OPTS", "fallbackHeight==%d", fallbackHeight);
                    } else if (key.equals("lines")) {
                        mLines = clamp(Integer.valueOf(value, 10), 1, 500);
                        Logger.dp("OPTS", "mLines==%s", mLines);
                    } else if (key.equals("logcatlevels")) {
                        logcatLevelOpts = value;
                        Logger.dp("OPTS", "logcatLevelOpts==%s", logcatLevelOpts);
                    } else if (key.equals("logcatbuffers")) {
                        logcatBufferOpts = value;
                        Logger.dp("OPTS", "logcatBufferOpts==%s", logcatBufferOpts);
                    } else if (key.equals("logcatformat")) {
                        logcatFormatOpt = value;
                        Logger.dp("OPTS", "logcatFormatOpt==%s", logcatFormatOpt);
                    } else if (key.equals("dmesg")) {
                        dmesgOpts = value;
                        Logger.dp("OPTS", "dmesgOpts==%s", dmesgOpts);
                    }
                }
            } catch (Exception e) {
                Logger.ex(e);
            }
        }

        loadModuleSettings();

        if ((new File(SCRIPT_NAME_SBIN)).exists()) {
            mRunScript = SCRIPT_NAME_SBIN;
        } else if ((new File(SCRIPT_NAME_SU)).exists()) {
            mRunScript = SCRIPT_NAME_SU;
        } else if ((new File(SCRIPT_NAME_SYSTEM)).exists()) {
            mRunScript = SCRIPT_NAME_SYSTEM;
        } //TODO Magisk, KernelSU

        mHandlerThread = new HandlerThread("LiveBoot HandlerThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        if (mLogSave) {
            try {
                mLogStream = new FileOutputStream(LOG_NAME, false);
            } catch (Exception e) {
            }
        }

        // start logcat and dmesg
        if (mRunScript == null) {
            mLogcat = new Logcat(this, mLines * 4, logcatLevelOpts, logcatBufferOpts, logcatFormatOpt, mHandler);
            mDmesg = new Dmesg(this, mLines * 4, dmesgOpts, mHandler);
        }
    }

    private void loadModuleSettings() {
        File config = new File(CONFIG_PATH);
        if (config.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(config))) {
                String raw;
                while ((raw = reader.readLine()) != null) {
                    String line = raw.trim();
                    if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;
                    int split = line.indexOf('=');
                    if (split <= 0) continue;
                    String key = line.substring(0, split).trim().toLowerCase();
                    String value = line.substring(split + 1).trim();
                    try {
                        if ("message".equals(key)) {
                            // Accept a file name only; do not allow paths outside messages/.
                            if (value.length() > 0 && new File(value).getName().equals(value)
                                    && !value.equals(".") && !value.equals("..")) {
                                mMessageFileName = value;
                            }
                        } else if ("transparent".equals(key)) {
                            mTransparent = parseBoolean(value, mTransparent);
                        } else if ("opacity".equals(key) || "background_opacity".equals(key)) {
                            mBackgroundOpacity = clamp(Integer.parseInt(value), 0, 100);
                        } else if ("finish_hold_ms".equals(key)) {
                            mFinishHoldMs = clamp(Integer.parseInt(value), 0, 60000);
                        } else if ("image_opacity".equals(key)) {
                            mImageOpacity = clamp(Integer.parseInt(value), 0, 100);
                        } else if ("image_fit".equals(key)) {
                            if ("contain".equalsIgnoreCase(value)) mImageFit = 0;
                            else if ("cover".equalsIgnoreCase(value)) mImageFit = 1;
                            else if ("stretch".equalsIgnoreCase(value)) mImageFit = 2;
                            else Logger.d("Invalid image_fit; using cover");
                        } else if ("image_layer".equals(key)) {
                            if ("front".equalsIgnoreCase(value)) mConfiguredImageOnTop = true;
                            else if ("behind".equalsIgnoreCase(value)) mConfiguredImageOnTop = false;
                            else Logger.d("Invalid image_layer; using behind");
                            mImageOnTop = mConfiguredImageOnTop;
                        } else if ("dark".equals(key)) {
                            mDark = parseBoolean(value, mDark);
                        } else if ("text_size".equals(key)) {
                            mConfiguredTextSize = clamp(Integer.parseInt(value), 0, 256);
                        } else if ("font".equals(key)) {
                            loadConfiguredFont(value);
                        } else if ("line_background".equals(key)) {
                            mLineBackground = parseBoolean(value, mLineBackground);
                        } else if ("lines".equals(key)) {
                            mLines = clamp(Integer.parseInt(value), 1, 500);
                        } else if ("wordwrap".equals(key)) {
                            mWordWrap = parseBoolean(value, mWordWrap);
                        } else if ("save".equals(key)) {
                            mLogSave = parseBoolean(value, mLogSave);
                        } else if ("logcatcolors".equals(key)) {
                            mLogcatColor = parseBoolean(value, mLogcatColor);
                        }
                    } catch (NumberFormatException ignored) {
                        Logger.d("Invalid numeric setting: %s", key);
                    }
                }
            } catch (Exception e) {
                Logger.ex(e);
            }
        }

        File candidate = new File(MESSAGE_ROOT, mMessageFileName);
        try {
            String root = new File(MESSAGE_ROOT).getCanonicalPath() + File.separator;
            String resolved = candidate.getCanonicalPath();
            if (resolved.startsWith(root) && candidate.isFile()
                    && candidate.length() <= MAX_MESSAGE_FILE_BYTES) {
                mMessageFile = candidate;
                mUseCustomMessage = true;
                mMessagePlaybackFinished = false;
            } else {
                Logger.d("Custom message unavailable; using live log output");
            }
        } catch (Exception e) {
            Logger.ex(e);
            mUseCustomMessage = false;
            mMessagePlaybackFinished = true;
        }
    }

    private static final String FONT_STATUS_PATH = "/data/local/tmp/liveboot_font_status.log";
    private static final long MAX_FONT_FILE_BYTES = 32L * 1024L * 1024L;

    private static final class FontLoadResult {
        final Typeface typeface;
        final String method;
        final String error;
        final long bytes;

        FontLoadResult(Typeface typeface, String method, String error, long bytes) {
            this.typeface = typeface;
            this.method = method;
            this.error = error;
            this.bytes = bytes;
        }
    }

    private void writeFontStatus(String status) {
        String completeStatus = status + "; Typeface system map: " + sTypefaceSystemMapStatus;
        Logger.d("FONT: %s", completeStatus);
        try {
            File statusFile = new File(FONT_STATUS_PATH);
            File statusDirectory = statusFile.getParentFile();
            if (statusDirectory != null && !statusDirectory.exists()) statusDirectory.mkdirs();
            try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
                output.write((completeStatus + "\n").getBytes("UTF-8"));
                output.flush();
            }
        } catch (Exception e) {
            Logger.d("Could not write font status: %s", describeFontException(e));
        }
    }

    private static String describeFontException(Exception error) {
        return describeFontThrowable(error);
    }

    private static String describeFontThrowable(Throwable error) {
        if (error == null) return "unknown failure";
        StringBuilder description = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) description.append(" <- ");
            description.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && message.trim().length() > 0) {
                description.append(": ").append(message.trim());
            }
            current = current.getCause();
            depth++;
        }
        return description.toString();
    }

    private static boolean hasSupportedFontSignature(byte[] signature) {
        if (signature == null || signature.length < 4) return false;
        // TrueType, OpenType/CFF, legacy TrueType, and PostScript Type 1 sfnt headers.
        return (signature[0] == 0 && signature[1] == 1 && signature[2] == 0 && signature[3] == 0)
                || (signature[0] == 'O' && signature[1] == 'T' && signature[2] == 'T' && signature[3] == 'O')
                || (signature[0] == 't' && signature[1] == 'r' && signature[2] == 'u' && signature[3] == 'e')
                || (signature[0] == 't' && signature[1] == 'y' && signature[2] == 'p' && signature[3] == '1');
    }

    /** Open a font only from an explicitly approved directory and preserve loader exceptions. */
    private FontLoadResult tryLoadFont(File fontFile, String allowedRoot) {
        long bytes = 0L;
        String builderError = "";
        try {
            if (fontFile == null) throw new java.io.IOException("font path was not supplied by the launcher");
            String root = new File(allowedRoot).getCanonicalPath() + File.separator;
            String resolved = fontFile.getCanonicalPath();
            if (!resolved.startsWith(root)) {
                throw new java.io.IOException("font path is outside the approved directory: " + resolved);
            }
            if (!fontFile.isFile()) throw new java.io.IOException("font file does not exist: " + resolved);
            bytes = fontFile.length();
            if (bytes <= 0L) throw new java.io.IOException("font file is empty: " + resolved);
            if (bytes > MAX_FONT_FILE_BYTES) {
                throw new java.io.IOException("font exceeds 32 MiB: " + bytes + " bytes");
            }
            if (!fontFile.canRead()) throw new java.io.IOException("font is not readable: " + resolved);

            byte[] signature = new byte[4];
            try (FileInputStream input = new FileInputStream(fontFile)) {
                if (input.read(signature) != 4) {
                    throw new java.io.IOException("font header is truncated: " + resolved);
                }
            }
            if (!hasSupportedFontSignature(signature)) {
                throw new java.io.IOException("unsupported TTF/OTF signature in " + resolved);
            }

            Typeface loaded = null;
            String loadMethod = "";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    loaded = new Typeface.Builder(fontFile).build();
                    if (loaded != null) loadMethod = "Typeface.Builder";
                } catch (Exception e) {
                    builderError = describeFontException(e);
                    Logger.d("Typeface.Builder failed for %s: %s", resolved, builderError);
                }
            }
            if (loaded == null) {
                try {
                    loaded = Typeface.createFromFile(fontFile);
                    if (loaded != null) loadMethod = "Typeface.createFromFile";
                } catch (Exception e) {
                    String compatibilityError = describeFontException(e);
                    Logger.d("Typeface.createFromFile failed for %s: %s", resolved, compatibilityError);
                    if (builderError.length() == 0) builderError = compatibilityError;
                    else builderError += "; Typeface.createFromFile: " + compatibilityError;
                }
            }
            if (loaded == null) {
                throw new java.io.IOException("Android returned no Typeface"
                        + (builderError.length() == 0 ? "" : " (" + builderError + ")"));
            }
            if (builderError.length() > 0) {
                loadMethod += " (Builder fallback: " + builderError + ")";
            }
            return new FontLoadResult(loaded, loadMethod, "", bytes);
        } catch (Exception e) {
            return new FontLoadResult(null, "", describeFontException(e), bytes);
        }
    }

    private void loadConfiguredFont(String fileName) {
        if (fileName == null || fileName.trim().length() == 0
                || "default".equalsIgnoreCase(fileName.trim())
                || "monospace".equalsIgnoreCase(fileName.trim())) {
            mConfiguredTypeface = Typeface.MONOSPACE;
            writeFontStatus("BUILTIN: monospace selected by settings.conf");
            return;
        }

        String name = fileName.trim();
        String lowerName = name.toLowerCase(java.util.Locale.US);
        if (!new File(name).getName().equals(name) || name.startsWith(".")
                || !(lowerName.endsWith(".ttf") || lowerName.endsWith(".otf"))) {
            name = "";
        }

        String customError;
        FontLoadResult customResult = null;
        String stagedPath = System.getenv("LIVEBOOT_FONT_PATH");
        if (name.length() > 0 && stagedPath != null && stagedPath.length() > 0
                && new File(stagedPath).getName().equals(name)) {
            customResult = tryLoadFont(new File(stagedPath), STAGED_FONT_ROOT);
            customError = customResult.error;
        } else if (name.length() == 0) {
            customError = "invalid font filename; expected a simple .ttf or .otf filename";
        } else {
            customError = "configured font was not staged by the launcher (LIVEBOOT_FONT_PATH missing or mismatched)";
        }

        if (customResult != null && customResult.typeface != null) {
            mConfiguredTypeface = customResult.typeface;
            writeFontStatus("LOADED: " + name + " from staged copy via " + customResult.method
                    + " (" + customResult.bytes + " bytes)");
            return;
        }

        // A failed custom font must not silently revert to Android's unrelated monospace face.
        // First use the separately staged bundled font, then try the module copy as a last resort
        // in case /data/local/tmp is blocked by this device's SELinux policy.
        String fallbackPath = System.getenv("LIVEBOOT_FONT_FALLBACK_PATH");
        FontLoadResult fallbackResult = null;
        String fallbackError = "staged LiveBootMono.ttf path was not provided";
        if (fallbackPath != null && fallbackPath.length() > 0
                && "LiveBootMono.ttf".equals(new File(fallbackPath).getName())) {
            fallbackResult = tryLoadFont(new File(fallbackPath), STAGED_FONT_ROOT);
            fallbackError = fallbackResult.error;
        }
        if (fallbackResult == null || fallbackResult.typeface == null) {
            FontLoadResult moduleFallback = tryLoadFont(
                    new File(FONT_ROOT, "LiveBootMono.ttf"), FONT_ROOT);
            if (moduleFallback.typeface != null) {
                fallbackResult = moduleFallback;
            } else {
                fallbackError += "; module copy: " + moduleFallback.error;
            }
        }

        if (fallbackResult != null && fallbackResult.typeface != null) {
            mConfiguredTypeface = fallbackResult.typeface;
            writeFontStatus("FALLBACK: custom font " + (name.length() == 0 ? fileName : name)
                    + " failed: " + customError + "; loaded bundled LiveBootMono.ttf via "
                    + fallbackResult.method + " (" + fallbackResult.bytes + " bytes)");
        } else {
            mConfiguredTypeface = Typeface.MONOSPACE;
            writeFontStatus("FALLBACK: custom font " + (name.length() == 0 ? fileName : name)
                    + " failed: " + customError + "; LiveBootMono.ttf also failed: " + fallbackError
                    + "; using Android built-in monospace");
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value) || "0".equals(value)) return false;
        if ("on".equalsIgnoreCase(value)) return true;
        return fallback;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    protected void onDone() {
        if (mMessagePlaybackThread != null && mMessagePlaybackThread.isAlive()) {
            mMessagePlaybackThread.interrupt();
        }
        if (mHandlerThread != null) mHandlerThread.quit();
        if (mLogcat != null) mLogcat.destroy();
        if (mDmesg != null) mDmesg.destroy();
        if (mScript != null) mScript.destroy();
    }

    @Override
    protected void onInitRender() {
        mComplete = 0;
        updateLineCount();
        mTextureManager = new GLTextureManager();
        mHelper = new GLHelper(mWidth, mHeight, GLHelper.getDefaultVMatrix());
        mTextManager = new SpanTextManager(mTextureManager, mHelper, mWidth, mHeight,
                computeLineHeight(), mConfiguredTypeface, mConfiguredTextSize, mLineBackground);

        GLPicture.initGl();
        initImageRenderer();

        if (mUseCustomMessage) {
            startCustomMessagePlayback();
        } else {
            // Describe the actual device; never hard-code one model in a public module.
            String model = SystemProperties.get("ro.product.model", "");
            if (model == null || model.trim().length() == 0) model = "Android device";
            String release = SystemProperties.get("ro.build.version.release", "");
            onLine(this, model, Color.GREEN);
            onLine(this, "Custom LiveBoot test", Color.WHITE);
            onLine(this, (release == null || release.length() == 0) ? "Android" : "Android " + release, Color.CYAN);
        }

        // Preserve the original logcat/dmesg readers and optional log-to-file behavior.
        if (mRunScript == null) {
            if (mDmesg != null) mDmesg.setReady();
            if (mLogcat != null) mLogcat.setReady();
        } else {
            mScript = new Script(this, mRunScript);
        }
    }

    @Override
    public void onGLRenderFrame() {
        GLES20.glDisable(GLES20.GL_BLEND);
        float fade = 1.0f;
        if (mComplete > 0) {
            fade = Math.max(0.0f, 1.0f - ((float) (SystemClock.elapsedRealtime() - mComplete) / (float) LEAD_TIME));
        }

        float shade = mDark ? 0.0f : 0.2f;
        float backgroundAlpha = mTransparent ? (mBackgroundOpacity / 100.0f) * fade : fade;
        GLES20.glClearColor(shade, shade, shade, backgroundAlpha);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        renderPendingImageChange();
        // Layer order, bottom to top: image(behind), text, image(front).
        if (!mImageOnTop) drawBackgroundImage(fade);
        if (mTextManager != null) mTextManager.draw();
        if (mImageOnTop) drawBackgroundImage(fade);
    }

    @Override
    protected void onDoneRender() {
        if (mImageTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mImageTextureId }, 0);
            mImageTextureId = 0;
        }
        if (mImageProgram != 0) {
            GLES20.glDeleteProgram(mImageProgram);
            mImageProgram = 0;
        }
        synchronized (mPendingImageLock) {
            if (mPendingImageBitmap != null) {
                mPendingImageBitmap.recycle();
                mPendingImageBitmap = null;
            }
            mImageChangePending = false;
        }
        mTextManager.destroy();
        mTextManager = null;
        mTextureManager.destroy();
        mTextureManager = null;
    }

    @Override
    public void onLine(Object sender, String text, int color) {
        // In custom-message mode, live log lines must not race or overwrite the message script.
        // onLog() still records output to /cache/liveboot.log when saving is enabled.
        if (mUseCustomMessage) return;

        final String t = text == null ? "" : text;
        final int c = color;
        final Object source = sender;
        if (mHandler == null) return;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mTextManager == null) return;
                long wait = 0L;
                if (mComplete == 0) {
                    if (mFirstLine == 0) mFirstLine = SystemClock.elapsedRealtime();
                    wait = mFirstLine;
                } else {
                    wait = mComplete;
                }
                mLinesPassed++;
                while (SystemClock.elapsedRealtime() - wait < Math.min(LEAD_TIME,
                        (int) ((float) LEAD_TIME * ((float) mLinesPassed / (float) Math.max(1, mLines))))) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (mComplete == 0) {
                    int outputColor = c;
                    if ((source == mLogcat) && !mLogcatColor) outputColor = Color.WHITE;
                    mTextManager.add(t, outputColor, mWordWrap);
                } else {
                    mTextManager.add("", Color.WHITE, mWordWrap);
                }
            }
        });
    }

    private void startCustomMessagePlayback() {
        mMessagePlaybackFinished = false;
        mMessagePlaybackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> rawLines = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new FileReader(mMessageFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (rawLines.size() >= 10000) throw new IllegalArgumentException("Message script has too many lines");
                            rawLines.add(line);
                        }
                    }
                    List<ScriptParser.ScriptLine> script = ScriptParser.parse(rawLines);
                    for (ScriptParser.ScriptLine item : script) {
                        sleepScriptDelay(item.wait);
                        sleepScriptDelay(item.pause);
                        if (Thread.currentThread().isInterrupted()) break;
                        final Bitmap imageBitmap;
                        if (item.isImage && !isImageClearDirective(item.imageName)) {
                            imageBitmap = loadMessageImage(item.imageName);
                            if (imageBitmap == null) {
                                Logger.d("Skipping unavailable message image: %s", item.imageName);
                                continue;
                            }
                        } else {
                            imageBitmap = null;
                        }
                        runOnHandlerAndWait(new Runnable() {
                            @Override
                            public void run() {
                                applyScriptLine(item, imageBitmap);
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Logger.ex(e);
                    runOnHandlerAndWaitQuietly(new Runnable() {
                        @Override
                        public void run() {
                            if (mTextManager != null) mTextManager.add("Message loading failed", Color.RED, mWordWrap);
                        }
                    });
                } finally {
                    mMessagePlaybackFinished = true;
                }
            }
        }, "LiveBoot Message Script");
        mMessagePlaybackThread.setDaemon(true);
        mMessagePlaybackThread.start();
    }

    private void sleepScriptDelay(long millis) throws InterruptedException {
        long remaining = Math.max(0L, millis);
        while (remaining > 0L && !Thread.currentThread().isInterrupted()) {
            long chunk = Math.min(remaining, 100L);
            Thread.sleep(chunk);
            remaining -= chunk;
        }
    }

    private void runOnHandlerAndWait(Runnable action) throws InterruptedException {
        if (mHandler == null) {
            action.run();
            return;
        }
        final CountDownLatch complete = new CountDownLatch(1);
        boolean posted = mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    complete.countDown();
                }
            }
        });
        if (!posted) throw new InterruptedException("LiveBoot render handler is shutting down");
        // Bounded wait: if the render handler thread dies with this task still queued, the latch
        // would never fire and the overlay would hang forever waiting for the script to finish.
        while (!complete.await(250, TimeUnit.MILLISECONDS)) {
            if (mHandlerThread == null || !mHandlerThread.isAlive()) {
                throw new InterruptedException("LiveBoot render handler stopped");
            }
        }
    }

    private void runOnHandlerAndWaitQuietly(Runnable action) {
        try {
            runOnHandlerAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isImageClearDirective(String imageName) {
        return imageName == null || imageName.trim().length() == 0
                || "none".equalsIgnoreCase(imageName.trim())
                || "clear".equalsIgnoreCase(imageName.trim());
    }

    /** Resolve and decode a bundled image without allowing paths outside the module images/ folder. */
    private Bitmap loadMessageImage(String imageName) {
        if (imageName == null) return null;
        String name = imageName.trim();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!new File(name).getName().equals(name)
                || !(lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp"))) {
            Logger.d("Rejected invalid image filename");
            return null;
        }
        File file = new File(IMAGE_ROOT, name);
        try {
            String root = new File(IMAGE_ROOT).getCanonicalPath() + File.separator;
            String resolved = file.getCanonicalPath();
            if (!resolved.startsWith(root) || !file.isFile()
                    || file.length() <= 0 || file.length() > MAX_IMAGE_FILE_BYTES) {
                Logger.d("Image missing or exceeds size limit: %s", name);
                return null;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false; // Image files use pixel dimensions, never resource-density scaling.
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logger.d("Unsupported or invalid image: %s", name);
                return null;
            }

            int sample = 1;
            while ((bounds.outWidth / sample > MAX_IMAGE_DIMENSION)
                    || (bounds.outHeight / sample > MAX_IMAGE_DIMENSION)
                    || (((long) bounds.outWidth / sample) * ((long) bounds.outHeight / sample)
                    > MAX_DECODED_IMAGE_PIXELS)) {
                if (sample >= (1 << 29)) break;
                sample *= 2;
            }
            long decodedWidth = (long) bounds.outWidth / sample;
            long decodedHeight = (long) bounds.outHeight / sample;
            if (decodedWidth <= 0 || decodedHeight <= 0
                    || decodedWidth > MAX_IMAGE_DIMENSION || decodedHeight > MAX_IMAGE_DIMENSION
                    || decodedWidth * decodedHeight > MAX_DECODED_IMAGE_PIXELS) {
                Logger.d("Image dimensions exceed safe decode limit: %s", name);
                return null;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decode.inScaled = false; // Preserve authored resolution such as 1080x2400 exactly.
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
            if (bitmap == null) Logger.d("Could not decode image: %s", name);
            return bitmap;
        } catch (Exception e) {
            Logger.d("Could not load message image: %s", name);
            return null;
        }
    }

    private void queueImageChange(Bitmap bitmap) {
        synchronized (mPendingImageLock) {
            if (mPendingImageBitmap != null && mPendingImageBitmap != bitmap) {
                mPendingImageBitmap.recycle();
            }
            mPendingImageBitmap = bitmap;
            mImageChangePending = true;
        }
    }

    private void initImageRenderer() {
        final String vertexShader =
                "attribute vec2 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform vec2 uScale;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main(){ gl_Position = vec4(aPosition * uScale, 0.0, 1.0); vTexCoord = aTexCoord; }";
        final String fragmentShader =
                "precision mediump float;\n" +
                "uniform sampler2D uTexture;\n" +
                "uniform float uOpacity;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main(){ vec4 c = texture2D(uTexture, vTexCoord); gl_FragColor = vec4(c.rgb, c.a * uOpacity); }";
        int vertex = compileImageShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragment = compileImageShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES20.glDeleteShader(vertex);
            if (fragment != 0) GLES20.glDeleteShader(fragment);
            return;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            Logger.d("Could not link image renderer shader program");
            GLES20.glDeleteProgram(program);
            return;
        }
        mImageProgram = program;
        mImagePositionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        mImageTexCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
        mImageScaleLocation = GLES20.glGetUniformLocation(program, "uScale");
        mImageOpacityLocation = GLES20.glGetUniformLocation(program, "uOpacity");
        mImageSamplerLocation = GLES20.glGetUniformLocation(program, "uTexture");
        // Bottom vertices use V=1 because Android bitmaps are stored top-row first.
        float[] vertices = {
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
        };
        mImageVertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mImageVertexBuffer.put(vertices).position(0);
    }

    private int compileImageShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) return 0;
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Logger.d("Could not compile image renderer shader");
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** Apply bitmap changes on the GL thread, where the current GL context is valid. */
    private void renderPendingImageChange() {
        final Bitmap bitmap;
        synchronized (mPendingImageLock) {
            if (!mImageChangePending) return;
            bitmap = mPendingImageBitmap;
            mPendingImageBitmap = null;
            mImageChangePending = false;
        }
        if (mImageTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mImageTextureId }, 0);
            mImageTextureId = 0;
            mImageTextureWidth = 0;
            mImageTextureHeight = 0;
        }
        if (bitmap == null) return; // [image=none] or [image=clear]
        if (mImageProgram == 0) {
            bitmap.recycle();
            Logger.d("Image renderer unavailable; image skipped");
            return;
        }
        try {
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            if (texture[0] == 0) {
                bitmap.recycle();
                Logger.d("Could not allocate image texture");
                return;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            mImageTextureId = texture[0];
            mImageTextureWidth = bitmap.getWidth();
            mImageTextureHeight = bitmap.getHeight();
            Logger.d("Image ready: %dx%d; display viewport: %dx%d; layer=%s",
                    mImageTextureWidth, mImageTextureHeight, mWidth, mHeight, mImageOnTop ? "front" : "behind");
        } catch (Exception e) {
            Logger.d("Failed to upload image to GL texture");
        } finally {
            bitmap.recycle();
        }
    }

    private void drawBackgroundImage(float fade) {
        if (mImageTextureId == 0 || mImageProgram == 0 || mImageTextureWidth <= 0
                || mImageTextureHeight <= 0 || mWidth <= 0 || mHeight <= 0
                || mImageOpacity <= 0) return;
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float imageAspect = (float) mImageTextureWidth / (float) mImageTextureHeight;
        float screenAspect = (float) mWidth / (float) mHeight;
        if (mImageFit == 0) { // contain: show the whole image, letterboxing as needed
            if (imageAspect > screenAspect) scaleY = screenAspect / imageAspect;
            else scaleX = imageAspect / screenAspect;
        } else if (mImageFit == 1) { // cover: fill screen, cropping overflow
            if (imageAspect > screenAspect) scaleX = imageAspect / screenAspect;
            else scaleY = screenAspect / imageAspect;
        } // stretch intentionally leaves both scales at 1

        GLES20.glUseProgram(mImageProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mImageTextureId);
        mImageVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mImagePositionLocation);
        GLES20.glVertexAttribPointer(mImagePositionLocation, 2, GLES20.GL_FLOAT, false, 16, mImageVertexBuffer);
        mImageVertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mImageTexCoordLocation);
        GLES20.glVertexAttribPointer(mImageTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, mImageVertexBuffer);
        GLES20.glUniform2f(mImageScaleLocation, scaleX, scaleY);
        GLES20.glUniform1f(mImageOpacityLocation, (mImageOpacity / 100.0f) * fade);
        GLES20.glUniform1i(mImageSamplerLocation, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mImagePositionLocation);
        GLES20.glDisableVertexAttribArray(mImageTexCoordLocation);
    }


    private void applyScriptLine(ScriptParser.ScriptLine item, Bitmap imageBitmap) {
        if (mTextManager == null || item == null) {
            if (imageBitmap != null) imageBitmap.recycle();
            return;
        }

        if (item.isImage) {
            if (item.imageLayer == 1) mImageOnTop = true;
            else if (item.imageLayer == 0) mImageOnTop = false;
            else mImageOnTop = mConfiguredImageOnTop;
            queueImageChange(imageBitmap);
            return;
        }

        if (item.isClear) {
            mDisplayedLines.clear();
            for (int i = 0; i < mLines; i++) mTextManager.add("", Color.WHITE, mWordWrap);
            return;
        }

        if (item.isFill) {
            for (int i = 0; i < mLines; i++) mTextManager.add("", Color.WHITE, mWordWrap);
            return;
        }

        if (item.isReplace) {
            boolean changed = false;
            for (DisplayLine line : mDisplayedLines) {
                List<TextSpan> replaced = TextSpan.replace(line.spans, item.oldText, item.newText);
                if (replaced != null) {
                    line.spans = replaced;
                    changed = true;
                }
            }
            if (changed) redrawDisplayedLines();
            return;
        }

        List<TextSpan> spans;
        if (item.isProgress) {
            int barLength = 40;
            int filled = (item.progressValue * barLength) / 100;
            StringBuilder bar = new StringBuilder();
            bar.append('[');
            for (int i = 0; i < barLength; i++) bar.append(i < filled ? '#' : '-');
            bar.append("] ").append(item.progressValue).append('%');
            spans = TextSpan.single(bar.toString(), item.color);
        } else {
            spans = item.spans;
        }

        if (item.replacePrevious && !mDisplayedLines.isEmpty()) {
            mDisplayedLines.set(mDisplayedLines.size() - 1, new DisplayLine(spans));
            redrawDisplayedLines();
            return;
        }

        mDisplayedLines.add(new DisplayLine(spans));
        int historyLimit = Math.max(100, mLines * 4);
        while (mDisplayedLines.size() > historyLimit) mDisplayedLines.remove(0);
        mTextManager.add(spans, mWordWrap);
    }

    /** Rebuild the visible text buffer after an edit to scripted message history. */
    private void redrawDisplayedLines() {
        for (int i = 0; i < mLines; i++) mTextManager.add("", Color.WHITE, mWordWrap);
        for (DisplayLine line : mDisplayedLines) mTextManager.add(line.spans, mWordWrap);
    }

    @Override
    public void onLog(Object sender, String text) {
        if (mLogSave) {
            mLogLock.lock();
            try {
                if (mLogStream != null) {
                    try {
                        mLogStream.write((text + "\n").getBytes());
                    } catch (Exception e) {
                    }
                }
            } finally {
                mLogLock.unlock();
            }
        }
    }

    @Override
    protected void onMainLoop() {
        if (mTest) {
            try {
                Thread.sleep(TEST_TIME);
            } catch (Exception e) {
            }
        } else {
            long start = SystemClock.elapsedRealtime();
            boolean bootAnimationSeen = false;
            boolean bootAnimationGone = false;
            boolean bootAnimationKilled = false;
            long complete = 0;
            while (true) {
                // do not check sys.boot_completed or dev.bootcomplete, as these are already set to 1 before entering decryption password
                long now = SystemClock.elapsedRealtime();
                if ((complete == 0) && SystemProperties.get("service.bootanim.exit", "0").equals("1")) {
                    // sign from Android that we should quit
                    Logger.d("service.bootanim.exit");
                    complete = now;
                }
                if ((complete == 0) && SystemProperties.get("service.bootanim.completed", "0").equals("1")) {
                    // sign from Android that we should quit
                    Logger.d("service.bootanim.completed");
                    complete = now;
                }
                if ((complete == 0) && !bootAnimationSeen && (now - start > 1500)) {
                    // register if we ever saw the bootanimation
                    if (isBootAnimationRunning()) {
                        Logger.d("bootAnimationSeen");
                        bootAnimationSeen = true;
                    }
                }
                if (bootAnimationSeen && !bootAnimationGone && (now - start > 2500)) {
                    // if we saw the bootanimation before and its gone now, note that
                    if (!isBootAnimationRunning()) {
                        Logger.d("bootAnimationGone");
                        bootAnimationGone = true;
                    }
                }
                if ((complete > 0) && bootAnimationSeen && !bootAnimationGone && !bootAnimationKilled) {
                    // if we have an exit sign from Android and bootanimation is still running, kill it
                    Logger.d("bootAnimationkill");
                    killBootAnimation();
                    bootAnimationKilled = true;
                    if (!isBootAnimationRunning()) {
                        Logger.d("bootAnimationkill/Gone");
                        bootAnimationGone = true;
                    }
                }
                if ((complete == 0) && (new File(LIVEBOOT_ABORT_FILE)).exists()) {
                    Logger.d("bootCompleteAbortFromAPK");
                    complete = now;
                    bootAnimationSeen = true;
                    bootAnimationKilled = true;
                    bootAnimationGone = true;
                }

                if (
                        // Android has signaled to quit, and we haven't seen bootanimation
                        ((complete > 0) && !bootAnimationSeen) ||

                        // bootanimation has come and gone
                        (bootAnimationSeen && bootAnimationGone) ||

                        // Android has signaled to quit, we've seen the bootanimation, but it's still there after 2.5 seconds
                        (bootAnimationSeen && (complete > 0) && (now - complete > 2500))
                ) {
                    Logger.dp("EXIT", "exit sequence");
                    break;
                }
                try {
                    Thread.sleep(64);
                } catch (Exception e) {
                }
            }
            Logger.d("Runtime: %dms", SystemClock.elapsedRealtime() - start);
        }
        // Keep the overlay alive until the final script action has been rendered, even in test mode.
        while (mUseCustomMessage && !mMessagePlaybackFinished) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Give the last scripted line a visible dwell time before the normal fade/shutdown.
        if (mUseCustomMessage && mFinishHoldMs > 0) {
            try {
                Thread.sleep(mFinishHoldMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        mComplete = SystemClock.elapsedRealtime();
        mLinesPassed = 0;
        if (!mUseCustomMessage) {
            // Keep the original live-log clearing behavior. For scripted messages, leave
            // the final frame intact during LEAD_TIME so the last line is actually visible.
            for (int i = 0; i < (mLines * 5) / 4; i++) onLine(null, "", Color.WHITE);
        }
        try {
            Thread.sleep(LEAD_TIME);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (mLogSave) {
            mLogLock.lock();
            try {
                try {
                    mLogStream.close();
                } catch (Exception e) {
                }
                mLogStream = null;
            } finally {
                mLogLock.unlock();
            }
        }
        killBootAnimation();
        infanticide();
        suicide();
    }
}
