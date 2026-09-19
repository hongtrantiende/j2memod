package namod.j2me.ninja;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Model lưu thông tin 1 tài khoản Ninja School.
 * Serializable sang JSON để lưu vào SharedPreferences.
 */
public class AccountModel {
    // ─── Persistent fields (lưu vào JSON) ─────────────
    public String username;
    public String password;
    public String serverName;   // Tên server (Bokken, Shuriken...)
    public boolean autoRun;     // Tự động chạy khi mở app
    public int slotIndex;       // -1 = chưa gán slot
    public long lastLoginTime;  // Timestamp lần login cuối
    public String note;         // Ghi chú tùy ý

    // ─── Runtime stats (KHÔNG lưu, cập nhật từ game) ─
    public transient boolean isRunning   = false;
    public transient int     level       = 0;
    public transient float   expPercent  = 0f;
    public transient long    yenPerHour  = 0L;
    public transient String  map         = null;
    public transient int     zone        = 0;
    public transient int     group       = 0;

    public AccountModel() {
        this.slotIndex = -1;
        this.serverName = "Bokken";
        this.autoRun = false;
    }


    public AccountModel(String username, String password, String serverName) {
        this.username = username;
        this.password = password;
        this.serverName = serverName != null ? serverName : "Bokken";
        this.slotIndex = -1;
        this.autoRun = false;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("username", username != null ? username : "");
        obj.put("password", password != null ? password : "");
        obj.put("serverName", serverName != null ? serverName : "Bokken");
        obj.put("autoRun", autoRun);
        obj.put("slotIndex", slotIndex);
        obj.put("lastLoginTime", lastLoginTime);
        obj.put("note", note != null ? note : "");
        return obj;
    }

    public static AccountModel fromJson(JSONObject obj) throws JSONException {
        AccountModel m = new AccountModel();
        m.username      = obj.optString("username", "");
        m.password      = obj.optString("password", "");
        m.serverName    = obj.optString("serverName", "Bokken");
        m.autoRun       = obj.optBoolean("autoRun", false);
        m.slotIndex     = obj.optInt("slotIndex", -1);
        m.lastLoginTime = obj.optLong("lastLoginTime", 0);
        m.note          = obj.optString("note", "");
        return m;
    }

    /** Hiển thị ngắn cho UI */
    public String getDisplayName() {
        return username != null && !username.isEmpty() ? username : "(chưa đặt tên)";
    }

    /** Kiểm tra hợp lệ */
    public boolean isValid() {
        return username != null && !username.isEmpty()
            && password != null && !password.isEmpty();
    }

    @Override
    public String toString() {
        return username + "@" + serverName;
    }
}
