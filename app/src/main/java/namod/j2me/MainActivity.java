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

package namod.j2me;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.view.ViewConfiguration;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import namod.j2me.applist.AppsListFragment;
import namod.j2me.base.BaseActivity;
import namod.j2me.config.Config;
import namod.j2me.settings.SettingsActivity;
import namod.j2me.util.ConsoleOutput;
import namod.j2me.util.FileUtils;
import namod.j2me.util.MigrationUtils;

public class MainActivity extends BaseActivity {

	public static final String APP_SORT_KEY = "appSort";
	public static final String APP_PATH_KEY = "appPath";
	public static final String APP_URI_KEY = "appUri";

	private SharedPreferences sp;
	private static final int MY_PERMISSIONS_REQUEST_WRITE_STORAGE = 0;
	private static final int REQUEST_STORAGE_PERMISSION = 100;
	private static final int REQUEST_OVERLAY_PERMISSION = 1;
	private String emulatorDir;
	private boolean isInitialized = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		Uri uri = getIntent().getData();
		if (!isTaskRoot() && uri == null) {
			finish();
			return;
		}
		sp = PreferenceManager.getDefaultSharedPreferences(this);
		checkStoragePermission();
	}

	private boolean hasStoragePermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return Environment.isExternalStorageManager();
		} else {
			return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
					== PackageManager.PERMISSION_GRANTED;
		}
	}

	private void checkStoragePermission() {
		if (hasStoragePermission()) {
			checkOverlayPermission();
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			new AlertDialog.Builder(this)
					.setTitle(R.string.storage_permission_title)
					.setMessage(R.string.storage_permission_message)
					.setCancelable(false)
					.setPositiveButton(R.string.grant, (d, w) -> {
						try {
							Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
							intent.setData(Uri.parse("package:" + getPackageName()));
							startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
						} catch (Exception e) {
							Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
							startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
						}
					})
					.setNegativeButton(R.string.close, (d, w) -> finish())
					.show();
		} else {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
					MY_PERMISSIONS_REQUEST_WRITE_STORAGE);
		}
	}

	private void checkOverlayPermission() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
			setupActivity(getIntent().getData() != null);
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.overlay_permission_title)
				.setMessage(R.string.overlay_permission_message)
				.setCancelable(false)
				.setPositiveButton(R.string.grant, (d, w) -> {
					Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
							Uri.parse("package:" + getPackageName()));
					startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
				})
				.setNegativeButton(R.string.close, (d, w) -> finish())
				.show();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_STORAGE_PERMISSION) {
			if (hasStoragePermission()) {
				checkOverlayPermission();
			} else {
				Toast.makeText(this, R.string.permission_request_failed, Toast.LENGTH_SHORT).show();
				finish();
			}
		} else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
				setupActivity(getIntent().getData() != null);
			} else {
				checkOverlayPermission();
			}
		}
	}

	private void setupActivity(boolean intentUri) {
		if (isInitialized) return;
		if (!initFolders()) {
			String msg = getString(R.string.create_apps_dir_failed, emulatorDir);
			new AlertDialog.Builder(this)
					.setTitle(R.string.error)
					.setCancelable(false)
					.setMessage(msg)
					.setNegativeButton(R.string.close, (d, w) -> finish())
					.setPositiveButton(R.string.action_settings, (d, w) -> startActivity(
							new Intent(getApplicationContext(), SettingsActivity.class)))
					.show();
			return;
		}
		isInitialized = true;
		checkActionBar();
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		MigrationUtils.check(this);
		String appSort = sp.getString("pref_app_sort", "name");
		Bundle bundleLoad = new Bundle();
		bundleLoad.putString(APP_SORT_KEY, appSort);
		if (intentUri) {
			bundleLoad.putString(APP_PATH_KEY, getAppPath(getIntent().getData()));
			bundleLoad.putParcelable(APP_URI_KEY, getIntent().getData());
		}
		AppsListFragment appsListFragment = new AppsListFragment();
		appsListFragment.setArguments(bundleLoad);
		FragmentManager fragmentManager = getSupportFragmentManager();
		fragmentManager.beginTransaction()
				.replace(R.id.container, appsListFragment).commitNowAllowingStateLoss();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
										   @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == MY_PERMISSIONS_REQUEST_WRITE_STORAGE) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				checkOverlayPermission();
			} else {
				Toast.makeText(this, R.string.permission_request_failed, Toast.LENGTH_SHORT).show();
				finish();
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (!isInitialized && hasStoragePermission()) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
				setupActivity(getIntent().getData() != null);
			}
		}
		if (emulatorDir != null && !Config.getEmulatorDir().equals(emulatorDir)) {
			new Handler().post(this::recreate);
		}
	}

	@Override
	protected void onDestroy() {
		ConsoleOutput.clear();
		super.onDestroy();
	}

	private boolean initFolders() {
		emulatorDir = Config.getEmulatorDir();
		File appsDir = new File(emulatorDir);
		File nomedia = new File(appsDir, ".nomedia");
		if (appsDir.isDirectory() || appsDir.mkdirs()) {
			//noinspection ResultOfMethodCallIgnored
			new File(Config.getShadersDir()).mkdir();
			try {
				//noinspection ResultOfMethodCallIgnored
				nomedia.createNewFile();
			} catch (Exception e) {
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}

	private void checkActionBar() {
		boolean firstStart = sp.getBoolean("pref_first_start", true);
		if (firstStart) {
			if (!ViewConfiguration.get(this).hasPermanentMenuKey()) {
				sp.edit().putBoolean("pref_actionbar_switch", true).apply();
			}
			sp.edit().putBoolean("pref_first_start", false).apply();
		}
	}

	private String getAppPath(Uri uri) {
		try {
			return FileUtils.getAppPath(this, uri);
		} catch (IOException | SecurityException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
			return null;
		}
	}
}
