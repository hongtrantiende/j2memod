package namod.j2me.tabs;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TabManager {
    private static final String TAG = "TabManager";
    private static TabManager INSTANCE;

    public static class GameTab {
        public final int taskId;
        public final int slotIndex;
        public String label;
        public GameTab(int taskId, int slotIndex, String label) {
            this.taskId = taskId;
            this.slotIndex = slotIndex;
            this.label = label;
        }
    }

    public interface TabListener {
        void onTabsChanged(List<GameTab> tabs);
    }

    private final List<GameTab> tabs = new ArrayList<>();
    private final List<TabListener> listeners = new CopyOnWriteArrayList<>();

    private TabManager() {}

    public static synchronized TabManager get() {
        if (INSTANCE == null) INSTANCE = new TabManager();
        return INSTANCE;
    }

    public synchronized void addTab(int taskId, int slotIndex, String label) {
        for (GameTab t : tabs) {
            if (t.taskId == taskId) { t.label = label; notifyListeners(); return; }
        }
        tabs.add(new GameTab(taskId, slotIndex, label));
        Log.i(TAG, "addTab taskId=" + taskId + " slot=" + slotIndex);
        notifyListeners();
    }

    public synchronized void removeTab(int taskId) {
        tabs.removeIf(t -> t.taskId == taskId);
        Log.i(TAG, "removeTab taskId=" + taskId + " remaining=" + tabs.size());
        notifyListeners();
    }

    public synchronized List<GameTab> getTabs() { return new ArrayList<>(tabs); }
    public synchronized int getTabCount() { return tabs.size(); }

    public synchronized int nextSlot() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (GameTab t : tabs) used.add(t.slotIndex);
        int s = 0;
        while (used.contains(s)) s++;
        return s;
    }

    public void switchToTab(Context ctx, int taskId) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        try { am.moveTaskToFront(taskId, 0); }
        catch (Exception e) { Log.w(TAG, "switchToTab: " + e.getMessage()); }
    }

    public synchronized void switchToIndex(Context ctx, int index) {
        if (index < 0 || index >= tabs.size()) return;
        switchToTab(ctx, tabs.get(index).taskId);
    }

    public void addListener(TabListener l) { listeners.add(l); }
    public void removeListener(TabListener l) { listeners.remove(l); }

    private void notifyListeners() {
        List<GameTab> snapshot = new ArrayList<>(tabs);
        for (TabListener l : listeners) {
            try { l.onTabsChanged(snapshot); } catch (Exception ignored) {}
        }
    }
}