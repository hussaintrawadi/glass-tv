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
 * Modes: off, "voice" (dialogue first: voices lifted, music and effects held down) and "boost" (Loud & Even: the same,
 * with more lift for voices and more gain overall). A
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
            // Dialogue first. Voices live around 1-4.5 kHz (consonants, what makes words clear); music and effects
            // carry most of their weight in the bass and the boom. So: the rumble cut, the voice presence lifted, the
            // top softened...
            dynamics.setInputGainAllChannelsTo(boost ? 5f : 3f);
            float[][] eq = {{80, -8}, {200, -4}, {800, 0}, {3500, boost ? 7 : 6}, {7000, 1}, {20000, -2}};
            for (int i = 0; i < eq.length; i++) {
                dynamics.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, eq[i][0], eq[i][1]));
            }
            // ...then each band compressed on its own: the bass and boom (music, explosions) held DOWN with no make-up,
            // the speech band levelled and lifted (quiet lines come up, and only they), the treble tamed. Music can't
            // rise between the lines any more; voices sit on top. Loud & Even gives voices more lift and more overall
            // gain.
            dynamics.setMbcBandAllChannelsTo(0, new DynamicsProcessing.MbcBand(true, 200, 5, 200, 6f, boost ? -38 : -36, 6, -90, 1, 0, boost ? 0 : -2));
            dynamics.setMbcBandAllChannelsTo(1, new DynamicsProcessing.MbcBand(true, 1000, 5, 150, 2.5f, -38, 6, -90, 1, 0, boost ? 6 : 4));
            dynamics.setMbcBandAllChannelsTo(2, new DynamicsProcessing.MbcBand(true, 4500, 3, 120, 3f, boost ? -44 : -42, 6, -90, 1, 0, boost ? 12 : 9));
            dynamics.setMbcBandAllChannelsTo(3, new DynamicsProcessing.MbcBand(true, 20000, 2, 100, 4f, -36, 6, -90, 1, 0, boost ? 1 : 0));
            // The limiter: no peak goes over, so the speakers never crackle however loud it gets.
            dynamics.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1, 50, 20f, boost ? -1f : -2f, 0));
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
                true, 6, true, 4, false, 0, true).setPreferredFrameDuration(10).build();
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
