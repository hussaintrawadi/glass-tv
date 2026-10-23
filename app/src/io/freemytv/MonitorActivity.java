package io.freemytv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Performance, laid out like a tvOS Settings page: on the left a large symbol and the title; on the right the memory
 * and storage in use (live) and three actions. "Free Up Memory" asks Android to close the apps waiting in the
 * background (what it does by itself when memory runs out; the app on screen and system services stay).
 */
public final class MonitorActivity extends Activity {
    private static final long REFRESH_MS = 2000;
    private static final String[] ACTIONS = {"Sound", "Free Up Memory", "Manage Storage", "Apps"};
    private static final String[] SOUND_MODES = {SoundService.OFF, SoundService.VOICE, SoundService.BOOST};
    private static final String[] SOUND_NAMES = {"Off", "Clear Voice", "Smart Volume"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityManager am;
    private Page page;
    private long memTotal, memFree, diskTotal, diskFree;
    private int ownMb, focus;
    private boolean cleaning;
    private String result = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            measure();
            page.invalidate();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        page = new Page(this);
        setContentView(page);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void measure() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        memTotal = mi.totalMem;
        memFree = mi.availMem;
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        diskTotal = fs.getTotalBytes();
        diskFree = fs.getAvailableBytes();
        ownMb = (int) (Debug.getPss() / 1024);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focus = Math.max(0, focus - 1);
                page.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focus = Math.min(ACTIONS.length - 1, focus + 1);
                page.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) act();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void act() {
        if (focus == 0) {
            // Off → Clear Voice → Smart Volume → Off
            String now = SoundService.mode(this);
            int i = 0;
            while (i < SOUND_MODES.length && !SOUND_MODES[i].equals(now)) i++;
            SoundService.set(this, SOUND_MODES[(i + 1) % SOUND_MODES.length]);
            page.invalidate();
            return;
        }
        if (focus == 1) {
            clean();
            return;
        }
        Intent i = new Intent(focus == 2 ? Settings.ACTION_INTERNAL_STORAGE_SETTINGS : Settings.ACTION_APPLICATION_SETTINGS);
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void clean() {
        if (cleaning) return;
        cleaning = true;
        result = "";
        page.invalidate();
        Context app = getApplicationContext();
        new Thread(() -> {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long before = mi.availMem;
            int asked = 0;
            List<ApplicationInfo> apps = app.getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo a : apps) {
                if (a.packageName.equals(app.getPackageName())) continue;
                try {
                    am.killBackgroundProcesses(a.packageName);
                    asked++;
                } catch (SecurityException ignored) {
                    // not ours to close
                }
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}   // the system reclaims the memory
            am.getMemoryInfo(mi);
            long freed = Math.max(0, mi.availMem - before);
            AppLog.w("cleaner: asked " + asked + " apps to close, " + (freed >> 20) + " MB freed");
            handler.post(() -> {
                cleaning = false;
                result = freed >= (8L << 20) ? String.format(Locale.US, "%d MB freed", freed >> 20) : "Already optimized";
                measure();
                page.invalidate();
            });
        }, "cleaner").start();
    }

    private String soundName() {
        String now = SoundService.mode(this);
        for (int i = 0; i < SOUND_MODES.length; i++) if (SOUND_MODES[i].equals(now)) return SOUND_NAMES[i];
        return SOUND_NAMES[0];
    }

    private static String size(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 1 ? String.format(Locale.US, "%.1f GB", gb) : String.format(Locale.US, "%d MB", bytes >> 20);
    }

    /** The page, drawn on one Canvas in 1920×1080 units. */
    private final class Page extends View {
        private Paint title, caption, header, rowText, rowValue, focusText, focusValue, note;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint();
        private final RectF r = new RectF();
        private float k;

