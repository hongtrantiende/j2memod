/*
 * Copyright 2020 Yury Kharchenko
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

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.Keep;

import java.util.HashMap;
import java.util.Map;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

@Keep
public final class MidletSystem {
	private static final String TAG = MidletSystem.class.getName();
	private static final Map<String, String> PROPERTY = new HashMap<>();

	static void setProperty(String key, String value) {
		PROPERTY.put(key, value);
	}

	public static String getProperty(String key) {
		String value = PROPERTY.get(key);
		if (Display.isMultiTouchSupported() && key.equals("com.nokia.pointer.number")) {
			Display.getDisplay((MIDlet) null);
			return Display.getPointerNumber();
		}
		if (TextUtils.isEmpty(value)) {
			value = System.getProperty(key);
		}
		Log.d(TAG, "System.getProperty: " + key + "=" + value);
		return value;
	}
}
