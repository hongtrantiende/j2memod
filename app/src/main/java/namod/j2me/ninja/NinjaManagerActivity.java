package namod.j2me.ninja;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import namod.j2me.R;
import namod.j2me.bundled.BundledAppInstaller;

/**
 * NinjaManagerActivity — Ứng dụng QLTK Ninja theo phong cách Tool V5.
 *
 * Layout:
 *  ┌──────────────────────┐
 *  │ TOOL Namod           │  ← Header
 *  │ X nick online        │
 *  ├──────────────────────┤
 *  │ [Auto nhóm] [TATL]   │  ← Feature shortcuts
 *  ├──────────────────────┤
 *  │ Nick card 1          │  ← RecyclerView
 *  │ Nick card 2          │
 *  │ ...                  │
 *  ├──────────────────────┤
 *  │ [+Nick][Bật][Tắt]    │  ← Bottom bar
 *  │ [TK]  [Con][Cài]     │
 *  └──────────────────────┘
 */
public class NinjaManagerActivity extends AppCompatActivity {

    private static final String PREFS = "ninja_manager_prefs";

    private AccountsManager accountsManager;
    private AccountsAdapter adapter;
    private TextView tvHeader;
    private TextView tvSubHeader;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    {
        refreshRunnable = () -> {
            updateHeader();
            if (adapter != null) adapter.refreshRunningState();
            handler.postDelayed(refreshRunnable, 3000);
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ninja_manager);

        accountsManager = new AccountsManager(this);
        accountsManager.assignSlotsIfNeeded(); // fix nick cu co slotIndex=-1


        setupViews();
        ensureGameInstalled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    // ─── Setup ──────────────────────────────────────────────────

    private void setupViews() {
        // Toolbar back button
        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Header refs
        tvHeader = findViewById(R.id.tv_tool_title);
        tvSubHeader = findViewById(R.id.tv_tool_subtitle);
        updateHeader();

        // RecyclerView
        RecyclerView rv = findViewById(R.id.rv_nick_list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AccountsAdapter();
        rv.setAdapter(adapter);

        // Feature shortcut cards
        View cardAutoNhom = findViewById(R.id.card_auto_nhom);
        if (cardAutoNhom != null) cardAutoNhom.setOnClickListener(v ->
            Toast.makeText(this, "Tính năng Auto Nhóm: cần game đang chạy", Toast.LENGTH_SHORT).show());

        View cardAutoTatl = findViewById(R.id.card_auto_tatl);
        if (cardAutoTatl != null) cardAutoTatl.setOnClickListener(v ->
            Toast.makeText(this, "Tính năng Auto TATL: cần game đang chạy", Toast.LENGTH_SHORT).show());

        // Bottom bar — Row 1
        Button btnAddNick = findViewById(R.id.btn_add_nick);
        Button btnEnableAll = findViewById(R.id.btn_enable_all);
        Button btnDisableAll = findViewById(R.id.btn_disable_all);

        btnAddNick.setOnClickListener(v -> showAddNickDialog(null, -1));
        btnEnableAll.setOnClickListener(v -> launchAllAccounts());
        btnDisableAll.setOnClickListener(v -> stopAllAccounts());

        // Bottom bar — Row 2
        Button btnAccount = findViewById(R.id.btn_account);
        Button btnConsole = findViewById(R.id.btn_console);
        Button btnSettings = findViewById(R.id.btn_settings);

        btnAccount.setOnClickListener(v -> showAccountsDialog());
        btnConsole.setOnClickListener(v -> showConsole());
        btnSettings.setOnClickListener(v -> showSettings());
    }

    private void updateHeader() {
        if (tvSubHeader == null) return;
        int total = accountsManager.size();
        int running = countRunning();
        tvSubHeader.setText(running + "/" + total + " nick đang online · " +
                (BundledAppInstaller.isConvertedReady() ? "Sẵn sàng" : "Đang cài..."));
    }

    private int countRunning() {
        int count = 0;
        for (AccountModel acc : accountsManager.getAll()) {
            if (acc.isRunning) count++;
        }
        return count;
    }

    // ─── Game Install ─────────────────────────────────────────

    private void ensureGameInstalled() {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        ex.execute(() -> {
            BundledAppInstaller.ensureInstalled(this);
            runOnUiThread(() -> {
                updateHeader();
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });
        ex.shutdown();
    }

    // ─── Launch ──────────────────────────────────────────────

    void launchAccount(AccountModel acc) {
        if (!BundledAppInstaller.isConvertedReady()) {
            Toast.makeText(this, "Đang cài dữ liệu game, vui lòng đợi...", Toast.LENGTH_SHORT).show();
            return;
        }
        acc.isRunning = true;
        accountsManager.save();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateHeader();
        new AutoLaunchManager(this).launchAccount(acc,
            () -> {
                acc.isRunning = true;
                runOnUiThread(() -> { if (adapter != null) adapter.notifyDataSetChanged(); updateHeader(); });
            },
            () -> {
                acc.isRunning = false;
                accountsManager.save();
                runOnUiThread(() -> { if (adapter != null) adapter.notifyDataSetChanged(); updateHeader(); });
            }
        );
        Toast.makeText(this, "Đang mở: " + acc.username, Toast.LENGTH_SHORT).show();
    }

    void stopAccount(AccountModel acc) {
        acc.isRunning = false;
        accountsManager.save();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateHeader();
        Toast.makeText(this, "Đã tắt: " + acc.username, Toast.LENGTH_SHORT).show();
    }

    private void launchAllAccounts() {
        List<AccountModel> all = accountsManager.getAll();
        if (all.isEmpty()) {
            Toast.makeText(this, "Chưa có nick nào!", Toast.LENGTH_SHORT).show();
            return;
        }
        int delay = 0;
        for (AccountModel acc : all) {
            final AccountModel a = acc;
            final int d = delay;
            handler.postDelayed(() -> launchAccount(a), d * 2000L);
            delay++;
        }
        Toast.makeText(this, "Đang bật " + all.size() + " nick...", Toast.LENGTH_SHORT).show();
    }

    private void stopAllAccounts() {
        for (AccountModel acc : accountsManager.getAll()) {
            acc.isRunning = false;
        }
        accountsManager.save();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateHeader();
        Toast.makeText(this, "Đã tắt tất cả", Toast.LENGTH_SHORT).show();
    }

    // ─── Dialogs ─────────────────────────────────────────────

    void showAddNickDialog(AccountModel existing, int editIndex) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(editIndex >= 0 ? "Sửa Nick" : "Thêm Nick");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 4);

        EditText etUser = makeEditText("Tên tài khoản");
        EditText etPass = makeEditText("Mật khẩu");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText etNote = makeEditText("Ghi chú (tùy chọn)");

        Spinner spServer = new Spinner(this);
        ArrayAdapter<String> serverAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, ServerConfig.getNames());
        serverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spServer.setAdapter(serverAdapter);

        if (existing != null) {
            etUser.setText(existing.username);
            etPass.setText(existing.password);
            if (existing.note != null) etNote.setText(existing.note);
            spServer.setSelection(ServerConfig.getIndexByName(existing.serverName));
        }

        layout.addView(etUser);
        layout.addView(etPass);
        layout.addView(spServer);
        layout.addView(etNote);
        builder.setView(layout);

        builder.setPositiveButton("Lưu", (d, w) -> {
            String user = etUser.getText().toString().trim();
            String pass = etPass.getText().toString().trim();
            String server = (String) spServer.getSelectedItem();
            String note = etNote.getText().toString().trim();
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Tên và mật khẩu không được trống!", Toast.LENGTH_SHORT).show();
                return;
            }
            AccountModel acc = new AccountModel(user, pass, server);
            acc.note = note.isEmpty() ? null : note;
            if (editIndex >= 0) accountsManager.update(editIndex, acc);
            else accountsManager.add(acc);
            if (adapter != null) adapter.refresh();
            updateHeader();
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    void showImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Import nick từ text");

        LinearLayout ll = new LinearLayout(this);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad / 2, pad, pad / 4);

        EditText et = makeEditText("user|pass|server (mỗi dòng 1 nick)");
        et.setMinLines(5);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ll.addView(et);
        builder.setView(ll);

        builder.setPositiveButton("Import", (d, w) -> {
            int count = accountsManager.importFromText(et.getText().toString());
            Toast.makeText(this, "Đã import " + count + " nick", Toast.LENGTH_SHORT).show();
            if (adapter != null) adapter.refresh();
            updateHeader();
        });
        builder.setNegativeButton("Hủy", null);
        builder.show();
    }

    private void showAccountsDialog() {
        String[] opts = {"Import từ text", "Xóa tất cả nick"};
        new AlertDialog.Builder(this)
            .setTitle("Quản lý tài khoản")
            .setItems(opts, (d, which) -> {
                if (which == 0) showImportDialog();
                else {
                    new AlertDialog.Builder(this)
                        .setTitle("Xác nhận")
                        .setMessage("Xóa tất cả nick?")
                        .setPositiveButton("Xóa", (d2, w) -> {
                            accountsManager.clearAll();
                            adapter.refresh();
                            updateHeader();
                        })
                        .setNegativeButton("Hủy", null)
                        .show();
                }
            }).show();
    }

    private void showConsole() {
        namod.j2me.util.LogConsoleDialogFragment console =
            new namod.j2me.util.LogConsoleDialogFragment();
        console.show(getSupportFragmentManager(), "console");
    }

    private void showSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String[] opts = {"FPS: 30 (Tiết kiệm)", "FPS: 60 (Mượt)", "Đặt server mặc định"};
        new AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setItems(opts, (d, which) -> {
                if (which == 0) {
                    prefs.edit().putInt("fps_limit", 30).apply();
                    Toast.makeText(this, "FPS = 30", Toast.LENGTH_SHORT).show();
                } else if (which == 1) {
                    prefs.edit().putInt("fps_limit", 60).apply();
                    Toast.makeText(this, "FPS = 60", Toast.LENGTH_SHORT).show();
                } else {
                    showServerPickerDialog(prefs);
                }
            }).show();
    }

