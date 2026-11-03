package org.freenetproject.mobile.ui.main.activity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Full-screen transparent overlay that draws seasonal particles:
 *   SNOW     (December 24–26): falling white snowflakes
 *   FISH     (April 1): fish swimming across the screen
 *   HALLOWEEN (October 31): falling skulls with red tint
 */
public class SeasonalOverlayView extends View {

    public enum Season { NONE, SNOW, FISH, HALLOWEEN, BIRTHDAY, NEW_YEAR, SUMMER, SPRING }

    private Season season = Season.NONE;
    private final List<Particle> particles = new ArrayList<>();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint();
    private final Random rng = new Random();
    private boolean running = false;

    private static final int PARTICLE_COUNT = 28;
    private static final long FRAME_MS = 40; // ~25 fps

    public SeasonalOverlayView(Context context) { super(context); init(); }
    public SeasonalOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
        tintPaint.setColor(Color.argb(55, 180, 0, 0)); // subtle red for Halloween
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
            for (Particle p : particles) p.step(getWidth(), getHeight());
            particles.removeIf(p -> p.dead(getWidth(), getHeight()));
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    private void spawnParticle() {
        Particle p = new Particle();
        p.x = rng.nextFloat() * getWidth();
        switch (season) {
            case SNOW:
                String[] snowEmojis = {"❄","❅","❆","🎅","🦌","🎄","⛄","🎁"};
                // more snowflakes than Santas
                p.emoji = rng.nextInt(5) < 3 ? snowEmojis[rng.nextInt(3)] : snowEmojis[3 + rng.nextInt(5)];
                p.size = 18 + rng.nextInt(20);
                p.vy = 1.5f + rng.nextFloat() * 2f;
                p.vx = (rng.nextFloat() - 0.5f) * 1.2f;
                p.y = -p.size;
                p.alpha = 180 + rng.nextInt(75);
                break;
            case FISH:
                String[] fishEmojis = {"🐟","🐠","🐡","🦈","🐙","🦑","🦐","🐬"};
                p.emoji = fishEmojis[rng.nextInt(fishEmojis.length)];
                p.size = 22 + rng.nextInt(18);
                p.vx = (rng.nextBoolean() ? 1 : -1) * (1.5f + rng.nextFloat() * 2f);
                p.x = p.vx > 0 ? -p.size : getWidth() + p.size;
                p.y = 60 + rng.nextFloat() * (getHeight() - 120);
                p.vy = (rng.nextFloat() - 0.5f) * 0.6f;
                p.alpha = 200 + rng.nextInt(55);
                break;
            case HALLOWEEN:
                String[] hwEmojis = {"🧟","💀","🦇","🕷","👻","🕸","🎃","🧛"};
                p.emoji = hwEmojis[rng.nextInt(hwEmojis.length)];
                p.size = 20 + rng.nextInt(22);
                p.vy = 1.2f + rng.nextFloat() * 1.8f;
                p.vx = (rng.nextFloat() - 0.5f) * 1.5f;
                p.y = -p.size;
                p.alpha = 190 + rng.nextInt(65);
                break;
            case BIRTHDAY:
                String[] bEmojis = {"🎉","🎊","🎈","🎂","🎁","✨"};
                p.emoji = bEmojis[rng.nextInt(bEmojis.length)];
                p.size = 22 + rng.nextInt(20);
                p.vy = 1.0f + rng.nextFloat() * 2.0f;
                p.vx = (rng.nextFloat() - 0.5f) * 2.5f;
                p.y = -p.size;
                p.alpha = 200 + rng.nextInt(55);
                break;
            case NEW_YEAR:
                String[] nyEmojis = {"🎆","🎇","✨","⭐","🌟","💫"};
                p.emoji = nyEmojis[rng.nextInt(nyEmojis.length)];
                p.size = 18 + rng.nextInt(22);
                p.vy = -(2.0f + rng.nextFloat() * 2.5f); // sale verso l'alto
                p.vx = (rng.nextFloat() - 0.5f) * 2.0f;
                p.y = getHeight() + p.size;
                p.alpha = 220 + rng.nextInt(35);
                break;
            case SUMMER:
                String[] sumEmojis = {"☀","🌊","🐚","🏄","🌴","⛱"};
                p.emoji = sumEmojis[rng.nextInt(sumEmojis.length)];
                p.size = 20 + rng.nextInt(22);
                p.vx = (rng.nextBoolean() ? 1 : -1) * (1.0f + rng.nextFloat() * 1.5f);
                p.x = p.vx > 0 ? -p.size : getWidth() + p.size;
                p.y = getHeight() * 0.3f + rng.nextFloat() * (getHeight() * 0.6f);
                p.vy = (rng.nextFloat() - 0.5f) * 0.8f;
                p.alpha = 200 + rng.nextInt(55);
                break;
            case SPRING:
                String[] spEmojis = {"🌸","🌺","🦋","🌼","🌷","🌿"};
                p.emoji = spEmojis[rng.nextInt(spEmojis.length)];
                p.size = 18 + rng.nextInt(18);
                p.vy = 0.8f + rng.nextFloat() * 1.5f;
                p.vx = (rng.nextFloat() - 0.5f) * 2.0f;
                p.y = -p.size;
                p.alpha = 190 + rng.nextInt(65);
                break;
        }
        particles.add(p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (season == Season.HALLOWEEN) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), tintPaint);
        }
        for (Particle p : particles) {
            textPaint.setTextSize(p.size);
            textPaint.setAlpha(p.alpha);
            canvas.drawText(p.emoji, p.x, p.y, textPaint);
        }
    }

    private static class Particle {
        String emoji;
        float x, y, vx, vy, size;
        int alpha;

        void step(int w, int h) {
            x += vx;
            y += vy;
        }

        boolean dead(int w, int h) {
            return y > h + size || x < -size * 2 || x > w + size * 2;
        }
    }
}
