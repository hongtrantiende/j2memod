package namod.j2me;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import namod.j2me.applist.AppItem;
import namod.j2me.network.TabStatusManager;
import namod.j2me.util.AppUtils;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.overlay.OverlayView;
import javax.microedition.m3g.Texture2D;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;
import javax.microedition.util.ContextHolder;

public class FloatingBubbleService extends Service {
    private static final String CHANNEL_ID = "floating_bubble_channel";
    private static final String KEY_HEIGHT = "window_height";
    private static final String KEY_WIDTH = "window_width";
    private static final String KEY_X = "window_x";
    private static final String KEY_Y = "window_y";
    private static final int NOTIFICATION_ID = 101;
    private static final String PREFS_NAME = "floating_window_prefs";
    private static FloatingBubbleService instance;

    public static FloatingBubbleService getInstance() {
        return instance;
    }
    private View floatingView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams removeParams;
    private View removeView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private View windowView;

    public class BubbleTouchListener implements View.OnTouchListener {
        private float initialTouchX;
        private float initialTouchY;
        private int initialX;
        private int initialY;
        private long lastTouchTime;

        public BubbleTouchListener() {
        }

        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            int action = motionEvent.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                this.initialX = FloatingBubbleService.this.params.x;
                this.initialY = FloatingBubbleService.this.params.y;
                this.initialTouchX = motionEvent.getRawX();
                this.initialTouchY = motionEvent.getRawY();
                this.lastTouchTime = System.currentTimeMillis();
                FloatingBubbleService.this.removeView.setVisibility(View.VISIBLE);
                return true;
            }
            if (action != MotionEvent.ACTION_UP) {
                if (action != MotionEvent.ACTION_MOVE) {
                    return false;
                }
                FloatingBubbleService.this.params.x = this.initialX + ((int) (motionEvent.getRawX() - this.initialTouchX));
                FloatingBubbleService.this.params.y = this.initialY + ((int) (motionEvent.getRawY() - this.initialTouchY));
                FloatingBubbleService.this.windowManager.updateViewLayout(FloatingBubbleService.this.floatingView, FloatingBubbleService.this.params);
                return true;
            }
            FloatingBubbleService.this.removeView.setVisibility(View.GONE);
            float rawX = motionEvent.getRawX() - this.initialTouchX;
            float rawY = motionEvent.getRawY() - this.initialTouchY;
            double distance = Math.sqrt((rawY * rawY) + (rawX * rawX));
            int[] iArr = new int[2];
            FloatingBubbleService.this.removeView.getLocationOnScreen(iArr);
            int width = (FloatingBubbleService.this.removeView.getWidth() / 2) + iArr[0];
            int height = (FloatingBubbleService.this.removeView.getHeight() + iArr[1]) - FloatingBubbleService.this.dpToPx(50);
            float rawX2 = motionEvent.getRawX();
            float rawY2 = motionEvent.getRawY();
            if (Math.sqrt(Math.pow(rawY2 - height, 2.0d) + Math.pow(rawX2 - width, 2.0d)) < FloatingBubbleService.this.dpToPx(80)) {
                FloatingBubbleService.this.sendBroadcast(new Intent("namod.j2me.CLOSE_GAME"));
                Toast.makeText(FloatingBubbleService.this.getApplicationContext(), "Đang đóng game...", Toast.LENGTH_SHORT).show();
                if (FloatingBubbleService.this.floatingView != null) {
                    try {
                        FloatingBubbleService.this.windowManager.removeView(FloatingBubbleService.this.floatingView);
                    } catch (Exception unused) {
                    }
                    FloatingBubbleService.this.floatingView = null;
                }
                if (FloatingBubbleService.this.removeView != null) {
                    try {
                        FloatingBubbleService.this.windowManager.removeView(FloatingBubbleService.this.removeView);
                    } catch (Exception unused2) {
                    }
                    FloatingBubbleService.this.removeView = null;
                }
                FloatingBubbleService.this.stopSelf();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Process.killProcess(Process.myPid());
                    System.exit(0);
                }, 500L);
            } else if (distance < 10.0d && System.currentTimeMillis() - this.lastTouchTime < 300) {
                FloatingBubbleService.this.showFloatingWindow();
            }
            return true;
        }
    }

    private void createFloatingWindow() {
        ContextThemeWrapper themeContext = new ContextThemeWrapper(this, R.style.AppTheme);
        this.windowView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_window, null);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, 0);
        int width = sharedPreferences.getInt(KEY_WIDTH, dpToPx(320));
        int height = sharedPreferences.getInt(KEY_HEIGHT, dpToPx(Texture2D.WRAP_CLAMP));
        int x = sharedPreferences.getInt(KEY_X, 100);
        int y = sharedPreferences.getInt(KEY_Y, 100);
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(width, height, type, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, -3);
        this.windowParams = layoutParams;
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        layoutParams.gravity = 8388659;
        layoutParams.x = x;
        layoutParams.y = y;
        this.windowView.setVisibility(View.GONE);
        this.windowManager.addView(this.windowView, this.windowParams);
        setupWindowListeners();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "Floating Bubble Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(notificationChannel);
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void hideFloatingWindow() {
        Displayable currentDisplayable;
        Displayable.isFloatingMode = false;
        this.windowView.setVisibility(View.GONE);
        this.floatingView.setVisibility(View.VISIBLE);
        MicroActivity activity = ContextHolder.getActivity();
        if (activity == null || (currentDisplayable = MidletThread.getCurrentDisplayable()) == null) {
            return;
        }
        currentDisplayable.clearDisplayableView();
        View displayableView = currentDisplayable.getDisplayableView();
        FrameLayout frameLayout = (FrameLayout) activity.findViewById(R.id.displayable_container);
        if (frameLayout != null) {
            frameLayout.removeAllViews();
            frameLayout.addView(displayableView);
        }
        if (currentDisplayable instanceof Canvas) {
            Canvas canvas = (Canvas) currentDisplayable;
            canvas.setOverlayView((OverlayView) activity.findViewById(R.id.vOverlay));
            canvas.updateSize();
            canvas.repaint();
        }
    }

    private void closeWindowClicked(View view) {
        hideFloatingWindow();
    }

    private void maximizeWindowClicked(View view) {
        hideFloatingWindow();
        Intent intent = new Intent(this, MicroActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        stopSelf();
    }

    private static boolean onMenuCommandClick(Command[] commandArr, Displayable displayable, MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId < 0 || itemId >= commandArr.length) {
            return false;
        }
        displayable.menuItemSelected(commandArr[itemId].hashCode());
        return true;
    }

    private static void updateCanvasLayout(FrameLayout frameLayout, OverlayView overlayView, Canvas canvas, View view) {
        int width = frameLayout.getWidth();
        int height = frameLayout.getHeight();
        if (width > 0 && height > 0) {
            overlayView.setTargetBounds(new Rect(0, 0, width, height));
        }
        canvas.updateSize();
        canvas.repaint();
        view.requestLayout();
    }

    private void performUpdateDisplayable() {
        Displayable currentDisplayable = MidletThread.getCurrentDisplayable();
        if (currentDisplayable == null || this.windowView == null) {
            return;
        }
        Displayable.isFloatingMode = true;
        MidletThread.resumeApp();
        View displayableView = currentDisplayable.getDisplayableView();
        if (displayableView == null) {
            return;
        }
        displayableView.setVisibility(View.VISIBLE);
        if (displayableView.getParent() != null) {
            ((ViewGroup) displayableView.getParent()).removeView(displayableView);
        }
        FrameLayout frameLayout = (FrameLayout) this.windowView.findViewById(R.id.game_container);
        frameLayout.removeAllViews();
        frameLayout.addView(displayableView, new FrameLayout.LayoutParams(-1, -1));
        this.windowView.setVisibility(View.VISIBLE);
        this.floatingView.setVisibility(View.GONE);
        OverlayView overlayView = (OverlayView) this.windowView.findViewById(R.id.vOverlay);
        overlayView.clearLayers();

        LinearLayout commandBar = (LinearLayout) this.windowView.findViewById(R.id.floating_command_bar);
        if (commandBar != null) {
            commandBar.removeAllViews();
        }

        if (currentDisplayable instanceof Canvas) {
            if (commandBar != null) {
                commandBar.setVisibility(View.GONE);
            }
            displayableView.setBackgroundColor(0xFF000000);
            this.windowView.setBackgroundColor(0);
            frameLayout.setBackgroundColor(0);
            overlayView.setVisibility(View.VISIBLE);
            Canvas canvas = (Canvas) currentDisplayable;
            canvas.setOverlayView(overlayView);
            View innerView = canvas.getInnerView();
            if (innerView instanceof SurfaceView) {
                ((SurfaceView) innerView).setZOrderMediaOverlay(true);
            }
            displayableView.post(() -> updateCanvasLayout(frameLayout, overlayView, canvas, displayableView));
        } else {
            overlayView.setVisibility(View.GONE);
            displayableView.setBackgroundColor(0);
            frameLayout.setBackgroundColor(0);
            this.windowView.setBackgroundColor(0);
            displayableView.setFocusable(true);
            displayableView.setFocusableInTouchMode(true);
            displayableView.requestFocus();
            displayableView.invalidate();

            boolean isTabMode = false;
            if (currentDisplayable instanceof javax.microedition.lcdui.List) {
                try {
                    javax.microedition.lcdui.List list = (javax.microedition.lcdui.List) currentDisplayable;
                    String title = list.getTitle();
                    String firstItem = null;
                    try {
                        if (list.size() > 0) {
                            firstItem = list.getString(0);
                        }
                    } catch (Throwable ignored) {}
                    if (TabStatusManager.isTabMenu(title, firstItem)) {
                        isTabMode = true;
                    }
                } catch (Throwable ignored) {}
            }

            if (commandBar != null) {
                if (isTabMode) {
                    commandBar.setVisibility(View.GONE);
                } else {
                    Command[] commands = currentDisplayable.getCommands();
                    if (commands != null && commands.length > 0) {
                        commandBar.setVisibility(View.VISIBLE);

                        if (currentDisplayable instanceof javax.microedition.lcdui.TextBox) {
                            Button hideKbBtn = new Button(this);
                            hideKbBtn.setText("Ẩn phím");
                            hideKbBtn.setTextSize(12);
                            hideKbBtn.setTextColor(0xFFB0BEC5);
                            hideKbBtn.setBackgroundResource(R.drawable.bg_btn_cmd);
                            LinearLayout.LayoutParams lpKb = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(34));
                            lpKb.setMargins(dpToPx(4), 0, dpToPx(4), 0);
                            hideKbBtn.setLayoutParams(lpKb);
                            hideKbBtn.setOnClickListener(v -> hideSoftKeyboard());
                            commandBar.addView(hideKbBtn);
                        }

                        for (final Command cmd : commands) {
                            Button btn = new Button(this);
                            btn.setText(cmd.getAndroidLabel());
                            btn.setTextSize(13);
                            btn.setTextColor(0xFFFFFFFF);
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(34));
                            lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
                            btn.setLayoutParams(lp);

                            int cmdType = cmd.getCommandType();
                            if (cmdType == Command.OK || cmdType == Command.SCREEN) {
                                btn.setBackgroundResource(R.drawable.bg_btn_ok);
                                btn.setTypeface(null, android.graphics.Typeface.BOLD);
                            } else if (cmdType == Command.CANCEL || cmdType == Command.BACK || cmdType == Command.EXIT) {
                                btn.setBackgroundResource(R.drawable.bg_btn_cancel);
                            } else {
                                btn.setBackgroundResource(R.drawable.bg_btn_cmd);
                            }

                            btn.setOnClickListener(v -> {
                                hideSoftKeyboard();
                                currentDisplayable.menuItemSelected(cmd.hashCode());
                            });
                            commandBar.addView(btn);
                        }
                    } else {
                        commandBar.setVisibility(View.GONE);
                    }
                }
            }
        }
        this.windowView.requestLayout();
    }

    public void hideSoftKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && this.windowView != null) {
                View focus = this.windowView.findFocus();
                if (focus != null) {
                    imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                    focus.clearFocus();
                }
                imm.hideSoftInputFromWindow(this.windowView.getWindowToken(), 0);
                View moveHandle = this.windowView.findViewById(R.id.move_handle);
                if (moveHandle != null) {
                    moveHandle.setFocusableInTouchMode(true);
                    moveHandle.requestFocus();
                }
            }
        } catch (Throwable ignored) {}
    }

    private void triggerTabMenu() {
        Displayable currentDisplayable = MidletThread.getCurrentDisplayable();
        if (currentDisplayable instanceof Canvas) {
            Canvas canvas = (Canvas) currentDisplayable;
            Toast.makeText(this, "Đang mở danh sách Tab (*)...", Toast.LENGTH_SHORT).show();
            canvas.postKeyPressed(Canvas.KEY_STAR);
            this.handler.postDelayed(() -> {
                canvas.postKeyReleased(Canvas.KEY_STAR);
            }, 600L);
        } else {
            Toast.makeText(this, "Đang ở màn hình danh sách", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint({"ClickableViewAccessibility"})
    private void setupWindowListeners() {
        View tabBtn = this.windowView.findViewById(R.id.btn_tab_menu);
        ImageView imgTabIcon = this.windowView.findViewById(R.id.img_tab_icon);
        TextView tvTabText = this.windowView.findViewById(R.id.tv_tab_text);
        View moveHandle = this.windowView.findViewById(R.id.move_handle);
        View resizeHandle = this.windowView.findViewById(R.id.resize_handle);
        View hideKeyBtn = this.windowView.findViewById(R.id.btn_hide_keyboard);
        View closeBtn = this.windowView.findViewById(R.id.close_window);
        View maxBtn = this.windowView.findViewById(R.id.maximize_window);

        int accentColor = AppUtils.getAccentColor(this);
        if (tabBtn != null) {
            GradientDrawable tabBg = new GradientDrawable();
            tabBg.setCornerRadius(dpToPx(13));
            tabBg.setColor(Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            tabBg.setStroke(dpToPx(1), Color.argb(130, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            tabBtn.setBackground(tabBg);
        }
        if (tvTabText != null) {
            tvTabText.setTextColor(accentColor);
        }
        if (imgTabIcon != null) {
            imgTabIcon.setImageTintList(ColorStateList.valueOf(accentColor));
        }

        if (hideKeyBtn != null) {
            hideKeyBtn.setOnClickListener(v -> hideSoftKeyboard());
        }

        if (tabBtn != null) {
            tabBtn.setOnClickListener(v -> triggerTabMenu());
            tabBtn.setOnLongClickListener(v -> {
                // Short star press
                Displayable currentDisplayable = MidletThread.getCurrentDisplayable();
                if (currentDisplayable instanceof Canvas) {
                    Canvas canvas = (Canvas) currentDisplayable;
                    canvas.postKeyPressed(Canvas.KEY_STAR);
                    canvas.postKeyReleased(Canvas.KEY_STAR);
                    Toast.makeText(this, "Nhấn phím *", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        moveHandle.setOnTouchListener(new View.OnTouchListener() {
            private float initialTouchX;
            private float initialTouchY;
            private int initialX;
            private int initialY;

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int action = motionEvent.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    FloatingBubbleService.this.hideSoftKeyboard();
                    this.initialX = FloatingBubbleService.this.windowParams.x;
                    this.initialY = FloatingBubbleService.this.windowParams.y;
                    this.initialTouchX = motionEvent.getRawX();
                    this.initialTouchY = motionEvent.getRawY();
                    return true;
                }
                if (action != MotionEvent.ACTION_MOVE) {
                    return false;
                }
                FloatingBubbleService.this.windowParams.x = this.initialX + ((int) (motionEvent.getRawX() - this.initialTouchX));
                FloatingBubbleService.this.windowParams.y = this.initialY + ((int) (motionEvent.getRawY() - this.initialTouchY));
                FloatingBubbleService.this.windowManager.updateViewLayout(FloatingBubbleService.this.windowView, FloatingBubbleService.this.windowParams);
                FloatingBubbleService.this.getSharedPreferences(PREFS_NAME, 0).edit()
                        .putInt(KEY_X, FloatingBubbleService.this.windowParams.x)
                        .putInt(KEY_Y, FloatingBubbleService.this.windowParams.y)
                        .apply();
                return true;
            }
        });

        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialHeight;
            private float initialTouchX;
            private float initialTouchY;
            private int initialWidth;

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                int action = motionEvent.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    this.initialWidth = FloatingBubbleService.this.windowParams.width;
                    this.initialHeight = FloatingBubbleService.this.windowParams.height;
                    this.initialTouchX = motionEvent.getRawX();
                    this.initialTouchY = motionEvent.getRawY();
                    return true;
                }
                if (action != MotionEvent.ACTION_MOVE) {
                    return false;
                }
                int rawX = this.initialWidth + ((int) (motionEvent.getRawX() - this.initialTouchX));
                int rawY = this.initialHeight + ((int) (motionEvent.getRawY() - this.initialTouchY));
                if (rawX < FloatingBubbleService.this.dpToPx(160)) {
                    rawX = FloatingBubbleService.this.dpToPx(160);
                }
                if (rawY < FloatingBubbleService.this.dpToPx(160)) {
                    rawY = FloatingBubbleService.this.dpToPx(160);
                }
                FloatingBubbleService.this.windowParams.width = rawX;
                FloatingBubbleService.this.windowParams.height = rawY;
                FloatingBubbleService.this.windowManager.updateViewLayout(FloatingBubbleService.this.windowView, FloatingBubbleService.this.windowParams);
                FloatingBubbleService.this.getSharedPreferences(PREFS_NAME, 0).edit()
                        .putInt(KEY_WIDTH, rawX)
                        .putInt(KEY_HEIGHT, rawY)
                        .apply();
                if (MidletThread.getCurrentDisplayable() instanceof Canvas) {
                    View gameContainer = FloatingBubbleService.this.windowView.findViewById(R.id.game_container);
                    int width = gameContainer.getWidth();
                    int height = gameContainer.getHeight();
                    if (width > 0 && height > 0) {
                        OverlayView overlayView = (OverlayView) FloatingBubbleService.this.windowView.findViewById(R.id.vOverlay);
                        overlayView.setTargetBounds(new Rect(0, 0, width, height));
                        overlayView.postInvalidate();
                    }
                }
                return true;
            }
        });

        closeBtn.setOnClickListener(this::closeWindowClicked);
        maxBtn.setOnClickListener(this::maximizeWindowClicked);
    }

    private void showFloatingWindow() {
        if (this.windowView.getVisibility() == View.VISIBLE) {
            return;
        }
        updateDisplayable();
    }

    private void showJ2MEMenu(View view) {
        final Displayable currentDisplayable = MidletThread.getCurrentDisplayable();
        if (currentDisplayable == null) {
            return;
        }
        PopupMenu popupMenu = new PopupMenu(this, view);
        final Command[] commands = currentDisplayable.getCommands();
        for (int i5 = 0; i5 < commands.length; i5++) {
            popupMenu.getMenu().add(0, i5, 0, commands[i5].getAndroidLabel());
        }
        popupMenu.setOnMenuItemClickListener(menuItem -> onMenuCommandClick(commands, currentDisplayable, menuItem));
        popupMenu.show();
    }

    private void updateDisplayable() {
        this.handler.post(this::performUpdateDisplayable);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressLint({"ClickableViewAccessibility"})
    public void onCreate() {
        super.onCreate();
        instance = this;
        this.windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("J2ME Loader")
                    .setContentText("Bong bóng chat đang hoạt động")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e("FloatingBubbleService", "Failed startForeground", t);
        }

        ContextThemeWrapper themeContext = new ContextThemeWrapper(this, R.style.AppTheme);
        this.floatingView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_bubble, null);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(-2, -2, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, -3);
        this.params = layoutParams;
        layoutParams.gravity = 8388659;
        layoutParams.x = 0;
        layoutParams.y = 100;
        this.windowManager.addView(this.floatingView, layoutParams);

        this.removeView = LayoutInflater.from(themeContext).inflate(R.layout.layout_remove_bubble, null);
        WindowManager.LayoutParams layoutParams2 = new WindowManager.LayoutParams(-1, -2, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, -3);
        this.removeParams = layoutParams2;
        layoutParams2.gravity = 81;
        this.removeView.setVisibility(View.GONE);
        this.windowManager.addView(this.removeView, this.removeParams);

        createFloatingWindow();

        ImageView imageView = (ImageView) this.floatingView.findViewById(R.id.bubble_icon);
        if (Build.VERSION.SDK_INT >= 21) {
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(12));
                }
            });
            imageView.setClipToOutline(true);
        }
        try {
            MicroActivity activity = ContextHolder.getActivity();
            if (activity != null) {
                String appPath = activity.getIntent().getStringExtra(MainActivity.APP_PATH_KEY);
                if (appPath != null) {
                    AppItem appItem = AppUtils.getApp(appPath);
                    if (appItem != null) {
                        java.io.File iconFile = new java.io.File(appItem.getImagePathExt());
                        if (iconFile.isFile()) {
                            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                            if (bmp != null) {
                                imageView.setImageBitmap(bmp);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        imageView.setOnTouchListener(new BubbleTouchListener());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Displayable.isFloatingMode = false;
        if (this.floatingView != null) {
            try {
                this.windowManager.removeView(this.floatingView);
            } catch (Exception unused) {
            }
        }
        if (this.removeView != null) {
            try {
                this.windowManager.removeView(this.removeView);
            } catch (Exception unused2) {
            }
        }
        if (this.windowView != null) {
            try {
                this.windowManager.removeView(this.windowView);
            } catch (Exception unused3) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        if ("ACTION_HIDE_WINDOW".equals(action)) {
            hideFloatingWindow();
            stopSelf();
            return START_STICKY;
        }
        if (!"ACTION_UPDATE_DISPLAYABLE".equals(action) || this.windowView == null || this.windowView.getVisibility() != View.VISIBLE) {
            return START_STICKY;
        }
        updateDisplayable();
        return START_STICKY;
    }
}
