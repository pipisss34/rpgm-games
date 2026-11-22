package com.rpgm.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Overlay tombol virtual. Semua tombol DIGAMBAR lewat Canvas (tanpa gambar).
 * Sentuhan di luar tombol dilewatkan ke WebView (return false pada DOWN).
 */
public class VirtualPad extends View {

    public interface Listener {
        void onKey(int keyCode, boolean down);

        void onOpenMenu();

        /** Kursor mouse virtual. buttons: bit0 = kiri, bit1 = kanan (keadaan setelah event). */
        void onMouse(float x, float y, int buttons);
    }

    // jenis tombol
    private static final int ROUND = 0, RECT = 1, MORE = 2, EYE = 3, CURSOR = 4;
    // pemilik pointer
    private static final int OWNER_DPAD = 0, OWNER_KEY = 1, OWNER_TAP = 2;

    private static final float[] SIZES = {0.82f, 1f, 1.2f};
    private static final String[] SIZE_LABELS = {"Kecil", "Sedang", "Besar"};
    private static final float[] OPACITIES = {0.30f, 0.45f, 0.65f, 0.85f};
    private static final long LONG_PRESS_MS = 600;

    private static final class Btn {
        final String label;
        final int keyCode;
        final int kind;
        final boolean extra;
        float cx, cy, w, h;
        boolean pressed;

        Btn(String label, int keyCode, int kind, boolean extra) {
            this.label = label;
            this.keyCode = keyCode;
            this.kind = kind;
            this.extra = extra;
        }
    }

    private static final class Ptr {
        final int id;
        final int owner;
        final Btn tap;
        float x, y;
        boolean longFired;

        Ptr(int id, int owner, Btn tap, float x, float y) {
            this.id = id;
            this.owner = owner;
            this.tap = tap;
            this.x = x;
            this.y = y;
        }
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Btn> btns = new ArrayList<>();
    private final List<Ptr> ptrs = new ArrayList<>();
    private final Set<Integer> held = new HashSet<>();
    private final Runnable longPress;
    private int longId = -1;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private boolean hidden;
    private boolean extraOpen;
    private boolean haptic;
    private int sizeIdx;
    private int opacityIdx;

    private float dpCx, dpCy, dpSize, slop, strokeW;
    private int dirMask; // 1=kiri 2=kanan 4=atas 8=bawah

    // ---- mode kursor mouse ----
    private boolean cursorMode;
    private float curX = -1, curY = -1;
    private int mouseButtons;
    private boolean cursorFast, tickOn;
    private int cursorTicks;
    private final Runnable cursorTick = new Runnable() {
        @Override
        public void run() {
            if (!cursorMode || hidden || dirMask == 0) {
                tickOn = false;
                return;
            }
            cursorTicks++;
            float base = Math.min(getWidth(), getHeight());
            float ramp = 1f + Math.min(2f, cursorTicks / 50f); // makin lama ditahan makin cepat
            float step = base * 0.006f * ramp * (cursorFast ? 2f : 1f);
            float dx = ((dirMask & 2) != 0 ? 1f : 0f) - ((dirMask & 1) != 0 ? 1f : 0f);
            float dy = ((dirMask & 8) != 0 ? 1f : 0f) - ((dirMask & 4) != 0 ? 1f : 0f);
            if (dx != 0f && dy != 0f) {
                dx *= 0.7071f;
                dy *= 0.7071f;
            }
            curX = Math.max(0f, Math.min(getWidth() - 1f, curX + dx * step));
            curY = Math.max(0f, Math.min(getHeight() - 1f, curY + dy * step));
            sendMouse();
            invalidate();
            ui.postDelayed(this, 16);
        }
    };

