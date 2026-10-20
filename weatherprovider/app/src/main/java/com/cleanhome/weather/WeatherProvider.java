package com.cleanhome.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Supplies the launcher's weather widget (its CVTE fallback reads
 * content://com.cvte.tv.weather.export.WeatherDataContentProvider/weather).
 * Data comes from Open-Meteo (free, no API key). Edit LAT/LON/CITY to change location.
 */
public class WeatherProvider extends ContentProvider {

    private static final String TAG = "CleanHomeWeather";

    // ---- EDIT THESE to change the city ----
    static final double LAT = 40.5167;   // Kastoria
    static final double LON = 21.2683;
    static final String CITY = "Καστοριά";
    // ---------------------------------------

    private static final long FRESH_MS = 30 * 60 * 1000; // 30 min
    private static final String CACHE = "weather.json";

    static final String[] COLUMNS = {
            "_id", "curTemperature", "min", "max", "temperatureUnit",
            "weatherText", "weatherIcon", "weatherCode",
            "weatherTextNight", "weatherIconNight",
            "day", "weekDay", "location"
    };

    @Override public boolean onCreate() {
        // warm the cache in the background
        refreshAsync(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] selArgs, String order) {
        Context ctx = getContext();
        JSONObject data = readCache(ctx);
        boolean fresh = data != null && (System.currentTimeMillis() - data.optLong("ts", 0) < FRESH_MS);
        if (data == null) {
            // first run: do a short synchronous fetch so the widget shows something now
            data = fetch(ctx);
        } else if (!fresh) {
            refreshAsync(ctx); // serve stale now, refresh for next time
        }
        MatrixCursor c = new MatrixCursor(COLUMNS);
        if (data != null) {
            c.addRow(new Object[]{
                    1,
                    data.optString("cur", "--"),
                    data.optString("min", "--"),
                    data.optString("max", "--"),
                    "C",
                    data.optString("text", ""),
                    data.optString("icon", "1"),
                    data.optString("icon", "1"),
                    data.optString("text", ""),
                    data.optString("iconNight", "33"),
                    data.optString("day", ""),
                    data.optString("weekDay", ""),
                    CITY
            });
        }
        return c;
    }

    // ----- fetch + cache -----

    private void refreshAsync(final Context ctx) {
        new Thread(() -> fetch(ctx)).start();
    }

    private static synchronized JSONObject fetch(Context ctx) {
        HttpURLConnection conn = null;
        try {
            String u = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                    + "&longitude=" + LON
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                    + "&timezone=auto&forecast_days=1";
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject cur = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            double t = cur.getDouble("temperature_2m");
            double mx = daily.getJSONArray("temperature_2m_max").getDouble(0);
            double mn = daily.getJSONArray("temperature_2m_min").getDouble(0);

            Calendar cal = Calendar.getInstance();
            JSONObject out = new JSONObject();
            out.put("ts", System.currentTimeMillis());
            out.put("cur", Long.toString(Math.round(t)));
            out.put("max", Long.toString(Math.round(mx)));
            out.put("min", Long.toString(Math.round(mn)));
            out.put("icon", Integer.toString(accuIcon(code, true)));
            out.put("iconNight", Integer.toString(accuIcon(code, false)));
            out.put("text", describe(code));
            out.put("weekDay", new SimpleDateFormat("EEEE", new Locale("el")).format(cal.getTime()));
            out.put("day", new SimpleDateFormat("dd/MM", Locale.US).format(cal.getTime()));
            writeCache(ctx, out);
            Log.i(TAG, "weather updated: " + out);
            return out;
        } catch (Throwable e) {
            Log.w(TAG, "fetch failed: " + e);
            return readCache(ctx); // fall back to whatever we had
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject readCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            return new JSONObject(sb.toString());
        } catch (Throwable e) { return null; }
    }

    private static void writeCache(Context ctx, JSONObject o) {
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), CACHE))) {
            w.write(o.toString());
        } catch (Throwable ignored) {}
    }

    // Map WMO weather code (Open-Meteo) -> AccuWeather icon number (1-44) used by the TV's icon set.
    static int accuIcon(int wmo, boolean day) {
        switch (wmo) {
            case 0:  return day ? 1 : 33;   // clear
            case 1:  return day ? 2 : 34;   // mainly clear
            case 2:  return day ? 3 : 35;   // partly cloudy
            case 3:  return 7;              // overcast
            case 45: case 48: return 11;    // fog
            case 51: case 53: case 55: return 12;      // drizzle
            case 56: case 57: return 26;               // freezing drizzle
            case 61: case 63: case 65: return 18;      // rain
            case 66: case 67: return 26;               // freezing rain
            case 71: case 73: case 75: return 22;      // snow
            case 77: return 19;                        // snow grains
            case 80: case 81: case 82: return 12;      // rain showers
            case 85: case 86: return 22;               // snow showers
            case 95: return 15;                        // thunderstorm
            case 96: case 99: return 15;               // thunderstorm w/ hail
            default: return day ? 1 : 33;
        }
    }

    static String describe(int wmo) {
        if (wmo == 0) return "Αίθριος";
        if (wmo <= 2) return "Λίγα σύννεφα";
        if (wmo == 3) return "Συννεφιά";
        if (wmo == 45 || wmo == 48) return "Ομίχλη";
        if (wmo >= 51 && wmo <= 57) return "Ψιχάλα";
        if (wmo >= 61 && wmo <= 67) return "Βροχή";
        if (wmo >= 71 && wmo <= 77) return "Χιόνι";
        if (wmo >= 80 && wmo <= 82) return "Μπόρες";
        if (wmo >= 85 && wmo <= 86) return "Χιονόνερο";
        if (wmo >= 95) return "Καταιγίδα";
        return "";
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/weather"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
