package com.cybermining.idleminer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {

    // =====================================================================
    // ADMOB: SEUS IDs. Para trocar, edite só estas duas linhas.
    // (O ID do APP fica no AndroidManifest.xml)
    // Para testar sem risco, use os IDs de teste do Google:
    //   banner:   ca-app-pub-3940256099942544/6300978111
    //   premiado: ca-app-pub-3940256099942544/5224354917
    // =====================================================================
    static final String BANNER_ID   = "ca-app-pub-8249414808542861/2173931218";
    static final String REWARDED_ID = "ca-app-pub-8249414808542861/5678061953";

    RewardedAd rewardedAd;
    boolean loadingAd = false;
    GameView game;
    AdView banner;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        game = new GameView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14111F);
        root.addView(game, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Banner fixo no rodapé
        banner = new AdView(this);
        banner.setAdUnitId(BANNER_ID);
        banner.setAdSize(AdSize.BANNER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(banner, lp);
        setContentView(root);

        MobileAds.initialize(this, status -> loadRewarded());
        banner.loadAd(new AdRequest.Builder().build());
        game.offlineWelcome();
    }

    @Override protected void onPause() { game.save(); banner.pause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); banner.resume(); }
    @Override protected void onDestroy() { banner.destroy(); super.onDestroy(); }

    // ---------------- Vídeo premiado ----------------
    void loadRewarded() {
        if (rewardedAd != null || loadingAd) return;
        loadingAd = true;
        RewardedAd.load(this, REWARDED_ID, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override public void onAdLoaded(RewardedAd ad) { rewardedAd = ad; loadingAd = false; }
            @Override public void onAdFailedToLoad(LoadAdError e) { rewardedAd = null; loadingAd = false; }
        });
    }

    /** Mostra o vídeo. onReward roda só se o jogador assistir até o fim; onFail roda se não. */
    void showRewarded(final Runnable onReward, final Runnable onFail) {
        if (rewardedAd == null) {
            Toast.makeText(this, "Vídeo indisponível. Tente de novo em instantes.", Toast.LENGTH_SHORT).show();
            loadRewarded();
            if (onFail != null) onFail.run();
            return;
        }
        final boolean[] earned = {false};
        final RewardedAd ad = rewardedAd;
        rewardedAd = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                loadRewarded();
                if (earned[0]) onReward.run(); else if (onFail != null) onFail.run();
            }
            @Override public void onAdFailedToShowFullScreenContent(AdError e) {
                loadRewarded();
                if (onFail != null) onFail.run();
            }
        });
        ad.show(this, item -> earned[0] = true);
    }

    // =====================================================================
    // JOGO
    // =====================================================================
    static class Btn {
        RectF r; Runnable a; boolean hud;
        Btn(RectF r, Runnable a, boolean hud) { this.r = r; this.a = a; this.hud = hud; }
    }
    static class Fl { float x, y, age; String s; }

    class GameView extends View {
        static final int MAX = 8;
        final float dens;
        double cash = 0;
        int nShafts = 1, elevL = 0, whL = 0;
        int[] lvl = new int[MAX];
        double[] stash = new double[MAX];
        double depot = 0, elevLoad = 0, whLoad = 0;
        float elevPhase = 0, whPhase = 0, time = 0, scrollY = 0, offY = 0, hudH;
        long boostUntil = 0, last = 0;

        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rf = new RectF();
        final Path pa = new Path();
        final ArrayList<Btn> btns = new ArrayList<>();
        final ArrayList<Fl> fls = new ArrayList<>();
        Canvas cv;
        Shader sky;
        float[] stars = new float[60];
        final GestureDetector gd;

        GameView(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            Random r = new Random(7);
            for (int i = 0; i < stars.length; i++) stars[i] = r.nextFloat();
            lvl[0] = 1;
            load();
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    scrollY += dy; invalidate(); return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    for (int k = btns.size() - 1; k >= 0; k--) {
                        Btn b = btns.get(k);
                        if (!b.hud && e.getY() < hudH) continue;
                        if (b.r.contains(e.getX(), e.getY())) { b.a.run(); invalidate(); return true; }
                    }
                    return true;
                }
            });
        }

        float d(float v) { return v * dens; }

        // ---------- economia ----------
        double mineRate(int i) { return lvl[i] * 1.5 * Math.pow(3, i); }
        double elevCap() { return 40 * (1 + 0.5 * elevL); }
        double elevPeriod() { return 8 / (1 + 0.08 * elevL); }
        double whCap() { return 30 * (1 + 0.5 * whL); }
        double whPeriod() { return 6 / (1 + 0.08 * whL); }
        double mult() { return System.currentTimeMillis() < boostUntil ? 2 : 1; }
        double incomeRate() {
            double m = 0;
            for (int i = 0; i < nShafts; i++) m += mineRate(i);
            return Math.min(m, Math.min(elevCap() / elevPeriod(), whCap() / whPeriod()));
        }
        double upCost(int i) { return Math.ceil(12 * Math.pow(4, i) * Math.pow(1.17, lvl[i])); }
        double newShaftCost() { return 300 * Math.pow(6, nShafts - 1); }
        double elevCost() { return Math.ceil(80 * Math.pow(1.35, elevL)); }
        double whCost() { return Math.ceil(80 * Math.pow(1.35, whL)); }

        void upgrade(int i) { double c = upCost(i); if (cash >= c) { cash -= c; lvl[i]++; } }

        // ---------- salvar / carregar ----------
        void save() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nShafts; i++) sb.append(lvl[i]).append(',');
            getContext().getSharedPreferences("s", 0).edit()
                .putLong("cash", Double.doubleToRawLongBits(cash))
                .putInt("ns", nShafts).putString("lv", sb.toString())
                .putInt("el", elevL).putInt("wl", whL)
                .putLong("bu", boostUntil).putLong("t", System.currentTimeMillis()).apply();
        }
        void load() {
            SharedPreferences s = getContext().getSharedPreferences("s", 0);
            if (!s.contains("ns")) return;
            cash = Double.longBitsToDouble(s.getLong("cash", 0));
            nShafts = Math.max(1, Math.min(MAX, s.getInt("ns", 1)));
            String[] a = s.getString("lv", "1,").split(",");
            for (int i = 0; i < nShafts && i < a.length; i++) {
                try { lvl[i] = Math.max(1, Integer.parseInt(a[i])); } catch (Exception e) { lvl[i] = 1; }
            }
            elevL = s.getInt("el", 0); whL = s.getInt("wl", 0); boostUntil = s.getLong("bu", 0);
        }

        /** Dinheiro offline: metade da produção por até 8 horas; pode dobrar com vídeo. */
        void offlineWelcome() {
            long t = getContext().getSharedPreferences("s", 0).getLong("t", 0);
            if (t == 0) return;
            double secs = Math.min(8 * 3600, (System.currentTimeMillis() - t) / 1000.0);
            final double earn = incomeRate() * secs * 0.5;
            if (secs < 60 || earn < 1) return;
            new AlertDialog.Builder(getContext())
                .setTitle("Bem-vindo de volta!")
                .setMessage("Seus mineradores geraram $" + fmt(earn) + " enquanto você estava fora.")
                .setCancelable(false)
                .setPositiveButton("Coletar", (dlg, w) -> cash += earn)
                .setNegativeButton("Dobrar (vídeo)", (dlg, w) ->
                    showRewarded(() -> cash += earn * 2, () -> cash += earn))
                .show();
        }

        // ---------- simulação ----------
        void update(float dt) {
            time += dt;
            for (int i = 0; i < nShafts; i++) stash[i] += mineRate(i) * dt;

            float pe = elevPhase;
            elevPhase += (float) (dt / elevPeriod());
            if (pe < 0.5f && elevPhase >= 0.5f) {           // chegou no fundo: carrega
                double cap = elevCap(); elevLoad = 0;
                for (int i = 0; i < nShafts && cap > 0; i++) {
                    double t = Math.min(stash[i], cap); stash[i] -= t; cap -= t; elevLoad += t;
                }
            }
            if (elevPhase >= 1) { elevPhase -= 1; depot += elevLoad; elevLoad = 0; }

            float pw = whPhase;
            whPhase += (float) (dt / whPeriod());
            if (pw < 0.5f && whPhase >= 0.5f) {             // pega no depósito
                double t = Math.min(depot, whCap()); depot -= t; whLoad = t;
            }
            if (whPhase >= 1) {                              // entrega no armazém: vira dinheiro
                whPhase -= 1;
                if (whLoad > 0) {
                    double g = whLoad * mult(); cash += g; whLoad = 0;
                    Fl f = new Fl(); f.s = "+$" + fmt(g); f.x = getWidth() / 2f; f.y = d(150);
                    fls.add(f);
                }
            }
            for (int i = fls.size() - 1; i >= 0; i--) { fls.get(i).age += dt; if (fls.get(i).age > 1.2f) fls.remove(i); }
        }

        // ---------- desenho ----------
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            sky = new LinearGradient(0, 0, 0, d(190), 0xFF1A1450, 0xFF6A3E9C, Shader.TileMode.CLAMP);
        }

        void txt(String s, float x, float y, float size, int color, Paint.Align al) {
            tp.setTextSize(size); tp.setColor(color); tp.setTextAlign(al); cv.drawText(s, x, y, tp);
        }
        void rr(float l, float t, float r, float b, float rad, int color) {
            p.setColor(color); rf.set(l, t, r, b); cv.drawRoundRect(rf, rad, rad, p);
        }
        void addBtn(float l, float t, float r, float b, boolean hud, Runnable a) {
            btns.add(new Btn(new RectF(l, t + offY, r, b + offY), a, hud));
        }
        void pill(float l, float t, float r, float b, String s, boolean ok, Runnable a) {
            rr(l, t, r, b, d(10), ok ? 0xFF3DBE4A : 0xFF7D7A86);
            txt(s, (l + r) / 2, (t + b) / 2 + d(4.5f), d(13), 0xFFFFFFFF, Paint.Align.CENTER);
            addBtn(l, t, r, b, false, a);
        }
        void crystal(float x, float y, float s, int color) {
            pa.reset(); pa.moveTo(x, y - s); pa.lineTo(x + s * .6f, y); pa.lineTo(x, y + s * .7f); pa.lineTo(x - s * .6f, y); pa.close();
            p.setColor(color); cv.drawPath(pa, p);
        }
        void miner(float x, float y, boolean carry) {
            float bob = (float) Math.sin(time * 8 + x) * d(1.5f);
            rr(x - d(6), y - d(22) + bob, x + d(6), y - d(6), d(4), 0xFF27C3D9);      // corpo
            rr(x - d(5), y - d(7), x + d(5), y, d(2), 0xFF2B2F3A);                    // pernas
            p.setColor(0xFFF1C27D); cv.drawCircle(x, y - d(28) + bob, d(6), p);       // cabeça
            p.setColor(0xFFE9ECF5); cv.drawCircle(x, y - d(31) + bob, d(6.5f), p);    // capacete
            p.setColor(0xFFFFE066); cv.drawCircle(x + d(3), y - d(32) + bob, d(2), p); // lanterna
            if (carry) crystal(x + d(9), y - d(14), d(4), 0xFF5FE3FF);
        }

        @Override protected void onDraw(Canvas c) {
            long now = System.nanoTime();
            float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            update(dt);
            cv = c; btns.clear();
            c.drawColor(0xFF14111F);

            float W = getWidth(); hudH = d(64);
            float surfH = d(190), shH = d(112);
            float total = surfH + nShafts * shH + d(80);
            scrollY = Math.max(0, Math.min(Math.max(0, total - (getHeight() - hudH)), scrollY));
            offY = hudH - scrollY;

            c.save();
            c.clipRect(0, hudH, W, getHeight());
            c.translate(0, offY);
            drawSurface(W, surfH);
            for (int i = 0; i < nShafts; i++) drawShaft(i, W, surfH, shH);
            drawElevator(surfH, shH);
            if (nShafts < MAX) {
                final double cost = newShaftCost();
                float y = surfH + nShafts * shH + d(14);
                pill(W / 2 - d(110), y, W / 2 + d(110), y + d(44), "Novo poço  $" + fmt(cost), cash >= cost, () -> {
                    if (cash >= cost) { cash -= cost; lvl[nShafts] = 1; nShafts++; }
                });
            }
            c.restore();
            offY = 0;
            drawHud(W);
            postInvalidateOnAnimation();
        }

        void drawSurface(float W, float surfH) {
            p.setShader(sky); cv.drawRect(0, 0, W, surfH, p); p.setShader(null);
            p.setColor(0xCCFFFFFF);
            for (int i = 0; i + 1 < stars.length; i += 2) cv.drawCircle(stars[i] * W, stars[i + 1] * surfH * .55f, d(1), p);
            p.setColor(0xFFE9E4FF); cv.drawCircle(W * .62f, d(52), d(22), p);
            float gy = surfH - d(30);
            p.setColor(0xFF3A2A6E); pa.reset(); pa.moveTo(W * .18f, gy); pa.lineTo(W * .40f, d(100)); pa.lineTo(W * .66f, gy); pa.close(); cv.drawPath(pa, p);
            p.setColor(0xFF2E2160); pa.reset(); pa.moveTo(W * .42f, gy); pa.lineTo(W * .60f, d(120)); pa.lineTo(W * .80f, gy); pa.close(); cv.drawPath(pa, p);
            p.setColor(0xFF3B2D63); cv.drawRect(0, gy, W, surfH, p);

            // Torre do elevador (esquerda)
            rr(d(12), d(22), d(92), gy, d(6), 0xFF2E5A78);
            rr(d(8), d(10), d(96), d(24), d(5), 0xFFE8742A);
            txt("ELEVADOR", d(52), d(40), d(10), 0xFFFFFFFF, Paint.Align.CENTER);
            rr(d(24), d(48), d(80), d(80), d(7), 0xFF1E7FC2);
            txt("Nv. " + (elevL + 1), d(52), d(70), d(15), 0xFFFFFFFF, Paint.Align.CENTER);
            final double ec = elevCost();
            pill(d(18), gy - d(72), d(86), gy - d(44), "▲ $" + fmt(ec), cash >= ec, () -> { if (cash >= ec) { cash -= ec; elevL++; } });

            // Armazém (direita)
            rr(W - d(104), d(22), W - d(12), gy, d(6), 0xFF2E5A78);
            rr(W - d(108), d(10), W - d(8), d(24), d(5), 0xFFE8742A);
            txt("ARMAZÉM", W - d(58), d(40), d(10), 0xFFFFFFFF, Paint.Align.CENTER);
            rr(W - d(86), d(48), W - d(30), d(80), d(7), 0xFF1E7FC2);
            txt("Nv. " + (whL + 1), W - d(58), d(70), d(15), 0xFFFFFFFF, Paint.Align.CENTER);
            final double wc = whCost();
            pill(W - d(92), gy - d(72), W - d(24), gy - d(44), "▲ $" + fmt(wc), cash >= wc, () -> { if (cash >= wc) { cash -= wc; whL++; } });

            // Depósito + carregador entre as torres
            float dx = d(108), wx = W - d(116);
            txt("Depósito: " + fmt(depot), (dx + wx) / 2, d(100), d(11), 0xFFFFFFFF, Paint.Align.CENTER);
            int n = (int) Math.min(8, Math.ceil(depot / Math.max(1, whCap() * .2)));
            for (int k = 0; k < n; k++) crystal(dx + d(8) + (k % 4) * d(9), gy - d(6) - (k / 4) * d(9), d(4), k % 2 == 0 ? 0xFF5FE3FF : 0xFFB57BFF);
            float q = whPhase;
            float mx = q < .5f ? wx + (dx + d(20) - wx) * (q / .5f) : dx + d(20) + (wx - dx - d(20)) * ((q - .5f) / .5f);
            miner(mx, gy + d(2), q >= .5f && whLoad > 0);

            for (Fl f : fls) {
                tp.setAlpha(Math.max(0, (int) (255 * (1 - f.age / 1.2f))));
                tp.setTextSize(d(16)); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(0xFF7BFF9A);
                tp.setAlpha(Math.max(0, (int) (255 * (1 - f.age / 1.2f))));
                cv.drawText(f.s, f.x, f.y - f.age * d(40), tp);
            }
            tp.setAlpha(255);
        }

        void drawShaft(final int i, float W, float surfH, float shH) {
            float y0 = surfH + i * shH;
            p.setColor(0xFF4A3A58); cv.drawRect(0, y0, W, y0 + shH, p);
            p.setColor(0xFF252233); cv.drawRect(d(92), y0 + d(8), W, y0 + shH - d(8), p);
            p.setColor(0xFF1C2230); cv.drawRect(d(12), y0, d(92), y0 + shH, p);
            txt("" + (i + 1), d(20), y0 + d(18), d(12), 0xFF8A93A6, Paint.Align.CENTER);
            float wx = W * .58f;
            p.setColor(0xFF5B3F7A); cv.drawRect(wx, y0 + d(8), W, y0 + shH - d(8), p);
            Random r = new Random(i * 31 + 5);
            for (int k = 0; k < 9; k++)
                crystal(wx + d(10) + r.nextFloat() * (W - wx - d(20)), y0 + d(20) + r.nextFloat() * (shH - d(40)), d(5 + r.nextInt(4)), k % 2 == 0 ? 0xFF5FE3FF : 0xFFB57BFF);

            float sx = d(104), fy = y0 + shH - d(12);
            int n = (int) Math.min(10, Math.ceil(stash[i] / Math.max(1, elevCap() * .15)));
            for (int k = 0; k < n; k++) crystal(sx + (k % 5) * d(7), fy - d(4) - (k / 5) * d(8), d(4), k % 2 == 0 ? 0xFF5FE3FF : 0xFFB57BFF);
            txt(fmt(stash[i]), sx - d(4), y0 + d(26), d(11), 0xFFFFFFFF, Paint.Align.LEFT);

            int nm = Math.min(3, 1 + lvl[i] / 8);
            for (int k = 0; k < nm; k++) {
                double a = time * 1.6 + k * 1.9 + i;
                float ph = (float) (Math.sin(a) * .5 + .5);
                float x0 = sx + d(50), x1 = wx - d(14);
                miner(x0 + ph * (x1 - x0), fy, Math.cos(a) < 0);
            }

            rr(W - d(80), y0 + d(12), W - d(12), y0 + d(42), d(7), 0xFF1E7FC2);
            txt("Nv. " + lvl[i], W - d(46), y0 + d(33), d(15), 0xFFFFFFFF, Paint.Align.CENTER);
            final double cost = upCost(i);
            pill(W - d(92), y0 + shH - d(48), W - d(8), y0 + shH - d(18), "▲ $" + fmt(cost), cash >= cost, () -> upgrade(i));
        }

        void drawElevator(float surfH, float shH) {
            float yTop = surfH - d(30) - d(40), yBot = surfH + nShafts * shH - d(48);
            float t = elevPhase < .5f ? elevPhase * 2 : 2 - elevPhase * 2;
            float y = yTop + (yBot - yTop) * t;
            p.setColor(0xFF8A93A6); p.setStrokeWidth(d(2)); float gy0 = surfH - d(30); cv.drawLine(d(52), Math.min(gy0, y), d(52), y, p);
            rr(d(26), y, d(78), y + d(40), d(5), 0xFFE8742A);
            rr(d(32), y + d(8), d(72), y + d(26), d(3), 0xFFCFE9FF);
            if (elevLoad > 0 && elevPhase >= .5f) {
                crystal(d(40), y + d(18), d(5), 0xFF5FE3FF);
                crystal(d(52), y + d(16), d(6), 0xFFB57BFF);
                crystal(d(64), y + d(18), d(5), 0xFF5FE3FF);
            }
        }

        void drawHud(float W) {
            p.setColor(0xFF141A2A); cv.drawRect(0, 0, W, hudH, p);
            p.setColor(0xFFF5C518); cv.drawCircle(d(30), hudH / 2, d(18), p);
            txt("$", d(30), hudH / 2 + d(7), d(20), 0xFF8A5A00, Paint.Align.CENTER);
            txt(fmt(cash), d(56), hudH / 2 - d(2), d(22), 0xFFFFFFFF, Paint.Align.LEFT);
            txt("+" + fmt(incomeRate() * mult()) + "/s", d(56), hudH / 2 + d(18), d(12), 0xFF6FE38A, Paint.Align.LEFT);

            boolean on = System.currentTimeMillis() < boostUntil;
            String label;
            if (on) {
                long s = (boostUntil - System.currentTimeMillis()) / 1000;
                label = String.format(Locale.US, "x2  %d:%02d", s / 60, s % 60);
            } else label = "▶ x2 (5 min)";
            rr(W - d(128), d(12), W - d(10), hudH - d(12), d(12), on ? 0xFF7D7A86 : 0xFFD6246E);
            txt(label, W - d(69), hudH / 2 + d(5), d(14), 0xFFFFFFFF, Paint.Align.CENTER);
            addBtn(W - d(128), d(12), W - d(10), hudH - d(12), true, () -> {
                if (System.currentTimeMillis() < boostUntil) return;
                showRewarded(() -> boostUntil = System.currentTimeMillis() + 5 * 60 * 1000, null);
            });
        }

        @Override public boolean onTouchEvent(MotionEvent e) { return gd.onTouchEvent(e); }
    }

    static String fmt(double v) {
        if (v < 1000) return String.format(Locale.US, "%.0f", v);
        String[] u = {"K", "M", "B", "T", "Qa", "Qi"};
        int i = -1;
        while (v >= 1000 && i < u.length - 1) { v /= 1000; i++; }
        return String.format(Locale.US, "%.2f%s", v, u[i]);
    }
}
