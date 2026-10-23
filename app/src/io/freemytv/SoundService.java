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
 * Modes: off, "voice" (clearer, a little louder) and "boost" (Loud & Even: a leveller, every scene at one loud level). A
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
            // Gain in, then: the boom trimmed, speech (1-5 kHz) lifted, a touch of air.
            dynamics.setInputGainAllChannelsTo(boost ? 6f : 4f);
            float[][] eq = boost ? new float[][] {{150, -2}, {1000, 1}, {5000, 5}, {20000, 2}}
                    : new float[][] {{150, -4}, {1000, 0}, {5000, 5}, {20000, 1}};
            for (int i = 0; i < eq.length; i++) {
                dynamics.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, eq[i][0], eq[i][1]));
            }
            if (boost) {
                // Loud & Even, a leveller: everything over -42 dB is pressed 5:1 and the result raised a lot, with a slow
                // release so it doesn't pump. Whispers come up, explosions and music come down: one steady, loud level.
                dynamics.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 250, 10, 400, 5f, -42, 8, -80, 1, 0, 8));
                dynamics.setMbcBandAllChannelsTo(1, new DynamicsProcessing.MbcBand(true, 5000, 8, 350, 5f, -42, 8, -80, 1, 0, 14));
                dynamics.setMbcBandAllChannelsTo(2, new DynamicsProcessing.MbcBand(true, 20000, 6, 300, 4f, -40, 8, -80, 1, 0, 9));
            } else {
                // Clear Voice: gentle compression per band; quiet dialogue comes up with the speech band's make-up gain.
                dynamics.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 250, 5, 120, 3f, -28, 6, -90, 1, 0, 0));
                dynamics.setMbcBandAllChannelsTo(1, new DynamicsProcessing.MbcBand(true, 5000, 3, 80, 3f, -32, 6, -90, 1, 0, 5));
                dynamics.setMbcBandAllChannelsTo(2, new DynamicsProcessing.MbcBand(true, 20000, 2, 80, 2f, -30, 6, -90, 1, 0, 1));
            }
            // The limiter: no peak goes over, so the speakers never crackle however loud it gets.
            dynamics.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1, 50, 20f, boost ? -1f : -3f, 0));
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
