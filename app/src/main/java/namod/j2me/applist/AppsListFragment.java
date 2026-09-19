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

package namod.j2me.applist;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.nononsenseapps.filepicker.FilePickerActivity;
import com.nononsenseapps.filepicker.Utils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.flowables.ConnectableFlowable;
import io.reactivex.schedulers.Schedulers;
import namod.j2me.MainActivity;
import namod.j2me.R;
import namod.j2me.appsdb.AppRepository;
import namod.j2me.config.Config;
import namod.j2me.config.ConfigActivity;
import namod.j2me.config.ProfilesActivity;
import namod.j2me.tabs.TabManager;
import namod.j2me.donations.DonationsActivity;
import namod.j2me.filepicker.FilteredFilePickerActivity;
import namod.j2me.filepicker.FilteredFilePickerFragment;
import namod.j2me.info.AboutDialogFragment;
import namod.j2me.info.HelpDialogFragment;
import namod.j2me.settings.SettingsActivity;
import namod.j2me.util.AppUtils;
import namod.j2me.util.JarConverter;
import namod.j2me.util.LogUtils;

public class AppsListFragment extends Fragment {

	private AppRepository appRepository;
	private CompositeDisposable compositeDisposable;
	private AppsListAdapter adapter;
	private JarConverter converter;
	private String appSort;
	private String appPath;
	private SharedPreferences sp;
	private ListView listView;
	private GridView gridView;
	private TextView emptyView;
	private static final int FILE_CODE = 0;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		compositeDisposable = new CompositeDisposable();
		converter = new JarConverter(getActivity().getApplicationInfo().dataDir);
		sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
		appSort = getArguments() != null ? getArguments().getString(MainActivity.APP_SORT_KEY, "name") : "name";
		appPath = getArguments() != null ? getArguments().getString(MainActivity.APP_PATH_KEY) : null;
		adapter = new AppsListAdapter(getActivity());
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_appslist, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		setHasOptionsMenu(true);

		listView = view.findViewById(android.R.id.list);
		gridView = view.findViewById(R.id.grid);
		emptyView = view.findViewById(android.R.id.empty);

		registerForContextMenu(listView);
		registerForContextMenu(gridView);

		listView.setOnItemClickListener((parent, v, position, id) -> {
			AppItem item = adapter.getItem(position);
			Config.startApp(getActivity(), item.getTitle(), item.getPath(), false);
		});
		gridView.setOnItemClickListener((parent, v, position, id) -> {
			AppItem item = adapter.getItem(position);
			Config.startApp(getActivity(), item.getTitle(), item.getPath(), false);
		});

		updateViewMode();
		initDb();


