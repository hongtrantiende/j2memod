package javax.microedition.shell;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * View dai dien cho 1 game slot trong slotHost.
 * Khi chinh: hien thi game. Khi an: game tiep tuc chay ngam.
 */
public final class SlotCell extends FrameLayout {
    public final int slot;
    private TextView placeholder;
    private final OnEmptyTap onEmptyTap;

    public interface OnEmptyTap {
        void onEmptyCell(int slot);
    }

    public SlotCell(Context context, int slot, OnEmptyTap onEmptyTap) {
        super(context);
        this.slot = slot;
        this.onEmptyTap = onEmptyTap;
        showPlaceholder();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (isEmpty()) {
                if (onEmptyTap != null) onEmptyTap.onEmptyCell(slot);
                return true;
            }
            SlotSession session = SlotRegistry.get(slot);
            if (session != null) SlotRegistry.bind(session);
        }
        return super.dispatchTouchEvent(ev);
    }

    public boolean isEmpty() { return placeholder != null; }

    public void clearPlaceholder() {
        if (placeholder != null) {
            removeView(placeholder);
            placeholder = null;
        }
    }

    public void showPlaceholder() {
        if (placeholder != null) return;
        TextView tv = new TextView(getContext());
        placeholder = tv;
        tv.setText("Tab " + (slot + 1) + " - Cham de mo game");
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundColor(0xFF1A1A2E);
        addView(tv, new FrameLayout.LayoutParams(-1, -1));
    }
}