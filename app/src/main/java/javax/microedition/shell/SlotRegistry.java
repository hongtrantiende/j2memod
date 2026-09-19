package javax.microedition.shell;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quan ly toan bo SlotSession dang hoat dong.
 * Thay the TabManager cu.
 */
public final class SlotRegistry {
    private static final String TAG = "SlotRegistry";

    /** Bind slot hien tai cho thread game (InheritableThreadLocal de child thread inherit) */
    private static final InheritableThreadLocal<SlotSession> CURRENT = new InheritableThreadLocal<>();

    private static final ConcurrentHashMap<Integer, SlotSession> SESSIONS = new ConcurrentHashMap<>();

    private static volatile int focusedSlot = 0;
    private static volatile boolean multiSlot = false;
    /** Khi chi co 1 slot, day la tham chieu nhanh */
    private static volatile SlotSession sole = null;

    public interface Listener {
        void onSlotsChanged(List<SlotSession> sessions, int focusedSlot);
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private SlotRegistry() {}

    public static synchronized SlotSession create(int slot) {
        if (SESSIONS.containsKey(slot)) {
            throw new IllegalStateException("Slot " + slot + " da duoc su dung");
        }
        SlotSession session = new SlotSession(slot);
        SESSIONS.put(slot, session);
        updateSole();
        Log.i(TAG, "create slot=" + slot + " total=" + SESSIONS.size());
        notifyListeners();
        return session;
    }

    public static void remove(SlotSession session) {
        SESSIONS.remove(session.slot);
        updateSole();
        Log.i(TAG, "remove slot=" + session.slot + " remaining=" + SESSIONS.size());
        notifyListeners();
    }

    public static SlotSession get(int slot) {
        return SESSIONS.get(slot);
    }

    public static void bind(SlotSession session) {
        CURRENT.set(session);
    }

    /** Session cua thread hien tai (null = chua bind) */
    public static SlotSession bound() {
        return CURRENT.get();
    }

    /** Session cua thread hien tai, fallback ve sole neu chua bind */
    public static SlotSession current() {
        SlotSession s = CURRENT.get();
        return s != null ? s : sole;
    }

    public static SlotSession focused() {
        SlotSession s = SESSIONS.get(focusedSlot);
        return s != null ? s : sole;
    }

    public static int getFocusedSlot() { return focusedSlot; }

    public static void setFocusedSlot(int slot) {
        focusedSlot = slot;
        notifyListeners();
    }

    public static boolean isMultiSlot() { return multiSlot; }

    public static void setMultiSlot(boolean v) {
        multiSlot = v;
    }

    public static int count() { return SESSIONS.size(); }

    public static List<SlotSession> all() {
        ArrayList<SlotSession> list = new ArrayList<>(SESSIONS.values());
        list.sort((a, b) -> Integer.compare(a.slot, b.slot));
        return list;
    }

    public static int nextFreeSlot() {
        for (int i = 0; i < 100; i++) {
            if (!SESSIONS.containsKey(i)) return i;
        }
        return -1;
    }

    public static void addListener(Listener l) { LISTENERS.add(l); }
    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    private static void updateSole() {
        if (SESSIONS.size() == 1) {
            sole = SESSIONS.values().iterator().next();
        } else {
            sole = null;
        }
    }

    private static void notifyListeners() {
        List<SlotSession> snap = all();
        int focused = focusedSlot;
        for (Listener l : LISTENERS) {
            try { l.onSlotsChanged(snap, focused); } catch (Exception ignored) {}
        }
    }
}