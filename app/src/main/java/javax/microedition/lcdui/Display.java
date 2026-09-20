/*
 * Copyright 2012 Kulikov Dmitriy
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

package javax.microedition.lcdui;

import javax.microedition.lcdui.event.Event;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.lcdui.event.RunnableEvent;
import javax.microedition.midlet.MIDlet;
import javax.microedition.shell.SlotRegistry;
import javax.microedition.shell.SlotSession;
import javax.microedition.util.ContextHolder;

import androidx.appcompat.app.AlertDialog;

/**
 * Display - theo kien truc NST:
 * Moi SlotSession co Display RIENG (khong con singleton global)
 * Moi SlotSession co EventQueue RIENG
 */
@SuppressWarnings("unused")
public class Display {
	public static final int LIST_ELEMENT = 1;
	public static final int CHOICE_GROUP_ELEMENT = 2;
	public static final int ALERT = 3;

	public static final int COLOR_BACKGROUND = 0;
	public static final int COLOR_FOREGROUND = 1;
	public static final int COLOR_HIGHLIGHTED_BACKGROUND = 2;
	public static final int COLOR_HIGHLIGHTED_FOREGROUND = 3;
	public static final int COLOR_BORDER = 4;
	public static final int COLOR_HIGHLIGHTED_BORDER = 5;

	private static final int[] COLORS =
			{
					0xFFD0D0D0,
					0xFF000080,
					0xFF000080,
					0xFFFFFFFF,
					0xFFFFFFFF,
					0xFF000080
			};

	/** Fallback instance khi khong co SlotSession (single-slot mode) */
	private static Display instance;
	private static boolean multiTouchSupported;
	private static String pointerNumber;
	/** Fallback EventQueue khi khong co SlotSession */
	static EventQueue queue = new EventQueue();

	static {
		queue.startProcessing();
	}

	private Displayable current;
	/** Session so huu Display nay (null = legacy/single-slot) */
	private final SlotSession ownerSession;

	private Display(SlotSession session) {
		this.ownerSession = session;
	}

	/**
	 * NST pattern: moi slot co Display rieng.
	 * getDisplay(midlet) tra Display cua slot hien tai (tu SlotRegistry.current()).
	 * Neu khong co session (single-slot), dung fallback static instance.
	 */
	public static Display getDisplay(MIDlet midlet) {
		SlotSession session = SlotRegistry.current();
		if (session != null) {
			// Multi-slot: moi session co Display rieng
			if (session.getDisplay() == null && midlet != null) {
				applyNokiaUi(midlet);
				session.setDisplay(new Display(session));
			}
			return session.getDisplay();
		}
		// Fallback: single-slot
		if (instance == null && midlet != null) {
			applyNokiaUi(midlet);
			instance = new Display(null);
		}
		return instance;
	}

	private static void applyNokiaUi(MIDlet midlet) {
		String nokiaUiEnhancement = midlet.getAppProperty("Nokia-UI-Enhancement");
		if (nokiaUiEnhancement != null) {
			multiTouchSupported = nokiaUiEnhancement.contains("EnableMultiPointTouchEvents");
		}
	}

	public static boolean isMultiTouchSupported() {
		return multiTouchSupported;
	}

	public static void setPointerNumber(int pointerNumber2) {
		pointerNumber = String.valueOf(pointerNumber2);
	}

	public static void resetPointerNumber() {
		pointerNumber = null;
	}

	public static String getPointerNumber() {
		return pointerNumber;
	}

	public static void initDisplay() {
		instance = null;
		SlotSession session = SlotRegistry.current();
		if (session != null) {
			session.setDisplay(null);
		}
	}

	/**
	 * NST pattern: postEvent dung EventQueue cua slot hien tai.
	 */
	public static void postEvent(Event event) {
		getEventQueue().postEvent(event);
	}

	/**
	 * NST pattern: EventQueue per-slot.
	 */
	static EventQueue getEventQueue() {
		SlotSession session = SlotRegistry.current();
		return session != null ? session.eventQueue() : queue;
	}

	public void setCurrent(Displayable disp) {
		if (disp == null || disp == current) {
			return;
		}
		if (disp instanceof Alert) {
			Alert alert = (Alert) disp;
			alert.setNextDisplayable(current);
			showAlert(alert);
		} else {
			changeCurrent(disp);
			showCurrent();
		}
	}

	public void setCurrent(final Alert alert, Displayable disp) {
		if (disp == null) {
			throw new NullPointerException();
		} else if (disp instanceof Alert) {
			throw new IllegalArgumentException();
		}
		alert.setNextDisplayable(disp);
		showAlert(alert);
	}

	private void showAlert(Alert alert) {
		ViewHandler.postEvent(() -> {
			AlertDialog alertDialog = alert.prepareDialog();
			alertDialog.show();
			if (alert.finiteTimeout()) {
				ViewHandler.postDelayed(alertDialog::dismiss, alert.getTimeout());
			}
		});
	}

	private void changeCurrent(Displayable disp) {
		if (current instanceof Canvas) {
			((Canvas) current).setOverlay(null);
		}
		if (disp instanceof Canvas) {
			((Canvas) disp).setOverlay(ContextHolder.getVk());
		}
		current = disp;
	}

	/**
	 * NST pattern: truyen ownerSession truc tiep cho MicroActivity.setCurrent
	 * de tranh race condition voi ThreadLocal.
	 */
	private void showCurrent() {
		ContextHolder.getActivity().setCurrent(ownerSession, current);
	}

	public Displayable getCurrent() {
		return current;
	}

	public void callSerially(Runnable r) {
		// Dung EventQueue cua slot so huu Display nay
		EventQueue eq = ownerSession != null ? ownerSession.eventQueue() : getEventQueue();
		eq.postEvent(RunnableEvent.getInstance(r));
	}

	public boolean flashBacklight(int duration) {
		return false;
	}

	/**
	 * @since MIDP 2.0
	 */
	public boolean vibrate(int duration) {
		return ContextHolder.vibrate(duration);
	}

	public void setCurrentItem(Item item) {
		if (item.hasOwnerForm()) {
			setCurrent(item.getOwnerForm());
		}
	}

	public int numAlphaLevels() {
		return 256;
	}

	public int numColors() {
		return Integer.MAX_VALUE;
	}

	public int getBestImageHeight(int imageType) {
		return 0;
	}

	public int getBestImageWidth(int imageType) {
		return 0;
	}

	public int getBorderStyle(boolean highlighted) {
		return highlighted ? Graphics.SOLID : Graphics.DOTTED;
	}

	public int getColor(int colorSpecifier) {
		return COLORS[colorSpecifier];
	}

	public boolean isColor() {
		return true;
	}
}