        Page(Context c) {
            super(c);
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.semibold, 52 * k, 0xFFF5F5F7);
            caption = Ui.text(Ui.medium, 28 * k, 0x99EBEBF5);
            header = Ui.text(Ui.semibold, 24 * k, 0x80EBEBF5);
            header.setLetterSpacing(0.08f);
            rowText = Ui.text(Ui.medium, 32 * k, 0xFFF5F5F7);
            rowValue = Ui.numbers(Ui.medium, 32 * k, 0x99EBEBF5);
            focusText = Ui.text(Ui.semibold, 32 * k, 0xFF1D1D1F);
            focusValue = Ui.numbers(Ui.medium, 32 * k, 0x991D1D1F);
            note = Ui.text(Ui.medium, 24 * k, 0x80EBEBF5);
            glow.setShader(new RadialGradient(w * 0.25f, h * 0.45f, w * 0.45f, 0xFF1C1C24, 0xFF0A0A0C, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null) return;
            c.drawRect(0, 0, getWidth(), getHeight(), glow);

            // Left: the symbol in a soft disc, the title and a caption.
            float cx = 480 * k, cy = 430 * k;
            fill.setColor(0x1FFFFFFF);
            c.drawCircle(cx, cy, 150 * k, fill);
            Symbols.draw(getContext(), c, "monitor", cx, cy, 150 * k, 0xF2FFFFFF);
            center(c, "Performance & Sound", cx, 680 * k, title);
            center(c, Config.BRAND + " uses " + ownMb + " MB", cx, 734 * k, caption);

            // Right: two information groups, then the actions.
            float x = 1000 * k, right = 1800 * k, y = 120 * k;
            y = group(c, "MEMORY", x, right, y, size(memFree) + " available", size(memTotal - memFree) + " used of " + size(memTotal),
                    frac(memTotal - memFree, memTotal), 0xFFBF5AF2);
            y = group(c, "STORAGE", x, right, y + 40 * k, size(diskFree) + " available", size(diskTotal - diskFree) + " used of " + size(diskTotal),
                    frac(diskTotal - diskFree, diskTotal), 0xFF0A84FF);
            y += 56 * k;
            c.drawText("SOUND AND MEMORY", x, y, header);
            y += 22 * k;
            for (int i = 0; i < ACTIONS.length; i++) {
                r.set(x - 24 * k, y, right + 24 * k, y + 80 * k);
                boolean f = i == focus;
                if (f) {
                    fill.setColor(0xFFF5F5F7);
                    c.drawRoundRect(r, 18 * k, 18 * k, fill);
                }
                String label = i == 1 && cleaning ? "Freeing Up Memory…" : ACTIONS[i];
                float base = r.centerY() + Ui.halfCap(rowText);
                c.drawText(label, x, base, f ? focusText : rowText);
                String value = i == 0 ? soundName() : i == 1 ? result : "›";
                if (!value.isEmpty()) {
                    Paint p = f ? focusValue : rowValue;
                    c.drawText(value, right - p.measureText(value), base, p);
                }
                y += 86 * k;
            }
            String tip = focus == 0 ? "Smart Volume: loud scenes fade down, quiet ones fade up."
                    : focus == 1 ? "Closes apps waiting in the background." : "Opens the TV's own settings.";
            // Always inside the column, whatever the language or text.
            tip = android.text.TextUtils.ellipsize(tip, new android.text.TextPaint(note), right - x, android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(tip, x, y + 26 * k, note);
        }

        /** A group: header, a row with the main value, a thin meter and the detail. Returns the y after it. */
        private float group(Canvas c, String name, float x, float right, float y, String main, String detail, float f, int color) {
            c.drawText(name, x, y, header);
            r.set(x - 24 * k, y + 22 * k, right + 24 * k, y + 180 * k);
            fill.setColor(0x14FFFFFF);
            c.drawRoundRect(r, 18 * k, 18 * k, fill);
            c.drawText(main, x, y + 78 * k, rowText);
            String pct = Math.round(f * 100) + "%";
            c.drawText(pct, right - rowValue.measureText(pct), y + 78 * k, rowValue);
            float barY = y + 102 * k, barH = 10 * k;
            r.set(x, barY, right, barY + barH);
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(r, barH / 2, barH / 2, fill);
            r.right = x + Math.max(barH, (right - x) * f);
            fill.setColor(color);
            c.drawRoundRect(r, barH / 2, barH / 2, fill);
            c.drawText(detail, x, y + 158 * k, caption);
            return y + 180 * k;
        }

        private float frac(long part, long total) {
            return total <= 0 ? 0 : Math.max(0, Math.min(1, part / (float) total));
        }

        private void center(Canvas c, String s, float cx, float y, Paint p) {
            c.drawText(s, cx - p.measureText(s) / 2, y, p);
        }
    }
}
