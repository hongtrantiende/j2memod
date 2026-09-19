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

                ServerConfig.Server server = ServerConfig.getByName(account.serverName);
                prepareProfile(jarPath, server);
                injectAutoLogin(account.username, account.password);
                startGame(jarPath);

                if (onSuccess != null) mainHandler.post(onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                showToast("Lỗi khởi động: " + e.getMessage());
                if (onError != null) mainHandler.post(onError);
            }
        });
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
                        injectAutoLogin(acc.username, acc.password);
                        startGame(jarPath);
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
     * Inject thông tin login vào System property.
     * MicroLoader sẽ đọc và set vào MIDlet system props.
     * Game (Code.java) đọc qua System.getProperty("ninja.auto_login").
     */
    private void injectAutoLogin(String username, String password) {
        String val = username + "|" + password;
        System.setProperty(PROP_AUTO_LOGIN, val);
        Log.i(TAG, "Injected auto_login: " + username);
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

