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
 * Clear Voice: makes dialogue easy to follow on the TV's own speakers, for every app. On the TV's whole output (audio
 * session 0) it lifts the speech frequencies and trims the boom, evens out loud and quiet parts (so voices aren't
 * buried under music and effects), adds gain, and limits the peaks so the speakers never distort.
 * Modes: off, "voice" (Clear Voice: dialogue a little forward, a light touch) and "boost" (Smart Volume: dialogue first,
 * and the level ridden slowly, like a hand on the remote, so loud and quiet scenes meet in the middle). A
 * foreground service: the effect
 * lives as long as this service does. The mode is kept in a file (the settings screen is in another process).
 */
public final class SoundService extends Service {
    static final String OFF = "off", VOICE = "voice", BOOST = "boost";
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
            return m.equals(VOICE) || m.equals(BOOST) ? m : OFF;
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
        boolean boost = BOOST.equals(m);
        try {
            dynamics = new DynamicsProcessing(PRIORITY, 0, config());
            dynamics.setInputGainAllChannelsTo(2f);
            // Dialogue first, gently: less rumble, a little more voice presence (where consonants make words clear).
            float[][] eq = {{150, -5}, {900, 0}, {4500, boost ? 4 : 5}, {20000, 0}};
            for (int i = 0; i < eq.length; i++) {
                dynamics.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, eq[i][0], eq[i][1]));
            }
            if (boost) {
                // Smart Volume rides the volume the way a person would with the remote, slowly: it starts turning a
                // loud scene down over a third of a second and brings a quiet one back up over two and a half seconds,
                // so a change is a fade, never a jump. Soft and mild (2.5:1 above -30 dB, a 12 dB knee): a raised
                // voice in a scene stays a raised voice; only scenes clearly too loud or too quiet are moved. The voice
                // band gets the make-up gain, the bass of music and effects doesn't.
                dynamics.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 250, 300, 2500, 3f, -30, 12, -90, 1, 0, 1));
                dynamics.setMbcBandAllChannelsTo(1, new DynamicsProcessing.MbcBand(true, 4500, 300, 2500, 2.5f, -30, 12, -90, 1, 0, 8));
                dynamics.setMbcBandAllChannelsTo(2, new DynamicsProcessing.MbcBand(true, 20000, 300, 2500, 2.5f, -30, 12, -90, 1, 0, 4));
            } else {
                // Clear Voice: a light, slow touch on each band; the voice band a little forward, the boom a little back.
                dynamics.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 250, 80, 800, 2f, -28, 10, -90, 1, 0, -1));
                dynamics.setMbcBandAllChannelsTo(1, new DynamicsProcessing.MbcBand(true, 4500, 60, 700, 2f, -30, 10, -90, 1, 0, 4));
                dynamics.setMbcBandAllChannelsTo(2, new DynamicsProcessing.MbcBand(true, 20000, 50, 600, 2f, -28, 10, -90, 1, 0, 1));
            }
            // A fast safety limiter only for rare sudden peaks, so nothing crackles. It never rides the level itself.
            dynamics.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1, 80, 10f, -1f, 0));
            dynamics.setEnabled(true);
            AppLog.w("clear voice on (" + m + "), control: " + dynamics.hasControl());
        } catch (RuntimeException e) {
            // No dynamics processing here: a loudness boost (it has its own limiter) still helps.
            AppLog.w("clear voice: dynamics unavailable (" + e.getMessage() + "), using loudness only");
            dynamics = null;
            try {
                loudness = new LoudnessEnhancer(0);
                loudness.setTargetGain(boost ? 1000 : 600);
                loudness.setEnabled(true);
            } catch (RuntimeException e2) {
                AppLog.w("clear voice: loudness unavailable: " + e2.getMessage());
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
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Clear Voice", NotificationManager.IMPORTANCE_MIN));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Clear Voice is on")
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
