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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.util.ArrayList;
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
	private FrameLayout layout;          // displayable_container
	private Toolbar toolbar;
	private MicroLoader microLoader;
	private String appName;
	private String appPath;             // path cua game .jar
	private SlotTabBar slotTabBar;

	// === Slot management (single Activity, nhieu slot) ===
	private final ArrayList<SlotCell> cells = new ArrayList<>();
	private boolean addingSlots;
	private int pendingSlots;

	private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if ("namod.j2me.MINIMIZE_GAME".equals(action)) {
				moveTaskToBack(true);
			} else {
				// CLOSE_GAME -> ket thuc hoan toan
				MidletThread.destroyApp();
			}
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		setTheme(sp.getString("pref_theme", "light"));
		super.onCreate(savedInstanceState);
		getWindow().setWindowAnimations(0);
		Displayable.isFloatingMode = false;
		ContextHolder.setCurrentActivity(this);
		setContentView(R.layout.activity_micro);
		OverlayView overlayView = findViewById(R.id.vOverlay);
		layout = findViewById(R.id.displayable_container);
		toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		IntentFilter filter = new IntentFilter("namod.j2me.CLOSE_GAME");
		filter.addAction("com.hunghero.j2me.CLOSE_GAME");
		filter.addAction("namod.j2me.MINIMIZE_GAME");
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
		appPath = intent.getDataString();

		// === Khoi tao slot dau tien (slot 0) ===
		Config.setSlotIndex(0);
		microLoader = new MicroLoader(this, appPath);
		if (!microLoader.init()) {
			Config.startApp(this, appName, appPath, true);
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

		// === Setup tab bar ===
		slotTabBar = findViewById(R.id.slot_tab_bar);
		if (slotTabBar != null) {
			slotTabBar.setListener(new SlotTabBar.Listener() {
				@Override public void onTabSelected(int index) { selectSlot(index); }
				@Override public void onAddOne() { addSlots(1); }
				@Override public void onAddMany() { showAddTabsDialog(); }
				@Override public void onCloseCurrent() { closeFocusedSlot(); }
			});
		}

		// === Tao SlotSession cho slot 0 va launch game ===
		launchSlot(0, appPath, appName);
	}

	// =====================================================
	// SLOT MANAGEMENT - Single Activity, Multiple Slots
	// =====================================================

	/** Launch 1 slot: tao session, tao cell, load game */
	private void launchSlot(int slotIndex, String path, String name) {
		SlotSession existing = SlotRegistry.get(slotIndex);
		if (existing != null) {
			// Slot da ton tai -> chi chuyen focus
			selectSlot(slotIndex);
			return;
		}

		// Tao data dir rieng: /data/ cho slot 0, /data2/ cho slot 1, ...
		// Moi tab co du lieu RIENG, khong copy tu tab khac
		String dataDir;
		if (slotIndex == 0) {
			dataDir = Config.getDataDir();
		} else {
			String base = Config.getEmulatorDir();
			dataDir = base + "/data" + (slotIndex + 1) + "/";
			java.io.File dir = new java.io.File(dataDir);
			boolean isFirstTime = !dir.exists();
			if (isFirstTime) dir.mkdirs();
			// Lan dau tao tab: copy config co ban tu slot 0 (server, ngon ngu...)
			// Lan sau: data rieng cua tab nay da co -> KHONG ghi de
			if (isFirstTime) {
				copyRmsData(Config.getDataDir(), dataDir);
			}
		}

		// Tao session
		SlotSession session = SlotRegistry.create(slotIndex);
		SlotRegistry.bind(session);
		session.initializeApp(path, name, dataDir);
		SlotRegistry.setFocusedSlot(slotIndex);

		// Container: slot 0 dung layout chinh, slot khac dung SlotCell rieng
		if (slotIndex == 0) {
			session.setContainer(layout);
		} else {
			SlotCell cell = newCell(slotIndex);
			session.setContainer(cell);
		}

		// Set currentSession cho UI thread
		currentSession = session;

		// Set data dir cho slot nay
		Config.setSlotIndex(slotIndex);

		// === NST pattern: MicroLoader rieng cho moi slot ===
		MicroLoader loader;
		if (slotIndex == 0) {
			// Slot 0: dung microLoader da tao san trong onCreate (da init + applyConfig)
			loader = microLoader;
		} else {
			// Slot 1+: tao MicroLoader MOI, goi init() + applyConfiguration()
			loader = new MicroLoader(this, path);
			if (!loader.init()) {
				showErrorDialog("init() thất bại cho slot " + slotIndex);
				return;
			}
			loader.applyConfiguration();
			// Set overlayView cho VK cua slot moi (chia se overlayView chung)
			VirtualKeyboard vk = ContextHolder.getVk();
			OverlayView overlay = findViewById(R.id.vOverlay);
			if (vk != null && overlay != null) {
				vk.setView(overlay);
			}
		}
		session.microLoader = loader;

		// Load MIDlet
		try {
			LinkedHashMap<String, String> midlets = loader.loadMIDletList();
			String[] classArray = midlets.keySet().toArray(new String[0]);
			if (classArray.length > 0) {
				MidletThread.create(loader, classArray[0]);
			}
		} catch (Exception e) {
			e.printStackTrace();
			showErrorDialog(e.toString());
		}

		// Sau khi launch xong, bind lai focused session
		SlotRegistry.bind(SlotRegistry.focused());
		showFocusedSlot();
		refreshTabBar();
	}

	/** Copy RMS data files tu srcDir sang dstDir (de slot moi co cung game state) */
	private void copyRmsData(String srcDir, String dstDir) {
		try {
			java.io.File src = new java.io.File(srcDir);
			java.io.File dst = new java.io.File(dstDir);
			if (!src.exists() || !src.isDirectory()) return;
			if (!dst.exists()) dst.mkdirs();
			java.io.File[] files = src.listFiles();
			if (files == null) return;
			for (java.io.File file : files) {
				if (file.isFile()) {
					java.io.File dstFile = new java.io.File(dst, file.getName());
					if (!dstFile.exists()) {
						// Chi copy khi chua ton tai (khong ghi de data cu)
						try (java.io.InputStream in = new java.io.FileInputStream(file);
							 java.io.OutputStream out = new java.io.FileOutputStream(dstFile)) {
							byte[] buf = new byte[4096];
							int len;
							while ((len = in.read(buf)) > 0) {
								out.write(buf, 0, len);
							}
						}
					}
				} else if (file.isDirectory()) {
					// Copy subfolders (RMS co the luu trong subdir)
					copyRmsData(file.getAbsolutePath(), new java.io.File(dst, file.getName()).getAbsolutePath());
				}
			}
		} catch (Exception e) {
			Log.w("MicroActivity", "copyRmsData failed: " + e.getMessage());
		}
	}

	/** Chuyen focus sang slot khac */
	private void selectSlot(int slotIndex) {
		if (slotIndex == SlotRegistry.getFocusedSlot()) return;
		SlotSession session = SlotRegistry.get(slotIndex);
		if (session == null) return;

		SlotRegistry.setFocusedSlot(slotIndex);
		Config.setSlotIndex(slotIndex);
		currentSession = session;
		showFocusedSlot();
		refreshTabBar();
	}

	/** An tat ca cell, chi hien cell cua focused slot */
	private void showFocusedSlot() {
		int focused = SlotRegistry.getFocusedSlot();
		// SlotCell la CON cua layout -> layout phai LUON VISIBLE
		// Khi slot 1+ duoc focus: SlotCell cua no (MATCH_PARENT) se che slot 0's view
		// Khi slot 0 duoc focus: tat ca SlotCell bi GONE -> slot 0's view hien ra
		for (SlotCell cell : cells) {
			cell.setVisibility(cell.slot == focused ? View.VISIBLE : View.GONE);
		}
		// KHONG bao gio an layout! No chua ca slot 0's view va cac SlotCell
		layout.setVisibility(View.VISIBLE);

		// Cap nhat current displayable
		SlotSession session = SlotRegistry.get(focused);
		if (session != null && session.current != null) {
			current = session.current;
			currentSession = session;
			// Repaint Canvas cua slot duoc focus de khong bi den
			if (current instanceof Canvas) {
				((Canvas) current).repaint();
			}
		}
	}

	/** Them N slot moi */
	private void addSlots(int count) {
		if (addingSlots) {
			Toast.makeText(this, "Dang them slot...", Toast.LENGTH_SHORT).show();
			return;
		}
		int max = Math.min(count, 20 - cells.size());
		if (max <= 0) {
			Toast.makeText(this, "Da dat toi da 20 slot", Toast.LENGTH_SHORT).show();
			return;
		}
		SlotSession current = SlotRegistry.focused();
		if (current == null || current.getAppPath() == null) return;

		addingSlots = true;
		pendingSlots = max;
		layout.post(addOneSlotRunnable);
	}

	private final Runnable addOneSlotRunnable = new Runnable() {
		@Override
		public void run() {
			if (pendingSlots <= 0 || isFinishing() || isDestroyed()) {
				addingSlots = false;
				return;
			}
			pendingSlots--;
			int nextSlot = SlotRegistry.nextFreeSlot();
			if (nextSlot < 0) {
				addingSlots = false;
				return;
			}
			launchSlot(nextSlot, appPath, appName);
			if (pendingSlots > 0) {
				layout.postDelayed(this, 100);
			} else {
				addingSlots = false;
			}
		}
	};

	/** Dong slot dang focus */
	private void closeFocusedSlot() {
		if (SlotRegistry.count() <= 1) {
			// Chi con 1 slot -> thoat game
			MidletThread.destroyApp();
			return;
		}
		int focusedSlot = SlotRegistry.getFocusedSlot();
		SlotSession session = SlotRegistry.get(focusedSlot);
		MidletThread.destroySlot(session);
	}

	/** Goi tu MidletThread khi 1 slot bi destroy (con slot khac) */
	public void onSlotRemoved(int removedSlot) {
		// Xoa cell
		SlotCell toRemove = cellOf(removedSlot);
		if (toRemove != null) {
			layout.removeView(toRemove);
			cells.remove(toRemove);
		}
		// Chuyen focus sang slot con lai
		if (SlotRegistry.count() > 0) {
			SlotSession next = SlotRegistry.all().get(0);
			selectSlot(next.slot);
		}
		refreshTabBar();
	}

	/** Tao SlotCell moi */
	private SlotCell newCell(int slot) {
		SlotCell cell = new SlotCell(this, slot, null);
		cell.clearPlaceholder(); // game se render vao day
		// Them vao dung vi tri (sap xep theo slot)
		int pos = cells.size();
		while (pos > 0 && cells.get(pos - 1).slot > slot) {
			pos--;
		}
		cells.add(pos, cell);
		layout.addView(cell, new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT,
			FrameLayout.LayoutParams.MATCH_PARENT));
		return cell;
	}

	/** Tim cell theo slot index */
	private SlotCell cellOf(int slot) {
		for (SlotCell cell : cells) {
			if (cell.slot == slot) return cell;
		}
		return null;
	}

	/** Cap nhat chip [1][2][3] tren tab bar */
	private void refreshTabBar() {
		if (slotTabBar == null) return;
		slotTabBar.setVisibility(View.VISIBLE);
		ArrayList<SlotSession> sessions = SlotRegistry.all();
		if (sessions.size() < 2) {
			slotTabBar.refresh(new int[0], -1);
			return;
		}
		int[] labels = new int[sessions.size()];
		int focused = 0;
		int focusedSlot = SlotRegistry.getFocusedSlot();
		for (int i = 0; i < sessions.size(); i++) {
			labels[i] = sessions.get(i).slot + 1;
			if (sessions.get(i).slot == focusedSlot) focused = i;
		}
		slotTabBar.refresh(labels, focused);
	}

	/** Dialog nhap so tab */
	private void showAddTabsDialog() {
		android.widget.EditText input = new android.widget.EditText(this);
		input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		input.setText("1");
		input.setSelectAllOnFocus(true);
		new AlertDialog.Builder(this)
			.setTitle("Mo nhieu man")
			.setMessage("Nhap so man muon them (toi da 20)")
			.setView(input)
			.setNegativeButton("HUY", null)
			.setPositiveButton("MO", (d, w) -> {
				try { addSlots(Integer.parseInt(input.getText().toString().trim())); }
				catch (Exception ignored) {}
			}).show();
	}

	// =====================================================
	// LIFECYCLE
	// =====================================================

	@Override
	public void onResume() {
		super.onResume();
		overridePendingTransition(0, 0);
		ContextHolder.setCurrentActivity(this);
		Displayable.isFloatingMode = false;
		Intent intent = new Intent(this, FloatingBubbleService.class);
		intent.setAction("ACTION_HIDE_WINDOW");
		startService(intent);
		visible = true;
		MidletThread.resumeApp();
		showFocusedSlot();
		refreshTabBar();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		Displayable.isFloatingMode = false;
		showFocusedSlot();
	}

	@Override
	public void onPause() {
		overridePendingTransition(0, 0);
		visible = false;
		boolean bgRun = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_background_run", true);
		if (!bgRun && !Displayable.isFloatingMode && FloatingBubbleService.getInstance() == null) {
			MidletThread.pauseApp();
		}
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		try {
			unregisterReceiver(closeReceiver);
		} catch (Exception ignored) {}
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

	/** Session duoc capture khi setCurrent duoc goi tu game thread */
	private volatile SlotSession currentSession;

	/** Apply setCurrent tren UI thread voi captured displayable va session */
	private void applySetCurrent(final Displayable disp, final SlotSession session) {
		if (visible) {
			Displayable.isFloatingMode = false;
		}
		if (Displayable.isFloatingMode && !visible) {
			disp.clearDisplayableView();
			Intent intent = new Intent(MicroActivity.this, FloatingBubbleService.class);
			intent.setAction("ACTION_UPDATE_DISPLAYABLE");
			startService(intent);
			return;
		}

		FrameLayout targetContainer = layout; // fallback slot 0
		if (session != null && session.container != null) {
			targetContainer = session.container;
		}

		disp.clearDisplayableView();
		View displayableView = disp.getDisplayableView();
		if (displayableView != null) {
			if (displayableView.getParent() != null) {
				((android.view.ViewGroup) displayableView.getParent()).removeView(displayableView);
			}
			targetContainer.removeAllViews();
			targetContainer.addView(displayableView);
		}

		// Chi update toolbar/actionbar cho focused slot
		if (session == null || session.slot == SlotRegistry.getFocusedSlot()) {
			current = disp;
			invalidateOptionsMenu();
			ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) toolbar.getLayoutParams();
			if (disp instanceof Canvas) {
				hideSystemUI();
				if (actionBarEnabled) {
					String title = disp.getTitle();
					actionBar.setTitle(title == null ? appName : title);
					layoutParams.height = (int) (getToolBarHeight() / 1.5);
				} else {
					actionBar.hide();
				}
			} else {
				showSystemUI();
				actionBar.show();
				final String title = disp.getTitle();
				actionBar.setTitle(title == null ? appName : title);
				layoutParams.height = getToolBarHeight();
			}
			toolbar.setLayoutParams(layoutParams);
		}
	}

	/** NST pattern: Display truyen ownerSession truc tiep */
	public void setCurrent(SlotSession session, Displayable displayable) {
		final SlotSession targetSession;
		if (session != null) {
			session.setCurrent(displayable);
			targetSession = session;
		} else {
			// Fallback: lay tu thread hoac focused
			SlotSession threadSession = SlotRegistry.current();
			if (threadSession != null) {
				threadSession.setCurrent(displayable);
				targetSession = threadSession;
			} else {
				targetSession = SlotRegistry.focused();
			}
		}
		// Update global state
		current = displayable;
		currentSession = targetSession;

		// Post voi captured displayable va session — KHONG race condition
		final Displayable capturedDisp = displayable;
		ViewHandler.postEvent(new SimpleEvent() {
			@Override
			public void process() {
				applySetCurrent(capturedDisp, targetSession);
			}
		});
	}

	/** Legacy: goi tu code cu khong co session */
	public void setCurrent(Displayable displayable) {
		setCurrent((SlotSession) null, displayable);
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return visible;
	}

	// =====================================================
	// UI HELPERS
	// =====================================================

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

	private void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.destroyApp())
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
	}

	// =====================================================
	// KEY EVENTS & MENUS
	// =====================================================

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
