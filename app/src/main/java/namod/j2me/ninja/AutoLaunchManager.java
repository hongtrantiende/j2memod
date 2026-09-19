package namod.j2me.ninja;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import namod.j2me.bundled.BundledAppInstaller;
import namod.j2me.config.Config;
import namod.j2me.config.ConfigActivity;
import namod.j2me.config.ProfileModel;
import namod.j2me.config.ProfilesManager;
import javax.microedition.shell.MicroActivity;

/**
 * Quản lý việc launch NinjaNamod.jar với config tài khoản được inject sẵn.
 *
 * Luồng:
 *  1. Ensure JAR đã được extract từ assets
 *  2. Chuẩn bị ProfileModel với proxy server đúng
 *  3. Set System property "ninja.auto_login" = "username|password"
 *  4. Start MicroActivity với path JAR
 */
public class AutoLaunchManager {
    private static final String TAG = "AutoLaunchManager";
    /** Property key đọc bởi MicroLoader khi MIDlet khởi động */
    public static final String PROP_AUTO_LOGIN = "ninja.auto_login";

    private final Context ctx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AutoLaunchManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ─── Public API ───────────────────────────────────────

    /** Launch một tài khoản. Chạy trên background thread. */
    public void launchAccount(AccountModel account, Runnable onSuccess, Runnable onError) {
        if (account == null || !account.isValid()) {
            showToast("Tài khoản không hợp lệ!");
            if (onError != null) mainHandler.post(onError);
            return;
        }
        executor.execute(() -> {
            try {
                String jarPath = prepareJar();
                if (jarPath == null) throw new Exception("Không tìm thấy NinjaNamod.jar!");

                // 1. Kill game cũ nếu đang chạy → để RMS cache được clear
                killGameIfRunning();
                Thread.sleep(1500); // chờ MicroActivity tắt hẳn

                // 2. Ghi RMS acc/pass → game sẽ đọc khi khởi động mới
                preWriteRmsCredentials(account.username, account.password);

                // 3. Mở game (đọc RMS mới → tự điền username/password)
                startGame(jarPath);

                // 4. Fallback: sau 8s gửi lệnh chat "dn user pass" nếu cần
                scheduleAutoLoginCommand(account.username, account.password);

                if (onSuccess != null) mainHandler.post(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                showToast("Lỗi khởi động: " + e.getMessage());
                if (onError != null) mainHandler.post(onError);
            }
        });
    }

    /**
     * Gửi broadcast CLOSE_GAME để kill MicroActivity đang chạy (nếu có).
     * Game sẽ kết thúc gracefully trước khi ta restart.
     */
    private void killGameIfRunning() {
        try {
            android.content.Intent closeIntent = new android.content.Intent("namod.j2me.CLOSE_GAME");
            closeIntent.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(closeIntent);
            Log.i(TAG, "Sent CLOSE_GAME broadcast");
        } catch (Exception e) {
            Log.w(TAG, "killGameIfRunning: " + e.getMessage());
        }
    }


    /** Launch N tài khoản liên tiếp với delay giữa mỗi cái. */
    public void launchMultiple(java.util.List<AccountModel> accounts, long delayMs) {
        executor.execute(() -> {
            for (int i = 0; i < accounts.size(); i++) {
                AccountModel acc = accounts.get(i);
                final int idx = i;
                mainHandler.post(() ->
                    showToast("Đang mở tab " + (idx + 1) + "/" + accounts.size()
                              + ": " + acc.username));
                try {
                    String jarPath = prepareJar();
                if (jarPath != null) {
                        ServerConfig.Server server = ServerConfig.getByName(acc.serverName);
                        prepareProfile(jarPath, server);
                        preWriteRmsCredentials(acc.username, acc.password);
                        startGame(jarPath);
                        scheduleAutoLoginCommand(acc.username, acc.password);
                        if (i < accounts.size() - 1) {
                            Thread.sleep(delayMs);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Multi-launch error at slot " + idx + ": " + e.getMessage());
                }
            }
        });
    }

    // ─── Private helpers ────────────────────────────────

    /** Đảm bảo NinjaNamod.jar tồn tại trong emulator dir. */
    private String prepareJar() {
        String path = BundledAppInstaller.ensureInstalled(ctx);
        if (path == null) {
            Log.e(TAG, "JAR install failed");
        }
        return path;
    }

    /**
     * Chuẩn bị ProfileModel cho JAR:
     * Set proxyAddr/proxyPort theo server đã chọn.
     */
    private void prepareProfile(String jarPath, ServerConfig.Server server) {
        try {
            File jarFile = new File(jarPath);
            File jarDir = jarFile.getParentFile();

            // Load hoặc tạo mới ProfileModel cho NinjaNamod
            ProfileModel profile = ProfilesManager.loadConfig(jarDir);
            if (profile == null) {
                profile = new ProfileModel();
                profile.dir = jarDir;
                ProfilesManager.updateSystemProperties(profile);
            }
            // Inject server proxy (redirect IP theo server đã chọn)
            profile.proxyAddr = server.host;
            profile.proxyPort = String.valueOf(server.port);
            // Tối ưu cho treo: FPS limit 30, giảm graphics, sleepTab
            profile.fpsLimit = 30;
            profile.reduceGraphics = true;
            profile.sleepTab = true;
            profile.dir = jarDir;
            ProfilesManager.saveConfig(profile);

            Log.i(TAG, "Profile saved: proxy=" + server.host + ":" + server.port);
        } catch (Exception e) {
            Log.w(TAG, "Profile prepare error (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Ghi sẵn thông tin đăng nhập vào RMS storage của game.
     * Game đọc key "acc" (username) và "pass" (password) khi khởi động.
     * Path: /sdcard/J2ME-Loader/data/NinjaNamod/
     */
    private void preWriteRmsCredentials(String username, String password) {
        try {
            // RMS path = Config.getDataDir() + "NinjaNamod/"
            // = /storage/emulated/0/J2ME-Loader/data/NinjaNamod/
            java.io.File rmsDir = new java.io.File(
                    namod.j2me.config.Config.getDataDir(), "NinjaNamod");
            if (!rmsDir.exists()) rmsDir.mkdirs();

            writeRmsRecord(rmsDir, "acc", username.getBytes("UTF-8"));
            writeRmsRecord(rmsDir, "pass", password.getBytes("UTF-8"));
            Log.i(TAG, "Pre-wrote RMS acc=" + username + " to " + rmsDir.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "preWriteRmsCredentials failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Ghi 1 RMS record theo đúng format của J2MELoader RecordStoreImpl:
     *
     * Header file (.rsh):
     *   [4D 49 44 52 4D 53] MIDRMS magic
     *   [03] versionMajor
     *   [00] versionMinor
     *   [00] encrypted flag
     *   writeUTF(storeName)   ← 2-byte length + UTF-8 bytes
     *   writeLong(lastModified = currentTimeMillis)
     *   writeInt(version = 1)
     *   writeInt(0)  authMode
     *   writeByte(0) writable
     *   writeInt(1)  size = 1 record
     *
     * Record file (.1.rsr):
     *   writeInt(1)             recordId
     *   writeInt(0)             tag
     *   writeInt(data.length)   data size
     *   write(data)             raw bytes
     */
    private void writeRmsRecord(java.io.File rmsDir, String storeName, byte[] data)
            throws Exception {
        // ── Record file: storeName.1.rsr ──────────────────────────────────
        java.io.File recFile = new java.io.File(rmsDir, storeName + ".1.rsr");
        java.io.DataOutputStream rec = new java.io.DataOutputStream(
                new java.io.FileOutputStream(recFile));
        rec.writeInt(1);            // recordId = 1
        rec.writeInt(0);            // tag (unused)
        rec.writeInt(data.length);  // data length
        rec.write(data);            // data bytes
        rec.close();

        // ── Header file: storeName.rsh ────────────────────────────────────
        java.io.File hdrFile = new java.io.File(rmsDir, storeName + ".rsh");
        java.io.DataOutputStream hdr = new java.io.DataOutputStream(
                new java.io.FileOutputStream(hdrFile));
        // Magic: "MIDRMS"
        hdr.write(new byte[]{0x4D, 0x49, 0x44, 0x52, 0x4D, 0x53});
        hdr.write(0x03); // versionMajor
        hdr.write(0x00); // versionMinor
        hdr.write(0x00); // encrypted = false
        hdr.writeUTF(storeName);                     // store name
        hdr.writeLong(System.currentTimeMillis());   // lastModified
        hdr.writeInt(1);   // version
        hdr.writeInt(0);   // authMode
        hdr.writeByte(0);  // writable
        hdr.writeInt(1);   // size = 1 record
        hdr.close();

        Log.d(TAG, "Wrote RMS: " + hdrFile.getName() + " + " + recFile.getName()
                + " (" + data.length + " bytes)");
    }


    /**
     * Sau khi game mở, chờ rồi gửi lệnh "dn user pass" qua ChatRouter.checkAll()
     * Dùng AppClassLoader.instance — ClassLoader thực sự load converted.dex
     */
    private void scheduleAutoLoginCommand(String username, String password) {
        // Retry tối đa 5 lần, mỗi lần cách 5s, bắt đầu sau 10s
        executor.execute(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    // Chờ game load (10s lần đầu, 5s các lần sau)
                    Thread.sleep(attempt == 0 ? 10000 : 5000);

                    // Lấy AppClassLoader của MIDlet đang chạy
                    javax.microedition.shell.AppClassLoader cl =
                            javax.microedition.shell.AppClassLoader.instance;
                    if (cl == null) {
                        Log.w(TAG, "AppClassLoader.instance null, retry " + attempt);
                        continue;
                    }

                    // Gọi ChatRouter.checkAll("dn user pass")
                    String cmd = "dn " + username + " " + password;
                    Class<?> chatRouter = cl.loadClass("ChatRouter");
                    java.lang.reflect.Method checkAll =
                            chatRouter.getMethod("checkAll", String.class);
                    boolean handled = (boolean) checkAll.invoke(null, cmd);
                    Log.i(TAG, "ChatRouter.checkAll(\"" + cmd + "\") = " + handled
                            + " (attempt " + attempt + ")");
                    if (handled) break; // login thành công

                } catch (ClassNotFoundException e) {
                    Log.w(TAG, "ChatRouter not found yet (attempt " + attempt + "): " + e.getMessage());
                } catch (Exception e) {
                    Log.w(TAG, "scheduleAutoLogin attempt " + attempt + " failed: " + e.getMessage());
                }
            }
        });
    }


    /** Khởi động MicroActivity với NinjaNamod — chạy trong converted/NinjaNamod/. */
    private void startGame(String jarPath) {
        mainHandler.post(() -> {
            try {
                // "NinjaNamod" = tên thư mục trong J2ME-Loader/converted/
                // MicroLoader constructor: path = Config.getAppDir() + "NinjaNamod"
                final String convertedName = "NinjaNamod";
                Intent intent = new Intent(
                        Intent.ACTION_DEFAULT,
                        Uri.parse(convertedName),
                        ctx,
                        MicroActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(ConfigActivity.MIDLET_NAME_KEY, convertedName);
                ctx.startActivity(intent);
                Log.i(TAG, "MicroActivity launched: " + convertedName);
            } catch (Exception e) {
                Log.e(TAG, "startActivity error: " + e.getMessage());
                showToast("Lỗi mở game: " + e.getMessage());
            }
        });
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}

