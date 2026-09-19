package namod.j2me.ninja;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý CRUD danh sách tài khoản Ninja School.
 * Lưu trữ bằng SharedPreferences (JSON array).
 */
public class AccountsManager {
    private static final String PREFS_NAME  = "ninja_accounts_prefs";
    private static final String KEY_ACCOUNTS = "accounts_json";

    private final SharedPreferences prefs;
    private final List<AccountModel> accounts = new ArrayList<>();

    public AccountsManager(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    // ───────────────── Persistence ─────────────────

    private void load() {
        accounts.clear();
        String json = prefs.getString(KEY_ACCOUNTS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    accounts.add(AccountModel.fromJson(arr.getJSONObject(i)));
                } catch (JSONException ignored) {}
            }
        } catch (JSONException e) {
            accounts.clear();
        }
    }

    public void save() {
        JSONArray arr = new JSONArray();
        for (AccountModel a : accounts) {
            try { arr.put(a.toJson()); } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
    }

    public void clearAll() {
        accounts.clear();
        save();
    }

    // ───────────────── CRUD ─────────────────

    public List<AccountModel> getAll() {
        return new ArrayList<>(accounts);
    }

    public int size() { return accounts.size(); }

    public AccountModel get(int index) {
        if (index < 0 || index >= accounts.size()) return null;
        return accounts.get(index);
    }

    public void add(AccountModel acc) {
        // Tu dong gan slot rieng neu chua co
        if (acc.slotIndex < 0) {
            acc.slotIndex = getNextSlot();
        }
        accounts.add(acc);
        save();
    }

    /** Tim slot chua duoc su dung */
    private int getNextSlot() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (AccountModel a : accounts) {
            if (a.slotIndex >= 0) used.add(a.slotIndex);
        }
        int slot = 0;
        while (used.contains(slot)) slot++;
        return slot;
    }

    /**
     * Fix cac nick cu co slotIndex=-1 bang cach gan slot theo thu tu.
     * Goi o onCreate cua NinjaManagerActivity.
     */
    public boolean assignSlotsIfNeeded() {
        boolean changed = false;
        for (AccountModel a : accounts) {
            if (a.slotIndex < 0) {
                a.slotIndex = getNextSlot();
                changed = true;
            }
        }
        if (changed) save();
        return changed;
    }

    public void update(int index, AccountModel acc) {
        if (index < 0 || index >= accounts.size()) return;
        accounts.set(index, acc);
        save();
    }

    public void remove(int index) {
        if (index < 0 || index >= accounts.size()) return;
        accounts.remove(index);
        save();
    }

    public void moveUp(int index) {
        if (index <= 0 || index >= accounts.size()) return;
        AccountModel tmp = accounts.get(index - 1);
        accounts.set(index - 1, accounts.get(index));
        accounts.set(index, tmp);
        save();
    }

    // ───────────────── Import ─────────────────

    /**
     * Import từ chuỗi accounts.txt dạng "username|password|server" mỗi dòng.
     * @return số tài khoản được import thành công
     */
    public int importFromText(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        String[] lines = text.split("[\\r\\n]+");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;
            String user   = parts[0].trim();
            String pass   = parts[1].trim();
            String server = parts.length >= 3 ? parts[2].trim() : "Bokken";
            if (user.isEmpty()) continue;
            // Tránh trùng lặp
            boolean exists = false;
            for (AccountModel a : accounts) {
                if (user.equalsIgnoreCase(a.username)) { exists = true; break; }
            }
            if (!exists) {
                accounts.add(new AccountModel(user, pass, server));
                count++;
            }
        }
        if (count > 0) save();
        return count;
    }

    /**
     * Export sang chuỗi định dạng accounts.txt
     */
    public String exportToText() {
        StringBuilder sb = new StringBuilder();
        for (AccountModel a : accounts) {
            sb.append(a.username).append("|")
              .append(a.password).append("|")
              .append(a.serverName).append("\n");
        }
        return sb.toString();
    }

    // ───────────────── Auto-run helpers ─────────────────

    /** Danh sách TK có autoRun = true */
    public List<AccountModel> getAutoRunAccounts() {
        List<AccountModel> result = new ArrayList<>();
        for (AccountModel a : accounts) {
            if (a.autoRun) result.add(a);
        }
        return result;
    }

    /** Cập nhật lastLoginTime cho username */
    public void markLoggedIn(String username) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).username != null &&
                accounts.get(i).username.equalsIgnoreCase(username)) {
                accounts.get(i).lastLoginTime = System.currentTimeMillis();
                save();
                return;
            }
        }
    }
}
