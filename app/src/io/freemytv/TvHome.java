package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * Glass TV home screen. Top to bottom: "For You" with the Explore / Movies / Shows tabs and the time; the featured
 * title in a large rounded card; your apps in a glass dock (the first row); then rows of what's trending and popular on
 * each of your apps. A glass rail on the left holds Home, All Apps, Performance and Settings. The whole screen is lit by
 * a soft blur of the featured artwork.
 *
 * Holding OK on an app in the dock picks it up: left and right move it, down takes it off the home screen, OK puts it
 * down. Holding OK on an app in All Apps adds it to the dock.
 *
 * Made for 1 GB TVs: one View on one Canvas, nothing drawn while still, only what's on screen is drawn, row images are
 * small (w300, RGB 565), the frosted glass reads one tiny pre-blurred copy of the artwork (no live blur), and nothing is
 * allocated while animating. Layout in 1920 × 1080 units, scaled by k.
 */
final class TvHome extends View implements Choreographer.FrameCallback, Rows.Listener {
    // Layout (1920 × 1080 units)
    private static final float M = 96, RIGHT = 1824;
    private static final float HEAD_TOP = 58, HEAD_H = 60;
    /** The featured title fills the screen (TMDB's artwork is 16:9, like the screen: nothing is cropped). */
    private static final float HERO_TEXT = 430;
    private static final float DOCK_TOP = 838, DOCK_PAD = 28, TILE_W = 196, TILE_H = 110, TILE_GAP = 24;
    /** The rows start below the screen: they show only when you scroll down from the dock. */
    private static final float ROWS_TOP = 1130, ROW_STEP = 276, CARD_W = 300, CARD_H = 169, CARD_GAP = 26, CARD_DY = 46;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP_X = 40, GRID_STEP_Y = 262, GRID_TOP = 210;
    private static final int GRID_COLUMNS = 5;
    /** How many titles the banner cycles through. */
    private static final int FEATURED = 10;
    private static final long HERO_EVERY = 9000, LONG_PRESS_MS = 550, TOAST_MS = 2600;
    private static final float FADE_MS = 700;

    // Apple's dark palette
    private static final int BG = 0xFF08080A, LABEL = 0xFFF5F5F7, SECONDARY = 0x99EBEBF5, TERTIARY = 0x59EBEBF5, INK = 0xFF1D1D1F;

    // Focus zones
    private static final int Z_TOP = 0, Z_TABS = 1, Z_HERO = 2, Z_DOCK = 3, Z_ROW = 4;
    private static final String[] TABS = {"Explore", "Movies", "Shows"};
    /** The buttons in the top right capsule, before the time. */
    private static final String[] TOP = {"apps", "monitor", "settings"};
    private static final String[] TOP_NAMES = {"All Apps", "Performance", "Settings"};

    private final Activity activity;
    private final Rows rows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Shadows shadows = new Shadows();

    private float k = 1;
    private int w, h;
    private boolean running, animating;
    private long lastNanos;

    // Content
    private List<HomeApps.Item> apps = new ArrayList<>();
    private List<HomeApps.Item> others = new ArrayList<>();
    private List<Rows.Row> allRows = new ArrayList<>(), rowList = new ArrayList<>();
    private List<Rows.Title> allFeatured = new ArrayList<>(), featured = new ArrayList<>();
    private int tab;
    /** The last tile of the dock: opens the app drawer to add an app. */
    private final HomeApps.Item addTile = HomeApps.action(HomeApps.ALL_APPS, "add", "Add App", "plus");

    // Focus
    private int zone = Z_DOCK, topIndex, heroIndex, appIndex, row, sheetIndex;
    private int[] rowIndex = new int[0];
    private Spring[] rowOffset = new Spring[0];
    private final Spring dockOffset = new Spring(0.38f, 0.92f, 0);
    private final Spring scroll = new Spring(0.42f, 0.95f, 0);
    private final Spring pop = new Spring(0.3f, 0.8f, 0), unpop = new Spring(0.3f, 0.9f, 0);
    private final Spring sheet = new Spring(0.34f, 0.95f, 0), sheetScroll = new Spring(0.38f, 0.95f, 0);
    private final Spring tabPill = new Spring(0.3f, 0.86f, 0);
    private boolean sheetOpen, picking, moving;   // picking: the drawer opened by "+" (OK adds the app to Home)
    private int prevZone = -1, prevRow, prevIndex;

    // Holding OK
    private boolean okDown, longFired;
    private final Runnable longPress = this::onLongPress;

    // Featured title
    private Rows.Title heroShown, heroFrom;
    private long heroFadeAt;
    /** The title the banner is heading to; it starts once its plate is ready. -1: none. */
    private int heroWanted = -1;

    /**
     * A featured title ready to show: the artwork at screen size with the dark shading already on it (one full-screen
     * draw per frame, two while fading), its blurs ([0] background, [1] glass, [2] the rows' dark backdrop) and its
     * text laid out. Made off the UI thread; only the shown, the outgoing and the next one are kept.
     */
    private static final class Plate {
        Bitmap art;
        Bitmap[] blur;
        String service, title, meta, watch;
        float titleSize;
        android.text.StaticLayout overview;
        TextPaint overviewPaint;
        int serviceColor;
        boolean saving;   // being written to disk: its bitmap isn't reused meanwhile
    }

    private final HashMap<Rows.Title, Plate> plates = new HashMap<>();
    /**
     * Screen-size bitmaps of plates no longer shown, reused for the next ones: allocating 3.7 MB for every title made
     * Android stop to collect garbage mid-animation. One is reused only a while after it left the screen.
     */
    private final java.util.ArrayDeque<Bitmap> spare = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> spareAt = new java.util.ArrayDeque<>();

    private void release(Plate p) {
        if (p == null || p.art == null || !p.art.isMutable() || p.saving || spare.size() >= 2) return;
        spare.add(p.art);
        spareAt.add(SystemClock.uptimeMillis());
    }

    /** A spare screen-size bitmap that has been off screen for half a second, or null. */
    private Bitmap takeSpare() {
        if (spare.isEmpty() || SystemClock.uptimeMillis() - spareAt.peekFirst() < 500) return null;
        spareAt.pollFirst();
        return spare.pollFirst();
    }
    private final java.util.Set<Rows.Title> plating = new java.util.HashSet<>();


    // Toast
    private String toast;
    private long toastAt;

