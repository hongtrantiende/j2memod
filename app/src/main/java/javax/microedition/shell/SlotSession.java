package javax.microedition.shell;

import javax.microedition.lcdui.Displayable;

/**
 * Luu tru toan bo state cua 1 game slot dang chay.
 * Moi slot co index rieng, thread rieng, classloader rieng.
 */
public final class SlotSession {
    public final int slot;
    public String appName;
    public String appPath;
    public volatile MidletThread midletThread;
    public volatile MicroLoader microLoader;
    public volatile Displayable current;
    public volatile android.widget.FrameLayout container;
    private volatile int fpsLimit = -1;

    public SlotSession(int slot) {
        this.slot = slot;
    }

    public int getFpsLimit() { return fpsLimit; }
    public void setFpsLimit(int v) { this.fpsLimit = v; }
    public String getAppName() { return appName; }
    public String getAppPath() { return appPath; }
}