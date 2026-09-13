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

package namod.j2me.util;

import android.util.Log;

import com.android.dx.command.dexer.DxContext;
import com.android.dx.command.dexer.Main;

import net.lingala.zip4j.exception.ZipException;

import org.acra.ACRA;
import org.microemu.android.asm.AndroidProducer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import io.reactivex.Single;
import namod.j2me.config.Config;

public class JarConverter {

	public static final String TEMP_JAR_NAME = "tmp.jar";
	public static final String TEMP_JAD_NAME = "tmp.jad";
	public static final String TEMP_URI_FOLDER_NAME = "tmp_uri";

	private static final String TEMP_FOLDER_NAME = "tmp";
	private static final String TAG = JarConverter.class.getName();

	private String appDirPath;
	private String dataDirPath;
	private final File tmpDir;
	private File appConverted;

	public static class ConversionResult {
		public final String appDirPath;
		public final long duration;

		public ConversionResult(String appDirPath, long duration) {
			this.appDirPath = appDirPath;
			this.duration = duration;
		}
	}

	public interface ProgressCallback {
		void onProgressUpdate(String message);
	}

	public JarConverter(String dataDirPath) {
		this.dataDirPath = dataDirPath;
		tmpDir = new File(dataDirPath, TEMP_FOLDER_NAME);
	}

	private File patchJar(File inputJar) throws IOException {
		File patchedJar = new File(tmpDir, inputJar.getName() + ".jar");
		AndroidProducer.processJar(inputJar, patchedJar);
		return patchedJar;
	}

	private void deleteTemp() {
		FileUtils.deleteDirectory(tmpDir);
		File uriFolder = new File(dataDirPath, JarConverter.TEMP_URI_FOLDER_NAME);
		FileUtils.deleteDirectory(uriFolder);
	}

	private void download(String urlStr, File outputJar) throws IOException {
		URL url = new URL(getRedirect(urlStr));
		Log.d(TAG, "Downloading " + outputJar.getPath());
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setReadTimeout(30000);
		connection.setConnectTimeout(15000);
		InputStream inputStream = connection.getInputStream();
		OutputStream outputStream = new FileOutputStream(outputJar);
		IOUtils.copy(inputStream, outputStream);
		inputStream.close();
		outputStream.close();
		connection.disconnect();
		Log.d(TAG, "Download complete");
	}

	private String getRedirect(String urlStr) throws IOException {
		URL url = new URL(urlStr);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setReadTimeout(30000);
		connection.setConnectTimeout(15000);
		if (connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM ||
				connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) {
			urlStr = connection.getHeaderField("Location");
		}
		connection.disconnect();
		return urlStr;
	}

