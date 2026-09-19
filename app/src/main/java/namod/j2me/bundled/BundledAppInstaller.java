package namod.j2me.bundled;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Tu dong extract NinjaNamod.jar + converted.dex data tu assets ra dung vi tri.
 * Giai quyet loi "No MIDlets found" bang cach bundle san converted.dex vao APK.
 *
 * Layout assets:
 *   assets/bundled/NinjaNamod.jar        -> /J2ME-Loader/converted/NinjaNamod/NinjaNamod.jar
 *   assets/bundled/ninja_data/converted.dex     -> /J2ME-Loader/converted/NinjaNamod/
 *   assets/bundled/ninja_data/converted.dex.conf-> /J2ME-Loader/converted/NinjaNamod/
 *   assets/bundled/ninja_data/res.jar           -> /J2ME-Loader/converted/NinjaNamod/
 *
 * Multi-slot: moi nick co folder rieng (NinjaNamod, NinjaNamod2, NinjaNamod3...)
 *   de RMS data (acc, pass, settings) hoan toan doc lap giua cac nick.
 */
public class BundledAppInstaller {
    private static final String TAG          = "BundledInstaller";
    private static final String ASSET_JAR    = "bundled/NinjaNamod.jar";
    private static final String JAR_NAME     = "NinjaNamod.jar";
    private static final String BASE_SLOT    = "NinjaNamod"; // slot 1 = ten goc
    private static final String PREFS_NAME   = "bundled_installer_prefs";
    private static final String KEY_JAR_HASH = "ninja_jar_hash";
    private static final String KEY_DATA_VER = "ninja_data_ver";
    private static final int    CURRENT_DATA_VER = 2;

    private static String getJ2MELoaderDir() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/J2ME-Loader";
    }

    /** Ten slot tu slotIndex: 0->NinjaNamod, 1->NinjaNamod2, 2->NinjaNamod3... */
    public static String slotName(int slotIndex) {
        return slotIndex <= 0 ? BASE_SLOT : BASE_SLOT + (slotIndex + 1);
    }

    private static String getConvertedDir(String slotName) {
        return getJ2MELoaderDir() + "/converted/" + slotName;
    }

    private static String getConvertedDir() {
        return getConvertedDir(BASE_SLOT);
    }

    private static String getJarDestPath(String slotName) {
        File dir = new File(getConvertedDir(slotName));
        dir.mkdirs();
        return new File(dir, JAR_NAME).getAbsolutePath();
    }

    private static String getJarDestPath() {
        return getJarDestPath(BASE_SLOT);
    }

    /**
     * Goi khi app khoi dong (tren background thread).
     * Extract JAR + converted DEX data neu chua co hoac co ban moi.
     * @return path toi JAR da install, hoac null neu loi.
     */
    public static String ensureInstalled(Context ctx) {
        return ensureSlotInstalled(ctx, BASE_SLOT);
    }

    /**
     * Goi khi bat nick voi slot cu the.
     * Neu slot la slot goc -> extract tu assets.
     * Neu slot phu -> copy tu slot goc (chi copy 1 lan, bo qua neu da co).
     * @param slotName "NinjaNamod", "NinjaNamod2", ...
     * @return path toi JAR trong slot do, hoac null neu loi.
     */
    public static String ensureSlotInstalled(Context ctx, String slotName) {
        try {
            // 1. Dam bao slot goc luon duoc extract day du tu assets
            extractJar(ctx);
            ensureConvertedDataInstalled(ctx);

            // 2. Neu la slot phu -> copy tu slot goc neu chua co
            if (!slotName.equals(BASE_SLOT)) {
                ensureSlotCopied(slotName);
            }

            Log.i(TAG, "Slot [" + slotName + "] ready at: " + getConvertedDir(slotName));
            return getJarDestPath(slotName);

        } catch (Exception e) {
            Log.e(TAG, "Install failed for slot " + slotName + ": " + e.getMessage(), e);
            File fallback = new File(getJarDestPath(slotName));
            return fallback.exists() ? fallback.getAbsolutePath() : null;
        }
    }

    /**
     * Tao thu muc slot phu bang cach copy cac file tu slot goc.
     * Chi copy: NinjaNamod.jar, converted.dex, converted.dex.conf, res.jar
     * RMS data (*.rsh, *.rsr) KHONG copy -> moi slot co data rieng.
     */
    private static void ensureSlotCopied(String slotName) throws IOException {
        File srcDir = new File(getConvertedDir(BASE_SLOT));
        File dstDir = new File(getConvertedDir(slotName));
        dstDir.mkdirs();

        String[] filesToCopy = { JAR_NAME, "converted.dex", "converted.dex.conf", "res.jar" };
        for (String fileName : filesToCopy) {
            File src = new File(srcDir, fileName);
            File dst = new File(dstDir, fileName);
            if (!dst.exists() && src.exists()) {
                copyFile(src, dst);
                Log.i(TAG, "Slot copy: " + fileName + " -> " + slotName);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

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

    public static void ensureConvertedDataInstalled(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int installedVer = prefs.getInt(KEY_DATA_VER, 0);
        if (installedVer >= CURRENT_DATA_VER) {
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

    public static String getInstalledPath() { return getJarDestPath(); }
    public static boolean isInstalled()     { return new File(getJarDestPath()).exists(); }
    public static boolean isConvertedReady(){ return new File(getConvertedDir(), "converted.dex").exists(); }
}