    public VirtualPad(Context ctx, SharedPreferences prefs, Listener listener) {
        super(ctx);
        this.prefs = prefs;
        this.listener = listener;
        hidden = prefs.getBoolean("hidden", false);
        extraOpen = prefs.getBoolean("extra", false);
        haptic = prefs.getBoolean("haptic", true);
        sizeIdx = clamp(prefs.getInt("size", 1), 0, SIZES.length - 1);
        opacityIdx = clamp(prefs.getInt("opac", 1), 0, OPACITIES.length - 1);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);

        setWillNotDraw(false);
        setFocusable(false);

        longPress = () -> {
            for (Ptr p : ptrs) {
                if (p.id == longId && p.owner == OWNER_TAP && p.tap.kind == EYE && inside(p.tap, p.x, p.y)) {
                    p.longFired = true;
                    ptrs.remove(p);
                    haptic();
                    refresh();
                    if (this.listener != null) this.listener.onOpenMenu();
                    break;
                }
            }
        };
    }

    // ------------------------------------------------------------ API publik

    public void releaseAll() {
        ptrs.clear();
        ui.removeCallbacks(longPress);
        refresh();
    }

    public String sizeLabel() {
        return SIZE_LABELS[sizeIdx];
    }

    public String opacityLabel() {
        return Math.round(OPACITIES[opacityIdx] * 100) + "%";
    }

    public boolean isHaptic() {
        return haptic;
    }

    public void cycleSize() {
        releaseAll();
        sizeIdx = (sizeIdx + 1) % SIZES.length;
        prefs.edit().putInt("size", sizeIdx).apply();
        layoutAll();
        invalidate();
    }

    public void cycleOpacity() {
        opacityIdx = (opacityIdx + 1) % OPACITIES.length;
        prefs.edit().putInt("opac", opacityIdx).apply();
        invalidate();
    }

    public void toggleHaptic() {
        haptic = !haptic;
        prefs.edit().putBoolean("haptic", haptic).apply();
    }

    // ------------------------------------------------------------ state

    private void setHidden(boolean v) {
        releaseAll();
        hidden = v;
        prefs.edit().putBoolean("hidden", hidden).apply();
        invalidate();
    }

    private void setCursorMode(boolean v) {
        releaseAll(); // lepas semua klik/tombol dulu
        cursorMode = v;
        if (v) sendMouse();
        invalidate();
    }

    private void sendMouse() {
        if (listener != null) listener.onMouse(curX, curY, mouseButtons);
    }

