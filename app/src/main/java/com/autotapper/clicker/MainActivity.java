package com.autotapper.clicker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Permission checklist + show/hide the floating button. Works on Android 6 - 16. */
public class MainActivity extends Activity {
    private interface Check { boolean ok(); }
    private interface Act { void go(); }

    private static class Row {
        TextView status;
        Button btn;
        Check check;
    }

    private final List<Row> rows = new ArrayList<>();
    private LinearLayout root;
    private SharedPreferences prefs;
    private int pad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        // Android 15+ draws edge to edge: keep content clear of the system bars
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(pad + in.getSystemWindowInsetLeft(), pad + in.getSystemWindowInsetTop(),
                        pad + in.getSystemWindowInsetRight(), pad + in.getSystemWindowInsetBottom());
                return in;
            }
        });

        TextView title = new TextView(this);
        title.setText("Auto Tapper");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setPadding(0, pad / 2, 0, pad / 2);
        intro.setText("Grant these once. Each line turns ✓ when it is done.");
        root.addView(intro);

        addRow("Accessibility service (required)",
                "Lets the app tap and swipe for you and detect the home screen.",
                new Check() { public boolean ok() { return accessibilityOn(); } },
                new Act() { public void go() {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this, "Open Auto Tapper and turn it on", Toast.LENGTH_LONG).show();
                } });

        if (Build.VERSION.SDK_INT >= 33) {
            addRow("Allow restricted settings (Android 13+)",
                    "If the accessibility switch is greyed out: App info, three dots, Allow restricted settings.",
                    null,
                    new Act() { public void go() {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } });
        }

        addRow("Display over other apps",
                "Recommended. Lets the floating button stay on top on every phone.",
                new Check() { public boolean ok() { return Settings.canDrawOverlays(MainActivity.this); } },
                new Act() { public void go() {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } });

        addRow("Run in background (battery)",
                "Stops the system from putting the app to sleep.",
                new Check() { public boolean ok() {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                } },
                new Act() { public void go() {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                } });

        if (Build.VERSION.SDK_INT >= 33) {
            addRow("Notifications",
                    "Needed to show the small \"running\" notification that keeps the app alive.",
                    new Check() { public boolean ok() {
                        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    } },
                    new Act() { public void go() {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                    } });
        }

        CheckBox boot = new CheckBox(this);
        boot.setText("Show the button automatically when the phone starts");
        boot.setChecked(prefs.getBoolean("autoshow", true));
        boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton c, boolean on) {
                prefs.edit().putBoolean("autoshow", on).apply();
            }
        });
        root.addView(boot);

        CheckBox keep = new CheckBox(this);
        keep.setText("Keep running in background (shows a quiet notification)");
        keep.setChecked(prefs.getBoolean("keepalive", true));
        keep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton c, boolean on) {
                prefs.edit().putBoolean("keepalive", on).apply();
                if (on) KeepAliveService.start(MainActivity.this); else KeepAliveService.stop(MainActivity.this);
            }
        });
        root.addView(keep);

        Button show = new Button(this);
        show.setText("Show the button");
        show.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClickerService s = ClickerService.instance;
                if (s == null) Toast.makeText(MainActivity.this, "Turn on the accessibility service first", Toast.LENGTH_LONG).show();
                else { s.showUi(); finish(); }
            }
        });
        root.addView(show);

        Button hide = new Button(this);
        hide.setText("Hide the button");
        hide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ClickerService.instance != null) ClickerService.instance.hideUi();
            }
        });
        root.addView(hide);

        TextView help = new TextView(this);
        help.setPadding(0, pad, 0, 0);
        String old = Build.VERSION.SDK_INT < 24
                ? "\n\nAndroid 6 note: this Android version has no tap API for apps. Tapping works only on a rooted phone."
                : "";
        help.setText(
                "The app is one small button (■ = stopped, ▶ = running). It stays where you leave it.\n\n"
                + "Touch it: run the loaded file's job (click targets or swipe) invisibly. Touch again: stop.\n"
                + "Long-press it: show the toolbar at the bottom and the targets. Long-press again: hide everything but the button.\n"
                + "Drag it to move it.\n\n"
                + "Toolbar: Job (Click/Swipe) | + add target | − delete last | ⇆ swipe points | 💾 files | ⚙ settings.\n"
                + "Tap a target or swipe point to open its menu. Recycle 0 = once, N = N extra rounds, ∞ = forever. "
                + "Clicking also stops when you go to the home screen; swipe stops only with the button.\n\n"
                + "Some brands (Xiaomi, Oppo, Vivo, Huawei, Samsung) also have an Autostart / Battery setting in "
                + "system settings for this app: allow it so the button survives restarts." + old);
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void addRow(String name, String desc, Check check, final Act act) {
        Row r = new Row();
        r.check = check;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, pad / 3, 0, pad / 3);
        r.status = new TextView(this);
        r.status.setTextSize(15);
        r.status.setTypeface(Typeface.DEFAULT_BOLD);
        r.status.setText(name);
        box.addView(r.status);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        box.addView(d);
        r.btn = new Button(this);
        r.btn.setText("Grant / open settings");
        r.btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act.go(); }
        });
        box.addView(r.btn);
        r.status.setTag(name);
        root.addView(box);
        rows.add(r);
    }

    private boolean accessibilityOn() {
        if (ClickerService.instance != null) return true;
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.toLowerCase().contains((getPackageName() + "/").toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Row r : rows) {
            String name = (String) r.status.getTag();
            if (r.check == null) { r.status.setText(name); continue; }
            boolean ok = r.check.ok();
            r.status.setText((ok ? "✓ " : "✗ ") + name);
            r.btn.setEnabled(!ok);
        }
        if (prefs.getBoolean("keepalive", true)) KeepAliveService.start(this);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        onResume();
    }
}
