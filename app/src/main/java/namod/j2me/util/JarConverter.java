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

			LinkedHashMap<String, String> params = null;
			if (jadInstall) {
				params = FileUtils.loadManifest(conf);
			} else {
				try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(patchedJar)) {
					java.util.zip.ZipEntry mfEntry = zf.getEntry("META-INF/MANIFEST.MF");
					if (mfEntry == null) {
						java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
						while (en.hasMoreElements()) {
							java.util.zip.ZipEntry e = en.nextElement();
							if (e.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
								mfEntry = e;
								break;
							}
						}
					}
					if (mfEntry != null) {
						try (InputStream is = zf.getInputStream(mfEntry)) {
							params = FileUtils.loadManifest(is);
						}
					}
				} catch (IOException e) {
					deleteTemp();
					throw new ConverterException("Invalid jar", e);
				}
			}

			if (params == null) {
				deleteTemp();
				throw new ConverterException("Manifest not found");
			}

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

			String imagePathFromManifest = AppUtils.getImagePathFromManifest(params);

			try {
				DxContext dxContext = new DxContext(new OutputStream() {
					private StringBuilder sb = new StringBuilder();

					@Override
					public void write(int b) {
						if (b != '\n' && b != '\r') {
							sb.append((char) b);
							return;
						}
						String line = sb.toString();
						if (line.startsWith("processing ") && line.endsWith("...")) {
							String name = line.substring(11, line.length() - 3);
							if (callback != null) {
								callback.onProgressUpdate(name);
							}
						}
						sb.setLength(0);
					}
				}, System.err);

				if (batchDex && batchSize > 0) {
					ArrayList<String> classFiles = new ArrayList<>();
					try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(patchedJar)) {
						if (imagePathFromManifest != null) {
							java.util.zip.ZipEntry iconEntry = zipFile.getEntry(imagePathFromManifest);
							if (iconEntry != null) {
								File iconTmp = new File(tmpDir, "icon.png");
								try (InputStream is = zipFile.getInputStream(iconEntry);
								     FileOutputStream fos = new FileOutputStream(iconTmp)) {
									IOUtils.copy(is, fos);
								}
							}
						}
						java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
						int classIndex = 0;
						while (entries.hasMoreElements()) {
							java.util.zip.ZipEntry next = entries.nextElement();
							if (next.getName().endsWith(".class")) {
								classIndex++;
								File classFile = new File(tmpDir, "c" + classIndex + ".class");
								try (InputStream is = zipFile.getInputStream(next);
								     FileOutputStream fos = new FileOutputStream(classFile)) {
									IOUtils.copy(is, fos);
								}
								classFiles.add(classFile.getAbsolutePath());
							}
						}
					}

					int numBatches = (classFiles.size() + batchSize - 1) / batchSize;
					for (int i = 0; i < numBatches; i++) {
						int from = i * batchSize;
						int to = Math.min(from + batchSize, classFiles.size());
						List<String> subList = classFiles.subList(from, to);
						String outDex = (i == 0) ? Config.MIDLET_DEX_FILE : ("/converted" + (i + 1) + ".dex");
						ArrayList<String> args = new ArrayList<>();
						args.add("--verbose");
						args.add("--no-optimize");
						args.add("--no-strict");
						args.add("--output=" + appConverted.getPath() + outDex);
						args.addAll(subList);

						Main.Arguments arguments = new Main.Arguments(dxContext);
						arguments.parse(args.toArray(new String[0]));
						int res = new Main(dxContext).runDx(arguments);
						if (res != 0) {
							throw new ConverterException("Dex batch " + (i + 1) + " failed with code " + res);
						}
					}
				} else {
					try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(patchedJar)) {
						if (imagePathFromManifest != null) {
							java.util.zip.ZipEntry iconEntry = zipFile.getEntry(imagePathFromManifest);
							if (iconEntry != null) {
								File iconTmp = new File(tmpDir, "icon.png");
								try (InputStream is = zipFile.getInputStream(iconEntry);
								     FileOutputStream fos = new FileOutputStream(iconTmp)) {
									IOUtils.copy(is, fos);
								}
							}
						}
					}

					ArrayList<String> args = new ArrayList<>();
					args.add("--verbose");
					args.add("--no-optimize");
					args.add("--output=" + appConverted.getPath() + Config.MIDLET_DEX_FILE);
					args.add(patchedJar.getAbsolutePath());
					Main.Arguments arguments = new Main.Arguments(dxContext);
					arguments.parse(args.toArray(new String[0]));
					int res = new Main(dxContext).runDx(arguments);
					if (res != 0) {
						throw new ConverterException("Dex conversion failed with code " + res);
					}
				}
			} catch (Throwable e) {
				deleteTemp();
				FileUtils.deleteDirectory(appConverted);
				throw new ConverterException("Can't convert: " + e.getMessage(), e);
			}

			try {
				if (jadInstall) {
					FileUtils.copyFileUsingChannel(conf, new File(appConverted, Config.MIDLET_MANIFEST_FILE));
				} else {
					try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(patchedJar)) {
						java.util.zip.ZipEntry entry = zipFile.getEntry("META-INF/MANIFEST.MF");
						if (entry == null) {
							java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zipFile.entries();
							while (en.hasMoreElements()) {
								java.util.zip.ZipEntry e = en.nextElement();
								if (e.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
									entry = e;
									break;
								}
							}
						}
						if (entry != null) {
							try (InputStream is = zipFile.getInputStream(entry);
							     FileOutputStream fos = new FileOutputStream(new File(appConverted, Config.MIDLET_MANIFEST_FILE))) {
								IOUtils.copy(is, fos);
							}
						}
					}
				}
				File iconTmp = new File(tmpDir, "icon.png");
				if (iconTmp.exists()) {
					FileUtils.copyFileUsingChannel(iconTmp, new File(appConverted, Config.MIDLET_ICON_FILE));
				}
			} catch (IOException | NullPointerException e) {
				e.printStackTrace();
			}
			try {
				FileUtils.copyFileUsingChannel(inputJar, new File(appConverted, Config.MIDLET_RES_FILE));
			} catch (IOException e) {
				e.printStackTrace();
			}
			deleteTemp();
			long duration = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
			emitter.onSuccess(new ConversionResult(appDirPath, duration));
		});
	}
}