		FloatingActionButton fab = view.findViewById(R.id.fab);
		if (fab != null) {
			applyFabColor(view);
			fab.setOnClickListener(v -> {
				Intent i = new Intent(getActivity(), FilteredFilePickerActivity.class);
				i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
				i.putExtra(FilePickerActivity.EXTRA_SINGLE_CLICK, true);
				i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false);
				i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_FILE);
				i.putExtra(FilePickerActivity.EXTRA_START_PATH, FilteredFilePickerFragment.getLastPath());
				startActivityForResult(i, FILE_CODE);
			});
		}
	}

	private void applyFabColor(View root) {
		if (root == null && getView() != null) root = getView();
		if (root == null || getContext() == null) return;
		FloatingActionButton fab = root.findViewById(R.id.fab);
		if (fab != null) {
			int accentColor = namod.j2me.util.AppUtils.getAccentColor(getContext());
			fab.setBackgroundTintList(ColorStateList.valueOf(accentColor));
			double luminance = (0.299 * Color.red(accentColor) + 0.587 * Color.green(accentColor) + 0.114 * Color.blue(accentColor)) / 255.0;
			fab.setImageTintList(ColorStateList.valueOf(luminance > 0.65 ? Color.BLACK : Color.WHITE));
		}
	}

	private void updateViewMode() {
		boolean isGrid = sp.getBoolean("pref_app_grid", false);
		adapter.setGridMode(isGrid);
		if (isGrid) {
			listView.setVisibility(View.GONE);
			gridView.setVisibility(View.VISIBLE);
			gridView.setAdapter(adapter);
		} else {
			gridView.setVisibility(View.GONE);
			listView.setVisibility(View.VISIBLE);
			listView.setAdapter(adapter);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		applyFabColor(getView());
		if (adapter != null) {
			adapter.notifyDataSetChanged();
		}
		if (appPath != null) {
			showDexOptionsDialog(appPath);
			appPath = null;
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		compositeDisposable.clear();
	}

	@SuppressLint("CheckResult")
	private void initDb() {
		appRepository = new AppRepository(getActivity().getApplication(), appSort.equals("date"));
		ConnectableFlowable<List<AppItem>> listConnectableFlowable = appRepository.getAll()
				.subscribeOn(Schedulers.io())
				.publish();
		listConnectableFlowable
				.firstElement()
				.subscribe(list -> AppUtils.updateDb(appRepository, list));
		compositeDisposable.add(listConnectableFlowable
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(list -> {
					adapter.setItems(list);
					if (emptyView != null) {
						emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
					}
				}));
		compositeDisposable.add(listConnectableFlowable.connect());
	}

	private void showDexOptionsDialog(final String path) {
		View inflate = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_dex_options, null);
		final CheckBox cbBatchDex = inflate.findViewById(R.id.cbBatchDex);
		final EditText etBatchSize = inflate.findViewById(R.id.etBatchSize);
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.dex_batch_title)
				.setView(inflate)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					boolean batch = cbBatchDex.isChecked();
					int size = 50;
					try {
						size = Integer.parseInt(etBatchSize.getText().toString().trim());
					} catch (Exception ignored) {}
					convertJar(path, batch, size);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	@SuppressLint("CheckResult")
	private void convertJar(String path, boolean batchDex, int batchSize) {
		ProgressDialog dialog = new ProgressDialog(getActivity());
		dialog.setIndeterminate(true);
		dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
		dialog.setCancelable(false);
		dialog.setMessage(getText(R.string.converting_message));
		dialog.setTitle(R.string.converting_wait);
		converter.convert(path, batchDex, batchSize, status -> {
					Activity a = getActivity();
					if (a != null) {
						a.runOnUiThread(() -> dialog.setMessage(getText(R.string.converting_message) + "\n" + status));
					}
				})
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(new SingleObserver<JarConverter.ConversionResult>() {
					@Override
					public void onSubscribe(Disposable d) {
						dialog.show();
					}

					@Override
					public void onSuccess(JarConverter.ConversionResult result) {
						AppItem app = AppUtils.getApp(result.appDirPath);
						appRepository.insert(app);
						if (isAdded()) {
							dialog.dismiss();
							showStartDialog(app, result.duration);
						}
					}

					@Override
					public void onError(Throwable e) {
						e.printStackTrace();
						if (isAdded()) {
							Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_LONG).show();
							dialog.dismiss();
						}
					}
				});
	}

	private void showStartDialog(AppItem item, long duration) {
		StringBuilder sb = new StringBuilder();
		sb.append(getString(R.string.author)).append(" ").append(item.getAuthor()).append("\n");
		sb.append(getString(R.string.version)).append(" ").append(item.getVersion()).append("\n");
		sb.append(getString(R.string.execution_time)).append(" ").append(duration).append(" ").append(getString(R.string.seconds)).append("\n");

		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
				.setTitle(item.getTitle())
				.setMessage(sb.toString())
				.setPositiveButton(R.string.START_CMD, (d, w) -> Config.startApp(getActivity(), item.getTitle(), item.getPath(), false))
				.setNegativeButton(R.string.close, null);
		Drawable icon = Drawable.createFromPath(item.getImagePathExt());
		if (icon != null) {
			builder.setIcon(icon);
		}
		builder.show();
	}

	private void showIpRedirectDialog() {
		LinearLayout layout = new LinearLayout(getActivity());
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (getResources().getDisplayMetrics().density * 20);
		layout.setPadding(pad, pad, pad, pad);
		final EditText etIp = new EditText(getActivity());
		etIp.setHint(R.string.ip_address);
		etIp.setText(sp.getString("pref_ip_redirect_addr", ""));
		layout.addView(etIp);
		final EditText etPort = new EditText(getActivity());
		etPort.setHint(R.string.port);
		etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
		etPort.setText(sp.getString("pref_ip_redirect_port", ""));
		layout.addView(etPort);

		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.ip_redirect_title)
				.setView(layout)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					sp.edit()
							.putString("pref_ip_redirect_addr", etIp.getText().toString().trim())
							.putString("pref_ip_redirect_port", etPort.getText().toString().trim())
							.apply();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showSortDialog() {
		int checked = appSort.equals("date") ? 1 : 0;
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.pref_app_sort_title)
				.setSingleChoiceItems(R.array.pref_app_sort_entries, checked, (d, which) -> {
					appSort = (which == 1) ? "date" : "name";
					sp.edit().putString("pref_app_sort", appSort).apply();
					initDb();
					d.dismiss();
				})
				.show();
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == FILE_CODE && resultCode == Activity.RESULT_OK) {
			List<Uri> files = Utils.getSelectedFilesFromResult(data);
			for (Uri uri : files) {
				File file = Utils.getFileForUri(uri);
				showDexOptionsDialog(file.getAbsolutePath());
			}
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);
		inflater.inflate(R.menu.main, menu);
		MenuItem searchItem = menu.findItem(R.id.action_search);
		SearchView searchView = (SearchView) searchItem.getActionView();
		Disposable searchDisposable = Observable.create((ObservableOnSubscribe<String>) emitter ->
				searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
					@Override
					public boolean onQueryTextSubmit(String query) {
						emitter.onNext(query);
						return false;
					}

					@Override
					public boolean onQueryTextChange(String newText) {
						emitter.onNext(newText);
						return false;
					}
				})).debounce(300, TimeUnit.MILLISECONDS)
				.map(String::toLowerCase)
				.distinctUntilChanged()
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(charSequence -> adapter.getFilter().filter(charSequence));
		compositeDisposable.add(searchDisposable);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_view_mode) {
			boolean isGrid = !sp.getBoolean("pref_app_grid", false);
			sp.edit().putBoolean("pref_app_grid", isGrid).apply();
			updateViewMode();
			return true;
		} else if (itemId == R.id.action_sort) {
			showSortDialog();
			return true;
		} else if (itemId == R.id.action_ip_redirect) {
			showIpRedirectDialog();
			return true;
		} else if (itemId == R.id.action_about) {
			new AboutDialogFragment().show(getFragmentManager(), "about");
			return true;
		} else if (itemId == R.id.action_profiles) {
			startActivity(new Intent(getActivity(), ProfilesActivity.class));
			return true;
		} else if (itemId == R.id.action_settings) {
			startActivity(new Intent(getActivity(), SettingsActivity.class));
			return true;
		} else if (itemId == R.id.action_help) {
			new HelpDialogFragment().show(getFragmentManager(), "help");
			return true;
		} else if (itemId == R.id.action_donate) {
			startActivity(new Intent(getActivity(), DonationsActivity.class));
			return true;
		} else if (itemId == R.id.action_save_log) {
			try {
				LogUtils.writeLog();
				Toast.makeText(getActivity(), R.string.log_saved, Toast.LENGTH_SHORT).show();
			} catch (IOException e) {
				Toast.makeText(getActivity(), R.string.error, Toast.LENGTH_SHORT).show();
			}
			return true;
		} else if (itemId == R.id.action_exit_app) {
			if (getActivity() != null) getActivity().finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		MenuInflater inflater = getActivity().getMenuInflater();
		inflater.inflate(R.menu.context_main, menu);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
		if (info == null) return false;
		int index = info.position;
		AppItem appItem = adapter.getItem(index);
		int itemId = item.getItemId();
		if (itemId == R.id.action_open_multi_tab) {
			showMultiTabDialog(appItem);
			return true;
		} else if (itemId == R.id.action_context_settings) {
			Config.startApp(getActivity(), appItem.getTitle(), appItem.getPath(), true);
			return true;
		} else if (itemId == R.id.action_context_delete) {
			showDeleteDialog(appItem);
			return true;
		} else if (itemId == R.id.action_context_shortcut) {
			Bitmap bitmap = BitmapFactory.decodeFile(appItem.getImagePathExt());
			IconCompat icon = (bitmap != null) ? IconCompat.createWithBitmap(bitmap)
					: IconCompat.createWithResource(getActivity(), R.mipmap.ic_launcher);
			Intent launchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(appItem.getPath()));
			launchIntent.setClass(getActivity(), MainActivity.class);
			launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(getActivity(), appItem.getTitle())
					.setShortLabel(appItem.getTitle())
					.setIcon(icon)
					.setIntent(launchIntent)
					.build();
			ShortcutManagerCompat.requestPinShortcut(getActivity(), shortcut, null);
			return true;
		} else if (itemId == R.id.action_context_rename) {
			showRenameDialog(appItem);
			return true;
		}
		return super.onContextItemSelected(item);
	}

	private void showMultiTabDialog(AppItem item) {
		int remaining = 20 - TabManager.get().getTabCount();
		if (remaining <= 0) {
			Toast.makeText(getContext(), "Da dat gioi han 20 tab!", Toast.LENGTH_SHORT).show();
			return;
		}
		final EditText input = new EditText(requireContext());
		input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		input.setText("1");
		input.setSelectAllOnFocus(true);

		new AlertDialog.Builder(requireContext())
				.setTitle("Mo nhieu man")
				.setMessage("Nhap so man muon mo them (tong cong toi da " + remaining + " man)")
				.setView(input)
				.setNegativeButton("CANCEL", null)
				.setPositiveButton("MO", (d, w) -> {
					String val = input.getText().toString().trim();
					int count;
					try { count = Integer.parseInt(val); } catch (Exception e) { return; }
					if (count <= 0) return;
					int maxNew = 20 - TabManager.get().getTabCount();
					if (count > maxNew) count = maxNew;
					final int finalCount = count;
					new Thread(() -> {
						for (int i = 0; i < finalCount; i++) {
							int slot = TabManager.get().nextSlot();
							requireActivity().runOnUiThread(() ->
									Config.startNewTab(requireContext(), item.getTitle(), item.getPath(), slot));
							try { Thread.sleep(1500); } catch (InterruptedException e2) { break; }
						}
					}).start();
					Toast.makeText(getContext(), "Dang mo " + finalCount + " man...", Toast.LENGTH_SHORT).show();
				})
				.show();
	}

	private void showRenameDialog(AppItem item) {
		EditText editText = new EditText(getActivity());
		editText.setText(item.getTitle());
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		LinearLayout layout = new LinearLayout(getContext());
		layout.setPadding(pad, pad, pad, pad);
		layout.addView(editText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.action_context_rename)
				.setView(layout)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String newTitle = editText.getText().toString().trim();
					if (!newTitle.isEmpty()) {
						item.setTitle(newTitle);
						appRepository.insert(item);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showDeleteDialog(AppItem item) {
		new AlertDialog.Builder(getActivity())
				.setTitle(android.R.string.dialog_alert_title)
				.setMessage(R.string.message_delete)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					AppUtils.deleteApp(item);
					appRepository.delete(item);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}
}