    private void setExtraOpen(boolean v) {
        releaseAll();
        extraOpen = v;
        prefs.edit().putBoolean("extra", extraOpen).apply();
        invalidate();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int argb(float a, int r, int g, int b) {
        return Color.argb((int) (255f * Math.max(0f, Math.min(1f, a))), r, g, b);
    }

    private void haptic() {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutAll();
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private Btn round(String label, int key, float cx, float cy, float d) {
        Btn b = new Btn(label, key, ROUND, false);
        b.cx = cx;
        b.cy = cy;
        b.w = d;
        b.h = d;
        return b;
    }

    private Btn rect(String label, int key, float cx, float cy, float w, float h, boolean extra) {
        Btn b = new Btn(label, key, RECT, extra);
        b.cx = cx;
        b.cy = cy;
        b.w = w;
        b.h = h;
        return b;
    }

    private Btn tap(int kind, float cx, float cy, float d) {
        Btn b = new Btn("", 0, kind, false);
        b.cx = cx;
        b.cy = cy;
        b.w = d;
        b.h = d;
        return b;
    }

    private void layoutAll() {
        btns.clear();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (curX < 0 || curX > w) curX = w / 2f;
        if (curY < 0 || curY > h) curY = h / 2f;

        float base = Math.min(w, h) * SIZES[sizeIdx];
        float m = base * 0.05f;
        float gap = base * 0.02f;
        slop = base * 0.015f;
        strokeW = Math.max(2f, base * 0.008f);
        stroke.setStrokeWidth(strokeW);
        icon.setStrokeWidth(strokeW * 1.2f);

        // D-pad (kiri bawah)
        dpSize = base * 0.46f;
        dpCx = m + dpSize / 2f;
        dpCy = h - m - dpSize / 2f;

        // Tombol bulat A/B/C/D (kanan bawah)
        float r = base * 0.085f;
        float d = r * 1.3f;
        float fcx = w - m - d - r;
        float fcy = h - m - d - r;
        btns.add(round("OK", KeyEvent.KEYCODE_Z, fcx + d, fcy, r * 2));
        btns.add(round("BATAL", KeyEvent.KEYCODE_X, fcx, fcy + d, r * 2));
        btns.add(round("DASH", KeyEvent.KEYCODE_SHIFT_LEFT, fcx - d, fcy, r * 2));
        btns.add(round("MENU", KeyEvent.KEYCODE_ESCAPE, fcx, fcy - d, r * 2));

        // Baris atas
        float bw = base * 0.20f, bh = base * 0.10f;
        float er = base * 0.055f;
        float rowY = m + er;
        btns.add(rect("PGUP", KeyEvent.KEYCODE_PAGE_UP, m + bw / 2f, rowY, bw, bh, false));
        btns.add(rect("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, m + bw * 1.5f + gap, rowY, bw, bh, false));

        Btn cur = new Btn("KURSOR", 0, CURSOR, false);
        cur.cx = m + bw * 2.5f + gap * 2;
        cur.cy = rowY;
        cur.w = bw;
        cur.h = bh;
        btns.add(cur);

        float eyeX = w - m - er;
        btns.add(tap(EYE, eyeX, rowY, er * 2));
        float moreX = eyeX - er * 2 - gap;
        btns.add(tap(MORE, moreX, rowY, er * 2));
        float tabX = moreX - er - gap - bw / 2f;
        btns.add(rect("TAB", KeyEvent.KEYCODE_TAB, tabX, rowY, bw, bh, false));
        btns.add(rect("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, tabX - bw - gap, rowY, bw, bh, false));

        // Baris ekstra (dibuka tombol "...")
        float bw2 = bw * 0.9f;
        float row2Y = rowY + er + gap * 1.5f + bh / 2f;
        String[] names = {"ENTER", "SPACE", "F2", "F3", "F9"};
        int[] keys = {KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_F2,
                KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F9};
        for (int i = 0; i < names.length; i++) {
            float x = w - m - bw2 / 2f - i * (bw2 + gap);
            btns.add(rect(names[i], keys[i], x, row2Y, bw2, bh, true));
        }
    }

    private boolean visible(Btn b) {
        if (b.kind == EYE) return true;
        if (hidden) return false;
        if (b.extra) return extraOpen;
        return true;
    }

    // ------------------------------------------------------------ hit test

    private boolean inside(Btn b, float x, float y) {
        if (b.kind == RECT || b.kind == CURSOR) {
            return Math.abs(x - b.cx) <= b.w / 2f + slop && Math.abs(y - b.cy) <= b.h / 2f + slop;
        }
        float rr = b.w / 2f + slop;
        float dx = x - b.cx, dy = y - b.cy;
        return dx * dx + dy * dy <= rr * rr;
    }

    private Btn hitKey(float x, float y) {
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if ((b.kind == ROUND || b.kind == RECT) && visible(b) && inside(b, x, y)) return b;
        }
        return null;
    }

    private int dirOf(float x, float y) {
        float dx = x - dpCx, dy = y - dpCy;
        if (Math.hypot(dx, dy) < dpSize * 0.12f) return 0;
        float ax = Math.abs(dx), ay = Math.abs(dy);
        int m = 0;
        if (ax > ay * 0.414f) m |= (dx < 0) ? 1 : 2;
        if (ay > ax * 0.414f) m |= (dy < 0) ? 4 : 8;
        return m;
    }

    // ------------------------------------------------------------ sentuhan

    private Ptr find(int id) {
        for (Ptr p : ptrs) if (p.id == id) return p;
        return null;
    }

    private Ptr begin(int id, float x, float y) {
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if ((b.kind == EYE || b.kind == MORE || b.kind == CURSOR) && visible(b) && inside(b, x, y)) {
                if (b.kind == EYE) {
                    ui.removeCallbacks(longPress);
                    longId = id;
                    ui.postDelayed(longPress, LONG_PRESS_MS);
                }
                return new Ptr(id, OWNER_TAP, b, x, y);
            }
        }
        if (hidden) return null;
        if (hitKey(x, y) != null) return new Ptr(id, OWNER_KEY, null, x, y);
        float dx = x - dpCx, dy = y - dpCy;
        float rr = dpSize * 0.62f;
        if (dx * dx + dy * dy <= rr * rr) return new Ptr(id, OWNER_DPAD, null, x, y);
        return null;
    }

    private void end(int id, float x, float y) {
        Ptr p = find(id);
        if (p == null) return;
        ptrs.remove(p);
        if (id == longId) ui.removeCallbacks(longPress);
        if (p.owner == OWNER_TAP && !p.longFired && inside(p.tap, x, y)) {
            if (p.tap.kind == EYE) setHidden(!hidden);
            else if (p.tap.kind == CURSOR) setCursorMode(!cursorMode);
            else setExtraOpen(!extraOpen);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                Ptr p = begin(e.getPointerId(i), e.getX(i), e.getY(i));
                if (p == null) return false; // lewatkan ke WebView
                ptrs.add(p);
                refresh();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (ptrs.isEmpty()) return false;
                for (Ptr p : ptrs) {
                    int i = e.findPointerIndex(p.id);
                    if (i >= 0) {
                        p.x = e.getX(i);
                        p.y = e.getY(i);
                    }
                }
                refresh();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                end(e.getPointerId(i), e.getX(i), e.getY(i));
                refresh();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
            default:
                return false;
        }
    }

    /** Hitung ulang tombol yang sedang ditekan lalu kirim selisihnya sebagai key down/up. */
    private void refresh() {
        for (Btn b : btns) b.pressed = false;
        int dir = 0;
        boolean left = false, right = false, fast = false;
        Set<Integer> want = new HashSet<>();
        for (Ptr p : ptrs) {
            if (p.owner == OWNER_DPAD) {
                dir |= dirOf(p.x, p.y);
            } else if (p.owner == OWNER_KEY) {
                Btn b = hitKey(p.x, p.y);
                if (b != null) {
                    b.pressed = true;
                    if (cursorMode && b.keyCode == KeyEvent.KEYCODE_Z) {
                        left = true; // OK = klik kiri
                    } else if (cursorMode && b.keyCode == KeyEvent.KEYCODE_X) {
                        right = true; // BATAL = klik kanan
                    } else {
                        want.add(b.keyCode);
                        if (b.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) fast = true;
                    }
                }
            } else {
                p.tap.pressed = inside(p.tap, p.x, p.y);
            }
        }
        dirMask = dir;
        int nb = (left ? 1 : 0) | (right ? 2 : 0);
        if (!cursorMode) { // mode normal: D-pad = tombol panah
            if ((dir & 1) != 0) want.add(KeyEvent.KEYCODE_DPAD_LEFT);
            if ((dir & 2) != 0) want.add(KeyEvent.KEYCODE_DPAD_RIGHT);
            if ((dir & 4) != 0) want.add(KeyEvent.KEYCODE_DPAD_UP);
            if ((dir & 8) != 0) want.add(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        for (Integer k : want) {
            if (held.add(k)) fire(k, true);
        }
        Iterator<Integer> it = held.iterator();
        while (it.hasNext()) {
            Integer k = it.next();
            if (!want.contains(k)) {
                it.remove();
                fire(k, false);
            }
        }
        cursorFast = fast;
        if (cursorMode && !hidden) {
            if (nb != mouseButtons) {
                boolean newPress = (nb & ~mouseButtons) != 0;
                mouseButtons = nb;
                if (newPress) haptic();
                sendMouse();
            }
            if (dir != 0) {
                if (!tickOn) {
                    tickOn = true;
                    ui.post(cursorTick);
                }
            } else {
                cursorTicks = 0;
            }
        } else if (mouseButtons != 0) {
            mouseButtons = 0;
            sendMouse();
        }
        invalidate();
    }

    private void fire(int key, boolean down) {
        if (down) haptic();
        if (listener != null) listener.onKey(key, down);
    }

    // ------------------------------------------------------------ gambar

    @Override
    protected void onDraw(Canvas c) {
        float op = OPACITIES[opacityIdx];
        if (!hidden) drawDpad(c, op);
        for (Btn b : btns) {
            if (visible(b)) drawBtn(c, b, op);
        }
        if (cursorMode && !hidden) drawCursor(c);
    }

    private void drawDpad(Canvas c, float op) {
        float aw = dpSize / 3f;
        float half = dpSize / 2f;
        float rad = aw * 0.22f;
        // pusat
        drawShape(c, dpCx - aw / 2, dpCy - aw / 2, dpCx + aw / 2, dpCy + aw / 2, rad, false, op);
        // lengan
        drawShape(c, dpCx - aw / 2, dpCy - half, dpCx + aw / 2, dpCy - aw / 2, rad, (dirMask & 4) != 0, op);
        drawShape(c, dpCx - aw / 2, dpCy + aw / 2, dpCx + aw / 2, dpCy + half, rad, (dirMask & 8) != 0, op);
        drawShape(c, dpCx - half, dpCy - aw / 2, dpCx - aw / 2, dpCy + aw / 2, rad, (dirMask & 1) != 0, op);
        drawShape(c, dpCx + aw / 2, dpCy - aw / 2, dpCx + half, dpCy + aw / 2, rad, (dirMask & 2) != 0, op);
        // panah
        float off = (half + aw / 2f) / 2f;
        float t = aw * 0.26f;
        drawArrow(c, dpCx, dpCy - off, 0, -1, t, (dirMask & 4) != 0, op);
        drawArrow(c, dpCx, dpCy + off, 0, 1, t, (dirMask & 8) != 0, op);
        drawArrow(c, dpCx - off, dpCy, -1, 0, t, (dirMask & 1) != 0, op);
        drawArrow(c, dpCx + off, dpCy, 1, 0, t, (dirMask & 2) != 0, op);
    }

    private void drawShape(Canvas c, float l, float t, float r, float b, float rad, boolean on, float op) {
        rect.set(l, t, r, b);
        fill.setColor(on ? argb(op + 0.25f, 255, 255, 255) : argb(op * 0.55f, 0, 0, 0));
        stroke.setColor(argb(op + 0.15f, 255, 255, 255));
        c.drawRoundRect(rect, rad, rad, fill);
        c.drawRoundRect(rect, rad, rad, stroke);
    }

    private void drawArrow(Canvas c, float ax, float ay, float dx, float dy, float t, boolean on, float op) {
        float px = -dy, py = dx; // tegak lurus
        path.reset();
        path.moveTo(ax + dx * t, ay + dy * t);
        path.lineTo(ax - dx * t + px * t, ay - dy * t + py * t);
        path.lineTo(ax - dx * t - px * t, ay - dy * t - py * t);
        path.close();
        fill.setColor(on ? argb(0.95f, 20, 20, 30) : argb(op + 0.35f, 255, 255, 255));
        c.drawPath(path, fill);
    }

    private void drawBtn(Canvas c, Btn b, float op) {
        boolean on = b.pressed || (b.kind == MORE && extraOpen) || (b.kind == CURSOR && cursorMode);
        float eff = (b.kind == EYE && hidden) ? Math.max(0.25f, op * 0.7f) : op;
        fill.setColor(on ? argb(eff + 0.25f, 255, 255, 255) : argb(eff * 0.55f, 0, 0, 0));
        stroke.setColor(argb(eff + 0.15f, 255, 255, 255));

        if (b.kind == RECT || b.kind == CURSOR) {
            rect.set(b.cx - b.w / 2f, b.cy - b.h / 2f, b.cx + b.w / 2f, b.cy + b.h / 2f);
            float rr = b.h * 0.3f;
            c.drawRoundRect(rect, rr, rr, fill);
            c.drawRoundRect(rect, rr, rr, stroke);
        } else {
            c.drawCircle(b.cx, b.cy, b.w / 2f, fill);
            c.drawCircle(b.cx, b.cy, b.w / 2f, stroke);
        }

        int fg = on ? argb(0.95f, 20, 20, 30) : argb(eff + 0.35f, 255, 255, 255);
        if (b.kind == EYE) drawEye(c, b, fg);
        else if (b.kind == MORE) drawDots(c, b, fg);
        else drawLabel(c, b, fg);
    }

    private void drawLabel(Canvas c, Btn b, int fg) {
        text.setColor(fg);
        float maxW = b.w * (b.kind == RECT || b.kind == CURSOR ? 0.84f : 0.78f);
        float base = (b.kind == RECT || b.kind == CURSOR) ? b.h * 0.42f : b.w * 0.30f;
        text.setTextSize(base);
        float tw = text.measureText(b.label);
        if (tw > maxW) text.setTextSize(base * maxW / tw);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(b.label, b.cx, b.cy - (fm.ascent + fm.descent) / 2f, text);
    }

    private void drawEye(Canvas c, Btn b, int fg) {
        float e = b.w * 0.30f;
        icon.setColor(fg);
        icon.setStyle(Paint.Style.STROKE);
        path.reset();
        path.moveTo(b.cx - e, b.cy);
        path.quadTo(b.cx, b.cy - e * 1.1f, b.cx + e, b.cy);
        path.quadTo(b.cx, b.cy + e * 1.1f, b.cx - e, b.cy);
        path.close();
        c.drawPath(path, icon);
        icon.setStyle(Paint.Style.FILL);
        c.drawCircle(b.cx, b.cy, e * 0.38f, icon);
        if (hidden) {
            icon.setStyle(Paint.Style.STROKE);
            c.drawLine(b.cx - e, b.cy + e * 0.8f, b.cx + e, b.cy - e * 0.8f, icon);
        }
        icon.setStyle(Paint.Style.STROKE);
    }

    private void drawCursor(Canvas c) {
        float s = Math.max(14f, Math.min(getWidth(), getHeight()) * 0.045f);
        path.reset();
        path.moveTo(curX, curY);
        path.lineTo(curX, curY + s * 1.4f);
        path.lineTo(curX + s * 0.38f, curY + s * 1.05f);
        path.lineTo(curX + s * 0.62f, curY + s * 1.65f);
        path.lineTo(curX + s * 0.85f, curY + s * 1.55f);
        path.lineTo(curX + s * 0.62f, curY + s * 0.95f);
        path.lineTo(curX + s * 1.05f, curY + s * 0.95f);
        path.close();
        fill.setColor((mouseButtons & 1) != 0 ? Color.rgb(255, 216, 102)
                : (mouseButtons & 2) != 0 ? Color.rgb(255, 120, 120) : Color.WHITE);
        stroke.setColor(Color.BLACK);
        c.drawPath(path, fill);
        c.drawPath(path, stroke);
    }

    private void drawDots(Canvas c, Btn b, int fg) {
        icon.setColor(fg);
        icon.setStyle(Paint.Style.FILL);
        float r = b.w * 0.07f;
        float s = b.w * 0.22f;
        c.drawCircle(b.cx - s, b.cy, r, icon);
        c.drawCircle(b.cx, b.cy, r, icon);
        c.drawCircle(b.cx + s, b.cy, r, icon);
        icon.setStyle(Paint.Style.STROKE);
    }
}
