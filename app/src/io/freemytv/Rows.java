package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's content (tvOS style): a few featured titles for the top shelf and rows of what's popular on each
 * streaming app the TV has ("Top Shows on Netflix", "Top Movies on Prime Video"...), from TMDB for your country. Fetched
 * at most every 6 hours and kept on disk, so the home screen shows them right away after a restart.
 * Images: small copies (w300) for the rows, the w1280 one for the top shelf; on disk, and a capped cache in memory.
 */
final class Rows {
    static final class Title {
        int id;
        String kind, name, backdrop, overview, meta, service, pkg;
        // Made once and reused while drawing (no new strings every frame): image cache keys and the name cut to fit.
        String smallKey, largeKey, shortName;
        float shortWidth;
    }

    static final class Row {
        String name;
        final List<Title> titles = new ArrayList<>();
    }

    interface Listener { void onRows(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long EVERY = 6 * 3600_000L;
    private static final int CACHE_VERSION = 2;
    /** Streaming apps with rows: key, TMDB provider name (exact, lowercase; then "contains"), package, display name. */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja", "Netflix"},
        {"prime", "amazon prime video", "com.amazon.amazonvideo.livingroom", "Prime Video"},
        {"hotstar", "jiohotstar", "in.startv.hotstar", "JioHotstar"},
        {"sonyliv", "sony liv", "com.sonyliv", "Sony LIV"},
    };

    private static Rows instance;

