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

	private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			finish();
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		setTheme(sp.getString("pref_theme", "light"));
		super.onCreate(savedInstanceState);
		ContextHolder.setCurrentActivity(this);
		setContentView(R.layout.activity_micro);
		OverlayView overlayView = findViewById(R.id.vOverlay);
		layout = findViewById(R.id.displayable_container);
		toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		IntentFilter filter = new IntentFilter("namod.j2me.CLOSE_GAME");
		filter.addAction("com.hunghero.j2me.CLOSE_GAME");
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

		Intent fgIntent = new Intent(this, ForegroundService.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(fgIntent);
		} else {
			startService(fgIntent);
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
	}

	@Override
	public void onResume() {
		super.onResume();
		Intent intent = new Intent(this, FloatingBubbleService.class);
		intent.setAction("ACTION_HIDE_WINDOW");
		startService(intent);
		visible = true;
		MidletThread.resumeApp();
	}

	@Override
	public void onPause() {
		visible = false;
		MidletThread.pauseApp();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		try {
			unregisterReceiver(closeReceiver);
		} catch (Exception ignored) {}
		stopService(new Intent(this, ForegroundService.class));
		ConsoleOutput.clear();
		super.onDestroy();
		if (isFinishing()) {
			Process.killProcess(Process.myPid());
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
		if (theme.equals("dark")) {
			setTheme(R.style.AppTheme_NoActionBar);
		} else {
			setTheme(R.style.AppTheme_Light_NoActionBar);
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
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.select_dialog_title)
				.setItems(midletsNameArray, (d, n) -> MidletThread.create(microLoader, midletsClassArray[n]))
				.setOnCancelListener(dialogInterface -> finish());
		builder.show();
	}

	void showErrorDialog(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.error)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, (d, w) -> ContextHolder.notifyDestroyed());
		builder.setOnCancelListener(dialogInterface -> ContextHolder.notifyDestroyed());
		builder.show();
	}

	private SimpleEvent msgSetCurrent = new SimpleEvent() {
		@Override
		public void process() {
			current.clearDisplayableView();
			if (Displayable.isFloatingMode) {
				Intent intent = new Intent(MicroActivity.this, FloatingBubbleService.class);
				intent.setAction("ACTION_UPDATE_DISPLAYABLE");
				startService(intent);
				return;
			}
			layout.removeAllViews();
			layout.addView(current.getDisplayableView());
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