    private void showServerPickerDialog(SharedPreferences prefs) {
        String[] names = ServerConfig.getNames();
        new AlertDialog.Builder(this)
            .setTitle("Chọn server mặc định")
            .setItems(names, (d, which) -> {
                prefs.edit().putString("default_server", names[which]).apply();
                Toast.makeText(this, "Server mặc định: " + names[which], Toast.LENGTH_SHORT).show();
            }).show();
    }

    // ─── Helpers ────────────────────────────────────────────

    private EditText makeEditText(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int)(8 * getResources().getDisplayMetrics().density);
        et.setLayoutParams(lp);
        return et;
    }

    AccountsManager getAccountsManager() { return accountsManager; }

    // ─── RecyclerView Adapter ────────────────────────────────

    class AccountsAdapter extends RecyclerView.Adapter<AccountsAdapter.VH> {
        private List<AccountModel> items = new ArrayList<>();

        AccountsAdapter() { refresh(); }

        void refresh() {
            items = accountsManager.getAll();
            notifyDataSetChanged();
        }

        void refreshRunningState() {
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                         .inflate(R.layout.item_account, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AccountModel acc = items.get(pos);
            h.bind(acc, pos);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvInfo, tvStatus, tvMap, tvKhu, tvNhom;
            TextView tvYen, tvExp;
            Button btnToggle, btnAutoToggle;
            View statusDot;

            VH(View v) {
                super(v);
                tvName      = v.findViewById(R.id.tv_nick_name);
                tvInfo      = v.findViewById(R.id.tv_nick_info);
                tvStatus    = v.findViewById(R.id.tv_nick_status);
                tvMap       = v.findViewById(R.id.tv_nick_map);
                tvKhu       = v.findViewById(R.id.tv_nick_khu);
                tvNhom      = v.findViewById(R.id.tv_nick_nhom);
                tvYen       = v.findViewById(R.id.tv_nick_yen);
                tvExp       = v.findViewById(R.id.tv_nick_exp);
                btnToggle   = v.findViewById(R.id.btn_nick_toggle);
                btnAutoToggle = v.findViewById(R.id.btn_auto_toggle);
                statusDot   = v.findViewById(R.id.view_status_dot);
            }

            void bind(AccountModel acc, int pos) {
                tvName.setText(acc.username);
                tvInfo.setText("Lv " + acc.level + " · " + acc.expPercent + "% · " + acc.serverName);

                boolean running = acc.isRunning;
                if (tvStatus != null) {
                    tvStatus.setText(running ? "Online" : "Offline");
                    tvStatus.setTextColor(running ? 0xFF4CAF50 : 0xFF757575);
                }
                if (statusDot != null) {
                    statusDot.setBackgroundColor(running ? 0xFF4CAF50 : 0xFF757575);
                }

                if (tvMap != null) tvMap.setText(acc.map != null ? acc.map : "—");
                if (tvKhu != null) tvKhu.setText(String.valueOf(acc.zone));
                if (tvNhom != null) tvNhom.setText(acc.group > 0 ? String.valueOf(acc.group) : "—");
                if (tvYen != null) tvYen.setText(acc.yenPerHour > 0 ? String.valueOf(acc.yenPerHour) : "0");
                if (tvExp != null) tvExp.setText(acc.expPercent > 0 ? acc.expPercent + "%" : "0,00%");

                if (btnToggle != null) {
                    btnToggle.setText(running ? "Tắt nick" : "Bật nick");
                    btnToggle.setOnClickListener(v -> {
                        if (acc.isRunning) stopAccount(acc);
                        else launchAccount(acc);
                    });
                }
                if (btnAutoToggle != null) {
                    btnAutoToggle.setText(acc.autoRun ? "Tắt auto" : "Bật auto");
                    btnAutoToggle.setOnClickListener(v -> {
                        acc.autoRun = !acc.autoRun;
                        accountsManager.save();
                        notifyItemChanged(pos);
                    });
                }

                // Long press → edit/delete
                itemView.setOnLongClickListener(v -> {
                    String[] opts = {"Sửa nick", "Xóa nick"};
                    new AlertDialog.Builder(NinjaManagerActivity.this)
                        .setTitle(acc.username)
                        .setItems(opts, (d, w) -> {
                            if (w == 0) showAddNickDialog(acc, pos);
                            else {
                                accountsManager.remove(pos);
                                refresh();
                                updateHeader();
                            }
                        }).show();
                    return true;
                });
            }
        }
    }
}
