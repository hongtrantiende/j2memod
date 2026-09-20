/*
 * SlotSession - Moi tab game = 1 SlotSession doc lap
 * Chua toan bo state: MidletThread, ClassLoader, RMS, Container, Display, etc.
 * Thiet ke theo kien truc NST: moi slot chay doc lap, du lieu rieng biet.
 */
package javax.microedition.shell;

import android.widget.FrameLayout;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.rms.impl.AndroidRecordStoreManager;

public final class SlotSession {
    public final int slot;

    // Game state - moi slot co cac truong rieng
    String appId;
    String appName;
    String appPath;
    String dataDir;

    volatile FrameLayout container;   // SlotCell chua game view
    volatile Displayable current;
    volatile MicroLoader microLoader;
    volatile MidletThread midletThread;

    Display display;
    AppClassLoader classLoader;
    Map<String, String> properties;

    private AndroidRecordStoreManager rms;
    private EventQueue slotEventQueue;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Map<String, String> systemProperties = new HashMap<>();
    private volatile int fpsLimit = -1;

    public SlotSession(int slot) {
        this.slot = slot;
    }

    /** Khoi tao thong tin app cho slot nay */
    public void initializeApp(String appPath, String appName, String dataDir) {
        this.appPath = appPath;
        this.appName = appName;
        this.dataDir = dataDir;
        try {
            this.appId = new File(appPath).getCanonicalPath();
        } catch (IOException unused) {
            this.appId = new File(appPath).getAbsolutePath();
        }
    }

    public String getAppId() { return appId; }
    public String getAppName() { return appName; }
    public String getAppPath() { return appPath; }
    public String getDataDir() { return dataDir; }

    public FrameLayout getContainer() { return container; }
    public void setContainer(FrameLayout container) { this.container = container; }

    public Displayable getCurrent() { return current; }
    public void setCurrent(Displayable d) { this.current = d; }

    public Display getDisplay() { return display; }
    public void setDisplay(Display display) { this.display = display; }

    public int getFpsLimit() { return fpsLimit; }
    public void setFpsLimit(int fps) { this.fpsLimit = fps; }

    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> props) { this.properties = props; }

    public synchronized String getSystemProperty(String key) {
        return systemProperties.get(key);
    }
    public synchronized void setSystemProperty(String key, String value) {
        systemProperties.put(key, value);
    }

    /** Lay RMS rieng cho slot nay */
    public synchronized AndroidRecordStoreManager rms() {
        if (rms == null) {
            rms = new AndroidRecordStoreManager();
        }
        return rms;
    }

    /** Lay EventQueue rieng cho slot nay (NST pattern) */
    public synchronized EventQueue eventQueue() {
        if (slotEventQueue == null) {
            slotEventQueue = new EventQueue();
            slotEventQueue.startProcessing();
        }
        return slotEventQueue;
    }

    public boolean isClosing() {
        return closing.get();
    }

    /** Tat tat ca tai nguyen cua slot nay */
    public void shutdownResources() {
        if (closing.compareAndSet(false, true)) {
            // Quit MidletThread
            MidletThread mt = midletThread;
            if (mt != null) {
                mt.quitSafely();
                midletThread = null;
            }
        }
    }
}