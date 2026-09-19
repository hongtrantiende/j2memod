/*
 * SlotRegistry - Quan ly trung tam cho tat ca slot sessions.
 * Dung InheritableThreadLocal de moi thread game tu biet minh thuoc slot nao.
 * Theo kien truc NST.
 */
package javax.microedition.shell;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class SlotRegistry {
    /** Moi thread tu biet slot cua minh (ke ca child threads) */
    private static final InheritableThreadLocal<SlotSession> CURRENT = new InheritableThreadLocal<>();

    /** Tat ca sessions dang chay */
    private static final ConcurrentHashMap<Integer, SlotSession> SESSIONS = new ConcurrentHashMap<>();

    /** Slot dang duoc hien thi phia truoc */
    private static volatile int focusedSlot;

    /** Khi chi co duy nhat 1 session */
    private static volatile SlotSession sole;

    private SlotRegistry() {}

    // === Thread-local binding ===

    /** Gan session cho thread hien tai (goi trong MidletThread.handleMessage) */
    public static void bind(SlotSession session) {
        CURRENT.set(session);
    }

    /** Huy gan session cho thread hien tai */
    public static void unbind() {
        CURRENT.remove();
    }

    /**
     * Lay session cua thread hien tai.
     * Fallback ve sole session neu chi co 1 slot.
     */
    public static SlotSession current() {
        SlotSession s = CURRENT.get();
        return s != null ? s : sole;
    }

    /**
     * Lay session cua thread hien tai (chi ThreadLocal, khong fallback).
     */
    public static SlotSession bound() {
        return CURRENT.get();
    }

    // === Session management ===

    /** Tao slot moi. Throw neu slot da ton tai. */
    public static synchronized SlotSession create(int slot) {
        SlotSession session = new SlotSession(slot);
        if (SESSIONS.putIfAbsent(slot, session) != null) {
            throw new IllegalStateException("Slot " + slot + " đang được dùng");
        }
        updateSole();
        return session;
    }

    /** Xoa slot. Return true neu xoa thanh cong. */
    public static synchronized boolean remove(SlotSession session) {
        if (session == null) return false;
        if (SESSIONS.remove(session.slot, session)) {
            if (CURRENT.get() == session) {
                CURRENT.remove();
            }
            updateSole();
            return true;
        }
        return false;
    }

    /** Lay session theo slot index */
    public static SlotSession get(int slot) {
        return SESSIONS.get(slot);
    }

    /** Lay tat ca sessions, sap xep theo slot */
    public static ArrayList<SlotSession> all() {
        ArrayList<SlotSession> list = new ArrayList<>(SESSIONS.values());
        list.sort((a, b) -> Integer.compare(a.slot, b.slot));
        return list;
    }

    /** So luong slot dang chay */
    public static int count() {
        return SESSIONS.size();
    }

    // === Focus management ===

    /** Lay session dang duoc focus */
    public static SlotSession focused() {
        SlotSession s = SESSIONS.get(focusedSlot);
        return s != null ? s : sole;
    }

    public static int getFocusedSlot() {
        return focusedSlot;
    }

    public static void setFocusedSlot(int slot) {
        focusedSlot = slot;
    }

    // === Internal ===

    private static void updateSole() {
        sole = SESSIONS.size() == 1 ? SESSIONS.values().iterator().next() : null;
    }

    /** Tim slot chua su dung nho nhat */
    public static int nextFreeSlot() {
        for (int i = 0; i < 100; i++) {
            if (!SESSIONS.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }
}