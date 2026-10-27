package com.autotapper.clicker;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accessibility service that owns the floating toolbar, the click targets,
 * the swipe markers and the playback engine.
 * An enabled accessibility service is started by Android automatically at boot
 * and kept alive in the background - that is what gives "start on boot".
 */
public class ClickerService extends AccessibilityService {

    static ClickerService instance;

    // ---------- state ----------
    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler run = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Profile p = new Profile();
    private String currentName = null;
    private final Set<String> homePkgs = new HashSet<>();

    private boolean uiVisible = false;
    private int uiState = 0;           // 0 = only the main button, 1 = toolbar + targets shown
    private int mainX = -1, mainY = -1;
    private int mode = 0;          // 0 idle, 1 clicking, 2 swiping
    private int runId = 0;
    private int idx = 0, cycle = 0, swipeCount = 0;

    // ---------- windows ----------
    private TextView mainView;
    private WindowManager.LayoutParams mainLp;
    private LinearLayout barView;
    private WindowManager.LayoutParams barLp;
    private LinearLayout panel;
    private final List<Node> tNodes = new ArrayList<>();
    private Node aNode, bNode;

    private static final int BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

    /** One draggable circle on screen: a click target (kind 0) or swipe end A (1) / B (2). */
    private class Node {
        int kind;
        Profile.Target t;
        TextView v;
        WindowManager.LayoutParams lp;
        boolean attached;

        int cx() { return lp.x + lp.width / 2; }
        int cy() { return lp.y + lp.height / 2; }
    }

    interface IntCb { void on(int v); }

