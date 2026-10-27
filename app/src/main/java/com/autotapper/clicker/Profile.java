package com.autotapper.clicker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All saved parameters. One Profile == one JSON file in files/profiles. */
class Profile {
    static class Target {
        int cx, cy;          // centre, screen px
        int size = 48;       // dp
        int hold = 50;       // ms, duration of the touch
        int delay = 500;     // ms, wait after previous target (or after cycle start)
    }

    static class Swipe {
        int x1, y1, x2, y2;  // screen px
        int dur = 300;       // ms, duration of one swipe
        int interval = 500;  // ms, pause between swipes (frequency = 1000 / (dur+interval) per s)
        int repeat = 0;      // extra repeats after the first swipe
        boolean inf = false; // repeat forever
    }

    List<Target> targets = new ArrayList<>();
    Swipe swipe;             // null = no swipe configured
    int recycle = 0;         // 0 = no recycling (one pass), N = N extra passes
    boolean recycleInf = false;
    int btnSize = 40;        // dp, toolbar button size
    int btnShape = 0;        // 0 circle, 1 rounded, 2 square
    int mainSize = 30;       // dp, size of the one main start/stop button
    int mode = 0;            // job type of this file: 0 = click targets, 1 = swipe

    String toJson() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray ta = new JSONArray();
        for (Target t : targets) {
            JSONObject j = new JSONObject();
            j.put("cx", t.cx).put("cy", t.cy).put("size", t.size).put("hold", t.hold).put("delay", t.delay);
            ta.put(j);
        }
        o.put("targets", ta);
        if (swipe != null) {
            JSONObject s = new JSONObject();
            s.put("x1", swipe.x1).put("y1", swipe.y1).put("x2", swipe.x2).put("y2", swipe.y2)
                    .put("dur", swipe.dur).put("interval", swipe.interval)
                    .put("repeat", swipe.repeat).put("inf", swipe.inf);
            o.put("swipe", s);
        }
        o.put("recycle", recycle).put("recycleInf", recycleInf)
                .put("btnSize", btnSize).put("btnShape", btnShape).put("mainSize", mainSize)
                .put("mode", mode);
        return o.toString(2);
    }

    static Profile fromJson(String str) throws Exception {
        JSONObject o = new JSONObject(str);
        Profile p = new Profile();
        JSONArray ta = o.optJSONArray("targets");
        if (ta != null) for (int i = 0; i < ta.length(); i++) {
            JSONObject j = ta.getJSONObject(i);
            Target t = new Target();
            t.cx = j.optInt("cx"); t.cy = j.optInt("cy"); t.size = j.optInt("size", 48);
            t.hold = j.optInt("hold", 50); t.delay = j.optInt("delay", 500);
            p.targets.add(t);
        }
        JSONObject s = o.optJSONObject("swipe");
        if (s != null) {
            Swipe w = new Swipe();
            w.x1 = s.optInt("x1"); w.y1 = s.optInt("y1"); w.x2 = s.optInt("x2"); w.y2 = s.optInt("y2");
            w.dur = s.optInt("dur", 300); w.interval = s.optInt("interval", 500);
            w.repeat = s.optInt("repeat", 0); w.inf = s.optBoolean("inf", false);
            p.swipe = w;
        }
        p.recycle = o.optInt("recycle", 0); p.recycleInf = o.optBoolean("recycleInf", false);
        p.btnSize = o.optInt("btnSize", 40); p.btnShape = o.optInt("btnShape", 0);
        p.mainSize = o.optInt("mainSize", 30);
        p.mode = o.optInt("mode", 0);
        return p;
    }

    // ---- file handling ----
    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "profiles");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String clean(String name) {
        return name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
    }

    static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".json")) out.add(n.substring(0, n.length() - 5));
        }
        return out;
    }

    static boolean save(Context c, String name, Profile p) {
        try (FileOutputStream fo = new FileOutputStream(new File(dir(c), clean(name) + ".json"))) {
            fo.write(p.toJson().getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Profile load(Context c, String name) {
        File f = new File(dir(c), clean(name) + ".json");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return fromJson(bo.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static void delete(Context c, String name) {
        new File(dir(c), clean(name) + ".json").delete();
    }
}
