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

import java.util.Iterator;

import androidx.annotation.NonNull;

/**
 * MidletThread - Moi SlotSession co 1 MidletThread rieng.
 * Khong con singleton - theo kien truc NST.
 */
public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();

	private static final int PAUSE = 2;
	private static final int START = 1;
	private static final int DESTROY = 3;

	private MIDlet midlet;
	private final Handler handler;
	private boolean started;
	private final SlotSession session;

	private MidletThread(SlotSession session, MicroLoader microLoader, String mainClass) {
		super("MidletMain-" + session.slot);
		this.session = session;
		start();
		handler = new Handler(getLooper(), this);
		Runnable r = () -> {
			// Bind session cho thread nay (va tat ca child threads)
			SlotRegistry.bind(session);
			Log.i(TAG, "[Slot " + session.slot + "] MidletThread started, loading " + mainClass);
			try {
				midlet = microLoader.loadMIDlet(mainClass);
				Log.i(TAG, "[Slot " + session.slot + "] loadMIDlet OK, calling startApp()");
				started = true;
				midlet.startApp();
				Log.i(TAG, "[Slot " + session.slot + "] startApp() returned");
			} catch (Throwable t) {
				Log.e(TAG, "[Slot " + session.slot + "] MidletThread error", t);
				t.printStackTrace();
				Throwable cause = t;
				Throwable e;
				while ((e = cause.getCause()) != null) {
					cause = e;
				}
				MicroActivity activity = ContextHolder.getActivity();
				if (activity != null) {
					activity.showErrorDialog(cause.toString());
				}
			}
		};
		handler.post(r);
	}

	/** Tao MidletThread moi cho session hien tai */
	public static void create(MicroLoader microLoader, String mainClass) {
		SlotSession session = SlotRegistry.current();
		if (session == null) {
			throw new IllegalStateException("Chua co SlotSession! Goi SlotRegistry.create() truoc.");
		}
		session.midletThread = new MidletThread(session, microLoader, mainClass);
	}

	/** Pause tat ca slot dang chay */
	public static void pauseApp() {
		for (SlotSession s : SlotRegistry.all()) {
			if (s.midletThread != null) {
				s.midletThread.handler.obtainMessage(PAUSE).sendToTarget();
			}
		}
	}

	/** Resume tat ca slot dang chay */
	public static void resumeApp() {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null || !activity.isVisible()) return;
		for (SlotSession s : SlotRegistry.all()) {
			if (s.midletThread != null) {
				s.midletThread.handler.obtainMessage(START).sendToTarget();
			}
		}
	}

	/** Kiem tra con slot nao dang chay khong */
	public static boolean isActive() {
		return SlotRegistry.count() > 0;
	}

	/** Lay displayable cua slot dang focus */
	public static Displayable getCurrentDisplayable() {
		SlotSession focused = SlotRegistry.focused();
		if (focused != null) {
			return focused.current;
		}
		return null;
	}

	/** Destroy 1 slot cu the */
	public static void destroySlot(SlotSession session) {
		if (session != null && session.midletThread != null) {
			session.midletThread.handler.obtainMessage(DESTROY).sendToTarget();
		}
	}

	/** Destroy tat ca slot va kill process */
	public static void destroyApp() {
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();

		// Send END key to focused canvas
		if (ContextHolder.getActivity() != null) {
			SlotSession focused = SlotRegistry.focused();
			Displayable current = focused != null ? focused.current : null;
			if (current instanceof Canvas) {
				Canvas canvas = (Canvas) current;
				int keyCode = Canvas.convertKeyCode(Canvas.KEY_END);
				Display.postEvent(CanvasEvent.getInstance(canvas, CanvasEvent.KEY_PRESSED, keyCode));
				Display.postEvent(CanvasEvent.getInstance(canvas, CanvasEvent.KEY_RELEASED, keyCode));
			}
		}

		// Destroy all slots
		for (SlotSession s : SlotRegistry.all()) {
			if (s.midletThread != null) {
				s.midletThread.handler.obtainMessage(DESTROY, 1).sendToTarget();
			}
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		// Bind session moi khi xu ly message
		SlotRegistry.bind(session);
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
					MicroActivity activity = ContextHolder.getActivity();
					if (activity != null) activity.showErrorDialog(t.getMessage());
				}
				break;
			case PAUSE:
				if (!started) return true;
				started = false;
				try {
					midlet.pauseApp();
				} catch (Throwable t) {
					Log.e(TAG, "pauseApp: ", t);
					MicroActivity activity = ContextHolder.getActivity();
					if (activity != null) activity.showErrorDialog(t.getMessage());
				}
				break;
			case DESTROY:
				try {
					midlet.destroyApp(true);
					started = false;
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "destroyApp:", e);
					return true;
				} catch (Throwable t) {
					Log.e(TAG, "destroyApp:", t);
				}
				// Cleanup
				session.shutdownResources();
				SlotRegistry.remove(session);
				// Neu tat ca slot da dong -> finish activity
				if (SlotRegistry.count() == 0) {
					ContextHolder.notifyDestroyed();
				} else {
					// Con slot khac -> chi cap nhat UI
					MicroActivity activity = ContextHolder.getActivity();
					if (activity != null) {
						activity.runOnUiThread(() -> activity.onSlotRemoved(session.slot));
					}
				}
				break;
		}
		return true;
	}
}
