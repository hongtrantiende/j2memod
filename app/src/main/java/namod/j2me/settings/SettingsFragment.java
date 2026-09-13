/*
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

package namod.j2me.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import com.nononsenseapps.filepicker.FilePickerActivity;
import com.nononsenseapps.filepicker.Utils;

import java.io.File;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import namod.j2me.R;
import namod.j2me.config.Config;
import namod.j2me.config.ProfilesActivity;
import namod.j2me.filepicker.FilteredFilePickerActivity;

public class SettingsFragment extends PreferenceFragmentCompat {
	private static final int FILE_CODE = 2315;
	private Preference prefFolder;
	private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPreferences, key) -> {
		if ("pref_theme".equals(key) || "pref_theme_accent".equals(key) || "pref_custom_bg".equals(key) || "pref_custom_text".equals(key)) {
			namod.j2me.util.AppUtils.applyTheme(sharedPreferences);
			if (getActivity() != null) {
				getActivity().recreate();
			}
		}
	};

	@Override
	public void onCreatePreferences(Bundle bundle, String s) {
		addPreferencesFromResource(R.xml.preferences);
		//noinspection ConstantConditions
		findPreference("pref_default_settings").setOnPreferenceClickListener(preference -> {
			Intent intent = new Intent(getActivity(), ProfilesActivity.class);
			startActivity(intent);
			return true;
		});

		Preference prefAccentColor = findPreference("pref_custom_accent_color");
		if (prefAccentColor != null) {
			prefAccentColor.setOnPreferenceClickListener(p -> {
				SharedPreferences sp = getPreferenceManager().getSharedPreferences();
				int defaultColor = 0xFF00C896;
				try {
					String val = sp.getString("pref_theme_accent", "#00C896");
					defaultColor = android.graphics.Color.parseColor(val);
				} catch (Exception ignored) {}
				new yuku.ambilwarna.AmbilWarnaDialog(getActivity(), defaultColor, new yuku.ambilwarna.AmbilWarnaDialog.OnAmbilWarnaListener() {
					@Override
					public void onCancel(yuku.ambilwarna.AmbilWarnaDialog dialog) {}

					@Override
					public void onOk(yuku.ambilwarna.AmbilWarnaDialog dialog, int color) {
						String hex = String.format("#%06X", (0xFFFFFF & color));
						sp.edit().putString("pref_theme_accent", hex).apply();
						android.widget.Toast.makeText(getActivity(), "Đã chọn màu chủ đạo: " + hex, android.widget.Toast.LENGTH_SHORT).show();
						if (getActivity() != null) getActivity().recreate();
					}
				}).show();
				return true;
			});
		}

		Preference prefBgColor = findPreference("pref_custom_bg_color");
		if (prefBgColor != null) {
			prefBgColor.setOnPreferenceClickListener(p -> {
				SharedPreferences sp = getPreferenceManager().getSharedPreferences();
				int defaultColor = 0xFF000000;
				try {
					String val = sp.getString("pref_custom_bg", "#000000");
					defaultColor = android.graphics.Color.parseColor(val);
				} catch (Exception ignored) {}
				new yuku.ambilwarna.AmbilWarnaDialog(getActivity(), defaultColor, new yuku.ambilwarna.AmbilWarnaDialog.OnAmbilWarnaListener() {
					@Override
					public void onCancel(yuku.ambilwarna.AmbilWarnaDialog dialog) {}

					@Override
					public void onOk(yuku.ambilwarna.AmbilWarnaDialog dialog, int color) {
						String hex = String.format("#%06X", (0xFFFFFF & color));
						sp.edit().putString("pref_custom_bg", hex).apply();
						android.widget.Toast.makeText(getActivity(), "Đã chọn màu nền: " + hex, android.widget.Toast.LENGTH_SHORT).show();
						if (getActivity() != null) getActivity().recreate();
					}
				}).show();
				return true;
			});
		}

		Preference prefTextColor = findPreference("pref_custom_text_color");
		if (prefTextColor != null) {
			prefTextColor.setOnPreferenceClickListener(p -> {
				SharedPreferences sp = getPreferenceManager().getSharedPreferences();
				int defaultColor = 0xFFFFFFFF;
				try {
					String val = sp.getString("pref_custom_text", "#FFFFFF");
					defaultColor = android.graphics.Color.parseColor(val);
				} catch (Exception ignored) {}
				new yuku.ambilwarna.AmbilWarnaDialog(getActivity(), defaultColor, new yuku.ambilwarna.AmbilWarnaDialog.OnAmbilWarnaListener() {
					@Override
					public void onCancel(yuku.ambilwarna.AmbilWarnaDialog dialog) {}

					@Override
					public void onOk(yuku.ambilwarna.AmbilWarnaDialog dialog, int color) {
						String hex = String.format("#%06X", (0xFFFFFF & color));
						sp.edit().putString("pref_custom_text", hex).apply();
						android.widget.Toast.makeText(getActivity(), "Đã chọn màu chữ: " + hex, android.widget.Toast.LENGTH_SHORT).show();
						if (getActivity() != null) getActivity().recreate();
					}
				}).show();
				return true;
			});
		}

		prefFolder = findPreference(Config.PREF_EMULATOR_DIR);
		//noinspection ConstantConditions
		prefFolder.setSummary(Config.getEmulatorDir());
		prefFolder.setOnPreferenceClickListener(this::pickFolder);
	}

	@Override
	public void onResume() {
		super.onResume();
		getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(prefListener);
	}

	@Override
	public void onPause() {
		super.onPause();
		getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(prefListener);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		if (requestCode == FILE_CODE && resultCode == Activity.RESULT_OK && data != null) {
			List<Uri> files = Utils.getSelectedFilesFromResult(data);
			File file = Utils.getFileForUri(files.get(0));
//			if (file.exists()) {
//				String[] list = file.list();
//				if (list != null && list.length > 0) {
//					List<String> names = Arrays.asList(list);
//					if (!names.contains("converted")
//							&& (list.length > 1 || !list[0].equals(".nomedia"))) {
//						file = new File(file, getString(R.string.app_name));
//					}
//				}
//			}
			applyChangeFolder(file);
		}
	}

	private void applyChangeFolder(File file) {
		String path = file.getAbsolutePath();
		getPreferenceManager().getSharedPreferences().edit()
				.putString(Config.PREF_EMULATOR_DIR, path)
				.apply();
		prefFolder.setSummary(path);
	}

	private boolean pickFolder(Preference preference) {
		Intent i = new Intent(getActivity(), FilteredFilePickerActivity.class);
		i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
		i.putExtra(FilePickerActivity.EXTRA_SINGLE_CLICK, false);
		i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true);
		i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR);
		i.putExtra(FilePickerActivity.EXTRA_START_PATH, Config.getEmulatorDir());
		startActivityForResult(i, FILE_CODE);
		return true;
	}
}
