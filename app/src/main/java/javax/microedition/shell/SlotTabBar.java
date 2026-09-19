package javax.microedition.shell;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Thanh tab phia duoi MicroActivity: [1][2][3] ... [X][+][++]
 * Tuong tu NST SlotTabBar.
 */
public final class SlotTabBar extends LinearLayout {
    public static final int HEIGHT_DP = 40;
    private static final int BTN_WIDTH_DP = 40;
    private static final int TAB_WIDTH_DP = 38;
    private static final int COLOR_BAR = 0xFF1A1A2E;
    private static final int COLOR_TAB_ON = 0xFF00E5FF;
    private static final int COLOR_TAB_OFF = 0xFF2A2A4A;
    private static final int COLOR_TEXT_ON = 0xFF000000;
    private static final int COLOR_TEXT_OFF = 0xFFAAAAAA;

    public interface Listener {
        void onTabSelected(int index);
        void onAddOne();
        void onAddMany();
        void onCloseCurrent();
    }

    private final Listener listener;
    private final LinearLayout tabs;
    private final HorizontalScrollView scroller;
    private final int tabWidthPx;
    private int[] shownLabels = new int[0];
    private int shownFocused = -1;

    public SlotTabBar(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.tabWidthPx = dp(ctx, TAB_WIDTH_DP);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(COLOR_BAR);

        LinearLayout tabRow = new LinearLayout(ctx);
        this.tabs = tabRow;
        tabRow.setOrientation(HORIZONTAL);

        HorizontalScrollView sv = new HorizontalScrollView(ctx);
        this.scroller = sv;
        sv.setHorizontalScrollBarEnabled(false);
        sv.setFillViewport(false);
        sv.addView(tabRow, new LayoutParams(-2, -1));
        addView(sv, new LayoutParams(0, -1, 1f));

        addView(btn(ctx, "X", v -> listener.onCloseCurrent()));
        addView(btn(ctx, "+", v -> listener.onAddOne()));
        addView(btn(ctx, "++", v -> listener.onAddMany()));
    }

    private TextView btn(Context ctx, String text, View.OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setClickable(true);
        tv.setOnClickListener(click);
        tv.setLayoutParams(new LayoutParams(dp(getContext(), BTN_WIDTH_DP), -1));
        return tv;
    }

    private TextView makeChip(Context ctx, final int index) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setOnClickListener(v -> listener.onTabSelected(index));
        tv.setLayoutParams(new LayoutParams(tabWidthPx, -1));
        return tv;
    }

    private void paint(int i, boolean focused) {
        if (i < 0 || i >= tabs.getChildCount()) return;
        TextView tv = (TextView) tabs.getChildAt(i);
        if (focused) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(getContext(), 6));
            bg.setColor(COLOR_TAB_ON);
            tv.setBackground(bg);
            tv.setTextColor(COLOR_TEXT_ON);
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(getContext(), 6));
            bg.setColor(COLOR_TAB_OFF);
            tv.setBackground(bg);
            tv.setTextColor(COLOR_TEXT_OFF);
            tv.setTypeface(null, Typeface.NORMAL);
        }
    }

    public void refresh(int[] labels, int focused) {
        if (labels == null) labels = new int[0];
        int len = labels.length;
        if (focused < 0 || focused >= len) focused = len > 0 ? len - 1 : -1;

        // Chi update highlight neu labels khong thay doi
        if (java.util.Arrays.equals(labels, shownLabels)) {
            if (focused == shownFocused) return;
            paint(shownFocused, false);
            paint(focused, true);
            shownFocused = focused;
            scrollToFocused(focused);
            return;
        }

        // Xoa bo cac chip thua
        while (tabs.getChildCount() > len) tabs.removeViewAt(tabs.getChildCount() - 1);
        // Them chip moi
        Context ctx = getContext();
        for (int i = tabs.getChildCount(); i < len; i++) tabs.addView(makeChip(ctx, i));

        shownLabels = labels.clone();
        shownFocused = focused;
        for (int i = 0; i < len; i++) {
            ((TextView) tabs.getChildAt(i)).setText(String.valueOf(labels[i]));
            paint(i, i == focused);
        }
        scrollToFocused(focused);
    }

    private void scrollToFocused(final int focused) {
        if (focused < 0) return;
        scroller.post(() -> scroller.smoothScrollTo(
            Math.max(0, focused * tabWidthPx - scroller.getWidth() / 2 + tabWidthPx / 2), 0));
    }

    public static int heightPx(Context ctx) {
        return dp(ctx, HEIGHT_DP);
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}