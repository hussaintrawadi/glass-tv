package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Dialogue Boost: makes dialogue easy to follow on the TV's own speakers, for every app. On the TV's whole output
 * (audio session 0) it lifts the speech frequencies and trims the boom, so voices aren't buried under music and
 * effects, and limits the peaks so the speakers never distort. It never rides the volume: the film's own loud and
 * quiet scenes stay as they were mixed. Modes: off and "voice". A foreground service: the effect lives as long as
 * this service does. The mode is kept in a file (the settings screen is in another process).
 */
public final class SoundService extends Service {
    static final String OFF = "off", VOICE = "voice";
    private static final String CHANNEL = "sound";
    private static final int PRIORITY = 1000;

    private DynamicsProcessing dynamics;
    private LoudnessEnhancer loudness;
    private String applied = "";

    /** The saved mode (off when never set). */
    static String mode(Context c) {
        File f = file(c);
        if (!f.exists()) return OFF;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            String m = n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : OFF;
            // "boost" was Smart Volume, which is gone: whoever had it on gets Dialogue Boost.
            return m.equals(VOICE) || m.equals("boost") ? VOICE : OFF;
        } catch (IOException e) {
            return OFF;
        }
    }

    /** Saves the mode and applies it now. */
    static void set(Context c, String mode) {
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(mode.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("sound mode: " + e.getMessage());
        }
        start(c);
    }

    /** Starts the service when a mode is on (after boot, after an update, when the mode changes). */
    static void start(Context c) {
        Intent i = new Intent(c, SoundService.class);
        try {
            if (OFF.equals(mode(c))) c.stopService(i);
            else if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            AppLog.w("sound service: " + e.getMessage());
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "sound-mode.txt");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(7, notification());
        String m = mode(this);
        if (OFF.equals(m)) {
            release();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!m.equals(applied)) apply(m);
        return START_STICKY;
    }

    private void apply(String m) {
        release();
        try {
            dynamics = new DynamicsProcessing(PRIORITY, 0, config());
            dynamics.setInputGainAllChannelsTo(2f);
            // Dialogue first: less rumble, more voice presence (where consonants make words clear).
            float[][] eq = {{150, -5}, {900, 0}, {4500, 6}, {20000, 0}};
            for (int i = 0; i < eq.length; i++) {
                dynamics.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, eq[i][0], eq[i][1]));
            }
            // A light, slow touch on each band (2:1, a soft knee): the voice band forward, the boom a little back.
            dynamics.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 250, 80, 800, 2f, -28, 10, -90, 1, 0, -2));
            dynamics.setMbcBandAllChannelsTo(1, new DynamicsProcessing.MbcBand(true, 4500, 60, 700, 2f, -30, 10, -90, 1, 0, 5));
            dynamics.setMbcBandAllChannelsTo(2, new DynamicsProcessing.MbcBand(true, 20000, 50, 600, 2f, -28, 10, -90, 1, 0, 1));
            // A fast safety limiter only for rare sudden peaks, so nothing crackles. It never rides the level itself.
            dynamics.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1, 80, 10f, -1f, 0));
            dynamics.setEnabled(true);
            AppLog.w("dialogue boost on, control: " + dynamics.hasControl());
        } catch (RuntimeException e) {
            // No dynamics processing here: a loudness boost (it has its own limiter) still helps.
            AppLog.w("dialogue boost: dynamics unavailable (" + e.getMessage() + "), using loudness only");
            dynamics = null;
            try {
                loudness = new LoudnessEnhancer(0);
                loudness.setTargetGain(600);
                loudness.setEnabled(true);
            } catch (RuntimeException e2) {
                AppLog.w("dialogue boost: loudness unavailable: " + e2.getMessage());
            }
        }
        applied = m;
    }

    private static DynamicsProcessing.Config config() {
        return new DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, 4, true, 3, false, 0, true).setPreferredFrameDuration(10).build();
    }

    private void release() {
        if (dynamics != null) {
            dynamics.release();
            dynamics = null;
        }
        if (loudness != null) {
            loudness.release();
            loudness = null;
        }
        applied = "";
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Dialogue Boost", NotificationManager.IMPORTANCE_MIN));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Dialogue Boost is on")
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
