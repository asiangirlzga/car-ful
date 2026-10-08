package com.tvdrive.lite;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Random;

/**
 * Whole game in one class. Pure Canvas, zero bitmaps, zero audio, zero libraries.
 * Surface is fixed at 1280x720 RGB_565 (about 1.8 MB) and scaled by the TV, even on 4K.
 */
final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    static final int W = 1280, H = 720;
    static final int ROAD_L = 340, ROAD_R = 940, LANE_W = 150;
    static final int CAR_W = 70, CAR_H = 130, PLAYER_Y = 540;
    static final int MAX_TRAFFIC = 8;
    static final float PXK = 4.2f;         // pixels per second per km/h
    static final float MAX_SPEED = 200f;
    static final int MENU = 0, PLAY = 1, OVER = 2, PAUSE = 3;

    // Themes: 0 desert dusk, 1 neon night, 2 snow pass (change every 1500 m)
    static final int[] GRASS = {0xFFC8A25A, 0xFF0B1A12, 0xFFE8F0F5};
    static final int[] ROAD  = {0xFF4A4038, 0xFF1C1F2B, 0xFF5B6168};
    static final int[] LINE  = {0xFFF2E3B3, 0xFF29E6FF, 0xFFFFFFFF};
    static final int[] DECO  = {0xFF7A9A3A, 0xFFFF3EA5, 0xFF9CC4D6};
    static final String[] NAMES = {"DESERT DUSK", "NEON NIGHT", "SNOW PASS"};
    static final int[] CARCOL = {0xFF1E88E5, 0xFFFDD835, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFFECEFF1};

    private final SurfaceHolder holder;
    private final SharedPreferences sp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private final Random rnd = new Random();
    private Thread thread;
    private volatile boolean running;

    private volatile boolean kL, kR, kU, kD;
    private volatile int state = MENU;

    private float px, speed, steer, scroll, dist, spawnT, shake, crashT;
    private int best;
    private boolean offRoad;

    private final float[] ex = new float[MAX_TRAFFIC], ey = new float[MAX_TRAFFIC], es = new float[MAX_TRAFFIC];
    private final int[] ec = new int[MAX_TRAFFIC];
    private final boolean[] ea = new boolean[MAX_TRAFFIC];

    GameView(Context ctx) {
        super(ctx);
        holder = getHolder();
        holder.setFormat(PixelFormat.RGB_565);
        holder.setFixedSize(W, H);
        holder.addCallback(this);
        sp = ctx.getSharedPreferences("d", 0);
        best = sp.getInt("b", 0);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        reset();
    }

    // ---------------------------------------------------------------- input
    boolean key(int code, boolean down, int repeat) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                kL = down; return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                kR = down; return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_BUTTON_A:
                kU = down; return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_BUTTON_B:
                kD = down; return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_SPACE:
                if (down && repeat == 0) onOk();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_P:
                if (down && repeat == 0) {
                    if (state == PLAY) state = PAUSE; else if (state == PAUSE) state = PLAY;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (state == PLAY || state == PAUSE || state == OVER) {
                    if (down) { state = MENU; reset(); }
                    return true;
                }
                return false; // on menu: let the system exit the app
        }
        return false;
    }

    private void onOk() {
        if (state == MENU || state == OVER) { reset(); state = PLAY; }
        else if (state == PAUSE) state = PLAY;
    }

    private void reset() {
        px = ROAD_L + LANE_W * 2.5f;
        speed = 0; steer = 0; scroll = 0; dist = 0; spawnT = 1.5f; shake = 0; crashT = 0;
        for (int i = 0; i < MAX_TRAFFIC; i++) ea[i] = false;
    }

    // --------------------------------------------------------------- loop
    @Override public void surfaceCreated(SurfaceHolder h) {
        running = true;
        thread = new Thread(this, "loop");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        if (state == PLAY) state = PAUSE;
        try { thread.join(); } catch (InterruptedException ignored) { }
    }

    @Override public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            update(dt);
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) draw(c);
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
            long spent = (System.nanoTime() - now) / 1000000L;
            if (spent < 16) {
                try { Thread.sleep(16 - spent); } catch (InterruptedException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------- update
    private void update(float dt) {
        if (state == OVER) {
            crashT += dt;
            shake = Math.max(0, shake - dt * 1.5f);
            return;
        }
        if (state != PLAY) return;

        // throttle / brake / coast
        if (kU) speed += 62f * dt * (1.15f - speed / MAX_SPEED);
        if (kD) speed -= 150f * dt;
        if (!kU && !kD) speed -= 14f * dt;

        // steering
        float target = (kR ? 1f : 0f) - (kL ? 1f : 0f);
        steer += (target - steer) * Math.min(1f, 9f * dt);
        float grip = Math.min(1f, speed / 25f);
        px += steer * (110f + speed * 3.2f) * grip * dt;
        if (px < 230) px = 230;
        if (px > 1050) px = 1050;

        // off-road penalty
        offRoad = px - CAR_W / 2f < ROAD_L || px + CAR_W / 2f > ROAD_R;
        if (offRoad) { speed -= 75f * dt; shake = Math.max(shake, 0.3f); }
        else shake = Math.max(0, shake - dt * 2f);

        if (speed < 0) speed = 0;
        if (speed > MAX_SPEED) speed = MAX_SPEED;

        scroll += speed * PXK * dt;
        dist += speed / 3.6f * dt;

        // traffic spawn
        spawnT -= dt;
        if (spawnT <= 0 && speed > 50f) {
            spawn();
            float diff = Math.max(0.6f, 1f - dist / 20000f);
            spawnT = (0.9f + rnd.nextFloat() * 0.8f) * (100f / Math.max(60f, speed)) * diff * 1.4f;
        }

        // traffic move + collide
        for (int i = 0; i < MAX_TRAFFIC; i++) {
            if (!ea[i]) continue;
            ey[i] += (speed - es[i]) * PXK * dt;
            if (ey[i] > H + 200 || ey[i] < -400) { ea[i] = false; continue; }
            if (Math.abs(ex[i] - px) < CAR_W - 10 && Math.abs(ey[i] - PLAYER_Y) < CAR_H - 16) {
                crash();
                return;
            }
        }
    }

    private void spawn() {
        int lane = rnd.nextInt(4);
        float x = ROAD_L + LANE_W * (lane + 0.5f);
        int slot = -1;
        for (int i = 0; i < MAX_TRAFFIC; i++) {
            if (ea[i]) { if (Math.abs(ex[i] - x) < 10 && ey[i] < 320) return; }
            else if (slot < 0) slot = i;
        }
        if (slot < 0) return;
        ea[slot] = true; ex[slot] = x; ey[slot] = -CAR_H;
        es[slot] = 45 + rnd.nextInt(45);
        ec[slot] = CARCOL[rnd.nextInt(CARCOL.length)];
    }

    private void crash() {
        state = OVER; shake = 1f; crashT = 0; speed = 0;
        int d = (int) dist;
        if (d > best) { best = d; sp.edit().putInt("b", best).apply(); }
    }

    // --------------------------------------------------------------- draw
    private int theme() { return ((int) (dist / 1500f)) % 3; }

    private void draw(Canvas c) {
        final int t = theme();
        c.drawColor(GRASS[t]);
        c.save();
        if (shake > 0) c.translate((rnd.nextFloat() - .5f) * 14 * shake, (rnd.nextFloat() - .5f) * 14 * shake);

        // road
        p.setColor(ROAD[t]);
        c.drawRect(ROAD_L, 0, ROAD_R, H, p);

        // curbs (60px stripes)
        int n0 = (int) (scroll / 60f);
        float o = scroll % 60f;
        for (int k = 0; k * 60 - 60 + o < H; k++) {
            float y = k * 60 - 60 + o;
            p.setColor(((k - n0) & 1) == 0 ? 0xFFE53935 : 0xFFFFFFFF);
            c.drawRect(ROAD_L - 14, y, ROAD_L, y + 60, p);
            c.drawRect(ROAD_R, y, ROAD_R + 14, y + 60, p);
        }

        // lane dashes
        p.setColor(LINE[t]);
        o = scroll % 80f;
        for (int k = 0; k * 80 - 80 + o < H; k++) {
            float y = k * 80 - 80 + o;
            for (int l = 1; l < 4; l++) {
                float x = ROAD_L + LANE_W * l;
                c.drawRect(x - 3, y, x + 3, y + 40, p);
            }
        }

        // roadside scenery
        n0 = (int) (scroll / 200f);
        o = scroll % 200f;
        p.setColor(DECO[t]);
        for (int k = 0; k * 200 - 200 + o < H + 40; k++) {
            float y = k * 200 - 200 + o;
            int h = (k - n0) * 73856093;
            h ^= (h >>> 13);
            float r = 18 + (h & 15);
            c.drawCircle(130 + ((h >> 4) & 63), y, r, p);
            c.drawCircle(1150 - ((h >> 10) & 63), y + 100, r, p);
        }

        // headlight beam at night
        if (t == 1 && state != OVER) {
            p.setColor(0x33FFFFAA);
            path.reset();
            path.moveTo(px - 22, PLAYER_Y - 62);
            path.lineTo(px + 22, PLAYER_Y - 62);
            path.lineTo(px + 120, PLAYER_Y - 400);
            path.lineTo(px - 120, PLAYER_Y - 400);
            path.close();
            c.drawPath(path, p);
        }

        // traffic + player
        for (int i = 0; i < MAX_TRAFFIC; i++) if (ea[i]) car(c, ex[i], ey[i], ec[i], false);
        if (state == OVER) {
            float r = Math.min(160f, crashT * 320f);
            p.setColor(0xAAFF7043); c.drawCircle(px, PLAYER_Y, r, p);
            p.setColor(0xCCFFEB3B); c.drawCircle(px, PLAYER_Y, r * 0.55f, p);
        } else {
            car(c, px, PLAYER_Y, 0xFFE53935, true);
        }
        c.restore();

        hud(c, t);
        if (state != PLAY) overlay(c);
    }

    private void car(Canvas c, float x, float y, int col, boolean player) {
        float hw = CAR_W / 2f, hh = CAR_H / 2f;
        p.setColor(0x55000000);
        rf.set(x - hw + 6, y - hh + 8, x + hw + 6, y + hh + 8);
        c.drawRoundRect(rf, 14, 14, p);
        p.setColor(col);
        rf.set(x - hw, y - hh, x + hw, y + hh);
        c.drawRoundRect(rf, 16, 16, p);
        p.setColor(0xFF1B2A3A);
        rf.set(x - hw + 9, y - hh + 34, x + hw - 9, y - hh + 60);
        c.drawRoundRect(rf, 6, 6, p);
        rf.set(x - hw + 10, y + hh - 42, x + hw - 10, y + hh - 24);
        c.drawRoundRect(rf, 6, 6, p);
        p.setColor(0xFFFFF59D); // headlights (front = top)
        c.drawRect(x - hw + 6, y - hh + 3, x - hw + 22, y - hh + 11, p);
        c.drawRect(x + hw - 22, y - hh + 3, x + hw - 6, y - hh + 11, p);
        p.setColor(player && kD ? 0xFFFF1744 : 0xFF8B0000); // tail lights
        c.drawRect(x - hw + 6, y + hh - 11, x - hw + 22, y + hh - 3, p);
        c.drawRect(x + hw - 22, y + hh - 11, x + hw - 6, y + hh - 3, p);
    }

    private void txt(Canvas c, String s, float x, float y, float size, int col, Paint.Align a) {
        p.setTextSize(size); p.setColor(col); p.setTextAlign(a);
        c.drawText(s, x, y, p);
    }

    private void hud(Canvas c, int t) {
        p.setColor(0x88000000);
        rf.set(30, 30, 290, 190); c.drawRoundRect(rf, 18, 18, p);
        rf.set(990, 30, 1250, 190); c.drawRoundRect(rf, 18, 18, p);

        txt(c, String.valueOf((int) speed), 160, 112, 76, 0xFFFFFFFF, Paint.Align.CENTER);
        txt(c, "KM/H", 160, 142, 24, 0xFFB0BEC5, Paint.Align.CENTER);
        p.setColor(0x55FFFFFF); rf.set(50, 158, 270, 172); c.drawRoundRect(rf, 7, 7, p);
        p.setColor(speed > 150 ? 0xFFFF5252 : 0xFF69F0AE);
        rf.set(50, 158, 50 + 220 * speed / MAX_SPEED, 172); c.drawRoundRect(rf, 7, 7, p);

        txt(c, "DIST", 1120, 70, 24, 0xFFB0BEC5, Paint.Align.CENTER);
        txt(c, (int) dist + " m", 1120, 118, 48, 0xFFFFFFFF, Paint.Align.CENTER);
        txt(c, "BEST " + best + " m", 1120, 166, 28, 0xFFFFD54F, Paint.Align.CENTER);

        txt(c, NAMES[t], W / 2f, 40, 26, 0xFFFFFFFF, Paint.Align.CENTER);
    }

    private void overlay(Canvas c) {
        p.setColor(state == OVER ? 0x88200000 : 0xAA000000);
        c.drawRect(0, 0, W, H, p);
        float cx = W / 2f;
        if (state == MENU) {
            txt(c, "TV DRIVE LITE", cx, 230, 92, 0xFFFFFFFF, Paint.Align.CENTER);
            txt(c, "LEFT / RIGHT  =  Steer", cx, 330, 38, 0xFFB3E5FC, Paint.Align.CENTER);
            txt(c, "UP  =  Accelerate", cx, 380, 38, 0xFFB9F6CA, Paint.Align.CENTER);
            txt(c, "DOWN  =  Brake", cx, 430, 38, 0xFFFF8A80, Paint.Align.CENTER);
            txt(c, "PLAY/PAUSE or MENU  =  Pause      BACK  =  Menu / Exit", cx, 490, 28, 0xFFCFD8DC, Paint.Align.CENTER);
            txt(c, "Press OK to start", cx, 590, 52, 0xFFFFD54F, Paint.Align.CENTER);
        } else if (state == PAUSE) {
            txt(c, "PAUSED", cx, 340, 90, 0xFFFFFFFF, Paint.Align.CENTER);
            txt(c, "Press OK to continue", cx, 430, 44, 0xFFFFD54F, Paint.Align.CENTER);
        } else {
            txt(c, "CRASH!", cx, 280, 100, 0xFFFF5252, Paint.Align.CENTER);
            txt(c, (int) dist + " m", cx, 370, 60, 0xFFFFFFFF, Paint.Align.CENTER);
            txt(c, "BEST " + best + " m", cx, 430, 38, 0xFFFFD54F, Paint.Align.CENTER);
            txt(c, "Press OK to retry", cx, 540, 48, 0xFFFFFFFF, Paint.Align.CENTER);
        }
    }
}