    static synchronized Rows get(Context c) {
        if (instance == null) instance = new Rows(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final File file;
    private volatile List<Row> rows = new ArrayList<>();
    private volatile List<Title> featured = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;
    private Listener listener;

    /** Decoded images by size+path, capped by bytes (thumbnails in RGB 565: half the memory, no alpha needed). */
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(10 << 20) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> loading = new HashSet<>();

    private Rows(Context c) {
        context = c;
        file = new File(c.getFilesDir(), "rows.json");
        io.execute(this::loadSaved);
    }

    static boolean enabled() {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    void setListener(Listener l) { listener = l; }
    List<Row> rows() { return rows; }
    List<Title> featured() { return featured; }

    /** Fetches again when the saved rows are older than 6 hours (or there are none). */
    void refresh() {
        if (!enabled() || fetching || System.currentTimeMillis() - fetchedAt < EVERY) return;
        fetching = true;
        io.execute(() -> {
            try {
                fetch();
            } catch (Exception e) {
                AppLog.w("rows: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    // MARK: - Fetching

    private void fetch() throws IOException, JSONException {
        Map<String, Integer> ids = providers();
        Map<Integer, String> genres = genres("movie");
        genres.putAll(genres("tv"));
        String region = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        PackageManager pm = context.getPackageManager();
        List<String[]> have = new ArrayList<>();
        for (String[] s : SERVICES) if (ids.containsKey(s[0]) && HomeApps.launchIntent(pm, s[2]) != null) have.add(s);
        // Shows of the first app, movies of the second... then the other way round: the rows alternate.
        List<Row> out = new ArrayList<>();
        Map<String, Row> byName = new HashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < have.size(); i++) {
                String[] s = have.get(i);
                String kind = (i + pass) % 2 == 0 ? "tv" : "movie";
                Row row = new Row();
                row.name = (kind.equals("tv") ? "Top Shows on " : "Top Movies on ") + s[3];
                JSONArray results = get("/discover/" + kind + "?watch_region=" + region + "&with_watch_providers=" + ids.get(s[0])
                        + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&language=en-US&page=1")
                        .optJSONArray("results");
                for (int j = 0; results != null && j < results.length() && row.titles.size() < 14; j++) {
                    Title t = title(results.getJSONObject(j), kind, genres);
                    if (t == null) continue;
                    t.service = s[3];
                    t.pkg = s[2];
                    row.titles.add(t);
                }
                if (!row.titles.isEmpty()) {
                    out.add(row);
                    byName.put(row.name, row);
                }
            }
        }
        // "Trending Now" first: the most popular of every row, taking turns, no title twice. The top shelf shows its
        // first ones.
        Row trending = new Row();
        trending.name = "Trending Now";
        Set<Integer> seen = new HashSet<>();
        for (int rank = 0; rank < 5; rank++) {
            for (Row r : out) {
                if (rank < r.titles.size() && seen.add(r.titles.get(rank).id) && trending.titles.size() < 16) trending.titles.add(r.titles.get(rank));
            }
        }
        if (!trending.titles.isEmpty()) out.add(0, trending);
        List<Title> top = new ArrayList<>(trending.titles.subList(0, Math.min(8, trending.titles.size())));
        if (out.isEmpty()) throw new IOException("TMDB has none of the TV's apps for " + region);
        long now = System.currentTimeMillis();
        save(out, top, now);
        publish(out, top, now);
        AppLog.w("rows: " + out.size() + " rows, " + top.size() + " featured");
    }

    private Title title(JSONObject o, String kind, Map<Integer, String> genres) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return null;
        Title t = new Title();
        t.id = o.optInt("id");
        t.kind = kind;
        t.name = o.optString(kind.equals("tv") ? "name" : "title", "");
        t.backdrop = backdrop;
        t.overview = o.optString("overview", "");
        String date = o.optString(kind.equals("tv") ? "first_air_date" : "release_date", "");
        List<String> meta = new ArrayList<>();
        if (date.length() >= 4) meta.add(date.substring(0, 4));
        meta.add(kind.equals("tv") ? "Series" : "Movie");
        JSONArray g = o.optJSONArray("genre_ids");
        if (g != null && g.length() > 0 && genres.containsKey(g.optInt(0))) meta.add(genres.get(g.optInt(0)));
        double vote = o.optDouble("vote_average", 0);
        if (vote >= 1 && o.optInt("vote_count", 0) >= 20) meta.add(String.format(Locale.US, "★ %.1f", vote));
        t.meta = String.join("  ·  ", meta);
        return t.name.isEmpty() ? null : t;
    }

    /** Provider ids for our services in this country (exact name first, then a name that contains it). */
    private Map<String, Integer> providers() throws IOException, JSONException {
        String region = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        Map<String, Integer> out = new HashMap<>();
        for (String kind : new String[] {"tv", "movie"}) {
            JSONArray list = get("/watch/providers/" + kind + "?watch_region=" + region).optJSONArray("results");
            for (int pass = 0; pass < 2 && list != null; pass++) {
                for (String[] s : SERVICES) {
                    if (out.containsKey(s[0])) continue;
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject p = list.getJSONObject(i);
                        String name = p.optString("provider_name", "").toLowerCase(Locale.ROOT);
                        boolean match = pass == 0 ? name.equals(s[1]) : name.contains(s[0]) && !name.contains("ads") && !name.contains("channel");
                        if (match) {
                            out.put(s[0], p.optInt("provider_id"));
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    private Map<Integer, String> genres(String kind) throws IOException, JSONException {
        Map<Integer, String> out = new HashMap<>();
        JSONArray list = get("/genre/" + kind + "/list?language=en-US").optJSONArray("genres");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject g = list.getJSONObject(i);
            out.put(g.optInt("id"), g.optString("name"));
        }
        return out;
    }

    private JSONObject get(String path) throws IOException, JSONException {
        String key = Config.TMDB_KEY.trim();
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // MARK: - Saved copy

    private void save(List<Row> list, List<Title> top, long at) {
        try {
            JSONObject o = new JSONObject().put("version", CACHE_VERSION).put("at", at);
            JSONArray rs = new JSONArray();
            for (Row r : list) rs.put(new JSONObject().put("name", r.name).put("titles", toJson(r.titles)));
            o.put("rows", rs).put("featured", toJson(top));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException e) {
            AppLog.w("rows: couldn't save: " + e.getMessage());
        }
    }

    private void loadSaved() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            JSONObject o = new JSONObject(read(in));
            if (o.optInt("version") != CACHE_VERSION) return;
            List<Row> list = new ArrayList<>();
            JSONArray rs = o.getJSONArray("rows");
            for (int i = 0; i < rs.length(); i++) {
                Row r = new Row();
                r.name = rs.getJSONObject(i).getString("name");
                r.titles.addAll(fromJson(rs.getJSONObject(i).getJSONArray("titles")));
                list.add(r);
            }
            publish(list, fromJson(o.getJSONArray("featured")), o.optLong("at"));
        } catch (IOException | JSONException e) {
            AppLog.w("rows: saved copy unreadable: " + e.getMessage());
        }
    }

    private static JSONArray toJson(List<Title> list) throws JSONException {
        JSONArray a = new JSONArray();
        for (Title t : list) {
            a.put(new JSONObject().put("id", t.id).put("kind", t.kind).put("name", t.name).put("backdrop", t.backdrop)
                    .put("overview", t.overview).put("meta", t.meta).put("service", t.service).put("pkg", t.pkg));
        }
        return a;
    }

    private static List<Title> fromJson(JSONArray a) throws JSONException {
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Title t = new Title();
            t.id = o.optInt("id");
            t.kind = o.optString("kind");
            t.name = o.optString("name");
            t.backdrop = o.optString("backdrop");
            t.overview = o.optString("overview");
            t.meta = o.optString("meta");
            t.service = o.optString("service");
            t.pkg = o.optString("pkg");
            out.add(t);
        }
        return out;
    }

    private void publish(List<Row> list, List<Title> top, long at) {
        main.post(() -> {
            rows = list;
            featured = top;
            fetchedAt = at;
            if (listener != null) listener.onRows();
        });
    }

    // MARK: - Images

    /** The title's image if it's ready; otherwise null, and it's fetched (then the listener redraws). Main thread only. */
    Bitmap image(Title t, boolean large) {
        if (large && t.largeKey == null) t.largeKey = "L" + t.backdrop;
        if (!large && t.smallKey == null) t.smallKey = "S" + t.backdrop;
        return image(t.backdrop, large ? t.largeKey : t.smallKey, large);
    }

    private Bitmap image(String path, String key, boolean large) {
        Bitmap b = memory.get(key);
        if (b != null || loading.contains(key)) return b;
        loading.add(key);
        images.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);   // never ahead of drawing
            Bitmap decoded = null;
            try {
                File f = new File(context.getCacheDir(), "img/" + (large ? "w1280" : "w300") + path.replace('/', '_'));
                if (!f.exists()) download(IMG + (large ? "w1280" : "w300") + path, f);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = large ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                decoded = BitmapFactory.decodeFile(f.getPath(), o);
                if (decoded == null) f.delete();   // a broken download: fetched again next time
            } catch (IOException e) {
                AppLog.w("image: " + e.getMessage());
            }
            Bitmap done = decoded;
            main.post(() -> {
                loading.remove(key);
                if (done != null) memory.put(key, done);
                if (done != null && listener != null) listener.onRows();
            });
        });
        return null;
    }

    private static void download(String url, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save " + f.getName());
    }

    /** Memory is short (the TV asked): drop the decoded images; they come back from disk. */
    void trim() {
        memory.evictAll();
    }

    /**
     * The home screen is hidden (an app or a movie is in front): give back the row images and every top shelf image but
     * the one on screen, so returning shows it at once. The rows are below the fold: they reload before they're seen.
     */
    /** The row image isn't needed in memory any more (the home screen made its finished card from it). */
    void forget(Title t) {
        if (t.smallKey != null) memory.remove(t.smallKey);
    }

    /** The banner made its plate from this title's large image: it isn't needed in memory any more. */
    void forgetLarge(Title t) {
        if (t.largeKey != null) memory.remove(t.largeKey);
    }

    void trimHidden(Title keep) {
        String keepKey = keep == null ? null : keep.largeKey;
        for (String key : memory.snapshot().keySet()) if (!key.equals(keepKey)) memory.remove(key);
    }

    static int hour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }
}