    // =====================================================================
    // lifecycle
    // =====================================================================
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);

        homePkgs.clear();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL)) {
            if (ri.activityInfo != null) homePkgs.add(ri.activityInfo.packageName);
        }

        String last = prefs.getString("last", null);
        if (last != null) {
            Profile loaded = Profile.load(this, last);
            if (loaded != null) {
                p = loaded;
                currentName = last;
            }
        }
        if (prefs.getBoolean("keepalive", true)) KeepAliveService.start(this);
        mainX = prefs.getInt("mx", -1);
        mainY = prefs.getInt("my", -1);
        uiState = prefs.getInt("ui", 0);
        if (prefs.getBoolean("autoshow", true)) showUi();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        // Tapping the home screen stops the click run (swipe run only stops with the play button).
        if (mode == 1 && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pk = e.getPackageName();
            if (pk != null && homePkgs.contains(pk.toString())) {
                stopRun();
                toast("Stopped (home)");
            }
        }
    }

    @Override
    public void onInterrupt() { stopRun(); }

    @Override
    public boolean onUnbind(Intent intent) {
        hideUi();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideUi();
        instance = null;
        super.onDestroy();
    }

    // called from MainActivity
    void showUi() {
        if (uiVisible) return;
        uiVisible = true;
        rebuildAll();
    }

    void hideUi() {
        stopRun();
        removeAllWindows();
        uiVisible = false;
    }

    boolean isUiVisible() { return uiVisible; }

    // =====================================================================
    // small helpers
    // =====================================================================
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int screenW() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenH() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private WindowManager.LayoutParams newLp(int w, int h, int x, int y, int extraFlags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                BASE_FLAGS | extraFlags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private GradientDrawable shape(int color, int kind, int sizePx, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        float r = kind == 0 ? sizePx / 2f : kind == 1 ? sizePx * 0.25f : 0f;
        g.setCornerRadius(r);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    private void addWin(View v, WindowManager.LayoutParams lp) {
        try { wm.addView(v, lp); } catch (Exception ignored) { }
    }

    private void removeWin(View v) {
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    private void updWin(View v, WindowManager.LayoutParams lp) {
        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
    }

    private String shapeName() { return p.btnShape == 0 ? "Circle" : p.btnShape == 1 ? "Rounded" : "Square"; }

    // =====================================================================
    // build / tear down windows
    // =====================================================================
    private void rebuildAll() {
        stopRun();
        removeAllWindows();
        tNodes.clear();
        aNode = bNode = null;
        for (Profile.Target t : p.targets) tNodes.add(makeNode(0, t, t.cx, t.cy, t.size));
        if (p.swipe != null) createSwipeNodes();
        refreshNumbers();
        buildMain();
        buildBar();
        applyVisibility();
    }

    private void removeAllWindows() {
        closePanel();
        if (mainView != null) { removeWin(mainView); mainView = null; }
        if (barView != null) { removeWin(barView); barView = null; }
        for (Node n : tNodes) detach(n);
        detach(aNode);
        detach(bNode);
    }

    /** targets / swipe points / toolbar show only in edit state while idle; the job type picks which nodes. */
    private void applyVisibility() {
        boolean edit = uiState == 1 && mode == 0;
        for (Node n : tNodes) { if (edit && p.mode == 0) attach(n); else detach(n); }
        if (edit && p.mode == 1) { attach(aNode); attach(bNode); } else { detach(aNode); detach(bNode); }
        if (barView != null) {
            if (edit && barView.getParent() == null) addWin(barView, barLp);
            if (!edit && barView.getParent() != null) removeWin(barView);
        }
        if (!edit) closePanel();
        paintMain();
    }

    private void attach(Node n) {
        if (n == null || n.attached) return;
        n.lp.x = clamp(n.lp.x, 0, Math.max(0, screenW() - n.lp.width));
        n.lp.y = clamp(n.lp.y, 0, Math.max(0, screenH() - n.lp.height));
        addWin(n.v, n.lp);
        n.attached = true;
    }

    private void detach(Node n) {
        if (n == null || !n.attached) return;
        removeWin(n.v);
        n.attached = false;
    }

    private void attachAllNodes() {
        for (Node n : tNodes) attach(n);
        attach(aNode);
        attach(bNode);
    }

    private void detachAllNodes() {
        for (Node n : tNodes) detach(n);
        detach(aNode);
        detach(bNode);
    }

    // ---------- nodes ----------
    private Node makeNode(int kind, Profile.Target t, int cx, int cy, int sizeDp) {
        final Node n = new Node();
        n.kind = kind;
        n.t = t;
        int px = dp(sizeDp);
        n.lp = newLp(px, px, cx - px / 2, cy - px / 2, 0);
        n.v = new TextView(this);
        n.v.setGravity(Gravity.CENTER);
        n.v.setTextColor(Color.WHITE);
        n.v.setTypeface(Typeface.DEFAULT_BOLD);
        n.v.setIncludeFontPadding(false);
        n.v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(8, Math.min(18, sizeDp * 0.35f)));
        styleNode(n);
        n.v.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int slop = ViewConfiguration.get(ClickerService.this).getScaledTouchSlop();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY();
                        ox = n.lp.x; oy = n.lp.y;
                        moved = false;
                        closePanel();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > slop) moved = true;
                        if (moved) {
                            n.lp.x = clamp((int) (ox + e.getRawX() - sx), 0, screenW() - n.lp.width);
                            n.lp.y = clamp((int) (oy + e.getRawY() - sy), 0, screenH() - n.lp.height);
                            updWin(n.v, n.lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) openNodeMenu(n); else syncPositions();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return n;
    }

    private void styleNode(Node n) {
        int fill, stroke = 0xFFFFFFFF;
        if (n.kind == 0) fill = 0xAA1E88E5;
        else if (n.kind == 1) fill = 0xAAFF9800;
        else fill = 0xAAE53935;
        n.v.setBackground(shape(fill, p.btnShape, n.lp.width, stroke, dp(1.5f)));
        if (n.kind == 1) n.v.setText("A");
        if (n.kind == 2) n.v.setText("B");
    }

    private void refreshNumbers() {
        for (int i = 0; i < tNodes.size(); i++) tNodes.get(i).v.setText(String.valueOf(i + 1));
    }

    private void createSwipeNodes() {
        Profile.Swipe s = p.swipe;
        aNode = makeNode(1, null, s.x1, s.y1, 32);
        bNode = makeNode(2, null, s.x2, s.y2, 32);
    }

    /** copy on-screen positions back into the profile data */
    private void syncPositions() {
        for (Node n : tNodes) { n.t.cx = n.cx(); n.t.cy = n.cy(); }
        if (p.swipe != null && aNode != null && bNode != null) {
            p.swipe.x1 = aNode.cx(); p.swipe.y1 = aNode.cy();
            p.swipe.x2 = bNode.cx(); p.swipe.y2 = bNode.cy();
        }
    }

    private void setTouchable(boolean touchable) {
        List<Node> all = new ArrayList<>(tNodes);
        if (aNode != null) all.add(aNode);
        if (bNode != null) all.add(bNode);
        for (Node n : all) {
            if (touchable) n.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else n.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            n.v.setAlpha(touchable ? 1f : 0.45f);
            if (n.attached) updWin(n.v, n.lp);
        }
    }

    // =====================================================================
    // the one main button + bottom toolbar
    // =====================================================================
    private TextView tbBtn(String txt, int bg, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setIncludeFontPadding(false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(11, p.btnSize * 0.4f));
        t.setPadding(dp(8), 0, dp(8), 0);
        t.setMinWidth(dp(p.btnSize));
        t.setBackground(shape(bg, p.btnShape, dp(p.btnSize), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(p.btnSize));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        t.setLayoutParams(lp);
        if (c != null) t.setOnClickListener(c);
        return t;
    }

    private void buildMain() {
        int sz = dp(p.mainSize);
        if (mainX < 0 || mainY < 0) { mainX = screenW() - sz - dp(12); mainY = screenH() * 62 / 100; }
        mainView = new TextView(this);
        mainView.setGravity(Gravity.CENTER);
        mainView.setTextColor(Color.WHITE);
        mainView.setIncludeFontPadding(false);
        mainView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, p.mainSize * 0.48f);
        mainLp = newLp(sz, sz, clamp(mainX, 0, Math.max(0, screenW() - sz)),
                clamp(mainY, 0, Math.max(0, screenH() - sz)), 0);
        // tap = start / stop, long press = toolbar on / off, drag = move (position is remembered)
        mainView.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved, longFired;
            final Runnable lp = new Runnable() {
                @Override public void run() {
                    if (!moved && mode == 0) { longFired = true; setUi(uiState == 1 ? 0 : 1); }
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int slop = ViewConfiguration.get(ClickerService.this).getScaledTouchSlop();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY();
                        ox = mainLp.x; oy = mainLp.y;
                        moved = false; longFired = false;
                        closePanel();
                        ui.postDelayed(lp, 600);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > slop) {
                            moved = true;
                            ui.removeCallbacks(lp);
                        }
                        if (moved) {
                            mainLp.x = clamp((int) (ox + e.getRawX() - sx), 0, Math.max(0, screenW() - mainLp.width));
                            mainLp.y = clamp((int) (oy + e.getRawY() - sy), 0, Math.max(0, screenH() - mainLp.height));
                            updWin(mainView, mainLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(lp);
                        if (moved) {
                            mainX = mainLp.x; mainY = mainLp.y;
                            prefs.edit().putInt("mx", mainX).putInt("my", mainY).apply();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP && !longFired) {
                            if (mode != 0) stopRun(); else startJob();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        addWin(mainView, mainLp);
        paintMain();
    }

    private void paintMain() {
        if (mainView == null) return;
        boolean running = mode != 0;
        mainView.setText(running ? "\u25B6" : "\u25A0");     // running = play, idle = stop square
        int bg = running ? 0xF2_2E7D32 : (uiState == 1 ? 0xF2_1565C0 : 0xEB_C62828);
        mainView.setBackground(shape(bg, p.btnShape, dp(p.mainSize), 0xCCFFFFFF, dp(1.5f)));
    }

    private void setUi(int u) {
        uiState = u;
        prefs.edit().putInt("ui", u).apply();
        applyVisibility();
    }

    private void buildBar() {
        if (barView != null && barView.getParent() != null) removeWin(barView);
        barView = new LinearLayout(this);
        barView.setOrientation(LinearLayout.HORIZONTAL);
        barView.setGravity(Gravity.CENTER);
        barView.setBackgroundColor(0xD2121619);
        barView.setPadding(dp(3), dp(3), dp(3), dp(3));

        final TextView modeB = tbBtn("Job: " + (p.mode == 1 ? "Swipe" : "Click"), 0xE68E24AA, null);
        modeB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.mode = p.mode == 1 ? 0 : 1;
                if (p.mode == 1 && p.swipe == null) addSwipe();
                modeB.setText("Job: " + (p.mode == 1 ? "Swipe" : "Click"));
                applyVisibility();
            }
        });
        barView.addView(modeB);
        barView.addView(tbBtn("+", 0xE61E88E5, new View.OnClickListener() {
            @Override public void onClick(View v) { addTarget(); }
        }));
        barView.addView(tbBtn("\u2212", 0xE6E53935, new View.OnClickListener() {
            @Override public void onClick(View v) { deleteLastTarget(); }
        }));
        barView.addView(tbBtn("\u21C6", 0xE6FF9800, new View.OnClickListener() {
            @Override public void onClick(View v) { swipeButton(); }
        }));
        barView.addView(tbBtn("\uD83D\uDCBE", 0xE600897B, new View.OnClickListener() {
            @Override public void onClick(View v) { openSaveMenu(); }
        }));
        barView.addView(tbBtn("\u2699", 0xE6546E7A, new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        }));
        // docked at the bottom of the screen, full width
        barLp = newLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, 0);
        barLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
    }

    // =====================================================================
    // actions
    // =====================================================================
    private boolean busy() {
        if (mode != 0) { toast("Stop first"); return true; }
        return false;
    }

    private void addTarget() {
        if (busy()) return;
        closePanel();
        if (p.mode != 0) { p.mode = 0; buildBar(); }
        Profile.Target t = new Profile.Target();
        int k = tNodes.size() % 6;
        t.cx = screenW() / 2 + (k - 3) * dp(26);
        t.cy = screenH() / 2 + (k - 3) * dp(26);
        p.targets.add(t);
        tNodes.add(makeNode(0, t, t.cx, t.cy, t.size));
        refreshNumbers();
        applyVisibility();
    }

    private void deleteLastTarget() {
        if (busy()) return;
        closePanel();
        if (tNodes.isEmpty()) { toast("No targets"); return; }
        Node n = tNodes.remove(tNodes.size() - 1);
        p.targets.remove(n.t);
        detach(n);
        refreshNumbers();
    }

    private void addSwipe() {
        Profile.Swipe s = new Profile.Swipe();
        int w = screenW(), h = screenH();
        s.x1 = w / 4; s.y1 = h / 2; s.x2 = w * 3 / 4; s.y2 = h / 2;
        p.swipe = s;
        createSwipeNodes();
    }

    private void swipeButton() {
        if (busy()) return;
        closePanel();
        if (p.mode != 1) { p.mode = 1; buildBar(); }
        if (p.swipe == null) addSwipe();
        applyVisibility();
        openSwipeMenu();
    }

    // =====================================================================
    // playback engine
    // =====================================================================
    /** One button, one job: click or swipe, whatever the loaded file says. */
    private void startJob() {
        if (p.mode == 1) startSwiping(); else startClicking();
    }

    private void startClicking() {
        if (p.targets.isEmpty()) { toast("No targets in this file"); return; }
        closePanel();
        syncPositions();
        mode = 1; idx = 0; cycle = 0; runId++;
        applyVisibility();
        final int id = runId;
        run.postDelayed(new Runnable() {
            @Override public void run() { if (id == runId) clickStep(); }
        }, Math.max(0, p.targets.get(0).delay));
    }

    private void clickStep() {
        if (mode != 1) return;
        final int id = runId;
        Profile.Target t = p.targets.get(idx);
        gesture(t.cx, t.cy, t.cx, t.cy, t.hold, new Runnable() {
            @Override public void run() {
                if (id != runId || mode != 1) return;
                idx++;
                if (idx >= p.targets.size()) {
                    idx = 0;
                    cycle++;
                    if (!p.recycleInf && cycle > p.recycle) { stopRun(); return; }
                }
                run.postDelayed(new Runnable() {
                    @Override public void run() { if (id == runId) clickStep(); }
                }, Math.max(0, p.targets.get(idx).delay));
            }
        });
    }

    private void startSwiping() {
        if (p.swipe == null) { toast("Add swipe points first (⇆ button)"); return; }
        closePanel();
        syncPositions();
        mode = 2; swipeCount = 0; runId++;
        applyVisibility();
        swipeStep();
    }

    private void swipeStep() {
        if (mode != 2) return;
        final int id = runId;
        final Profile.Swipe s = p.swipe;
        gesture(s.x1, s.y1, s.x2, s.y2, s.dur, new Runnable() {
            @Override public void run() {
                if (id != runId || mode != 2) return;
                swipeCount++;
                if (!s.inf && swipeCount > s.repeat) { stopRun(); return; }
                run.postDelayed(new Runnable() {
                    @Override public void run() { if (id == runId) swipeStep(); }
                }, Math.max(0, s.interval));
            }
        });
    }

    /** Tap (x1==x2,y1==y2) or swipe. 'next' is run when the gesture finished. */
    private void gesture(int x1, int y1, int x2, int y2, int durMs, final Runnable next) {
        final int id = runId;
        long d = Math.max(1, Math.min(durMs, 60000));
        if (Build.VERSION.SDK_INT >= 24) {
            boolean ok = GestureApi.dispatch(this, x1, y1, x2, y2, d, next);
            if (!ok) {
                run.postDelayed(new Runnable() {
                    @Override public void run() { if (id == runId) next.run(); }
                }, 150);
            }
        } else {
            rootGesture(x1, y1, x2, y2, d, next);
        }
    }

    private boolean rootWarned = false;

    /** Android 6 (API 23) cannot inject touches through accessibility; use root 'input' if the phone has it. */
    private void rootGesture(final int x1, final int y1, final int x2, final int y2, final long d, final Runnable next) {
        final int id = runId;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + d});
                    ok = pr.waitFor() == 0;
                } catch (Exception ignored) { }
                final boolean fok = ok;
                run.post(new Runnable() {
                    @Override public void run() {
                        if (id != runId) return;
                        if (!fok) {
                            if (!rootWarned) { rootWarned = true; toast("Android 6 needs root to tap"); }
                            stopRun();
                            return;
                        }
                        next.run();
                    }
                });
            }
        }).start();
    }

    private void stopRun() {
        mode = 0;
        runId++;
        run.removeCallbacksAndMessages(null);
        applyVisibility();
    }

    // =====================================================================
    // small panels (menus)
    // =====================================================================
    private void closePanel() {
        if (panel != null) { removeWin(panel); panel = null; }
    }

    private LinearLayout newPanel(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(0xEE212121, 1, dp(40), 0, 0));
        box.setPadding(dp(4), dp(3), dp(4), dp(3));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView tt = label(title, true);
        tt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tt);
        TextView x = label("✕", true);
        x.setPadding(dp(8), 0, dp(2), 0);
        x.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        });
        head.addView(x);
        box.addView(head);
        return box;
    }

    private TextView label(String s, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setIncludeFontPadding(false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView smallBtn(String s, int bg, View.OnClickListener c) {
        TextView t = label(s, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(3), dp(6), dp(3));
        t.setBackground(shape(bg, 1, dp(18), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        t.setLayoutParams(lp);
        t.setOnClickListener(c);
        return t;
    }

    /** Shows 'box' near (ax,ay); prefers right of the anchor, flips to the left if it does not fit. */
    private void showPanel(LinearLayout box, int ax, int ay, int anchorHalfW) {
        closePanel();
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = box.getMeasuredWidth(), h = box.getMeasuredHeight();
        int x = ax + anchorHalfW + dp(4);
        if (x + w > screenW()) x = ax - anchorHalfW - dp(4) - w;
        x = clamp(x, 0, Math.max(0, screenW() - w));
        int y = clamp(ay - h / 2, 0, Math.max(0, screenH() - h));
        WindowManager.LayoutParams lp = newLp(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, x, y,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) closePanel();
                return false;
            }
        });
        panel = box;
        addWin(box, lp);
    }

    /** Panel centred just above the bottom toolbar. */
    private void showPanelAtToolbar(LinearLayout box) {
        closePanel();
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = box.getMeasuredWidth(), h = box.getMeasuredHeight();
        int barH = barView != null && barView.getHeight() > 0 ? barView.getHeight() : dp(p.btnSize + 10);
        int x = clamp((screenW() - w) / 2, 0, Math.max(0, screenW() - w));
        int y = Math.max(0, screenH() - barH - h - dp(4));
        WindowManager.LayoutParams lp = newLp(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, x, y,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) closePanel();
                return false;
            }
        });
        panel = box;
        addWin(box, lp);
    }

    /** Row:  label  [-]  value  [+]  - hold the buttons to change quickly. Returns the value TextView. */
    private class Stepper {
        int v, min, max, step;
        boolean inf;
        TextView tv;
        IntCb cb;
        int n;
        int dir;
        final Runnable rep = new Runnable() {
            @Override public void run() {
                n++;
                change();
                ui.postDelayed(this, 70);
            }
        };

        void change() {
            int mult = n < 8 ? 1 : n < 20 ? 5 : n < 40 ? 25 : 100;
            v = clamp(v + dir * step * mult, min, max);
            show();
            cb.on(v);
        }

        void show() { tv.setText(inf ? "∞" : String.valueOf(v)); }
    }

    private Stepper addStepper(LinearLayout parent, String name, int val, int min, int max, int step, IntCb cb) {
        final Stepper s = new Stepper();
        s.v = val; s.min = min; s.max = max; s.step = step; s.cb = cb;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView nm = label(name, false);
        nm.setLayoutParams(new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(nm);
        row.addView(holdBtn(s, "−", -1));
        s.tv = label("", true);
        s.tv.setGravity(Gravity.CENTER);
        s.tv.setLayoutParams(new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(s.tv);
        row.addView(holdBtn(s, "+", 1));
        s.show();
        parent.addView(row);
        return s;
    }

    private TextView holdBtn(final Stepper s, String sym, final int d) {
        TextView b = label(sym, true);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        b.setBackground(shape(0xFF424242, 1, dp(22), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        b.setLayoutParams(lp);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        s.dir = d; s.n = 0;
                        s.change();
                        ui.postDelayed(s.rep, 400);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(s.rep);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return b;
    }

    // ---------- target menu ----------
    private void openNodeMenu(final Node n) {
        if (mode != 0) return;
        if (n.kind == 0) openTargetMenu(n); else openSwipeMenuAt(n.cx(), n.cy(), n.lp.width / 2);
    }

    private void openTargetMenu(final Node n) {
        final Profile.Target t = n.t;
        LinearLayout box = newPanel("Target " + (tNodes.indexOf(n) + 1));
        addStepper(box, "Size dp", t.size, 16, 200, 2, new IntCb() {
            @Override public void on(int v) {
                t.size = v;
                int cx = n.cx(), cy = n.cy();
                n.lp.width = n.lp.height = dp(v);
                n.lp.x = cx - n.lp.width / 2;
                n.lp.y = cy - n.lp.height / 2;
                n.v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(8, Math.min(18, v * 0.35f)));
                styleNode(n);
                updWin(n.v, n.lp);
            }
        });
        addStepper(box, "Touch ms", t.hold, 1, 60000, 10, new IntCb() {
            @Override public void on(int v) { t.hold = v; }
        });
        addStepper(box, "Delay ms", t.delay, 0, 3600000, 50, new IntCb() {
            @Override public void on(int v) { t.delay = v; }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn("Delete", 0xFFC62828, new View.OnClickListener() {
            @Override public void onClick(View v) {
                tNodes.remove(n);
                p.targets.remove(t);
                detach(n);
                refreshNumbers();
                closePanel();
            }
        }));
        row.addView(smallBtn("OK", 0xFF455A64, new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        }));
        box.addView(row);
        showPanel(box, n.cx(), n.cy(), n.lp.width / 2);
    }

    // ---------- swipe menu ----------
    private void openSwipeMenu() {
        if (aNode == null) return;
        openSwipeMenuAt(aNode.cx(), aNode.cy(), aNode.lp.width / 2);
    }

    private void openSwipeMenuAt(int ax, int ay, int half) {
        if (p.swipe == null) return;
        final Profile.Swipe s = p.swipe;
        LinearLayout box = newPanel("Swipe A→B");
        final TextView freq = label("", false);
        addStepper(box, "Swipe ms", s.dur, 20, 60000, 10, new IntCb() {
            @Override public void on(int v) { s.dur = v; freq.setText(freqText(s)); }
        });
        addStepper(box, "Gap ms", s.interval, 0, 3600000, 50, new IntCb() {
            @Override public void on(int v) { s.interval = v; freq.setText(freqText(s)); }
        });
        box.addView(freq);
        freq.setText(freqText(s));

        final Stepper rep = addStepper(box, "Repeat", s.repeat, 0, 9999999, 1, new IntCb() {
            @Override public void on(int v) { s.repeat = v; }
        });
        rep.inf = s.inf;
        rep.show();
        final TextView infBtn = smallBtn(s.inf ? "∞ on" : "∞ off", s.inf ? 0xFF2E7D32 : 0xFF424242, null);
        infBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                s.inf = !s.inf;
                rep.inf = s.inf;
                rep.show();
                infBtn.setText(s.inf ? "∞ on" : "∞ off");
                infBtn.setBackground(shape(s.inf ? 0xFF2E7D32 : 0xFF424242, 1, dp(18), 0, 0));
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(infBtn);
        row.addView(smallBtn("Delete", 0xFFC62828, new View.OnClickListener() {
            @Override public void onClick(View v) {
                detach(aNode); detach(bNode);
                aNode = bNode = null;
                p.swipe = null;
                closePanel();
            }
        }));
        row.addView(smallBtn("OK", 0xFF455A64, new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        }));
        box.addView(row);
        showPanel(box, ax, ay, half);
    }

    private String freqText(Profile.Swipe s) {
        int period = Math.max(1, s.dur + s.interval);
        return String.format(java.util.Locale.US, "Frequency: %.2f swipes/s", 1000f / period);
    }

    // ---------- settings menu ----------
    private void openSettings() {
        if (busy()) return;
        LinearLayout box = newPanel("Settings");
        addStepper(box, "Bar btns", p.btnSize, 22, 60, 2, new IntCb() {
            @Override public void on(int v) { p.btnSize = v; rebuildBarKeepOpen(); }
        });
        addStepper(box, "Main btn", p.mainSize, 16, 80, 2, new IntCb() {
            @Override public void on(int v) {
                p.mainSize = v;
                if (mainView != null) {
                    mainLp.width = mainLp.height = dp(v);
                    mainView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, v * 0.48f);
                    paintMain();
                    updWin(mainView, mainLp);
                }
            }
        });
        final Stepper rec = addStepper(box, "Recycle", p.recycle, 0, 9999999, 1, new IntCb() {
            @Override public void on(int v) { p.recycle = v; }
        });
        rec.inf = p.recycleInf;
        rec.show();

        final TextView infBtn = smallBtn(p.recycleInf ? "∞ on" : "∞ off",
                p.recycleInf ? 0xFF2E7D32 : 0xFF424242, null);
        infBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.recycleInf = !p.recycleInf;
                rec.inf = p.recycleInf;
                rec.show();
                infBtn.setText(p.recycleInf ? "∞ on" : "∞ off");
                infBtn.setBackground(shape(p.recycleInf ? 0xFF2E7D32 : 0xFF424242, 1, dp(18), 0, 0));
            }
        });
        final TextView shapeBtn = smallBtn("Shape: " + shapeName(), 0xFF424242, null);
        shapeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.btnShape = (p.btnShape + 1) % 3;
                shapeBtn.setText("Shape: " + shapeName());
                for (Node nd : tNodes) styleNode(nd);
                if (aNode != null) styleNode(aNode);
                if (bNode != null) styleNode(bNode);
                paintMain();
                rebuildBarKeepOpen();
            }
        });
        final TextView bootBtn = smallBtn(bootText(), 0xFF424242, null);
        bootBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("autoshow", !prefs.getBoolean("autoshow", true)).apply();
                bootBtn.setText(bootText());
            }
        });
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(infBtn);
        r1.addView(shapeBtn);
        box.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(bootBtn);
        r2.addView(smallBtn("OK", 0xFF455A64, new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        }));
        box.addView(r2);
        showPanelAtToolbar(box);
    }

    /** rebuild bar after size/shape change, keeping it on screen */
    private void rebuildBarKeepOpen() {
        buildBar();
        applyVisibility();
    }

    private String bootText() {
        return "Show at start: " + (prefs.getBoolean("autoshow", true) ? "ON" : "OFF");
    }

    // ---------- save / load menu ----------
    private void openSaveMenu() {
        if (busy()) return;
        syncPositions();
        LinearLayout box = newPanel("Files" + (currentName != null ? " (" + currentName + ")" : ""));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<String> names = Profile.list(this);
        if (names.isEmpty()) list.addView(label("No saved files", false));
        for (final String nm : names) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label((nm.equals(currentName) ? "• " : "") + nm, false);
            name.setLayoutParams(new LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(name);
            row.addView(smallBtn("Load", 0xFF1565C0, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Profile loaded = Profile.load(ClickerService.this, nm);
                    if (loaded == null) { toast("Cannot load"); return; }
                    p = loaded;
                    currentName = nm;
                    prefs.edit().putString("last", nm).apply();
                    rebuildAll();
                    toast("Loaded " + nm);
                }
            }));
            row.addView(smallBtn("Del", 0xFFC62828, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Profile.delete(ClickerService.this, nm);
                    if (nm.equals(currentName)) {
                        currentName = null;
                        prefs.edit().remove("last").apply();
                    }
                    openSaveMenu();
                }
            }));
            list.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Math.min(dp(130), Math.max(dp(24), names.size() * dp(26)))));
        box.addView(sv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallBtn(currentName != null ? "Save" : "Save new", 0xFF00897B, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = currentName != null ? currentName : nextName();
                doSave(name);
            }
        }));
        row.addView(smallBtn("Add new", 0xFF455A64, new View.OnClickListener() {
            @Override public void onClick(View v) { doSave(nextName()); }
        }));
        box.addView(row);
        showPanelAtToolbar(box);
    }

    private void doSave(String name) {
        syncPositions();
        if (Profile.save(this, name, p)) {
            currentName = name;
            prefs.edit().putString("last", name).apply();
            toast("Saved " + name);
            openSaveMenu();
        } else {
            toast("Save failed");
        }
    }

    private String nextName() {
        List<String> names = Profile.list(this);
        int i = 1;
        while (names.contains("Profile " + i)) i++;
        return "Profile " + i;
    }
}
