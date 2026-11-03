package org.freenetproject.mobile.ui.main.activity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Full-screen transparent overlay that draws seasonal particles using canvas shapes.
 */
public class SeasonalOverlayView extends View {

    public enum Season { NONE, SNOW, FISH, HALLOWEEN, BIRTHDAY, NEW_YEAR, SUMMER, SPRING }

    private Season season = Season.NONE;
    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint();
    private final Random rng = new Random();
    private boolean running = false;

    private static final int PARTICLE_COUNT = 30;
    private static final long FRAME_MS = 40; // ~25 fps

    public SeasonalOverlayView(Context context) { super(context); init(); }
    public SeasonalOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        tintPaint.setColor(Color.argb(45, 160, 0, 0));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setSeason(Season s) {
        season = s;
        particles.clear();
        running = s != Season.NONE;
        if (running) postDelayed(ticker, FRAME_MS);
        setVisibility(running ? VISIBLE : GONE);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running || getWidth() == 0) { if (running) postDelayed(this, FRAME_MS); return; }
            if (particles.size() < PARTICLE_COUNT) spawnParticle();
            for (Particle p : particles) p.step();
            particles.removeIf(p -> p.dead(getWidth(), getHeight()));
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    private void spawnParticle() {
        Particle p = new Particle();
        p.x = rng.nextFloat() * getWidth();
        switch (season) {
            case SNOW: {
                p.type = rng.nextInt(3); // 0=snowflake, 1=snowflake2, 2=circle snow
                p.size = 16 + rng.nextInt(24);
                p.vy = 1.5f + rng.nextFloat() * 2f;
                p.vx = (rng.nextFloat() - 0.5f) * 1.2f;
                p.y = -p.size;
                p.alpha = 180 + rng.nextInt(75);
                p.color = Color.argb(p.alpha, 220 + rng.nextInt(35), 240 + rng.nextInt(15), 255);
                p.rotation = rng.nextFloat() * 60f;
                p.vr = (rng.nextFloat() - 0.5f) * 1.5f;
                break;
            }
            case FISH: {
                p.type = rng.nextInt(3); // 0=fish, 1=fish small, 2=bubble
                p.size = 28 + rng.nextInt(22);
                p.vx = (rng.nextBoolean() ? 1 : -1) * (1.5f + rng.nextFloat() * 2f);
                p.x = p.vx > 0 ? -p.size : getWidth() + p.size;
                p.y = 80 + rng.nextFloat() * (getHeight() - 160);
                p.vy = (rng.nextFloat() - 0.5f) * 0.6f;
                p.alpha = 200 + rng.nextInt(55);
                int[][] fc = {{255,120,40},{40,150,255},{255,220,0},{0,200,180},{255,80,80},{200,100,255}};
                int[] c = fc[rng.nextInt(fc.length)];
                p.color = Color.argb(p.alpha, c[0], c[1], c[2]);
                break;
            }
            case HALLOWEEN: {
                p.type = rng.nextInt(3); // 0=bat, 1=ghost, 2=pumpkin
                p.size = 24 + rng.nextInt(22);
                p.vy = 1.0f + rng.nextFloat() * 1.5f;
                p.vx = (rng.nextFloat() - 0.5f) * 1.5f;
                p.y = -p.size;
                p.alpha = 200 + rng.nextInt(55);
                p.rotation = (rng.nextFloat() - 0.5f) * 20f;
                p.vr = (rng.nextFloat() - 0.5f) * 2f;
                if (p.type == 0) p.color = Color.argb(p.alpha, 30, 5, 50);
                else if (p.type == 1) p.color = Color.argb(p.alpha, 230, 240, 255);
                else p.color = Color.argb(p.alpha, 220, 90, 0);
                break;
            }
            case BIRTHDAY: {
                p.type = rng.nextInt(4); // 0=rect, 1=circle, 2=star, 3=ribbon
                p.size = 14 + rng.nextInt(20);
                p.vy = 1.0f + rng.nextFloat() * 2.0f;
                p.vx = (rng.nextFloat() - 0.5f) * 2.5f;
                p.y = -p.size;
                p.alpha = 210 + rng.nextInt(45);
                int[][] cc = {{255,50,100},{50,200,255},{255,220,0},{180,50,255},{0,230,130},{255,100,50}};
                int[] c = cc[rng.nextInt(cc.length)];
                p.color = Color.argb(p.alpha, c[0], c[1], c[2]);
                p.rotation = rng.nextFloat() * 360f;
                p.vr = (rng.nextFloat() - 0.5f) * 8f;
                break;
            }
            case NEW_YEAR: {
                p.type = rng.nextInt(3); // 0=sparkle 4pt, 1=star 6pt, 2=burst circle
                p.size = 16 + rng.nextInt(26);
                p.vy = -(2.0f + rng.nextFloat() * 2.5f);
                p.vx = (rng.nextFloat() - 0.5f) * 2.0f;
                p.y = getHeight() + p.size;
                p.alpha = 220 + rng.nextInt(35);
                int[][] nc = {{255,215,0},{255,255,200},{200,200,255},{255,255,255},{255,180,0}};
                int[] c = nc[rng.nextInt(nc.length)];
                p.color = Color.argb(p.alpha, c[0], c[1], c[2]);
                p.rotation = rng.nextFloat() * 360f;
                p.vr = (rng.nextFloat() - 0.5f) * 5f;
                break;
            }
            case SUMMER: {
                p.type = rng.nextInt(3); // 0=sun, 1=wave, 2=bubble
                p.size = 20 + rng.nextInt(22);
                p.vx = (rng.nextBoolean() ? 1 : -1) * (1.0f + rng.nextFloat() * 1.5f);
                p.x = p.vx > 0 ? -p.size : getWidth() + p.size;
                p.y = getHeight() * 0.2f + rng.nextFloat() * (getHeight() * 0.7f);
                p.vy = (rng.nextFloat() - 0.5f) * 0.8f;
                p.alpha = 200 + rng.nextInt(55);
                if (p.type == 0) p.color = Color.argb(p.alpha, 255, 220, 0);
                else if (p.type == 1) p.color = Color.argb(p.alpha, 0, 160, 220);
                else p.color = Color.argb(p.alpha, 120, 210, 255);
                p.rotation = rng.nextFloat() * 360f;
                p.vr = (rng.nextFloat() - 0.5f) * 3f;
                break;
            }
            case SPRING: {
                p.type = rng.nextInt(3); // 0=petal, 1=flower, 2=leaf
                p.size = 16 + rng.nextInt(22);
                p.vy = 0.8f + rng.nextFloat() * 1.5f;
                p.vx = (rng.nextFloat() - 0.5f) * 2.0f;
                p.y = -p.size;
                p.alpha = 200 + rng.nextInt(55);
                if (p.type == 0) p.color = Color.argb(p.alpha, 255, 150, 190);
                else if (p.type == 1) p.color = Color.argb(p.alpha, 255, 180, 220);
                else p.color = Color.argb(p.alpha, 100, 200, 80);
                p.rotation = rng.nextFloat() * 360f;
                p.vr = (rng.nextFloat() - 0.5f) * 4f;
                break;
            }
        }
        particles.add(p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (season == Season.HALLOWEEN) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), tintPaint);
        }
        for (Particle p : particles) {
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(p.rotation);
            paint.setAlpha(p.alpha);
            paint.setColor(p.color);
            drawShape(canvas, p);
            canvas.restore();
        }
    }

    private void drawShape(Canvas canvas, Particle p) {
        switch (season) {
            case SNOW:     drawSnow(canvas, p);     break;
            case FISH:     drawFish(canvas, p);     break;
            case HALLOWEEN:drawHalloween(canvas, p);break;
            case BIRTHDAY: drawConfetti(canvas, p); break;
            case NEW_YEAR: drawSparkle(canvas, p);  break;
            case SUMMER:   drawSummer(canvas, p);   break;
            case SPRING:   drawSpring(canvas, p);   break;
        }
    }

    // ── SNOW ─────────────────────────────────────────────────────────────────
    private void drawSnow(Canvas canvas, Particle p) {
        float s = p.size;
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);
        if (p.type == 2) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0, 0, s * 0.4f, paint);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(1.5f, s * 0.09f));
        // 6 arms
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(-s * 0.5f, 0, s * 0.5f, 0, paint);
            canvas.rotate(60f);
        }
        if (p.type == 0) {
            // small branch pairs at 1/3 and 2/3 of each arm
            canvas.rotate(-120f);
            for (int i = 0; i < 6; i++) {
                float b = s * 0.28f;
                canvas.drawLine(b, 0, b * 0.65f, -b * 0.35f, paint);
                canvas.drawLine(b, 0, b * 0.65f,  b * 0.35f, paint);
                canvas.rotate(60f);
            }
        }
    }

    // ── FISH ─────────────────────────────────────────────────────────────────
    private void drawFish(Canvas canvas, Particle p) {
        float s = p.size;
        float dir = p.vx >= 0 ? 1f : -1f;
        canvas.scale(dir, 1f);

        if (p.type == 2) { // bubble
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.07f);
            paint.setColor(p.color);
            paint.setAlpha(p.alpha);
            canvas.drawCircle(0, 0, s * 0.35f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(p.alpha / 4);
            canvas.drawCircle(-s * 0.1f, -s * 0.1f, s * 0.1f, paint);
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);

        // tail
        Path tail = new Path();
        tail.moveTo(s * 0.3f, 0);
        tail.lineTo(s * 0.7f, -s * 0.28f);
        tail.lineTo(s * 0.7f,  s * 0.28f);
        tail.close();
        canvas.drawPath(tail, paint);

        // body
        canvas.drawOval(new RectF(-s * 0.5f, -s * 0.24f, s * 0.35f, s * 0.24f), paint);

        // dorsal fin
        paint.setAlpha(p.alpha - 50);
        Path fin = new Path();
        fin.moveTo(-s * 0.05f, -s * 0.24f);
        fin.lineTo(s * 0.08f, -s * 0.48f);
        fin.lineTo(s * 0.22f, -s * 0.24f);
        fin.close();
        canvas.drawPath(fin, paint);

        // eye white
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(p.alpha);
        canvas.drawCircle(-s * 0.28f, -s * 0.07f, s * 0.08f, paint);
        // pupil
        paint.setColor(Color.BLACK);
        canvas.drawCircle(-s * 0.28f, -s * 0.07f, s * 0.045f, paint);

        // stripe
        paint.setColor(Color.argb(p.alpha / 3, 255, 255, 255));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.06f);
        canvas.drawOval(new RectF(-s * 0.1f, -s * 0.2f, s * 0.1f, s * 0.2f), paint);
    }

    // ── HALLOWEEN ────────────────────────────────────────────────────────────
    private void drawHalloween(Canvas canvas, Particle p) {
        float s = p.size;
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);
        paint.setStyle(Paint.Style.FILL);

        if (p.type == 0) { // bat silhouette
            Path bat = new Path();
            // left wing
            bat.moveTo(0, 0);
            bat.cubicTo(-s * 0.15f, -s * 0.25f, -s * 0.55f, -s * 0.45f, -s * 0.8f, -s * 0.08f);
            bat.cubicTo(-s * 0.6f, -s * 0.55f, -s * 0.25f, -s * 0.65f, -s * 0.08f, -s * 0.18f);
            // right wing (mirror)
            bat.lineTo(s * 0.08f, -s * 0.18f);
            bat.cubicTo(s * 0.25f, -s * 0.65f, s * 0.6f, -s * 0.55f, s * 0.8f, -s * 0.08f);
            bat.cubicTo(s * 0.55f, -s * 0.45f, s * 0.15f, -s * 0.25f, 0, 0);
            canvas.drawPath(bat, paint);
            // body
            canvas.drawOval(new RectF(-s * 0.12f, -s * 0.06f, s * 0.12f, s * 0.2f), paint);
            // ears
            Path ears = new Path();
            ears.moveTo(-s * 0.07f, -s * 0.06f); ears.lineTo(-s * 0.12f, -s * 0.22f); ears.lineTo(-s * 0.01f, -s * 0.06f);
            ears.moveTo(s * 0.07f, -s * 0.06f);  ears.lineTo(s * 0.12f, -s * 0.22f);  ears.lineTo(s * 0.01f, -s * 0.06f);
            canvas.drawPath(ears, paint);
            // red eyes
            paint.setColor(Color.argb(p.alpha, 220, 0, 0));
            canvas.drawCircle(-s * 0.04f, s * 0.04f, s * 0.03f, paint);
            canvas.drawCircle( s * 0.04f, s * 0.04f, s * 0.03f, paint);

        } else if (p.type == 1) { // ghost
            Path ghost = new Path();
            ghost.addArc(new RectF(-s * 0.38f, -s * 0.5f, s * 0.38f, s * 0.05f), 180, 180);
            ghost.lineTo(s * 0.38f, s * 0.42f);
            ghost.cubicTo(s * 0.22f, s * 0.22f, s * 0.06f, s * 0.48f, -s * 0.12f, s * 0.3f);
            ghost.cubicTo(-s * 0.28f, s * 0.12f, -s * 0.38f, s * 0.34f, -s * 0.38f, s * 0.42f);
            ghost.close();
            canvas.drawPath(ghost, paint);
            // wavy bottom shadow
            paint.setAlpha(p.alpha / 3);
            canvas.drawPath(ghost, paint);
            paint.setAlpha(p.alpha);
            // dark eyes
            paint.setColor(Color.argb(p.alpha, 50, 0, 80));
            canvas.drawOval(new RectF(-s * 0.2f, -s * 0.2f, -s * 0.05f, -s * 0.05f), paint);
            canvas.drawOval(new RectF(s * 0.05f, -s * 0.2f, s * 0.2f, -s * 0.05f), paint);
            // smile
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.05f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path smile = new Path();
            smile.moveTo(-s * 0.15f, s * 0.07f);
            smile.cubicTo(-s * 0.07f, s * 0.18f, s * 0.07f, s * 0.18f, s * 0.15f, s * 0.07f);
            canvas.drawPath(smile, paint);

        } else { // pumpkin
            // ribs
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.color);
            paint.setAlpha(p.alpha);
            for (float ox : new float[]{-s*0.28f, -s*0.1f, s*0.1f, s*0.28f}) {
                canvas.drawOval(new RectF(ox - s * 0.2f, -s * 0.38f, ox + s * 0.2f, s * 0.35f), paint);
            }
            // darken edges
            paint.setColor(Color.argb(p.alpha / 3, 0, 0, 0));
            canvas.drawOval(new RectF(-s * 0.45f, -s * 0.38f, -s * 0.2f, s * 0.35f), paint);
            canvas.drawOval(new RectF(s * 0.2f, -s * 0.38f, s * 0.45f, s * 0.35f), paint);
            // stem
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.09f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.argb(p.alpha, 80, 150, 30));
            canvas.drawLine(0, -s * 0.38f, s * 0.06f, -s * 0.56f, paint);
            // face: triangle eyes
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(p.alpha, 10, 5, 0));
            Path eye = new Path();
            eye.moveTo(-s * 0.22f, -s * 0.1f); eye.lineTo(-s * 0.12f, -s * 0.22f); eye.lineTo(-s * 0.02f, -s * 0.1f); eye.close();
            canvas.drawPath(eye, paint);
            eye.reset();
            eye.moveTo(s * 0.02f, -s * 0.1f); eye.lineTo(s * 0.12f, -s * 0.22f); eye.lineTo(s * 0.22f, -s * 0.1f); eye.close();
            canvas.drawPath(eye, paint);
            // jagged mouth
            Path mouth = new Path();
            mouth.moveTo(-s * 0.28f, s * 0.08f);
            mouth.lineTo(-s * 0.18f, s * 0.2f);
            mouth.lineTo(-s * 0.1f,  s * 0.1f);
            mouth.lineTo(0,           s * 0.22f);
            mouth.lineTo(s * 0.1f,   s * 0.1f);
            mouth.lineTo(s * 0.18f,  s * 0.2f);
            mouth.lineTo(s * 0.28f,  s * 0.08f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.07f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(mouth, paint);
        }
    }

    // ── BIRTHDAY CONFETTI ────────────────────────────────────────────────────
    private void drawConfetti(Canvas canvas, Particle p) {
        float s = p.size;
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);
        if (p.type == 0) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(-s * 0.42f, -s * 0.2f, s * 0.42f, s * 0.2f, paint);
        } else if (p.type == 1) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0, 0, s * 0.32f, paint);
        } else if (p.type == 2) {
            paint.setStyle(Paint.Style.FILL);
            drawStar(canvas, 5, s * 0.42f, s * 0.18f, paint);
        } else { // curly ribbon
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.14f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path ribbon = new Path();
            ribbon.moveTo(-s * 0.42f, 0);
            ribbon.cubicTo(-s * 0.2f, -s * 0.28f, s * 0.2f, s * 0.28f, s * 0.42f, 0);
            canvas.drawPath(ribbon, paint);
        }
    }

    // ── NEW YEAR SPARKLES ────────────────────────────────────────────────────
    private void drawSparkle(Canvas canvas, Particle p) {
        float s = p.size;
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);
        if (p.type == 0) { // 4-point sparkle
            paint.setStyle(Paint.Style.FILL);
            Path sp = new Path();
            sp.moveTo(0, -s * 0.5f); sp.lineTo(s * 0.1f, -s * 0.1f);
            sp.lineTo(s * 0.5f, 0);  sp.lineTo(s * 0.1f,  s * 0.1f);
            sp.lineTo(0,  s * 0.5f); sp.lineTo(-s * 0.1f,  s * 0.1f);
            sp.lineTo(-s * 0.5f, 0); sp.lineTo(-s * 0.1f, -s * 0.1f);
            sp.close();
            canvas.drawPath(sp, paint);
        } else if (p.type == 1) { // 6-point star
            paint.setStyle(Paint.Style.FILL);
            drawStar(canvas, 6, s * 0.46f, s * 0.2f, paint);
        } else { // burst ring
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.09f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawCircle(0, 0, s * 0.3f, paint);
            for (int i = 0; i < 8; i++) {
                canvas.drawLine(s * 0.42f, 0, s * 0.6f, 0, paint);
                canvas.rotate(45f);
            }
        }
    }

    // ── SUMMER ───────────────────────────────────────────────────────────────
    private void drawSummer(Canvas canvas, Particle p) {
        float s = p.size;
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);
        if (p.type == 0) { // sun with rays
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0, 0, s * 0.3f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.09f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                canvas.drawLine(s * 0.4f, 0, s * 0.58f, 0, paint);
                canvas.rotate(45f);
            }
        } else if (p.type == 1) { // wave
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.13f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path wave = new Path();
            wave.moveTo(-s * 0.5f, 0);
            wave.cubicTo(-s * 0.25f, -s * 0.32f, s * 0.25f, s * 0.32f, s * 0.5f, 0);
            canvas.drawPath(wave, paint);
            // second wave below
            paint.setAlpha(p.alpha / 2);
            canvas.translate(0, s * 0.2f);
            canvas.drawPath(wave, paint);
        } else { // bubble
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.09f);
            canvas.drawCircle(0, 0, s * 0.38f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(p.alpha / 4);
            canvas.drawCircle(-s * 0.1f, -s * 0.13f, s * 0.1f, paint);
        }
    }

    // ── SPRING ───────────────────────────────────────────────────────────────
    private void drawSpring(Canvas canvas, Particle p) {
        float s = p.size;
        paint.setColor(p.color);
        paint.setAlpha(p.alpha);
        if (p.type == 0) { // elongated petal
            paint.setStyle(Paint.Style.FILL);
            Path petal = new Path();
            petal.moveTo(0, -s * 0.48f);
            petal.cubicTo(s * 0.22f, -s * 0.2f, s * 0.22f, s * 0.1f, 0, s * 0.12f);
            petal.cubicTo(-s * 0.22f, s * 0.1f, -s * 0.22f, -s * 0.2f, 0, -s * 0.48f);
            canvas.drawPath(petal, paint);
        } else if (p.type == 1) { // 5-petal flower
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 5; i++) {
                canvas.drawOval(new RectF(-s * 0.12f, -s * 0.42f, s * 0.12f, 0), paint);
                canvas.rotate(72f);
            }
            paint.setColor(Color.argb(p.alpha, 255, 240, 80));
            canvas.drawCircle(0, 0, s * 0.15f, paint);
        } else { // leaf
            paint.setStyle(Paint.Style.FILL);
            Path leaf = new Path();
            leaf.moveTo(0, -s * 0.48f);
            leaf.cubicTo(s * 0.28f, -s * 0.2f, s * 0.28f, s * 0.15f, 0, s * 0.12f);
            leaf.cubicTo(-s * 0.28f, s * 0.15f, -s * 0.28f, -s * 0.2f, 0, -s * 0.48f);
            canvas.drawPath(leaf, paint);
            // center vein
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.045f);
            paint.setColor(Color.argb(p.alpha / 2, 30, 100, 10));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(0, -s * 0.42f, 0, s * 0.08f, paint);
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────
    private void drawStar(Canvas canvas, int points, float outer, float inner, Paint p) {
        Path path = new Path();
        double angle = -Math.PI / 2;
        double step = Math.PI / points;
        path.moveTo((float)(outer * Math.cos(angle)), (float)(outer * Math.sin(angle)));
        for (int i = 0; i < points; i++) {
            angle += step;
            path.lineTo((float)(inner * Math.cos(angle)), (float)(inner * Math.sin(angle)));
            angle += step;
            path.lineTo((float)(outer * Math.cos(angle)), (float)(outer * Math.sin(angle)));
        }
        path.close();
        canvas.drawPath(path, p);
    }

    private static class Particle {
        float x, y, vx, vy, size, rotation, vr;
        int alpha, type, color;

        void step() {
            x += vx;
            y += vy;
            rotation += vr;
        }

        boolean dead(int w, int h) {
            return y > h + size * 2 || y < -h || x < -size * 3 || x > w + size * 3;
        }
    }
}
