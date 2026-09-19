package namod.j2me.bundled;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Tự động extract NinjaNamod.jar + converted.dex data từ assets ra đúng vị trí.
 * Giải quyết lỗi "No MIDlets found" bằng cách bundle sẵn converted.dex vào APK.
 *
 * Layout assets:
 *   assets/bundled/NinjaNamod.jar        → /J2ME-Loader/converted/NinjaNamod/NinjaNamod.jar
 *   assets/bundled/ninja_data/converted.dex     → /J2ME-Loader/converted/NinjaNamod/
 *   assets/bundled/ninja_data/converted.dex.conf→ /J2ME-Loader/converted/NinjaNamod/
 *   assets/bundled/ninja_data/res.jar           → /J2ME-Loader/converted/NinjaNamod/
 */
public class BundledAppInstaller {
    private static final String TAG          = "BundledInstaller";
    private static final String ASSET_JAR    = "bundled/NinjaNamod.jar";
    private static final String JAR_NAME     = "NinjaNamod.jar";
    private static final String PREFS_NAME   = "bundled_installer_prefs";
    private static final String KEY_JAR_HASH = "ninja_jar_hash";
    private static final String KEY_DATA_VER = "ninja_data_ver";
    private static final int    CURRENT_DATA_VER = 2; // tăng khi update converted data

    // Thư mục J2ME-Loader trên storage ngoài
    private static String getJ2MELoaderDir() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/J2ME-Loader";
    }

    // Thư mục converted/NinjaNamod/ (nơi J2MELoader đọc DEX)
    private static String getConvertedDir() {
        return getJ2MELoaderDir() + "/converted/NinjaNamod";
    }

    // Thư mục JAR source (để launch)
    private static String getJarDestPath() {
        File dir = new File(getConvertedDir());
        dir.mkdirs();
        return new File(dir, JAR_NAME).getAbsolutePath();
    }

    /**
     * Gọi khi app khởi động (trên background thread).
     * Extract JAR + converted DEX data nếu chưa có hoặc có bản mới.
     * @return path tới JAR đã install, hoặc null nếu lỗi.
     */
    public static String ensureInstalled(Context ctx) {
        try {
            // 1. Extract NinjaNamod.jar
            extractJar(ctx);

            // 2. Extract converted.dex + conf + res.jar
            ensureConvertedDataInstalled(ctx);

            Log.i(TAG, "NinjaNamod fully installed at: " + getConvertedDir());
            return getJarDestPath();

        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage(), e);
            if (new File(getJarDestPath()).exists()) return getJarDestPath();
            return null;
        }
    }

    /** Extract NinjaNamod.jar nếu cần */
    private static void extractJar(Context ctx) throws Exception {
        String destPath = getJarDestPath();
        String assetHash = computeHash(ctx, ASSET_JAR);
        String installedHash = getInstalledHash(ctx);

        File destFile = new File(destPath);
        if (destFile.exists() && assetHash != null && assetHash.equals(installedHash)) {
            Log.d(TAG, "JAR up-to-date");
            return;
        }

        Log.i(TAG, "Extracting JAR...");
        copyAsset(ctx, ASSET_JAR, destPath);
        if (assetHash != null) saveInstalledHash(ctx, assetHash);
        Log.i(TAG, "JAR extracted OK");
    }

    /** Extract converted.dex, converted.dex.conf, res.jar nếu cần */
    public static void ensureConvertedDataInstalled(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int installedVer = prefs.getInt(KEY_DATA_VER, 0);
        if (installedVer >= CURRENT_DATA_VER) {
            // Kiểm tra file thực sự tồn tại
            File dex = new File(getConvertedDir(), "converted.dex");
            if (dex.exists()) {
                Log.d(TAG, "converted data up-to-date (v" + installedVer + ")");
                return;
            }
        }

        String[] dataFiles = { "converted.dex", "converted.dex.conf", "res.jar" };
        String convertedDir = getConvertedDir();
        new File(convertedDir).mkdirs();

        for (String fileName : dataFiles) {
            String assetPath = "bundled/ninja_data/" + fileName;
            String destPath = convertedDir + "/" + fileName;
            try {
                copyAsset(ctx, assetPath, destPath);
                Log.i(TAG, "Extracted: " + fileName);
            } catch (IOException e) {
                Log.e(TAG, "Failed to extract " + fileName + ": " + e.getMessage());
            }
        }

        prefs.edit().putInt(KEY_DATA_VER, CURRENT_DATA_VER).apply();
        Log.i(TAG, "Converted data installed (v" + CURRENT_DATA_VER + ")");
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static void copyAsset(Context ctx, String assetPath, String destPath) throws IOException {
        File dest = new File(destPath);
        dest.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private static String computeHash(Context ctx, String assetPath) {
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) md.update(buf, 0, len);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Hash error: " + e.getMessage());
            return null;
        }
    }

    private static String getInstalledHash(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .getString(KEY_JAR_HASH, null);
    }

    private static void saveInstalledHash(Context ctx, String hash) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit().putString(KEY_JAR_HASH, hash).apply();
    }

    /** Path tới JAR đã install */
    public static String getInstalledPath() {
        return getJarDestPath();
    }

    /** Kiểm tra JAR đã được install chưa */
    public static boolean isInstalled() {
        return new File(getJarDestPath()).exists();
    }

    /** Kiểm tra converted data đã sẵn sàng chưa */
    public static boolean isConvertedReady() {
        return new File(getConvertedDir(), "converted.dex").exists();
    }
}
