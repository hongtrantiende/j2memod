package javax.microedition.shell;

/**
 * Gioi han FPS cho cac slot dang chay ngam (khong phai focused).
 * Tuong tu NST SlotThrottle.
 */
public final class SlotThrottle {
    private static volatile int backgroundFps = 10;

    private SlotThrottle() {}

    /**
     * Tra ve FPS cap cho slot nay. 0 = khong gioi han (fps thiet lap).
     * Slot focused chay full FPS, slot ngam cap o backgroundFps.
     */
    public static int capFor(int slot) {
        if (SlotRegistry.count() <= 1 || slot == SlotRegistry.getFocusedSlot()) {
            return 0; // Khong gioi han
        }
        return backgroundFps;
    }

    public static int getBackgroundFps() { return backgroundFps; }
    public static void setBackgroundFps(int fps) { backgroundFps = fps; }
}