/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.overlay.OverlayView;
import javax.microedition.lcdui.pointer.FixedKeyboard;
import javax.microedition.lcdui.pointer.VirtualKeyboard;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import namod.j2me.FloatingBubbleService;
import namod.j2me.R;
import namod.j2me.config.Config;
import namod.j2me.config.ConfigActivity;
import namod.j2me.util.ConsoleOutput;
import namod.j2me.util.LogConsoleDialogFragment;
import namod.j2me.util.LogUtils;

import java.util.ArrayList;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;
	private static final int REQUEST_CODE_OVERLAY_PERMISSION = 1001;

	private Displayable current;
	private boolean visible;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private boolean keyLongPressed;
	private FrameLayout layout;
	private Toolbar toolbar;
	private MicroLoader microLoader;
	private String appName;
	private int tabSlotIndex = 0; // slot du lieu rieng biet

    // === MULTI-SLOT (NST-style) ===
    private LinearLayout slotHost;
    private SlotTabBar slotTabBar;
    private final ArrayList<SlotCell> cells = new ArrayList<>();
    private boolean addingSlots = false;
    private int pendingSlots = 0;
    private String addingPath;
    private String addingName;
    private static final long ADD_SLOT_INTERVAL_MS = 120;

    private final SlotTabBar.Listener tabBarListener = new SlotTabBar.Listener() {
        @Override public void onTabSelected(int index) {
            if (index >= 0 && index < cells.size())
                selectSlot(cells.get(index).slot);
        }
        @Override public void onAddOne() { addSlots(1); }
        @Override public void onAddMany() { showAddSlotsDialog(); }
        @Override public void onCloseCurrent() { closeFocusedSlot(); }
    };

    private final Runnable addOneSlot = new Runnable() {
        @Override
        public void run() {
            if (pendingSlots <= 0 || isFinishing() || cells.size() >= 100) {
                pendingSlots = 0; addingSlots = false; return;
            }
            pendingSlots--;
            int nextSlot = nextFreeSlot();
            if (nextSlot < 0) { pendingSlots = 0; addingSlots = false; return; }
            newCell(nextSlot);
            SlotRegistry.setMultiSlot(true);
            SlotRegistry.setFocusedSlot(nextSlot);
            launchSlot(nextSlot, addingPath, addingName);
            if (pendingSlots > 0) {
                if (slotHost != null) slotHost.postDelayed(this, ADD_SLOT_INTERVAL_MS);
            } else {
                addingSlots = false;
            }
        }
    };

	private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if ("namod.j2me.MINIMIZE_GAME".equals(action)) {
				// Chuyen game xuong nen, giu nguyen trang thai
				moveTaskToBack(true);
			} else {
				// CLOSE_GAME -> ket thuc hoan toan
				finish();
			}
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		setTheme(sp.getString("pref_theme", "light"));
		super.onCreate(savedInstanceState);
		Displayable.isFloatingMode = false;
		ContextHolder.setCurrentActivity(this);
		setContentView(R.layout.activity_micro);
		OverlayView overlayView = findViewById(R.id.vOverlay);
		layout = findViewById(R.id.displayable_container);
		toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		IntentFilter filter = new IntentFilter("namod.j2me.CLOSE_GAME");
		filter.addAction("com.hunghero.j2me.CLOSE_GAME");
		filter.addAction("namod.j2me.MINIMIZE_GAME"); // chuyen game xuong nen
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(closeReceiver, filter, Context.RECEIVER_EXPORTED);
		} else {
			registerReceiver(closeReceiver, filter);
		}

		actionBarEnabled = sp.getBoolean("pref_actionbar_switch", false);
		statusBarEnabled = sp.getBoolean("pref_statusbar_switch", false);
		boolean wakelockEnabled = sp.getBoolean("pref_wakelock_switch", false);
		if (wakelockEnabled) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		Intent intent = getIntent();
		appName = intent.getStringExtra(ConfigActivity.MIDLET_NAME_KEY);
		microLoader = new MicroLoader(this, intent.getDataString());
		if (!microLoader.init()) {
			Config.startApp(this, appName, intent.getDataString(), true);
			finish();
			return;
		}
		microLoader.applyConfiguration();
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk != null) {
			vk.setView(overlayView);
			overlayView.addLayer(vk);
		}
		if (vk instanceof FixedKeyboard) {
			setOrientation(ORIENTATION_PORTRAIT);
		} else {
			int orientation = microLoader.getOrientation();
			setOrientation(orientation);
		}

		try {
			Intent fgIntent = new Intent(this, ForegroundService.class);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				startForegroundService(fgIntent);
			} else {
				startService(fgIntent);
			}
		} catch (Throwable t) {
			Log.e("MicroActivity", "Failed to start ForegroundService", t);
		}

		if (MidletThread.isActive()) {
			Displayable currentDisplayable = MidletThread.getCurrentDisplayable();
			if (currentDisplayable != null) {
				setCurrent(currentDisplayable);
				return;
			}
		}

		try {
			loadMIDlet();
		} catch (Exception e) {
			e.printStackTrace();
			showErrorDialog(e.toString());
		}

        // Dang ky slot-0 voi SlotRegistry
        SlotSession slot0;
        if (SlotRegistry.get(0) == null) {
            slot0 = SlotRegistry.create(0);
        } else {
            slot0 = SlotRegistry.get(0);
        }
        slot0.appName = appName;
        slot0.appPath = intent.getDataString();
        SlotRegistry.bind(slot0);
        SlotRegistry.setFocusedSlot(0);

        // Tao slotHost + SlotTabBar phia duoi
        FrameLayout rootFrame = findViewById(R.id.displayable_container);
        if (rootFrame != null && rootFrame.getParent() instanceof LinearLayout) {
            LinearLayout root = (LinearLayout) rootFrame.getParent();
            slotHost = new LinearLayout(this);
            slotHost.setOrientation(LinearLayout.VERTICAL);

            // Them slotHost + tabBar vao root layout (phia tren displayable_container)
            // slotHost hien thi slot dang focus trong layout cha
            slotTabBar = new SlotTabBar(this, tabBarListener);
            SlotRegistry.addListener((sessions, focused) -> runOnUiThread(() -> refreshTabBar()));

            // Them tab bar vao cuoi man hinh
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, SlotTabBar.heightPx(this));
            root.addView(slotTabBar, tabParams);
        }

        // Tao cell-0 cho slot dau tien
        SlotCell cell0 = newCell(0);
        slot0.container = cell0;
        if (cell0 != null) cell0.clearPlaceholder();
        refreshTabBar();
	}

    /** Tao SlotCell moi va them vao cells list theo thu tu slot */
    private SlotCell newCell(int slotIndex) {
        SlotCell cell = new SlotCell(this, slotIndex,
            tapSlot -> launchSlot(tapSlot, addingPath != null ? addingPath : "", addingName != null ? addingName : ""));
        int ins = cells.size();
        while (ins > 0 && cells.get(ins - 1).slot > slotIndex) ins--;
        cells.add(ins, cell);
        return cell;
    }

    /** Lay SlotCell theo slot index */
    private SlotCell cellOf(int slotIndex) {
        for (SlotCell c : cells) { if (c.slot == slotIndex) return c; }
        return null;
    }

    /** Tim slot index chua duoc su dung */
    private int nextFreeSlot() {
        for (int i = 0; i < 100; i++) {
            if (SlotRegistry.get(i) == null) return i;
        }
        return -1;
    }

    /** Them N slot moi, sao chep JAR cua slot dang focused */
    public void addSlots(int count) {
        if (addingSlots) { Toast.makeText(this, "Dang mo tab...", Toast.LENGTH_SHORT).show(); return; }
        int maxNew = 100 - cells.size();
        if (maxNew <= 0) { Toast.makeText(this, "Da dat gioi han 100 tab", Toast.LENGTH_SHORT).show(); return; }
        SlotSession focused = SlotRegistry.focused();
        if (focused == null || focused.appPath == null) {
            Toast.makeText(this, "Khong xac dinh duoc JAR", Toast.LENGTH_SHORT).show(); return;
        }
        addingPath = focused.appPath;
        addingName = focused.appName;
        addingSlots = true;
        pendingSlots = Math.min(count, maxNew);
        if (slotHost != null) slotHost.post(addOneSlot);
        else runOnUiThread(addOneSlot);
    }

    /** Khoi dong game vao slot chi dinh */
    public void launchSlot(int slotIndex, String path, String name) {
        SlotSession session = SlotRegistry.get(slotIndex);
        if (session == null) {
            session = SlotRegistry.create(slotIndex);
        }
        session.appPath = path;
        session.appName = name;
        SlotRegistry.bind(session);
        SlotCell cell = cellOf(slotIndex);
        if (cell != null) {
            cell.clearPlaceholder();
            session.container = cell;
        }
        // Khoi tao MicroLoader va chay game
        MicroLoader loader = new MicroLoader(this, path);
        if (!loader.init()) {
            Toast.makeText(this, "Loi khoi tao JAR slot " + (slotIndex + 1), Toast.LENGTH_SHORT).show();
            return;
        }
        loader.applyConfiguration();
        session.microLoader = loader;
        try {
            java.util.LinkedHashMap<String, String> midlets = loader.loadMIDletList();
            String[] classes = midlets.keySet().toArray(new String[0]);
            if (classes.length == 0) return;
            MidletThread.create(loader, classes[0]);
        } catch (Exception e) {
            Log.e("MicroActivity", "launchSlot error", e);
        }
        selectSlot(slotIndex);
    }

    /** Chuyen sang slot khac - chi doi view hien thi */
    public void selectSlot(int slotIndex) {
        SlotCell cell = cellOf(slotIndex);
        if (cell == null || SlotRegistry.getFocusedSlot() == slotIndex) return;
        SlotRegistry.setFocusedSlot(slotIndex);
        SlotSession sess = SlotRegistry.get(slotIndex);
        if (sess != null) SlotRegistry.bind(sess);
        // Cap nhat Displayable trong layout chinh
        if (sess != null && sess.current != null) {
            setCurrent(sess.current);
        }
        refreshTabBar();
    }

    /** Dong slot dang focused */
    public void closeFocusedSlot() {
        if (cells.size() <= 1) {
            finish(); return;
        }
        int focused = SlotRegistry.getFocusedSlot();
        SlotSession sess = SlotRegistry.get(focused);
        if (sess != null) MidletThread.destroySlot(sess);
        SlotCell cell = cellOf(focused);
        if (cell != null) cells.remove(cell);
        // Chuyen sang slot ke truoc
        int newFocus = cells.isEmpty() ? -1 : cells.get(Math.max(0, cells.size() - 1)).slot;
        if (newFocus >= 0) selectSlot(newFocus);
        else finish();
    }

    /** Hien thi dialog nhap so tab muon mo */
    private void showAddSlotsDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText("1");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
            .setTitle("Mo nhieu man")
            .setMessage("Nhap so man muon mo them (toi da " + (100 - cells.size()) + ")")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("MO", (d, w) -> {
                try { addSlots(Integer.parseInt(input.getText().toString().trim())); }
                catch (Exception ignored) {}
            }).show();
    }

    /** Cap nhat SlotTabBar chip [1][2][3] */
    private void refreshTabBar() {
        if (slotTabBar == null) return;
        int[] labels = new int[cells.size()];
        int focused = 0;
        int focusedSlot = SlotRegistry.getFocusedSlot();
        for (int i = 0; i < cells.size(); i++) {
            labels[i] = cells.get(i).slot + 1;
            if (cells.get(i).slot == focusedSlot) focused = i;
        }
        int finalFocused = focused;
        runOnUiThread(() -> slotTabBar.refresh(labels, finalFocused));
    }

	@Override
	public void onResume() {
		super.onResume();
		Displayable.isFloatingMode = false;
		Intent intent = new Intent(this, FloatingBubbleService.class);
		intent.setAction("ACTION_HIDE_WINDOW");
		startService(intent);
		visible = true;
		MidletThread.resumeApp();
		if (current != null) {
			setCurrent(current);
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		Displayable.isFloatingMode = false;
		if (current != null) {
			setCurrent(current);
		}
	}

	@Override
	public void onPause() {
		visible = false;
		boolean bgRun = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_background_run", true);
		if (!bgRun && !Displayable.isFloatingMode && FloatingBubbleService.getInstance() == null) {
			MidletThread.pauseApp();
		}
		super.onPause();
	}

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(closeReceiver); } catch (Exception ignored) {}
        if (!Displayable.isFloatingMode && FloatingBubbleService.getInstance() == null) {
            stopService(new Intent(this, ForegroundService.class));
        }
        ConsoleOutput.clear();
        super.onDestroy();
        if (isFinishing()) {
            if (!Displayable.isFloatingMode && FloatingBubbleService.getInstance() == null) {
                Process.killProcess(Process.myPid());
            }
        }
    }

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			if (current != null && current.getDisplayableView() != null) {
				current.getDisplayableView().requestFocus();
			}
			if (current instanceof Canvas) {
				((Canvas) current).repaint();
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && current instanceof Canvas) {
				hideSystemUI();
			}
		}
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void setOrientation(int orientation) {
		switch (orientation) {
			case ORIENTATION_AUTO:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
				break;
			case ORIENTATION_PORTRAIT:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
				break;
			case ORIENTATION_LANDSCAPE:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
				break;
			case ORIENTATION_DEFAULT:
			default:
				break;
		}
	}

	private void setTheme(String theme) {
		namod.j2me.util.AppUtils.applyTheme(theme);
		if ("light".equals(theme)) {
			setTheme(R.style.AppTheme_Light_NoActionBar);
		} else {
			setTheme(R.style.AppTheme_NoActionBar);
		}
	}

	private void loadMIDlet() throws Exception {
		LinkedHashMap<String, String> midlets = microLoader.loadMIDletList();
		int size = midlets.size();
		String[] midletsNameArray = midlets.values().toArray(new String[0]);
		String[] midletsClassArray = midlets.keySet().toArray(new String[0]);
		if (size == 0) {
			throw new Exception("No MIDlets found");
		} else if (size == 1) {
			MidletThread.create(microLoader, midletsClassArray[0]);
		} else {
			showMidletDialog(midletsNameArray, midletsClassArray);
		}
	}

	private void showMidletDialog(String[] midletsNameArray, final String[] midletsClassArray) {
		runOnUiThread(() -> {
			if (isFinishing() || isDestroyed()) return;
			AlertDialog.Builder builder = new AlertDialog.Builder(this)
					.setTitle(R.string.select_dialog_title)
					.setItems(midletsNameArray, (d, n) -> MidletThread.create(microLoader, midletsClassArray[n]))
					.setOnCancelListener(dialogInterface -> finish());
			builder.show();
		});
	}

	void showErrorDialog(String message) {
		runOnUiThread(() -> {
			if (isFinishing() || isDestroyed()) return;
			AlertDialog.Builder builder = new AlertDialog.Builder(this)
					.setIcon(android.R.drawable.ic_dialog_alert)
					.setTitle(R.string.error)
					.setMessage(message)
					.setPositiveButton(android.R.string.ok, (d, w) -> ContextHolder.notifyDestroyed());
			builder.setOnCancelListener(dialogInterface -> ContextHolder.notifyDestroyed());
			builder.show();
		});
	}

	private SimpleEvent msgSetCurrent = new SimpleEvent() {
		@Override
		public void process() {
			if (visible) {
				Displayable.isFloatingMode = false;
			}
			if (Displayable.isFloatingMode && !visible) {
				current.clearDisplayableView();
				Intent intent = new Intent(MicroActivity.this, FloatingBubbleService.class);
				intent.setAction("ACTION_UPDATE_DISPLAYABLE");
				startService(intent);
				return;
			}
			current.clearDisplayableView();
			View displayableView = current.getDisplayableView();
			if (displayableView != null) {
				if (displayableView.getParent() != null) {
					((android.view.ViewGroup) displayableView.getParent()).removeView(displayableView);
				}
				layout.removeAllViews();
				layout.addView(displayableView);
			}
			invalidateOptionsMenu();
			ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) toolbar.getLayoutParams();
			if (current instanceof Canvas) {
				hideSystemUI();
				if (actionBarEnabled) {
					String title = current.getTitle();
					actionBar.setTitle(title == null ? appName : title);
					layoutParams.height = (int) (getToolBarHeight() / 1.5);
				} else {
					actionBar.hide();
				}
			} else {
				showSystemUI();
				actionBar.show();
				final String title = current.getTitle();
				actionBar.setTitle(title == null ? appName : title);
				layoutParams.height = getToolBarHeight();
			}
			toolbar.setLayoutParams(layoutParams);
		}
	};

	private int getToolBarHeight() {
		int[] attrs = new int[]{androidx.appcompat.R.attr.actionBarSize};
		TypedArray ta = obtainStyledAttributes(attrs);
		int toolBarHeight = ta.getDimensionPixelSize(0, -1);
		ta.recycle();
		return toolBarHeight;
	}

	private void hideSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!statusBarEnabled) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
		} else if (!statusBarEnabled) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	private void showSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	public void setCurrent(Displayable displayable) {
		current = displayable;
		ViewHandler.postEvent(msgSetCurrent);
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return visible;
	}

	private void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.destroyApp())
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
			onKeyUp(event.getKeyCode(), event);
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public void openOptionsMenu() {
		if (!actionBarEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && current instanceof Canvas) {
			showSystemUI();
		}
		super.openOptionsMenu();
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			showExitConfirmation();
			keyLongPressed = true;
			return true;
		}
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) && !keyLongPressed) {
			openOptionsMenu();
			return true;
		}
		keyLongPressed = false;
		return super.onKeyUp(keyCode, event);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (current != null) {
			menu.clear();
			MenuInflater inflater = getMenuInflater();
			inflater.inflate(R.menu.midlet_displayable, menu);
			if (current instanceof Canvas) {
				SubMenu group = menu.getItem(0).getSubMenu();
				if (actionBarEnabled) {
					inflater.inflate(R.menu.midlet_canvas_no_keys2, menu);
				} else {
					inflater.inflate(R.menu.midlet_canvas_no_keys, group);
				}
				VirtualKeyboard vk = ContextHolder.getVk();
				if (vk instanceof FixedKeyboard) {
					inflater.inflate(R.menu.midlet_canvas_fixed, group);
				} else if (vk != null) {
					inflater.inflate(R.menu.midlet_canvas, group);
				}
			}
			for (Command cmd : current.getCommands()) {
				menu.add(Menu.NONE, cmd.hashCode(), Menu.NONE, cmd.getAndroidLabel());
			}
		}

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (current != null) {
			int id = item.getItemId();
			if (item.getGroupId() == R.id.action_group_common_settings) {
				if (id == R.id.action_exit_midlet) {
					showExitConfirmation();
				} else if (id == R.id.action_take_screenshot) {
					takeScreenshot();
				} else if (id == R.id.action_save_log) {
					saveLog();
				} else if (id == R.id.action_log_console) {
					new LogConsoleDialogFragment().show(getSupportFragmentManager(), "log_console");
				} else if (id == R.id.action_chat_bubble) {
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
						startFloatingBubbleService();
					} else {
						Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
								Uri.parse("package:" + getPackageName()));
						startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
					}
				} else if (ContextHolder.getVk() != null) {
					handleVkOptions(id);
				}
				return true;
			}
			return current.menuItemSelected(id);
		}

		return super.onOptionsItemSelected(item);
	}

	private void startFloatingBubbleService() {
		startService(new Intent(this, FloatingBubbleService.class));
		moveTaskToBack(true);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
				startFloatingBubbleService();
			} else {
				Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
			}
		}
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	private void handleVkOptions(int id) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (id == R.id.action_layout_edit_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
			Toast.makeText(this, R.string.layout_edit_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_scale_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
			Toast.makeText(this, R.string.layout_scale_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_edit_finish) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
			Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_switch) {
			showSetLayoutDialog();
		} else if (id == R.id.action_hide_buttons) {
			showHideButtonDialog();
		}
	}

	@SuppressLint("CheckResult")
	private void takeScreenshot() {
		microLoader.takeScreenshot((Canvas) current)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(new SingleObserver<String>() {
					@Override
					public void onSubscribe(Disposable d) {
					}

					@Override
					public void onSuccess(String s) {
						Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
								+ " " + s, Toast.LENGTH_LONG).show();
					}

					@Override
					public void onError(Throwable e) {
						e.printStackTrace();
						Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
					}
				});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		String[] keyNames = vk.getKeyNames();
		boolean[] vkHidden = vk.getKeyVisibility();
		AlertDialog.Builder dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.hide_buttons)
				.setMultiChoiceItems(keyNames, vkHidden, (dialogInterface, i, b) -> vk.setKeyVisibility(i, b))
				.setPositiveButton(android.R.string.ok, null);
		dialog.show();
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		String[] layoutNames = vk.getLayoutNames();
		AlertDialog.Builder dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.layout_switch)
				.setSingleChoiceItems(layoutNames, -1, (dialogInterface, i) -> {
					vk.setLayout(i);
					dialogInterface.dismiss();
				})
				.setPositiveButton(android.R.string.ok, null);
		dialog.show();
	}
}
