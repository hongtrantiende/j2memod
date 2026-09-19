/*
 *  Copyright 2020 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.microedition.shell;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.event.CanvasEvent;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();

	private static final int PAUSE = 2;
	private static final int START = 1;
	private static final int DESTROY = 3;

	/** Session slot ma thread nay phuc vu */
	private final SlotSession session;
	private MIDlet midlet;
	final Handler handler;
	private boolean started;

	private MidletThread(SlotSession session, MicroLoader microLoader, String mainClass) {
		super("MidletMain-" + session.slot);
		this.session = session;
		start();
		handler = new Handler(getLooper(), this);
		Runnable r = () -> {
			// Bind session cho thread game nay
			SlotRegistry.bind(session);
			try {
				midlet = microLoader.loadMIDlet(mainClass);
				started = true;
				midlet.startApp();
			} catch (Throwable t) {
				t.printStackTrace();
				Throwable e;
				Throwable cause = t;
				while ((e = cause.getCause()) != null) {
					cause = e;
				}
				MicroActivity act = ContextHolder.getActivity();
				if (act != null) act.showErrorDialog(cause.toString());
			}
		};
		handler.post(r);
	}

	/**
	 * Tao MidletThread cho slot hien tai (lay tu SlotRegistry.current()).
	 * Backward-compat API.
	 */
	public static MidletThread create(MicroLoader microLoader, String mainClass) {
		SlotSession sess = SlotRegistry.current();
		if (sess == null) {
			// Fallback: tao slot 0 neu chua co
			sess = SlotRegistry.create(0);
			SlotRegistry.bind(sess);
		}
		MidletThread t = new MidletThread(sess, microLoader, mainClass);
		sess.midletThread = t;
		return t;
	}

	/** Pause slot hien tai (focused) */
	public static void pauseApp() {
		SlotSession sess = SlotRegistry.focused();
		if (sess != null && sess.midletThread != null)
			sess.midletThread.handler.obtainMessage(PAUSE).sendToTarget();
	}

	/** Resume slot hien tai */
	public static void resumeApp() {
		SlotSession sess = SlotRegistry.focused();
		if (sess != null && sess.midletThread != null)
			sess.midletThread.handler.obtainMessage(START).sendToTarget();
	}

	public static boolean isActive() {
		return SlotRegistry.count() > 0;
	}

	/** Lay Displayable cua slot hien tai (focused) - backward compat */
	public static Displayable getCurrentDisplayable() {
		SlotSession sess = SlotRegistry.focused();
		if (sess != null) return sess.current;
		return null;
	}

	/** Destroy tat ca slot (thoat game) */
	public static void destroyApp() {
		new Thread(() -> {
			try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
		// Gui phim End cho canvas focused
		SlotSession focused = SlotRegistry.focused();
		if (focused != null && focused.current instanceof Canvas) {
			Canvas canvas = (Canvas) focused.current;
			int keyCode = Canvas.convertKeyCode(Canvas.KEY_END);
			Display.postEvent(CanvasEvent.getInstance(canvas, CanvasEvent.KEY_PRESSED, keyCode));
			Display.postEvent(CanvasEvent.getInstance(canvas, CanvasEvent.KEY_RELEASED, keyCode));
		}
		// Destroy tat ca sessions
		for (SlotSession sess : SlotRegistry.all()) {
			if (sess.midletThread != null) {
				sess.midletThread.handler.obtainMessage(DESTROY, 1).sendToTarget();
			}
		}
	}

	/** Destroy 1 slot cu the */
	public static void destroySlot(SlotSession session) {
		if (session != null && session.midletThread != null) {
			session.midletThread.handler.obtainMessage(DESTROY, 1).sendToTarget();
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		if (midlet == null) return true;
		switch (msg.what) {
			case START:
				if (started) return true;
				started = true;
				try {
					midlet.startApp();
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "startApp:", e);
				} catch (Throwable t) {
					Log.e(TAG, "startApp:", t);
					MicroActivity act = ContextHolder.getActivity();
					if (act != null) act.showErrorDialog(t.getMessage());
				}
				break;
			case PAUSE:
				if (!started) return true;
				started = false;
				try {
					midlet.pauseApp();
				} catch (Throwable t) {
					Log.e(TAG, "pauseApp: ", t);
				}
				break;
			case DESTROY:
				try {
					midlet.destroyApp(true);
					started = false;
					SlotRegistry.remove(session);
					ContextHolder.notifyDestroyed();
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "destroyApp:", e);
					return true;
				} catch (Throwable t) {
					Log.e(TAG, "destroyApp:", t);
				}
				break;
		}
		return true;
	}
}