    // Paints and reusable objects
    private Paint title, tabText, tabOn, clock, heroLabel, heroTitlePaint, heroMetaPaint, button, buttonOn, rowTitle, cardName,
            hint, small, focusLabel, toastText;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), nameShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient sheenShader, nameShader, badgeShader;
    private final TextPaint overviewPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix(), unitMatrix = new Matrix();
    private final WeakHashMap<Bitmap, BitmapShader> shaders = new WeakHashMap<>();
    private final RectF r = new RectF(), tmp = new RectF(), tmp2 = new RectF(), screen = new RectF(), heroRect = new RectF();
    private String clockText = "", greeting = "";
    private int clockMinute = -1;

    // Glass: a tiny blurred copy of the featured artwork, made once per image off the UI thread, shows through every
    // glass surface (lined up with the screen) and lights the background. Frosted glass without a GPU blur.
    private final java.util.concurrent.ExecutorService blurrer = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final WeakHashMap<Bitmap, BitmapShader> blurShaders = new WeakHashMap<>();
    private LinearGradient glassRimShader;
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glassTint = new Paint(Paint.ANTI_ALIAS_FLAG), glassRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glassMatrix = new Matrix(), glassRimMatrix = new Matrix();
    private float glassDy;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClock();
            handler.postDelayed(this, 15_000);
        }
    };

    private final Runnable nextHero = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!sheetOpen && !moving && zone != Z_ROW && featured.size() > 1) showHero((heroIndex + 1) % featured.size());
            handler.postDelayed(this, HERO_EVERY);
        }
    };

    private final android.content.pm.LauncherApps launcherApps;
    private boolean appsStale = true;
    private long appsAt;
    private final android.content.pm.LauncherApps.Callback packages = new android.content.pm.LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, android.os.UserHandle user) { appsChanged(); }
        @Override public void onPackageAdded(String pkg, android.os.UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, android.os.UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, android.os.UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, android.os.UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) loadApps();
    }

    TvHome(Activity a) {
        super(a);
        activity = a;
        rows = Rows.get(a);
        launcherApps = (android.content.pm.LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        rim.setStyle(Paint.Style.STROKE);
        glassRim.setStyle(Paint.Style.STROKE);
        sheenShader = new LinearGradient(0, 0, 1, 1, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sheen.setShader(sheenShader);
        nameShader = new LinearGradient(0, 0.4f, 0, 1, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP);
        nameShade.setShader(nameShader);
        badgeShader = new LinearGradient(0, 0, 1, 1, 0xFFFF5F6D, 0xFF8E54E9, Shader.TileMode.CLAMP);
        badge.setShader(badgeShader);
        glassRimShader = new LinearGradient(0, 0, 0, 1, 0x80FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP);
        glassRim.setShader(glassRimShader);
        rows.setListener(this);
        onRows();
        quickDock();   // the dock as it was, from disk, before the first frame
        loadApps();
    }

    // MARK: - Lifecycle (HomeActivity)

    void resume() {
        running = true;
        updateClock();
        handler.post(tick);
        handler.removeCallbacks(nextHero);
        handler.postDelayed(nextHero, HERO_EVERY);
        ensureBanner();   // the banner is on screen from the first frame back, never "Good evening" while it loads
        rows.refresh();
        // Coming back from an app: the dock is already built. Only an install, an update or an uninstall (or a
        // quarter of an hour) rebuilds it, so returning home costs no work.
        if (appsStale || SystemClock.uptimeMillis() - appsAt > 15 * 60_000L) loadApps();
        SoundService.start(getContext());   // in case the system stopped it
        wake();
    }

    void pause() {
        running = false;
        if (moving) dropMove();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(nextHero);
        handler.removeCallbacks(longPress);
        Choreographer.getInstance().removeFrameCallback(this);
        animating = false;
        saveHero();
    }

    void destroy() {
        pause();
        rows.setListener(null);
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        blurrer.shutdownNow();
    }

    /** Home pressed on the home screen: back to the first app, everything closed. */
    void reset(boolean animated) {
        if (moving) dropMove();
        sheetOpen = false;
        sheet.target = 0;
        zone = Z_DOCK;
        appIndex = 0;
        dockOffset.target = 0;
        scroll.target = 0;
        for (int i = 0; i < rowIndex.length; i++) {
            rowIndex[i] = 0;
            rowOffset[i].target = 0;
        }
        if (!animated) {
            sheet.snap(0);
            scroll.snap(0);
            dockOffset.snap(0);
            for (Spring s : rowOffset) s.snap(0);
        }
        focusChanged();
    }

    void trim() {
        rows.trim();
        // The banner on screen stays, always: dropping it is what left "Good evening" on screen after an app.
        Rows.Title keep = heroShown;
        plates.keySet().removeIf(t -> t != keep);
        spare.clear();
        spareAt.clear();
        heroFrom = null;
        baked.evictAll();
        shaders.clear();
    }

    /** An app is in front: keep only what returning home needs right away (see Rows.trimHidden). */
    void trimHidden() {
        rows.trimHidden(heroShown);
        Rows.Title keep = heroShown;
        plates.keySet().removeIf(t -> t != keep);   // returning home shows the banner at once; the rest come back
        spare.clear();
        spareAt.clear();
        baked.evictAll();
        shaders.clear();
        fitted.clear();
    }

    // MARK: - Data

    @Override public void onRows() {
        boolean changed = rows.rows() != allRows || rows.featured() != allFeatured;
        allRows = rows.rows();
        allFeatured = rows.featured();
        if (changed) applyTab();
        else if (heroWanted >= 0) showHero(heroWanted);   // an image arrived: maybe the plate we wait for
        invalidate();
    }

    /** The tab picks what shows: everything (Explore), movies only or shows only. */
    private void applyTab() {
        String kind = tab == 1 ? "movie" : tab == 2 ? "tv" : null;
        List<Rows.Row> list = new ArrayList<>();
        for (Rows.Row rw : allRows) {
            if (kind == null) {
                list.add(rw);
                continue;
            }
            Rows.Row only = new Rows.Row();
            only.name = rw.name.equals("Trending Now") ? (tab == 1 ? "Trending Movies" : "Trending Shows") : rw.name;
            for (Rows.Title t : rw.titles) if (kind.equals(t.kind)) only.titles.add(t);
            if (!only.titles.isEmpty()) list.add(only);
        }
        // The banner: the first titles of the tab's first row (Trending Now, or its movies or shows), 10 on every tab.
        List<Rows.Title> top = new ArrayList<>();
        if (!list.isEmpty()) top.addAll(list.get(0).titles.subList(0, Math.min(FEATURED, list.get(0).titles.size())));
        rowList = list;
        featured = top;
        int[] idx = new int[list.size()];
        Spring[] off = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) off[i] = i < rowOffset.length ? rowOffset[i] : new Spring(0.38f, 0.92f, 0);
        for (Spring s : off) {
            s.target = 0;
            s.snap(0);
        }
        rowIndex = idx;
        rowOffset = off;
        if (zone == Z_ROW && row >= list.size()) zone = Z_DOCK;
        // Carry on from the title on screen (restored from disk after a restart), else from the first.
        int start = 0;
        for (int i = 0; heroShown != null && i < top.size(); i++) {
            if (top.get(i).id == heroShown.id && top.get(i).kind.equals(heroShown.kind)) start = i;
        }
        heroIndex = start;
        heroWanted = -1;
        if (!featured.isEmpty()) showHero(start);
        focusChanged();
    }

    // MARK: - The dock, kept on disk

    private static java.io.File dockDir(Context c) {
        return new java.io.File(c.getFilesDir(), "dock");
    }

    /** The dock's apps in order, with their names, and each one's art (once): what quickDock shows on a restart. */
    private static void saveDock(Context c, List<HomeApps.Item> shelf) {
        java.io.File dir = dockDir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        StringBuilder index = new StringBuilder();
        for (HomeApps.Item it : shelf) {
            if (it.pkg == null || it.tile == null) continue;
            java.io.File art = new java.io.File(dir, it.pkg + ".png");
            if (!art.exists()) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(art)) {
                    it.tile.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (java.io.IOException e) {
                    continue;
                }
            }
            index.append(it.pkg).append('\t').append(it.label.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "index.txt"))) {
            out.write(index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            AppLog.w("dock cache: " + e.getMessage());
        }
    }

    /**
     * The dock as it was last time, read from disk (a few small images): on a 1 GB TV the home screen is often closed
     * while an app runs, and rebuilding the dock from the apps themselves took a second or two. loadApps then brings
     * it up to date in the background.
     */
    private void quickDock() {
        java.io.File dir = dockDir(getContext()), index = new java.io.File(dir, "index.txt");
        if (!index.exists()) return;
        List<HomeApps.Item> list = new ArrayList<>();
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(index), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String pkg = line.substring(0, tab);
                Bitmap art = android.graphics.BitmapFactory.decodeFile(new java.io.File(dir, pkg + ".png").getPath());
                if (art == null) return;   // incomplete: wait for the full load
                HomeApps.Item it = HomeApps.app(pkg, line.substring(tab + 1));
                it.tile = art;
                list.add(it);
            }
        } catch (java.io.IOException e) {
            return;
        }
        if (list.isEmpty()) return;
        list.add(addTile);
        apps = list;
    }

    /** The dock's apps and All Apps, built off the UI thread (reading the apps' TV art takes a moment). */
    private void loadApps() {
        appsStale = false;
        appsAt = SystemClock.uptimeMillis();
        Context c = getContext().getApplicationContext();
        new Thread(() -> {
            try {
            List<HomeApps.Item> shelf = HomeApps.shelf(c, true);
            for (HomeApps.Item it : shelf) if (it.pkg != null) it.tile = HomeApps.banner(c, it.pkg);
            saveDock(c, shelf);
            List<HomeApps.Item> rest = HomeApps.others(c);
            rest.add(0, HomeApps.action(HomeApps.MONITOR, "monitor", "Performance", "monitor"));
            rest.add(1, HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", "TV Settings", "tv_settings"));
            handler.post(() -> {
                if (moving) return;   // don't pull the dock from under an app being moved
                shelf.add(addTile);
                apps = shelf;
                others = rest;
                appIndex = Math.max(0, Math.min(appIndex, apps.size() - 1));
                sheetIndex = Math.min(sheetIndex, Math.max(0, sheetList().size() - 1));
                focusChanged();
            });
            } catch (Throwable e) {
                AppLog.w("apps: " + e);   // the dock keeps what it shows; tried again on the next resume
                handler.post(() -> appsStale = true);
            }
        }, "apps").start();
    }

    // MARK: - Size

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        title = Ui.text(Ui.displaySemibold, 46 * k, LABEL);
        tabText = Ui.text(Ui.medium, 26 * k, SECONDARY);
        tabOn = Ui.text(Ui.semibold, 26 * k, LABEL);
        clock = Ui.numbers(Ui.semibold, 28 * k, LABEL);
        heroLabel = Ui.text(Ui.semibold, 22 * k, LABEL);
        heroLabel.setLetterSpacing(0.14f);
        heroTitlePaint = Ui.text(Ui.displaySemibold, 72 * k, LABEL);
        heroMetaPaint = Ui.text(Ui.medium, 26 * k, 0xB3EBEBF5);
        button = Ui.text(Ui.semibold, 27 * k, LABEL);
        buttonOn = Ui.text(Ui.semibold, 27 * k, INK);
        rowTitle = Ui.text(Ui.semibold, 30 * k, LABEL);
        cardName = Ui.text(Ui.semibold, 21 * k, LABEL);
        hint = Ui.text(Ui.medium, 24 * k, SECONDARY);
        small = Ui.text(Ui.medium, 24 * k, TERTIARY);
        focusLabel = Ui.text(Ui.semibold, 26 * k, LABEL);
        toastText = Ui.text(Ui.semibold, 26 * k, LABEL);
        shadow.setColor(0x99000000);
        rim.setStrokeWidth(2.5f * k);
        glassRim.setStrokeWidth(Math.max(1, 1.6f * k));
        overviewPaint.setTypeface(Ui.regular);
        overviewPaint.setTextSize(27 * k);
        overviewPaint.setColor(0xCCEBEBF5);
        clockMinute = -1;
        updateClock();
        ensureBanner();
        focusChanged();
        scroll.snap(scroll.target);
        dockOffset.snap(dockOffset.target);
        tabPill.snap(tab);
        for (Spring s : rowOffset) s.snap(s.target);
    }

    // MARK: - Frames: only while something moves

    private void wake() {
        if (!running || animating) return;
        animating = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        try {
            frame(nanos);
        } catch (RuntimeException e) {
            AppLog.w("frame: " + e);
            animating = false;
        }
    }

    private void frame(long nanos) {
        if (!running) {
            animating = false;
            return;
        }
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        boolean busy = scroll.step(dt) | dockOffset.step(dt) | pop.step(dt) | unpop.step(dt) | sheet.step(dt)
                | sheetScroll.step(dt) | tabPill.step(dt);
        for (Spring s : rowOffset) busy |= s.step(dt);
        long up = SystemClock.uptimeMillis();
        if (heroFadeAt != 0) {
            if (up - heroFadeAt >= FADE_MS) {
                heroFadeAt = 0;
                heroFrom = null;
            }
            busy = true;
        }
        if (toast != null) {
            if (up - toastAt > TOAST_MS + 300) toast = null;
            busy = true;
        }
        invalidate();
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else animating = false;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        try {
            draw(c, 0);
        } catch (RuntimeException e) {
            // A home screen must never go down: this frame is skipped, the next one tries again.
            if (SystemClock.uptimeMillis() - drawErrorAt > 10_000) AppLog.w("draw: " + e);
            drawErrorAt = SystemClock.uptimeMillis();
        }
    }

    private long drawErrorAt;

    private void draw(Canvas c, int unused) {
        if (title == null) return;
        if (sheet.value >= 0.999f) {   // All Apps covers everything: nothing under it is drawn
            drawSheet(c);
            drawToast(c);
            return;
        }
        float sy = scroll.value * k;
        drawBackground(c, sy);
        c.save();
        c.translate(0, -sy);
        glassDy = sy;
        drawHeader(c);
        drawHero(c);
        drawDock(c);
        drawRows(c, sy);
        c.restore();
        glassDy = 0;
        if (sheet.value > 0.002f) drawSheet(c);
        drawToast(c);
    }

    /** The featured artwork, blurred, lights the whole screen (fading between titles), under a dark veil. */
    private void drawBackground(Canvas c, float sy) {
        Plate now = heroShown == null ? null : plates.get(heroShown);
        Plate from = heroFrom == null ? null : plates.get(heroFrom);
        float dim = Ui.clamp(sy / (500 * k));
        if (dim >= 0.999f && now != null && heroFadeAt == 0) {
            drawBlur(c, now.blur[2], 255);   // down in the rows: the artwork, blurred and dark, in one pass
            return;
        }
        if (now == null) {
            c.drawRect(screen, emptyBackground());
        } else {
            float t = heroFade();
            if (from != null && t < 1) c.drawBitmap(from.art, null, screen, plain);
            image.setAlpha(Math.round(255 * (from != null ? t : 1)));
            c.drawBitmap(now.art, null, screen, from != null && t < 1 ? image : plain);
            image.setAlpha(255);
        }
        if (dim > 0.004f) c.drawColor(Ui.alpha(0xFF060608, 0.82f * dim));
    }

    /** 0 → 1 over the crossfade between two titles (1 when there's none). */
    private float heroFade() {
        return heroFadeAt == 0 ? 1 : ease(Ui.clamp((SystemClock.uptimeMillis() - heroFadeAt) / FADE_MS));
    }

    private Paint emptyBackground;

    private Paint emptyBackground() {
        if (emptyBackground == null) {
            emptyBackground = new Paint();
            emptyBackground.setShader(new android.graphics.RadialGradient(w * 0.25f, h * 0.3f, w * 0.8f, 0xFF1C1C24, BG,
                    Shader.TileMode.CLAMP));
        }
        return emptyBackground;
    }


    private void drawBlur(Canvas c, Bitmap blur, int alpha) {
        BitmapShader s = blurShader(blur);
        glassMatrix.setScale((float) w / blur.getWidth(), (float) h / blur.getHeight());
        s.setLocalMatrix(glassMatrix);
        glassPaint.setShader(s);
        glassPaint.setAlpha(alpha);
        c.drawRect(screen, glassPaint);
        glassPaint.setAlpha(255);
    }

    /** "For You" with its badge, the tabs in a glass capsule (centered) and the time in another (right). */
    private void drawHeader(Canvas c) {
        float top = HEAD_TOP * k, hh = HEAD_H * k, cy = top + hh / 2;
        // The badge: white sparkles on glass.
        r.set(M * k, top + 2 * k, M * k + 56 * k, top + 58 * k);
        glass(c, r, 16 * k, 1);
        Symbols.draw(getContext(), c, "sparkles", r.centerX(), r.centerY(), 28 * k, 0xF2FFFFFF);
        c.drawText("For You", r.right + 20 * k, cy + Ui.halfCap(title), title);

        // Tabs: a glass capsule; the selected tab has a lighter pill that slides; focused, the pill turns white.
        float segW = 156 * k, capW = segW * 3 + 12 * k, capL = (w - capW) / 2;
        r.set(capL, top, capL + capW, top + hh);
        glass(c, r, hh / 2, 1);
        float pillL = capL + 6 * k + tabPill.value * segW;
        tmp.set(pillL, top + 6 * k, pillL + segW, top + hh - 6 * k);
        boolean focused = zone == Z_TABS && !sheetOpen;
        fill.setShader(null);
        fill.setColor(focused ? Ui.alpha(0xFFF5F5F7, 0.35f + 0.65f * Ui.clamp(pop.value)) : 0x2EFFFFFF);
        c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill);
        for (int i = 0; i < 3; i++) {
            Paint p = i == tab ? (focused && pop.value > 0.5f ? buttonOn : tabOn) : tabText;
            float x = capL + 6 * k + i * segW + segW / 2;
            c.drawText(TABS[i], x - p.measureText(TABS[i]) / 2, cy + Ui.halfCap(p), p);
        }

        // Right: All Apps, Performance and Settings, then the time, in one glass capsule. The focused button turns
        // white (tvOS) and its name shows under the capsule.
        float tw = clock.measureText(clockText), right = RIGHT * k, btn = 60 * k;
        float capR = right, capL2 = right - tw - 44 * k - TOP.length * btn - 12 * k;
        r.set(capL2, top, capR, top + hh);
        glass(c, r, hh / 2, 1);
        float timeX = capR - 22 * k - tw;
        c.drawText(clockText, timeX, cy + Ui.halfCap(clock), clock);
        fill.setColor(0x33FFFFFF);
        c.drawRect(timeX - 18 * k, top + 16 * k, timeX - 16 * k, top + hh - 16 * k, fill);
        for (int i = 0; i < TOP.length; i++) {
            float bx = capL2 + 6 * k + btn / 2 + i * btn;
            float v = zone == Z_TOP && topIndex == i && !sheetOpen ? Ui.clamp(pop.value) : 0;
            if (v > 0.02f) {
                fill.setColor(Ui.alpha(0xFFF5F5F7, v));
                c.drawCircle(bx, cy, (hh / 2 - 6 * k) * (1 + 0.08f * v), fill);
                String name = TOP_NAMES[i];
                float nw = focusLabel.measureText(name);
                focusLabel.setAlpha(Math.round(255 * v));
                c.drawText(name, Math.min(right - nw, bx - nw / 2), top + hh + 40 * k, focusLabel);
                focusLabel.setAlpha(255);
            }
            Symbols.draw(getContext(), c, TOP[i], bx, cy, 26 * k, v > 0.5f ? INK : 0xD9FFFFFF);
        }
    }

    /** The featured title: its artwork in a large rounded card, the details on a dark fade, Watch and the page dots. */
    private void drawHero(Canvas c) {
        float x = M * k, top = HERO_TEXT * k;
        Plate now = heroShown == null ? null : plates.get(heroShown);
        if (now == null) {
            heroTitlePaint.setTextSize(80 * k);
            heroTitlePaint.setAlpha(255);
            c.drawText(greeting, x, top + 120 * k, heroTitlePaint);
            heroMetaPaint.setAlpha(0xB3);
            c.drawText(Rows.enabled() ? "Loading what's popular on your apps…" : dateText(), x, top + 176 * k, heroMetaPaint);
            return;
        }
        // The old text fades out over the first half; the new one fades in over the second, rising into place.
        float t = heroFade();
        Plate from = heroFrom == null ? null : plates.get(heroFrom);
        if (from != null && t < 1) heroText(c, from, x, top, 1 - smooth(0, 0.45f, t), 0);
        float in = from != null && t < 1 ? smooth(0.35f, 1, t) : 1;
        heroText(c, now, x, top, in, (1 - in) * 16 * k);

        // Watch: glass, white when focused (tvOS). Its label crossfades with the text.
        float f = zone == Z_HERO && !sheetOpen ? Ui.clamp(pop.value) : 0;
        String longest = from != null && t < 1 && from.watch.length() > now.watch.length() ? from.watch : now.watch;
        Paint p = f > 0.5f ? buttonOn : button;
        float bw = p.measureText(longest) + 64 * k, bh = 66 * k, by = top + 262 * k;
        float s = 1 + 0.06f * f;
        r.set(x, by, x + bw, by + bh);
        tmp.set(r.centerX() - bw * s / 2, r.centerY() - bh * s / 2, r.centerX() + bw * s / 2, r.centerY() + bh * s / 2);
        if (f > 0.02f) {
            tmp2.set(tmp);
            tmp2.offset(0, 8 * k);
            shadow.setAlpha(Math.round(140 * f));
            shadows.draw(c, tmp2, tmp.height() / 2, 22 * k, shadow);
        }
        glass(c, tmp, tmp.height() / 2, 1);
        if (f > 0.02f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFF5F5F7, f));
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill);
        }
        float cy = tmp.centerY() + Ui.halfCap(p);
        if (from != null && t < 1) {
            p.setAlpha(Math.round(255 * (1 - smooth(0, 0.45f, t))));
            c.drawText(from.watch, tmp.centerX() - p.measureText(from.watch) / 2, cy, p);
        }
        p.setAlpha(Math.round(255 * in));
        c.drawText(now.watch, tmp.centerX() - p.measureText(now.watch) / 2, cy, p);
        p.setAlpha(255);

        // Page dots, under the button; the current one is a short bar (tvOS).
        float dx = x + 6 * k, dy = by + bh + 34 * k;
        fill.setShader(null);
        for (int i = 0; i < featured.size(); i++) {
            boolean on = i == heroIndex;
            fill.setColor(on ? LABEL : 0x59FFFFFF);
            if (on) {
                tmp2.set(dx - 5 * k, dy - 5 * k, dx + 19 * k, dy + 5 * k);
                c.drawRoundRect(tmp2, 5 * k, 5 * k, fill);
                dx += 36 * k;
            } else {
                c.drawCircle(dx, dy, 4.5f * k, fill);
                dx += 22 * k;
            }
        }
    }

    /** One title's service, name, details and summary, at `alpha`, `dy` lower than its place. */
    private void heroText(Canvas c, Plate pl, float x, float top, float alpha, float dy) {
        if (alpha <= 0.01f) return;
        int a = Math.round(255 * alpha);
        heroLabel.setColor(pl.serviceColor);
        heroLabel.setAlpha(a);
        c.drawText(pl.service, x, top + dy, heroLabel);
        heroTitlePaint.setTextSize(pl.titleSize);
        heroTitlePaint.setAlpha(a);
        c.drawText(pl.title, x, top + 86 * k + dy, heroTitlePaint);
        heroMetaPaint.setAlpha(Math.round(0xB3 * alpha));
        c.drawText(pl.meta, x, top + 136 * k + dy, heroMetaPaint);
        if (pl.overview != null) {
            pl.overviewPaint.setAlpha(Math.round(0xCC * alpha));
            c.save();
            c.translate(x, top + 160 * k + dy);
            pl.overview.draw(c);
            c.restore();
        }
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    /** Your apps on a glass dock. Focused, an app grows a little (it stays inside the glass); held, it's lifted. */
    private void drawDock(Canvas c) {
        float top = DOCK_TOP * k, pad = DOCK_PAD * k;
        tmp.set(M * k, top, RIGHT * k, top + TILE_H * k + 2 * pad);
        glass(c, tmp, 34 * k, 1);
        float dockBottom = tmp.bottom, dockLeft = tmp.left, dockRight = tmp.right;
        float x0 = M * k + pad - dockOffset.value * k, ty = top + pad;
        c.save();
        c.clipRect(dockLeft + 6 * k, tmp.top, dockRight - 6 * k, dockBottom);
        int focused = zone == Z_DOCK && !sheetOpen ? appIndex : -1, faded = prevZone == Z_DOCK ? prevIndex : -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < apps.size(); i++) {
                boolean isFocus = i == focused;
                if ((pass == 0) == isFocus) continue;
                float x = x0 + i * (TILE_W + TILE_GAP) * k;
                if (x > w || x + TILE_W * k < 0) continue;
                tmp.set(x, ty, x + TILE_W * k, ty + TILE_H * k);
                float f = isFocus ? pop.value : i == faded ? unpop.value : 0;
                drawTile(c, apps.get(i), tmp, f, moving && isFocus ? 0.12f : 0.07f, 12 * k);
            }
        }
        c.restore();
        if (moving) {
            String text = "◀ ▶  Move        ▼  Remove from Home        OK  Done";
            c.drawText(text, (w - hint.measureText(text)) / 2, dockBottom + 52 * k, hint);
        }
    }

    private void drawTile(Canvas c, HomeApps.Item it, RectF base, float f, float grow, float radius) {
        lift(base, f, grow, 0);
        if (f > 0.02f) {
            tmp2.set(r);
            tmp2.offset(0, 8 * k * f);
            shadow.setAlpha(Math.round(150 * Ui.clamp(f)));
            shadows.draw(c, tmp2, radius, 22 * k, shadow);
        }
        if (it.tile != null) {
            roundImage(c, it.tile, r, radius, 255);
        } else if (it == addTile) {
            fill.setShader(null);
            fill.setColor(f > 0.5f ? 0x40FFFFFF : 0x1FFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
            float cx = r.centerX(), cy = r.centerY(), arm = 20 * k, th = 3.5f * k;
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(cx - arm, cy - th, cx + arm, cy + th, th, th, fill);
            c.drawRoundRect(cx - th, cy - arm, cx + th, cy + arm, th, th, fill);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(r, radius, radius, fill);
            Symbols.draw(getContext(), c, it.symbol == null ? "apps" : it.symbol, r.centerX(), r.centerY(), 40 * k, 0xE6FFFFFF);
        }
        focusRim(c, r, radius, f);
    }

    private void drawRows(Canvas c, float sy) {
        for (int ri = 0; ri < rowList.size(); ri++) {
            float top = (ROWS_TOP + ri * ROW_STEP) * k;
            if (top - sy > h || top + ROW_STEP * k - sy < 0) continue;
            Rows.Row rw = rowList.get(ri);
            c.drawText(rw.name, M * k, top + 30 * k, rowTitle);
            float x0 = M * k - rowOffset[ri].value * k, cardTop = top + CARD_DY * k;
            int focused = zone == Z_ROW && row == ri && !sheetOpen ? rowIndex[ri] : -1;
            int faded = prevZone == Z_ROW && prevRow == ri ? prevIndex : -1;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < rw.titles.size(); i++) {
                    boolean isFocus = i == focused;
                    if ((pass == 0) == isFocus) continue;
                    float x = x0 + i * (CARD_W + CARD_GAP) * k;
                    if (x > w || x + CARD_W * k < 0) continue;
                    tmp.set(x, cardTop, x + CARD_W * k, cardTop + CARD_H * k);
                    float f = isFocus ? pop.value : i == faded ? unpop.value : 0;
                    drawCard(c, rw.titles.get(i), tmp, f);
                }
            }
        }
    }

    /** A title: its artwork, its name on a dark fade at the bottom, and the focus lift. */
    private void drawCard(Canvas c, Rows.Title t, RectF base, float f) {
        float radius = 14 * k;
        lift(base, f, 0.08f, 6);
        if (f > 0.02f) {
            tmp2.set(r);
            tmp2.offset(0, 14 * k * f);
            shadow.setAlpha(Math.round(160 * Ui.clamp(f)));
            shadows.draw(c, tmp2, radius, 30 * k, shadow);
        }
        Bitmap card = bakedCard(t);
        if (card != null) {
            c.drawBitmap(card, null, r, f > 0.02f ? image : plain);   // at rest it's pixel for pixel: no filtering
        } else {
            fill.setShader(null);
            fill.setColor(0xFF1C1C1E);
            c.drawRoundRect(r, radius, radius, fill);
            float maxW = (CARD_W - 32) * k;
            if (t.shortName == null || t.shortWidth != maxW) {
                t.shortName = fit(t.name, cardName, maxW);
                t.shortWidth = maxW;
            }
            c.drawText(t.shortName, r.left + 16 * k, r.bottom - 16 * k, cardName);
        }
        focusRim(c, r, radius, f);
    }

    /**
     * The finished card for a title: its artwork at the card's exact size on screen, rounded, with the dark fade and
     * its name already on it, made once off the UI thread. Drawing a row is then one plain copy per card (before, each
     * card was scaled through a shader, shaded and lettered on every frame). null until it's ready.
     */
    private Bitmap bakedCard(Rows.Title t) {
        Bitmap done = baked.get(t);
        if (done != null || baking.contains(t)) return done;
        Bitmap src = rows.image(t, false);
        if (src == null) return null;
        baking.add(t);
        int bw = Math.round(CARD_W * k), bh = Math.round(CARD_H * k);
        float radius = 14 * k, pad = 16 * k;
        String name = fit(t.name, cardName, bw - 2 * pad);
        Paint text = new Paint(cardName);
        blurrer.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
            Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            RectF box = new RectF(0, 0, bw, bh);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max((float) bw / src.getWidth(), (float) bh / src.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((bw - src.getWidth() * scale) / 2, (bh - src.getHeight() * scale) / 2);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            cv.drawRoundRect(box, radius, radius, p);
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setShader(new LinearGradient(0, bh * 0.4f, 0, bh, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
            cv.drawRoundRect(box, radius, radius, shade);
            cv.drawText(name, pad, bh - pad, text);
            handler.post(() -> {
                baking.remove(t);
                baked.put(t, out);
                rows.forget(t);   // the card has it now
                invalidate();
            });
            } catch (Throwable e) {
                AppLog.w("card " + t.name + ": " + e);   // a plain card shows; tried again when it's next drawn
                handler.post(() -> baking.remove(t));
            }
        });
        return null;
    }

    private final android.util.LruCache<Rows.Title, Bitmap> baked = new android.util.LruCache<Rows.Title, Bitmap>(9 << 20) {
        @Override protected int sizeOf(Rows.Title t, Bitmap b) { return b.getByteCount(); }
    };
    private final java.util.Set<Rows.Title> baking = new java.util.HashSet<>();
    private final Paint plain = new Paint();


    private void drawSheet(Canvas c) {
        float a = Ui.clamp(sheet.value);
        Bitmap[] blur = blurOf(heroShown);
        if (blur != null) {
            drawBlur(c, blur[0], Math.round(255 * a));
            c.drawColor(Ui.alpha(0xFF050507, 0.6f * a));
        } else {
            c.drawColor(Ui.alpha(0xFF050507, a));
        }
        float lift = (1 - a) * 40 * k;
        rowTitle.setAlpha(Math.round(255 * a));
        c.drawText(picking ? "Add to Home" : "All Apps", M * k, 140 * k + lift, rowTitle);
        rowTitle.setAlpha(255);
        String tip = picking ? "Choose an app to add it to Home" : "Hold OK to add an app to Home";
        small.setAlpha(Math.round(0x59 * a));
        c.drawText(tip, RIGHT * k - small.measureText(tip), 140 * k + lift, small);
        float sy = sheetScroll.value * k;
        float gridW = GRID_COLUMNS * GRID_W + (GRID_COLUMNS - 1) * GRID_GAP_X, x0 = (1920 - gridW) / 2 * k;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < sheetList().size(); i++) {
                boolean isFocus = sheetOpen && i == sheetIndex;
                if ((pass == 0) == isFocus) continue;
                int col = i % GRID_COLUMNS, rw = i / GRID_COLUMNS;
                float x = x0 + col * (GRID_W + GRID_GAP_X) * k, y = (GRID_TOP + rw * GRID_STEP_Y) * k - sy + lift;
                if (y > h || y + GRID_H * k < 0) continue;
                tmp.set(x, y, x + GRID_W * k, y + GRID_H * k);
                float f = isFocus ? pop.value : 0;
                HomeApps.Item it = sheetList().get(i);
                drawTile(c, it, tmp, f * a, 0.08f, 16 * k);
                Paint p = isFocus ? focusLabel : hint;
                String label = fit(it.label, p, GRID_W * k);
                p.setAlpha(Math.round((isFocus ? 255 : 0x99) * a));
                c.drawText(label, x + (GRID_W * k - p.measureText(label)) / 2, y + GRID_H * k + 48 * k, p);
                p.setAlpha(isFocus ? 255 : 0x99);
            }
        }
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        long age = SystemClock.uptimeMillis() - toastAt;
        float a = Ui.clamp(Math.min(age / 200f, (TOAST_MS + 300 - age) / 300f));
        if (a <= 0.01f) return;
        float tw = toastText.measureText(toast);
        RectF box = toastBox;
        box.set((w - tw) / 2 - 36 * k, 40 * k, (w + tw) / 2 + 36 * k, 104 * k);
        glass(c, box, box.height() / 2, a);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, 0.35f * a));
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill);
        toastText.setAlpha(Math.round(255 * a));
        c.drawText(toast, (w - tw) / 2, box.centerY() + Ui.halfCap(toastText), toastText);
    }

    private void showToast(String text) {
        toast = text;
        toastAt = SystemClock.uptimeMillis();
        wake();
    }

    // MARK: - Drawing helpers

    /** `base` grown around its center by `grow` × f and lifted `liftPx` × f, into r (the tvOS focus). */
    private void lift(RectF base, float f, float grow, float liftPx) {
        float s = 1 + grow * f, cx = base.centerX(), cy = base.centerY() - liftPx * k * f;
        float hw = base.width() * s / 2, hh = base.height() * s / 2;
        r.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /** Focused: a sheen from the top left and a bright rim. */
    private void focusRim(Canvas c, RectF rr, float radius, float f) {
        if (f <= 0.02f) return;
        int a = Math.round(255 * Ui.clamp(f));
        unitMatrix.setScale(rr.width(), rr.height());
        unitMatrix.postTranslate(rr.left, rr.top);
        sheenShader.setLocalMatrix(unitMatrix);
        sheen.setAlpha(a);
        c.drawRoundRect(rr, radius, radius, sheen);
        rim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * Ui.clamp(f)));
        c.drawRoundRect(rr, radius, radius, rim);
    }

    /** A bitmap covering a rounded rectangle (a shader, not clipPath: older TV GPUs choke on clipping). */
    private void roundImage(Canvas c, Bitmap b, RectF rr, float radius, int alpha) {
        BitmapShader s = shaders.get(b);
        if (s == null) {
            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(b, s);
        }
        float scale = Math.max(rr.width() / b.getWidth(), rr.height() / b.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate(rr.centerX() - b.getWidth() * scale / 2, rr.centerY() - b.getHeight() * scale / 2);
        s.setLocalMatrix(matrix);
        card.setShader(s);
        card.setAlpha(alpha);
        c.drawRoundRect(rr, radius, radius, card);
        card.setShader(null);
        card.setAlpha(255);
    }

    private final TextPaint ellipsizer = new TextPaint();
    /** Names cut to fit, made once per name, size and width (no new strings while animating). */
    private final HashMap<String, String> fitted = new HashMap<>();

    private String fit(String s, Paint p, float maxW) {
        if (p.measureText(s) <= maxW) return s;
        String key = Math.round(p.getTextSize()) + "|" + Math.round(maxW) + "|" + s;
        String out = fitted.get(key);
        if (out == null) {
            ellipsizer.set(p);
            out = TextUtils.ellipsize(s, ellipsizer, maxW, TextUtils.TruncateAt.END).toString();
            if (fitted.size() > 400) fitted.clear();
            fitted.put(key, out);
        }
        return out;
    }

    private static float ease(float t) {
        return t * t * (3 - 2 * t);
    }

    private static int serviceColor(String service) {
        switch (service) {
            case "Netflix": return 0xFFFF3B45;
            case "Prime Video": return 0xFF3FA9FF;
            case "JioHotstar": return 0xFF7FA2FF;
            case "Sony LIV": return 0xFFFFB340;
            default: return LABEL;
        }
    }

    // MARK: - Glass

    /**
     * The featured artwork blurred, in two ready-made tones: [0] darkened for the background, [1] the frosted glass
     * (lighter, with its white veil). Baking the tints in means each glass surface is one draw, not three.
     */
    private Bitmap[] blurOf(Rows.Title t) {
        Plate p = t == null ? null : plates.get(t);
        return p == null ? null : p.blur;
    }

    /** A copy of `src` with its colors scaled by `scale` and lifted by `add` (0-255). */
    private static Bitmap tone(Bitmap src, float scale, float add) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] {
            scale, 0, 0, 0, add, 0, scale, 0, 0, add, 0, 0, scale, 0, add, 0, 0, 0, 1, 0}));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }

    private BitmapShader blurShader(Bitmap blur) {
        BitmapShader s = blurShaders.get(blur);
        if (s == null) {
            s = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            blurShaders.put(blur, s);
        }
        return s;
    }

    /**
     * Frosted glass in `rr`: the blurred artwork behind it (lined up with the screen, even when the content scrolls), a
     * smoky tint, a white veil and a rim bright on top. Without artwork: just the veil and the rim.
     */
    private void glass(Canvas c, RectF rr, float radius, float alpha) {
        if (alpha <= 0.01f) return;
        Bitmap[] blur = blurOf(heroShown), was = blurOf(heroFrom);
        if (blur != null) {
            // The glass changes with the picture, at the midpoint (on blurred glass it doesn't show, and it's one draw).
            glassLayer(c, rr, radius, was != null && heroFade() < 0.5f ? was[1] : blur[1], alpha);
        } else {
            glassTint.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * alpha));
            c.drawRoundRect(rr, radius, radius, glassTint);
        }
        glassRimMatrix.setScale(1, Math.max(1, rr.height()));
        glassRimMatrix.postTranslate(0, rr.top);
        glassRimShader.setLocalMatrix(glassRimMatrix);
        glassRim.setAlpha(Math.round(255 * alpha));
        float in = glassRim.getStrokeWidth() / 2;
        glassBox.set(rr.left + in, rr.top + in, rr.right - in, rr.bottom - in);
        c.drawRoundRect(glassBox, radius - in, radius - in, glassRim);
        glassRim.setAlpha(255);
    }

    private void glassLayer(Canvas c, RectF rr, float radius, Bitmap tone, float alpha) {
        BitmapShader s = blurShader(tone);
        glassMatrix.setScale((float) w / tone.getWidth(), (float) h / tone.getHeight());
        glassMatrix.postTranslate(0, glassDy);
        s.setLocalMatrix(glassMatrix);
        glassPaint.setShader(s);
        glassPaint.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(rr, radius, radius, glassPaint);
    }

    private final RectF glassBox = new RectF(), toastBox = new RectF();

    // MARK: - Featured title

    private void showHero(int index) {
        if (featured.isEmpty() || w == 0) return;
        index = ((index % featured.size()) + featured.size()) % featured.size();
        Rows.Title next = featured.get(index);
        if (cachedHero != null && heroShown == cachedHero && next.id == cachedHero.id && next.kind.equals(cachedHero.kind)) {
            // The rows arrived and their first title is the one restored from disk: it just takes its place.
            plates.put(next, plates.remove(cachedHero));
            heroShown = next;
            cachedHero = null;
            heroIndex = index;
            heroWanted = -1;
            plate(featured.get((index + 1) % featured.size()));
            invalidate();
            return;
        }
        plate(featured.get((index + 1) % featured.size()));   // the next one, made ahead
        if (next == heroShown && heroWanted < 0 && plates.containsKey(next)) return;   // already on screen, ready
        Plate p = plate(next);
        if (p == null) {   // not ready: it starts as soon as it is (onRows, or when the plate is done)
            heroWanted = index;
            return;
        }
        heroWanted = -1;
        heroIndex = index;
        if (next == heroShown) return;
        boolean animate = heroShown != null && running && plates.containsKey(heroShown);
        heroFrom = animate ? heroShown : null;
        heroShown = next;
        heroFadeAt = animate ? SystemClock.uptimeMillis() : 0;
        // Keep only the plates in use and the next one.
        Rows.Title after = featured.get((index + 1) % featured.size());
        java.util.Iterator<java.util.Map.Entry<Rows.Title, Plate>> it = plates.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Rows.Title, Plate> e = it.next();
            if (e.getKey() != heroShown && e.getKey() != heroFrom && e.getKey() != after) {
                release(e.getValue());
                it.remove();
            }
        }
        wake();
        invalidate();
    }

    /**
     * Makes sure a banner is ready to show: the one in memory, else the last one shown, from disk (about 50 ms), then the
     * live recommendations carry on from that title (or start from the first one) as soon as their plate is made.
     */
    private void ensureBanner() {
        if (w == 0) return;
        if (heroShown == null || plates.get(heroShown) == null) restoreHero();
        if (featured.isEmpty()) return;
        int at = heroIndex;
        for (int i = 0; heroShown != null && i < featured.size(); i++) {
            if (featured.get(i).id == heroShown.id && featured.get(i).kind.equals(heroShown.kind)) at = i;
        }
        heroWanted = -1;
        showHero(at);
    }

    // MARK: - The banner on screen, kept on disk

    /** The title restored from disk (until the rows arrive and the same title takes its place). */
    private Rows.Title cachedHero;
    private int savedHeroId = -1;

    private java.io.File heroFile(String ext) {
        return new java.io.File(getContext().getFilesDir(), "hero." + ext);
    }

    /**
     * Leaving home: the banner on screen (its picture, shading included, and its text) goes to disk. On a 1 GB TV the
     * home screen is often closed while an app runs; coming back, restoreHero shows this at once instead of a greeting
     * while the rows load, decode and compose (3 to 5 seconds).
     */
    private void saveHero() {
        Rows.Title t = heroShown;
        Plate p = t == null ? null : plates.get(t);
        if (p == null || t == cachedHero || t.id == savedHeroId) return;
        savedHeroId = t.id;
        p.saving = true;
        final int sw = w;
        final float kk = k;
        blurrer.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
            java.io.File jpg = heroFile("jpg"), part = heroFile("part"), info = heroFile("json");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                p.art.compress(Bitmap.CompressFormat.JPEG, 88, out);
            } catch (java.io.IOException e) {
                AppLog.w("banner cache: " + e.getMessage());
            }
            handler.post(() -> p.saving = false);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(info)) {
                org.json.JSONObject o = new org.json.JSONObject().put("w", sw).put("id", t.id).put("kind", t.kind)
                        .put("name", t.name).put("service", t.service).put("pkg", t.pkg).put("backdrop", t.backdrop)
                        .put("overview", t.overview).put("title", p.title).put("meta", p.meta).put("watch", p.watch)
                        .put("titleSize", p.titleSize / kk).put("color", p.serviceColor);
                out.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!part.renameTo(jpg)) AppLog.w("banner cache: couldn't save");
            } catch (java.io.IOException | org.json.JSONException e) {
                AppLog.w("banner cache: " + e.getMessage());
            }
            } catch (Throwable e) {
                AppLog.w("banner cache: " + e);
                handler.post(() -> p.saving = false);
            }
        });
    }

    /** Shows the banner saved by saveHero (only when it was saved at this screen size). */
    private void restoreHero() {
        java.io.File jpg = heroFile("jpg"), info = heroFile("json");
        if (!jpg.exists() || !info.exists() || w == 0) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(info)) {
            byte[] b = new byte[(int) info.length()];
            int n = in.read(b);
            org.json.JSONObject o = new org.json.JSONObject(new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8));
            if (o.optInt("w") != w) return;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inMutable = true;   // like every other plate, so it can be reused later
            Bitmap art = android.graphics.BitmapFactory.decodeFile(jpg.getPath(), opts);
            if (art == null) return;
            Rows.Title t = new Rows.Title();
            t.id = o.optInt("id");
            t.kind = o.optString("kind");
            t.name = o.optString("name");
            t.service = o.optString("service");
            t.pkg = o.optString("pkg");
            t.backdrop = o.optString("backdrop");
            t.overview = o.optString("overview");
            Plate p = new Plate();
            p.art = art;
            Bitmap small = Bitmap.createScaledBitmap(art, 160, 90, true);
            Bitmap soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, 32, 18, true), 64, 36, true);
            p.blur = new Bitmap[] {tone(soft, 0.5f, 0), tone(soft, 0.7f, 20), tone(soft, 0.2f, 0)};
            p.service = t.service.toUpperCase(Locale.ROOT);
            p.serviceColor = o.optInt("color", LABEL);
            p.title = o.optString("title");
            p.titleSize = (float) o.optDouble("titleSize", 80) * k;
            p.meta = o.optString("meta");
            p.watch = o.optString("watch");
            p.overviewPaint = new TextPaint(overviewPaint);
            p.overview = android.text.StaticLayout.Builder.obtain(t.overview, 0, t.overview.length(), p.overviewPaint, Math.round(860 * k))
                    .setLineSpacing(0, 1.12f).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
            plates.put(t, p);
            heroShown = t;
            heroFrom = null;
            heroFadeAt = 0;
            cachedHero = t;
            savedHeroId = t.id;
        } catch (Throwable e) {   // a bad or partial file: just start without it
            AppLog.w("banner cache unreadable: " + e);
        }
    }

    /** The title's plate if ready; otherwise null, and it's made in the background once the artwork has arrived. */
    private Plate plate(Rows.Title t) {
        Plate done = plates.get(t);
        if (done != null || plating.contains(t) || w == 0) return done;
        Bitmap src = rows.image(t, true);
        if (src == null) return null;
        plating.add(t);
        final int sw = w, sh = h;
        final float kk = k;
        // Text measured here (the paints belong to the UI thread), laid out in the background.
        float maxW = 1100 * kk, size = 80 * kk;
        heroTitlePaint.setTextSize(size);
        while (heroTitlePaint.measureText(t.name) > maxW && size > 56 * kk) {
            size -= 4 * kk;
            heroTitlePaint.setTextSize(size);
        }
        final float titleSize = size;
        final String title = fit(t.name, heroTitlePaint, maxW);
        final TextPaint op = new TextPaint(overviewPaint);
        final int color = serviceColor(t.service);
        final Bitmap reuse = takeSpare();
        blurrer.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Plate p = new Plate();
            // The artwork at screen size with the shading on it: dark on the left for the text, at the bottom for the dock.
            try {
            // A spare is reused only if it can be drawn into (one decoded from disk can't).
            p.art = reuse != null && reuse.isMutable() && reuse.getWidth() == sw && reuse.getHeight() == sh ? reuse
                    : Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(p.art);
            cv.drawColor(0xFF000000);
            float scale = Math.max((float) sw / src.getWidth(), (float) sh / src.getHeight());
            float bw = src.getWidth() * scale, bh = src.getHeight() * scale;
            cv.drawBitmap(src, null, new RectF((sw - bw) / 2, (sh - bh) / 2, (sw + bw) / 2, (sh + bh) / 2),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint shade = new Paint(Paint.DITHER_FLAG);
            shade.setShader(new LinearGradient(0, 0, sw, 0, new int[] {0xD9000000, 0x80000000, 0x00000000},
                    new float[] {0, 0.38f, 0.7f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, sw, sh, shade);
            shade.setShader(new LinearGradient(0, 0, 0, sh, new int[] {0x66000000, 0x00000000, 0x00000000, 0xCC000000, 0xF2060608},
                    new float[] {0, 0.16f, 0.5f, 0.8f, 1}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, sw, sh, shade);
            // Blurs: downscaling with filtering averages the pixels, a soft blur, cheaply.
            Bitmap small = Bitmap.createScaledBitmap(src, 160, 90, true);
            Bitmap tiny = Bitmap.createScaledBitmap(small, 32, 18, true);
            Bitmap soft = Bitmap.createScaledBitmap(tiny, 64, 36, true);
            p.blur = new Bitmap[] {tone(soft, 0.5f, 0), tone(soft, 0.7f, 20), tone(soft, 0.2f, 0)};
            p.service = t.service.toUpperCase(Locale.ROOT);
            p.serviceColor = color;
            p.title = title;
            p.titleSize = titleSize;
            p.meta = t.meta == null ? "" : t.meta;
            p.watch = "▶  Watch on " + t.service;
            p.overviewPaint = op;
            String text = t.overview == null ? "" : t.overview;
            int width = Math.round(860 * kk);
            // Two lines at most, cut with "…" at the end of the second (never a stray third line).
            p.overview = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), op, width)
                    .setLineSpacing(0, 1.12f).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
            handler.post(() -> {
                plating.remove(t);
                plates.put(t, p);
                rows.forgetLarge(t);   // the plate has it now
                if (heroWanted >= 0 && heroWanted < featured.size() && featured.get(heroWanted) == t) showHero(heroWanted);
                invalidate();
            });
            } catch (Throwable e) {
                // Never fatal: the banner keeps the title it has, and this one is tried again next time.
                AppLog.w("banner " + t.name + ": " + e);
                handler.post(() -> plating.remove(t));
            }
        });
        return null;
    }

    // MARK: - Clock

    private void updateClock() {
        Calendar cal = Calendar.getInstance();
        int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (minute == clockMinute) return;
        clockMinute = minute;
        clockText = android.text.format.DateFormat.getTimeFormat(getContext()).format(cal.getTime());
        int hr = cal.get(Calendar.HOUR_OF_DAY);
        greeting = hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 17 ? "Good afternoon" : "Good evening";
        invalidate();
    }

    private String dateText() {
        return android.text.format.DateFormat.format("EEEE, d MMMM", Calendar.getInstance()).toString();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        try {
            return handleKey(e);
        } catch (RuntimeException ex) {
            AppLog.w("key: " + ex);   // the key is dropped, the home screen stays
            return true;
        }
    }

    private boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: if (down) move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // A tap opens; holding picks the app up (dock) or adds it to Home (All Apps).
                if (down && e.getRepeatCount() == 0) {
                    okDown = true;
                    longFired = false;
                    handler.postDelayed(longPress, LONG_PRESS_MS);
                } else if (!down && okDown) {
                    okDown = false;
                    handler.removeCallbacks(longPress);
                    if (!longFired) {
                        if (moving) dropMove();
                        else activate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) back();
                return true;
            default:
                return false;
        }
    }

    private void onLongPress() {
        if (!okDown) return;
        longFired = true;
        if (sheetOpen) {
            addToHome();
        } else if (zone == Z_DOCK && !moving && !apps.isEmpty() && apps.get(appIndex) != addTile) {
            moving = true;
            pop.snap(0.6f);
            pop.target = 1;
            showToast("Move " + apps.get(appIndex).label);
            wake();
        }
    }

    private void back() {
        if (moving) {
            dropMove();
        } else if (sheetOpen) {
            sheetOpen = false;
            picking = false;
            sheet.target = 0;
        } else {
            zone = Z_DOCK;
        }
        focusChanged();
    }

    private void move(int dx, int dy) {
        if (moving) {
            moveApp(dx, dy);
            return;
        }
        if (sheetOpen) {
            int n = sheetList().size();
            if (n == 0) return;
            int col = sheetIndex % GRID_COLUMNS, next = sheetIndex;
            if (dx != 0 && col + dx >= 0 && col + dx < GRID_COLUMNS && sheetIndex + dx < n) next += dx;
            if (dy != 0) next = Math.max(0, Math.min(n - 1, sheetIndex + dy * GRID_COLUMNS));
            if (next == sheetIndex) return;
            sheetIndex = next;
            sheetScroll.target = Math.max(0, (sheetIndex / GRID_COLUMNS - 1) * GRID_STEP_Y);
            focusChanged();
            return;
        }
        int before = zone, beforeIndex = currentIndex(), beforeRow = row;
        switch (zone) {
            case Z_TOP:
                if (dy > 0) zone = featured.isEmpty() ? Z_DOCK : Z_HERO;
                else if (dx < 0 && topIndex == 0) zone = Z_TABS;
                else if (dx != 0) topIndex = Math.max(0, Math.min(TOP.length - 1, topIndex + dx));
                break;
            case Z_TABS:
                if (dy > 0) zone = featured.isEmpty() ? Z_DOCK : Z_HERO;
                else if (dx > 0 && tab == TABS.length - 1) {
                    zone = Z_TOP;
                    topIndex = 0;
                } else if (dx != 0 && tab + dx >= 0 && tab + dx < TABS.length) {
                    tab += dx;
                    tabPill.target = tab;
                    applyTab();
                }
                break;
            case Z_HERO:
                if (dy < 0) zone = Z_TABS;
                else if (dy > 0) zone = Z_DOCK;
                else if (dx != 0 && !featured.isEmpty()) {
                    // The banner loops: right from the last goes to the first, left from the first to the last.
                    int n = featured.size();
                    showHero((heroIndex + dx + n) % n);
                    handler.removeCallbacks(nextHero);
                    handler.postDelayed(nextHero, HERO_EVERY);
                }
                break;
            case Z_DOCK:
                if (dy < 0) zone = featured.isEmpty() ? Z_TABS : Z_HERO;
                else if (dy > 0 && !rowList.isEmpty()) {
                    zone = Z_ROW;
                    row = 0;
                } else if (dx != 0) appIndex = Math.max(0, Math.min(apps.size() - 1, appIndex + dx));
                break;
            default:   // Z_ROW
                if (dy < 0) {
                    if (row > 0) row--;
                    else zone = Z_DOCK;
                } else if (dy > 0 && row < rowList.size() - 1) {
                    row++;
                } else if (dx != 0) {
                    rowIndex[row] = Math.max(0, Math.min(rowList.get(row).titles.size() - 1, rowIndex[row] + dx));
                }
        }
        if (zone != before || currentIndex() != beforeIndex || row != beforeRow) {
            prevZone = before;
            prevRow = beforeRow;
            prevIndex = beforeIndex;
            focusChanged();
        }
    }


    private int currentIndex() {
        switch (zone) {
            case Z_TOP: return topIndex;
            case Z_TABS: return tab;
            case Z_HERO: return heroIndex;
            case Z_DOCK: return appIndex;
            default: return row < rowIndex.length ? rowIndex[row] : 0;
        }
    }

    /** Focus moved: the new item pops, the old one settles, and things scroll to keep it in view. */
    private void focusChanged() {
        unpop.snap(pop.value);
        unpop.target = 0;
        pop.snap(0);
        pop.target = 1;
        float dockInner = RIGHT - M - 2 * DOCK_PAD, tile = TILE_W + TILE_GAP;
        // The dock scrolls only while you're in it (to keep the focused app in view), then back to its start.
        dockOffset.target = zone == Z_DOCK && !sheetOpen ? clampOffset((appIndex - 5) * tile, apps.size() * tile - TILE_GAP, dockInner) : 0;
        float content = RIGHT - M, step = CARD_W + CARD_GAP;
        for (int i = 0; i < rowIndex.length; i++) {
            rowOffset[i].target = clampOffset((rowIndex[i] - 1) * step, rowList.get(i).titles.size() * step - CARD_GAP, content);
        }
        // A row scrolls up to near the top, but never past the point where the last row sits at the bottom.
        float end = ROWS_TOP + rowList.size() * ROW_STEP + 30 - 1080;
        scroll.target = zone == Z_ROW ? Math.min(ROWS_TOP + row * ROW_STEP - 160, Math.max(ROWS_TOP - 160, end)) : 0;
        if (zone == Z_ROW || zone == Z_DOCK) {
            // The images of the rows around, before they come into view.
            int from = zone == Z_ROW ? Math.max(0, row - 1) : 0, to = Math.min(rowList.size(), (zone == Z_ROW ? row : 0) + 3);
            for (int i = from; i < to; i++) {
                List<Rows.Title> list = rowList.get(i).titles;
                for (int j = 0; j < Math.min(list.size(), rowIndex[i] + 7); j++) rows.image(list.get(j), false);
            }
        }
        wake();
        invalidate();
    }

    private static float clampOffset(float want, float total, float visible) {
        return Math.max(0, Math.min(want, Math.max(0, total - visible)));
    }

    // MARK: - Customizing the dock

    private void moveApp(int dx, int dy) {
        if (dy > 0) {
            if (apps.size() <= 2) return;   // keep at least one app (and "+")
            HomeApps.Item gone = apps.remove(appIndex);
            appIndex = Math.min(appIndex, apps.size() - 1);
            moving = false;
            saveDock();
            showToast(gone.label + " removed from Home");
            loadApps();
            focusChanged();
            return;
        }
        if (dy < 0 || dx == 0) return;
        int to = appIndex + dx;
        if (to < 0 || to >= apps.size() || apps.get(to) == addTile) return;   // "+" stays last
        HomeApps.Item it = apps.remove(appIndex);
        apps.add(to, it);
        appIndex = to;
        prevZone = -1;
        float tile = TILE_W + TILE_GAP;
        dockOffset.target = clampOffset((appIndex - 4) * tile, apps.size() * tile - TILE_GAP, RIGHT - M - 2 * DOCK_PAD);
        wake();
        invalidate();
    }

    private void dropMove() {
        moving = false;
        saveDock();
        focusChanged();
    }

    private void addToHome() {
        if (sheetList().isEmpty()) return;
        HomeApps.Item it = sheetList().get(sheetIndex);
        if (it.pkg == null || it.kind != HomeApps.APP) {
            showToast(it.label + " can't go on Home");
            return;
        }
        for (HomeApps.Item a : apps) {
            if (it.pkg.equals(a.pkg)) {
                showToast(it.label + " is already on Home");
                return;
            }
        }
        apps.add(Math.max(0, apps.size() - 1), it);   // before the "+" tile
        saveDock();
        showToast(it.label + " added to Home");
        loadApps();
    }

    private void saveDock() {
        List<String> pkgs = new ArrayList<>();
        for (HomeApps.Item a : apps) if (a.pkg != null) pkgs.add(a.pkg);
        Prefs.setShelf(pkgs);
        if (activity instanceof HomeActivity) ((HomeActivity) activity).acceptPrefs();   // our own change: no restart
    }

    // MARK: - Opening

    private void activate() {
        HomeApps.Item it;
        if (sheetOpen) {
            if (sheetList().isEmpty()) return;
            if (picking) {
                addToHome();
                sheetOpen = false;
                picking = false;
                sheet.target = 0;
                zone = Z_DOCK;
                focusChanged();
                return;
            }
            it = sheetList().get(sheetIndex);
        } else if (zone == Z_TOP) {
            if (topIndex == 0) {
                openSheet(false);
                return;
            }
            it = topIndex == 1 ? HomeApps.action(HomeApps.MONITOR, "monitor", "Performance", "monitor") : HomeApps.settings();
        } else if (zone == Z_TABS) {
            zone = featured.isEmpty() ? Z_DOCK : Z_HERO;
            focusChanged();
            return;
        } else if (zone == Z_HERO) {
            if (heroShown == null) return;
            it = HomeApps.app(heroShown.pkg, heroShown.service, null);
        } else if (zone == Z_ROW) {
            Rows.Title t = rowList.get(row).titles.get(rowIndex[row]);
            it = HomeApps.app(t.pkg, t.service, null);
        } else {
            if (apps.isEmpty()) return;
            it = apps.get(appIndex);
            if (it == addTile) {
                openSheet(true);
                return;
            }
        }
        boolean opened = HomeApps.open(activity, it, this, w / 2 - 50, h / 2 - 30, 100, 60);
        if (!opened && it.kind == HomeApps.ANDROID_SETTINGS) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (!opened) {
            showToast("Couldn't open " + it.label);
        }
    }

    /** What the drawer lists: everything, or (opened with "+") the apps not on Home yet. */
    private List<HomeApps.Item> sheetList() {
        if (!picking) return others;
        if (pickList == null) {
            pickList = new ArrayList<>();
            for (HomeApps.Item it : others) {
                if (it.kind != HomeApps.APP || it.pkg == null) continue;
                boolean onHome = false;
                for (HomeApps.Item a : apps) if (it.pkg.equals(a.pkg)) onHome = true;
                if (!onHome) pickList.add(it);
            }
        }
        return pickList;
    }

    private List<HomeApps.Item> pickList;

    private void openSheet() {
        openSheet(false);
    }

    private void openSheet(boolean pick) {
        picking = pick;
        pickList = null;
        sheetOpen = true;
        sheet.target = 1;
        sheetIndex = 0;
        sheetScroll.snap(0);
        sheetScroll.target = 0;
        focusChanged();
    }
}