	private File findManifest(File tmpDir) {
		String confName = "/META-INF/MANIFEST.MF";
		File conf = new File(tmpDir, confName);
		if (conf.exists()) {
			return conf;
		}
		File parent = null;
		File[] files = tmpDir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.getName().equalsIgnoreCase(conf.getParentFile().getName())) {
					parent = file;
					break;
				}
			}
		}
		if (parent == null) {
			return null;
		}
		File[] parentFiles = parent.listFiles();
		if (parentFiles != null) {
			for (File file : parentFiles) {
				if (file.getName().equalsIgnoreCase(conf.getName())) {
					return file;
				}
			}
		}
		return null;
	}

	private void collectClassFiles(File dir, List<String> classFiles) {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.isDirectory()) {
				collectClassFiles(f, classFiles);
			} else if (f.getName().endsWith(".class")) {
				classFiles.add(f.getAbsolutePath());
			}
		}
	}

	public Single<ConversionResult> convert(final String path) {
		return convert(path, false, 50, null);
	}

	public Single<ConversionResult> convert(final String path, final boolean batchDex, final int batchSize, final ProgressCallback callback) {
		return Single.create(emitter -> {
			long startTime = System.currentTimeMillis();
			boolean jadInstall = false;
			String pathToJad = null;
			String pathToJar = path;
			tmpDir.mkdir();

			String targetJarName = pathToJar.substring(pathToJar.lastIndexOf('/') + 1);
			ACRA.getErrorReporter().putCustomData("Last installed app", targetJarName);
			Log.d(TAG, "doInBackground$ pathToJar=" + pathToJar);
			String extension = pathToJar.substring(pathToJar.lastIndexOf('.'));
			if (extension.equalsIgnoreCase(".jad")) {
				jadInstall = true;
				pathToJad = pathToJar;
				pathToJar = pathToJar.substring(0, pathToJar.length() - 1).concat("r");
			}
			File conf = null;
			if (jadInstall) {
				conf = new File(pathToJad);
			}

			File inputJar = new File(pathToJar);
			if (jadInstall && !inputJar.exists()) {
				String url = FileUtils.loadManifest(conf).get("MIDlet-Jar-URL");
				try {
					download(url, inputJar);
				} catch (IOException e) {
					inputJar.delete();
					deleteTemp();
					throw new ConverterException("Can't download jar", e);
				}
			}
			File patchedJar;
			try {
				patchedJar = patchJar(inputJar);
			} catch (ZipException e) {
				deleteTemp();
				throw new ConverterException("Invalid jar", e);
			} catch (Exception e) {
				deleteTemp();
				throw new ConverterException("Can't patch", e);
			}
			try {
				ZipUtils.unzip(patchedJar, tmpDir);
			} catch (IOException e) {
				deleteTemp();
				throw new ConverterException("Invalid jar", e);
			}

			if (!jadInstall) {
				conf = findManifest(tmpDir);
				if (conf == null) {
					deleteTemp();
					throw new ConverterException("Manifest not found");
				}
			}
			LinkedHashMap<String, String> params = FileUtils.loadManifest(conf);
			appDirPath = params.get("MIDlet-Name");
			if (appDirPath == null) {
				deleteTemp();
				throw new ConverterException("Invalid manifest");
			}
			appDirPath = appDirPath.replaceAll("[?:\"*|/\\\\<>]", "");
			if (appDirPath.isEmpty()) {
				deleteTemp();
				throw new ConverterException("Invalid manifest");
			}
			appConverted = new File(Config.getAppDir(), appDirPath);
			FileUtils.deleteDirectory(appConverted);
			appConverted.mkdirs();
			Log.d(TAG, "appConverted=" + appConverted.getPath());

			try {
				if (batchDex && batchSize > 0) {
					List<String> classFiles = new ArrayList<>();
					collectClassFiles(tmpDir, classFiles);
					int numBatches = (classFiles.size() + batchSize - 1) / batchSize;
					DxContext dxContext = new DxContext();
					for (int i = 0; i < numBatches; i++) {
						int from = i * batchSize;
						int to = Math.min(from + batchSize, classFiles.size());
						List<String> sub = classFiles.subList(from, to);
						String outDex = (i == 0) ? Config.MIDLET_DEX_FILE : ("/converted" + (i + 1) + ".dex");
						ArrayList<String> args = new ArrayList<>();
						args.add("--no-optimize");
						args.add("--no-strict");
						args.add("--output=" + appConverted.getPath() + outDex);
						args.addAll(sub);

						Main.Arguments arguments = new Main.Arguments(dxContext);
						arguments.parse(args.toArray(new String[0]));
						new Main(dxContext).runDx(arguments);
					}
				} else {
					Main.main(new String[]{
							"--no-optimize", "--output=" + appConverted.getPath()
							+ Config.MIDLET_DEX_FILE, patchedJar.getAbsolutePath()});
				}
			} catch (Throwable e) {
				deleteTemp();
				FileUtils.deleteDirectory(appConverted);
				throw new ConverterException("Can't convert", e);
			}

			try {
				FileUtils.copyFileUsingChannel(conf, new File(appConverted, Config.MIDLET_MANIFEST_FILE));
				File image = new File(tmpDir, AppUtils.getImagePathFromManifest(params));
				FileUtils.copyFileUsingChannel(image, new File(appConverted, Config.MIDLET_ICON_FILE));
			} catch (IOException | NullPointerException e) {
				e.printStackTrace();
			} catch (ArrayIndexOutOfBoundsException e) {
				deleteTemp();
				FileUtils.deleteDirectory(appConverted);
				throw new ConverterException("Invalid manifest");
			}
			FileUtils.copyFileUsingChannel(inputJar, new File(appConverted, Config.MIDLET_RES_FILE));
			deleteTemp();
			long duration = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
			emitter.onSuccess(new ConversionResult(appDirPath, duration));
		});
	}
}
