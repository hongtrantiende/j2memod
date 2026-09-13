/**
 * MicroEmulator
 * Copyright (C) 2001-2007 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2006-2007 Vlad Skarzhevskyy
 * <p>
 * It is licensed under the following two licenses as alternatives:
 * 1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 * 2. Apache License (the "AL") Version 2.0
 * <p>
 * You may not use this file except in compliance with at least one of
 * the above two licenses.
 * <p>
 * You may obtain a copy of the LGPL at
 * http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 * <p>
 * You may obtain a copy of the AL at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the LGPL or the AL for the specific language governing permissions and
 * limitations.
 */

package javax.microedition.io;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.microemu.microedition.ImplFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.util.ContextHolder;

public class Connector {

	private static final String TAG = "Connector";

	public static final int READ = 1;

	public static final int WRITE = 2;

	public static final int READ_WRITE = 3;

	private Connector() {

	}

	public static String redirectUrl(String str) {
		if (str == null) {
			return null;
		}
		int protoIdx = str.indexOf("://");
		if (protoIdx == -1) {
			return str;
		}
		String proto = str.substring(0, protoIdx);
		if (!proto.equalsIgnoreCase("socket") && !proto.equalsIgnoreCase("ssl")) {
			return str;
		}

		String redirectAddr = System.getProperty("pref_ip_redirect_addr");
		String redirectPort = System.getProperty("pref_ip_redirect_port");

		if ((redirectAddr == null || redirectAddr.trim().isEmpty()) && (redirectPort == null || redirectPort.trim().isEmpty())) {
			try {
				Context ctx = ContextHolder.getAppContext();
				if (ctx != null) {
					SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
					redirectAddr = sp.getString("pref_ip_redirect_addr", "");
					redirectPort = sp.getString("pref_ip_redirect_port", "");
				}
			} catch (Throwable ignored) {}
		}

		if ((redirectAddr == null || redirectAddr.trim().isEmpty()) && (redirectPort == null || redirectPort.trim().isEmpty())) {
			return str;
		}

		redirectAddr = redirectAddr != null ? redirectAddr.trim() : "";
		redirectPort = redirectPort != null ? redirectPort.trim() : "";

		String rest = str.substring(protoIdx + 3);
		int endIdx = rest.indexOf('/');
		if (endIdx == -1) {
			endIdx = rest.indexOf(';');
		}
		if (endIdx == -1) {
			endIdx = rest.length();
		}
		String hostPort = rest.substring(0, endIdx);
		String suffix = rest.substring(endIdx);

		String origHost = hostPort;
		String origPort = "";
		int colonIdx = hostPort.lastIndexOf(':');
		if (colonIdx != -1) {
			origHost = hostPort.substring(0, colonIdx);
			origPort = hostPort.substring(colonIdx + 1);
		}

		String targetHost = !redirectAddr.isEmpty() ? redirectAddr : origHost;
		String targetPort = !redirectPort.isEmpty() ? redirectPort : origPort;

		StringBuilder sb = new StringBuilder(proto).append("://").append(targetHost);
		if (!targetPort.isEmpty()) {
			sb.append(':').append(targetPort);
		}
		sb.append(suffix);
		String redirected = sb.toString();
		Log.i(TAG, "Chuyển hướng IP: " + str + " -> " + redirected);
		return redirected;
	}

	public static Connection open(String name) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).open(redirected);
	}

	public static Connection open(String name, int mode) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).open(redirected, mode);
	}

	public static Connection open(String name, int mode, boolean timeouts) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).open(redirected, mode, timeouts);
	}

	public static DataInputStream openDataInputStream(String name) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).openDataInputStream(redirected);
	}

	public static DataOutputStream openDataOutputStream(String name) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).openDataOutputStream(redirected);
	}

	public static InputStream openInputStream(String name) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).openInputStream(redirected);
	}

	public static OutputStream openOutputStream(String name) throws IOException {
		String redirected = redirectUrl(name);
		return ImplFactory.getCGFImplementation(redirected).openOutputStream(redirected);
	}

}
