package io.github.louisbrusset.juliawallpaper;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Moteur de fonds d'écran fractals, version 2 (même algorithme que
 * src/fractal-render.c du projet Linux). Java pur, testable sur une JVM.
 *
 * 1. tirage d'un TYPE de fractale (Julia, Julia multi, Phoenix, zoom
 *    Mandelbrot / Burning Ship / Tricorne, Newton, Lyapunov, tapis de
 *    Sierpinski généralisé, triangle de Sierpinski, IFS) ;
 * 2. tirage de plusieurs jeux de paramètres de ce type, notés sur un aperçu
 *    (densité de détails, remplissage, rapport largeur/hauteur) ;
 * 3. cadrage : objet entier (axe principal le long de l'écran, rapport entre
 *    1:1 et celui de l'écran) ou zoom plein écran ;
 * 4. tirage d'une PALETTE : thème prédéfini, monochrome, bicolore,
 *    tricolore ou arc-en-ciel, générée en OKLCH, variantes jour et nuit ;
 * 5. rendu multi-thread, anti-crénelage adaptatif, PNG écrit en flux.
 *
 * Mémoire : 4 octets par pixel (+4 pour les IFS), les images couleur ne sont
 * jamais entières en mémoire.
 */
public final class Renderer {

    /* ================================================================ */
    /* Aléatoire (splitmix64)                                            */

    private long rngState;
    private long u64() {
        long z = (rngState += 0x9e3779b97f4a7c15L);
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
    private double rnd() { return (u64() >>> 11) * (1.0 / 9007199254740992.0); }
    private double rndIn(double a, double b) { return a + (b - a) * rnd(); }
    private int rndInt(int n) { return (int) (rnd() * n); }
    private int pick(double[] w) {
        double r = rnd(), acc = 0;
        for (int i = 0; i < w.length; i++) { acc += w[i]; if (r < acc) return i; }
        return w.length - 1;
    }

    /* ================================================================ */
    /* Couleurs                                                          */

    static final int LUT_N = 1024;
    static final class Lut { final float[] ext = new float[(LUT_N + 1) * 3]; final float[] in0 = new float[3], in1 = new float[3]; }

    private static float srgbToLin(float c) { return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f); }
    private static void hexLin(int h, float[] o) {
        o[0] = srgbToLin(((h >> 16) & 255) / 255f);
        o[1] = srgbToLin(((h >> 8) & 255) / 255f);
        o[2] = srgbToLin((h & 255) / 255f);
    }
    private static void linOklab(float[] c, float[] o) {
        float l = (float) Math.cbrt(0.4122214708f*c[0] + 0.5363325363f*c[1] + 0.0514459929f*c[2]);
        float m = (float) Math.cbrt(0.2119034982f*c[0] + 0.6806995451f*c[1] + 0.1073969566f*c[2]);
        float s = (float) Math.cbrt(0.0883024619f*c[0] + 0.2817188376f*c[1] + 0.6299787005f*c[2]);
        o[0] = 0.2104542553f*l + 0.7936177850f*m - 0.0040720468f*s;
        o[1] = 1.9779984951f*l - 2.4285922050f*m + 0.4505937099f*s;
        o[2] = 0.0259040371f*l + 0.7827717662f*m - 0.8086757660f*s;
    }
    /** OKLab -> RVB linéaire, sans écrêtage. */
    private static void oklabLinRaw(float L, float A, float B, float[] o, int off) {
        float l = L + 0.3963377774f*A + 0.2158037573f*B;
        float m = L - 0.1055613458f*A - 0.0638541728f*B;
        float s = L - 0.0894841775f*A - 1.2914855480f*B;
        l = l*l*l; m = m*m*m; s = s*s*s;
        o[off]     =  4.0767416621f*l - 3.3077115913f*m + 0.2309699292f*s;
        o[off + 1] = -1.2684380046f*l + 2.6097574011f*m - 0.3413193965f*s;
        o[off + 2] = -0.0041960863f*l - 0.7034186147f*m + 1.7076147010f*s;
    }
    private static boolean inGamut(float[] o, int off) {
        for (int i = 0; i < 3; i++) if (o[off + i] < -1e-4f || o[off + i] > 1.0001f) return false;
        return true;
    }
    /** OKLab -> RVB linéaire dans le gamut sRGB : on réduit la chroma (teinte et luminance gardées). */
    private static void oklabLin(float L, float A, float B, float[] o, int off) {
        oklabLinRaw(L, A, B, o, off);
        if (!inGamut(o, off)) {
            float lo = 0f, hi = 1f;
            for (int it = 0; it < 14; it++) {
                float mid = 0.5f * (lo + hi);
                oklabLinRaw(L, A * mid, B * mid, o, off);
                if (inGamut(o, off)) lo = mid; else hi = mid;
            }
            oklabLinRaw(L, A * lo, B * lo, o, off);
        }
        for (int i = 0; i < 3; i++) o[off + i] = Math.max(0f, Math.min(1f, o[off + i]));
    }

    /** Palette définie par des arrêts en OKLab (L, a, b). */
    static Lut buildLut(float[] pos, float[][] lab, float[][] inLab) {
        Lut L = new Lut(); int n = pos.length;
        for (int k = 0; k <= LUT_N; k++) {
            float t = (float) k / LUT_N; int j = 0;
            while (j < n - 2 && t > pos[j + 1]) j++;
            float u = (t - pos[j]) / (pos[j + 1] - pos[j]);
            u = Math.max(0f, Math.min(1f, u));
            oklabLin(lab[j][0] + (lab[j+1][0] - lab[j][0]) * u,
                     lab[j][1] + (lab[j+1][1] - lab[j][1]) * u,
                     lab[j][2] + (lab[j+1][2] - lab[j][2]) * u, L.ext, k * 3);
        }
        oklabLin(inLab[0][0], inLab[0][1], inLab[0][2], L.in0, 0);
        oklabLin(inLab[1][0], inLab[1][1], inLab[1][2], L.in1, 0);
        return L;
    }
    private static float[] lch(double L, double C, double hDeg) {
        double h = Math.toRadians(hDeg);
        return new float[]{(float) L, (float) (C * Math.cos(h)), (float) (C * Math.sin(h))};
    }
    private static float[] hexLab(int hex) { float[] c = new float[3], o = new float[3]; hexLin(hex, c); linOklab(c, o); return o; }

    /* Thèmes prédéfinis (v1), t = 0 : fond, t = 1 : contact de l'ensemble */
    private static final float[] P6 = {0f, .30f, .55f, .75f, .90f, 1f};
    private static final String[] THEME_NAME = {"aurore", "braise", "nebuleuse", "ocean", "jade"};
    private static final int[][] THEME_NIGHT = {
        {0x04070c, 0x08202c, 0x0d5560, 0x2fae94, 0xa8f0c8, 0xf4fff8, 0x02060a, 0x082629},
        {0x070305, 0x220810, 0x6e1418, 0xd0501a, 0xffbb4a, 0xfff4da, 0x0b0306, 0x2a0a0c},
        {0x05040d, 0x160c33, 0x4a1a6e, 0xb33680, 0xff98b4, 0xfff2f6, 0x070516, 0x1d0c38},
        {0x02040b, 0x061c38, 0x0c4c86, 0x2f98d0, 0x9fe0f4, 0xf2fdff, 0x020610, 0x08203c},
        {0x040706, 0x0e2019, 0x285640, 0xa58f44, 0xf0d888, 0xfffbe8, 0x030806, 0x112a1f},
    };
    private static final int[][] THEME_DAY = {
        {0xc6d6cf, 0xb2cbc2, 0x7fb0a4, 0x3a8580, 0x15485a, 0x061824, 0xbdd3ca, 0x8fbcb0},
        {0xdcc9b2, 0xd9b795, 0xcd8c5a, 0xa8432a, 0x5c1620, 0x2a0810, 0xe0c19f, 0xcb926b},
        {0xcbc2d8, 0xbdaed4, 0x9a7dc4, 0x64399a, 0x31155a, 0x150830, 0xc6b9de, 0xa38acb},
        {0xc2d0d9, 0xabc4d4, 0x7eaacb, 0x2f6d9f, 0x123f6c, 0x061a34, 0xb9cedb, 0x8ab4ce},
        {0xd3ccb8, 0xc9c0a0, 0xb09e66, 0x4f775a, 0x1d3d2f, 0x0b1a14, 0xcfc6a6, 0xb2a16c},
    };

    /* Rampes de luminance / chroma des palettes générées (néon la nuit, pastel le jour) */
    private static final float[] RAMP_POS = {0f, .30f, .55f, .75f, .90f, 1f};
    private static final float[] N_L = {0.11f, 0.24f, 0.44f, 0.68f, 0.87f, 0.98f};
    private static final float[] N_C = {0.030f, 0.075f, 0.140f, 0.190f, 0.130f, 0.030f};
    private static final float[] D_L = {0.86f, 0.80f, 0.68f, 0.50f, 0.33f, 0.20f};
    private static final float[] D_C = {0.035f, 0.060f, 0.110f, 0.130f, 0.100f, 0.050f};
    private static final float[] P8 = {0f, .20f, .35f, .50f, .65f, .78f, .90f, 1f};
    private static float ramp(float[] v, float t) {
        int j = 0; while (j < RAMP_POS.length - 2 && t > RAMP_POS[j + 1]) j++;
        float u = (t - RAMP_POS[j]) / (RAMP_POS[j + 1] - RAMP_POS[j]);
        return v[j] + (v[j + 1] - v[j]) * Math.max(0f, Math.min(1f, u));
    }
    private static double hueMix(double a, double b, double u) {
        double d = ((b - a) % 360 + 540) % 360 - 180;     // plus court chemin
        return a + d * u;
    }

    static final class Pal { String name; boolean multi; Lut day, night; }

    static final String[] PAL_KIND = {"theme", "mono", "duo", "trio", "arc-en-ciel"};
    private static final double[] PAL_W = {0.15, 0.35, 0.20, 0.12, 0.18};

    private Pal makePalette(int forceTheme) {
        Pal p = new Pal();
        int kind = forceTheme >= 0 ? 0 : pick(PAL_W);
        if (kind == 0) {
            int t = forceTheme >= 0 ? forceTheme % THEME_NAME.length : rndInt(THEME_NAME.length);
            float[][] n = new float[6][], d = new float[6][];
            for (int i = 0; i < 6; i++) { n[i] = hexLab(THEME_NIGHT[t][i]); d[i] = hexLab(THEME_DAY[t][i]); }
            p.night = buildLut(P6, n, new float[][]{hexLab(THEME_NIGHT[t][6]), hexLab(THEME_NIGHT[t][7])});
            p.day = buildLut(P6, d, new float[][]{hexLab(THEME_DAY[t][6]), hexLab(THEME_DAY[t][7])});
            p.name = THEME_NAME[t]; p.multi = false;
            return p;
        }
        double[] hue = new double[8];
        double h1 = rndIn(0, 360), cBoost = 1.0;
        if (kind == 1) {                                   // monochrome, légère dérive de teinte
            double drift = rndIn(-25, 25);
            for (int i = 0; i < 8; i++) hue[i] = h1 + drift * P8[i];
            p.name = String.format(Locale.ROOT, "mono %.0f", h1);
        } else if (kind == 2) {                            // bicolore
            double[] gaps = {180, 150, 210, 120, 240, 60, 300};
            double h2 = h1 + gaps[rndInt(gaps.length)] + rndIn(-15, 15);
            double[] u = {0, 0, 0.2, 0.5, 0.8, 1, 1, 1};
            for (int i = 0; i < 8; i++) hue[i] = hueMix(h1, h2, u[i]);
            p.name = String.format(Locale.ROOT, "duo %.0f/%.0f", h1, ((h2 % 360) + 360) % 360);
        } else if (kind == 3) {                            // tricolore
            double step = rnd() < 0.5 ? 120 : rndIn(50, 80) * (rnd() < 0.5 ? -1 : 1);
            double h2 = h1 + step, h3 = h1 + 2 * step;
            double[] hs = {h1, h1, h1, h2, h2, h3, h3, h3};
            for (int i = 0; i < 8; i++) hue[i] = hs[i];
            for (int i = 1; i < 8; i++) hue[i] = hue[i - 1] + (((hue[i] - hue[i - 1]) % 360 + 540) % 360 - 180);
            p.name = String.format(Locale.ROOT, "trio %.0f", h1);
        } else {                                           // arc-en-ciel
            double[] spans = {240, 300, 360, 420};
            double span = spans[rndInt(spans.length)] * (rnd() < 0.5 ? -1 : 1);
            for (int i = 0; i < 8; i++) hue[i] = h1 + span * P8[i];
            cBoost = 1.12;
            p.name = String.format(Locale.ROOT, "arc-en-ciel %.0f", h1);
        }
        p.multi = kind >= 2;
        float[][] n = new float[8][], d = new float[8][];
        for (int i = 0; i < 8; i++) {
            n[i] = lch(ramp(N_L, P8[i]), ramp(N_C, P8[i]) * cBoost, hue[i]);
            d[i] = lch(ramp(D_L, P8[i]), ramp(D_C, P8[i]) * cBoost, hue[i]);
        }
        p.night = buildLut(P8, n, new float[][]{lch(0.09, 0.02, hue[7]), lch(0.20, 0.05, hue[5])});
        p.day = buildLut(P8, d, new float[][]{lch(0.90, 0.025, hue[3]), lch(0.80, 0.06, hue[3])});
        return p;
    }

    /* ================================================================ */
    /* Types de fractales                                                */

    static final int JULIA = 0, MULTIJULIA = 1, PHOENIX = 2, MANDEL = 3, SHIP = 4, TRICORN = 5,
                     NEWTON = 6, LYAPUNOV = 7, CARPET = 8, TRIANGLE = 9, IFS = 10;
    static final String[] TYPE_NAME = {"julia", "julia-multi", "phoenix", "mandelbrot", "burning-ship",
                                       "tricorne", "newton", "lyapunov", "tapis", "sierpinski", "ifs"};
    private static final double[] TYPE_W = {0.22, 0.10, 0.05, 0.14, 0.06, 0.03, 0.09, 0.07, 0.08, 0.05, 0.11};

    /** Paramètres d'un candidat (type + valeurs tirées + cadrage). */
    static final class Params {
        int type, deg = 2;
        double cx, cy, p;                     // Julia / Phoenix
        double[] rr, ri; double ar = 1, ai = 0; // Newton
        boolean[] seq;                        // Lyapunov (true = A)
        int base; boolean[] mask;             // tapis
        boolean[] keep;                       // triangle : coins 0, 1, 2 puis milieu
        double[][] maps; double[] cum;        // IFS : a b c d e f
        String desc = "";
        boolean plane;                        // zoom plein écran (sinon objet entier)
        double ang, uc, vc, w, h;             // cadrage (repère tourné)
        double score, density, fill, aspect, deep, interior;
    }

    static boolean isEscape(int t) { return t <= TRICORN; }

    /* ---------------------------------------------------------------- */
    /* État du rendu                                                      */

    static final double BAIL2 = 1.0e6;
    static final double LOG_BAIL = 0.5 * Math.log(BAIL2);

    private Params P;
    private double uc, vc, cs, sn, s, nlo, nhiLog;
    private int Wl, Hl, Wimg, maxit = 1500, depth = 10;
    private boolean portrait, multiPal;
    private float RELIEF = 0.45f, DEW = 0.5f, DEK = 2.0f;

    /** Itération z -> z^d + c (Julia : z0 = point ; Mandelbrot : c = point).
     *  o[0] = distance estimée, o[1] = piège d'orbite, o[2] = relief. */
    static float iterPow(double zx, double zy, double cx, double cy, int d, int maxit, float[] o, boolean julia) {
        double dx = 1, dy = 0;
        if (!julia) { zx = cx; zy = cy; }
        double r2 = zx*zx + zy*zy, tr = r2, hx = zx, hy = zy;
        int lam = 0, pw = 8, i;
        for (i = 0; i < maxit; i++) {
            if (r2 > BAIL2) break;
            double px = zx, py = zy;                          // z^(d-1)
            for (int k = 2; k < d; k++) { double t = px*zx - py*zy; py = px*zy + py*zx; px = t; }
            double ndx = d * (px*dx - py*dy), ndy = d * (px*dy + py*dx);
            if (!julia) ndx += 1;
            dx = ndx; dy = ndy;
            double nzx = px*zx - py*zy + cx; zy = px*zy + py*zx + cy; zx = nzx;
            r2 = zx*zx + zy*zy;
            if (r2 < tr) tr = r2;
            if (Math.abs(zx - hx) < 1e-9 && Math.abs(zy - hy) < 1e-9) { i = maxit; break; }
            if (++lam == pw) { hx = zx; hy = zy; lam = 0; pw <<= 1; }
        }
        o[1] = (float) tr;
        if (i >= maxit || r2 <= BAIL2) { o[0] = 0; o[2] = 1; return -1f; }
        double lr = 0.5 * Math.log(r2);
        double dd = Math.sqrt(r2) * lr / Math.sqrt(dx*dx + dy*dy);
        o[0] = (dd >= 0 && dd < 1e30) ? (float) dd : 0f;
        double ux = zx*dx + zy*dy, uy = zy*dx - zx*dy, un = Math.sqrt(ux*ux + uy*uy);
        if (un > 0 && un < 1e300) { ux /= un; uy /= un; } else { ux = uy = 0; }
        o[2] = (float) ((ux * 0.7071 + uy * 0.7071 + 1.2) / 2.2);
        return (float) (i + 1 - Math.log(lr / LOG_BAIL) / Math.log(d));
    }

    /** Burning Ship (|Re z| + i|Im z|)^2 + c ou Tricorne conj(z)^2 + c, c = point. */
    static float iterShip(double cx, double cy, int maxit, float[] o, boolean tricorn) {
        double zx = 0, zy = 0, r2 = 0, tr = 1e30, hx = 0, hy = 0;
        int lam = 0, pw = 8, i;
        for (i = 0; i < maxit; i++) {
            if (r2 > BAIL2) break;
            double nzx;
            if (tricorn) { nzx = zx*zx - zy*zy + cx; zy = -2*zx*zy + cy; }
            else { double ax = Math.abs(zx), ay = Math.abs(zy); nzx = ax*ax - ay*ay + cx; zy = 2*ax*ay + cy; }
            zx = nzx; r2 = zx*zx + zy*zy;
            if (r2 < tr) tr = r2;
            if (Math.abs(zx - hx) < 1e-9 && Math.abs(zy - hy) < 1e-9) { i = maxit; break; }
            if (++lam == pw) { hx = zx; hy = zy; lam = 0; pw <<= 1; }
        }
        o[0] = -1; o[1] = (float) tr; o[2] = 1;
        if (i >= maxit || r2 <= BAIL2) return -1f;
        return (float) (i + 1 - Math.log(0.5 * Math.log(r2) / LOG_BAIL) / Math.log(2));
    }

    /** Phoenix : z(n+1) = z(n)^2 + c + p z(n-1), z0 = point. */
    static float iterPhoenix(double zx, double zy, double cx, double cy, double p, int maxit, float[] o) {
        double qx = 0, qy = 0, r2 = zx*zx + zy*zy, tr = r2, hx = zx, hy = zy;
        int lam = 0, pw = 8, i;
        for (i = 0; i < maxit; i++) {
            if (r2 > BAIL2) break;
            double nzx = zx*zx - zy*zy + cx + p*qx, nzy = 2*zx*zy + cy + p*qy;
            qx = zx; qy = zy; zx = nzx; zy = nzy;
            r2 = zx*zx + zy*zy;
            if (r2 < tr) tr = r2;
            if (Math.abs(zx - hx) < 1e-9 && Math.abs(zy - hy) < 1e-9) { i = maxit; break; }
            if (++lam == pw) { hx = zx; hy = zy; lam = 0; pw <<= 1; }
        }
        o[0] = -1; o[1] = (float) tr; o[2] = 1;
        if (i >= maxit || r2 <= BAIL2) return -1f;
        return (float) (i + 1 - Math.log(0.5 * Math.log(r2) / LOG_BAIL) / Math.log(2));
    }

    /** Bassins de Newton (relaxés) de prod(z - r_k). Valeur directe. */
    private float newton(double zx, double zy, float[] o) {
        o[0] = -1; o[1] = 0; o[2] = 1;
        int n = P.rr.length;
        final double EPS2 = 1e-10;
        for (int i = 0; i < 100; i++) {
            double sx = 0, sy = 0, dmin = 1e300; int kmin = 0;
            for (int k = 0; k < n; k++) {
                double ex = zx - P.rr[k], ey = zy - P.ri[k], m = ex*ex + ey*ey;
                if (m < dmin) { dmin = m; kmin = k; }
                if (m < 1e-300) { m = 1e-300; }
                sx += ex / m; sy -= ey / m;               // somme des 1/(z - r_k)
            }
            if (dmin < EPS2) {
                double sm = i - Math.log(Math.max(Math.log(dmin) / Math.log(EPS2), 1e-9)) / Math.log(2);
                double g = 1 - Math.exp(-0.11 * Math.max(sm, 0));
                return multiPal ? (float) ((kmin + 0.1 + 0.8 * g) / n) : (float) g;
            }
            double s2 = sx*sx + sy*sy;
            if (s2 == 0) break;
            double qx = sx / s2, qy = -sy / s2;            // 1 / S = p / p'
            zx -= P.ar*qx - P.ai*qy; zy -= P.ar*qy + P.ai*qx;
        }
        return 2.5f;
    }

    /** Exposant de Lyapunov de la suite logistique alternée A/B. Valeur directe. */
    private float lyapunov(double a, double b, float[] o) {
        o[0] = -1; o[1] = 0; o[2] = 1;
        if (a <= 0 || a > 4 || b <= 0 || b > 4) return 0f;
        boolean[] q = P.seq; int L = q.length;
        double x = 0.5;
        for (int n = 0; n < 60; n++) { double r = q[n % L] ? a : b; x = r * x * (1 - x); }
        double sum = 0, prod = 1; final int N = 160;
        for (int n = 0; n < N; n++) {
            double r = q[n % L] ? a : b;
            x = r * x * (1 - x);
            prod *= Math.abs(r * (1 - 2 * x));
            if ((n & 7) == 7) { if (prod == 0) return 1f; sum += Math.log(prod); prod = 1; }
        }
        double lam = sum / N;
        if (lam < 0) return (float) (1 - Math.exp(lam * 1.6));
        return 2f + (float) Math.min(1.0, lam * 1.5);
    }

    /** Tapis de Sierpinski généralisé (grille b x b, masque des cases gardées). */
    private float carpet(double x, double y, float[] o) {
        o[0] = -1; o[1] = 0; o[2] = 1;
        double u = x + 0.5, v = y + 0.5; int b = P.base;
        if (u < 0 || u >= 1 || v < 0 || v >= 1) {
            double dd = Math.max(Math.max(-u, u - 1), Math.max(-v, v - 1));
            return (float) (0.35 * Math.exp(-dd * 30));
        }
        for (int lev = 1; lev <= depth; lev++) {
            double fu = u * b, fv = v * b; int i = Math.min((int) fu, b - 1), j = Math.min((int) fv, b - 1);
            u = fu - i; v = fv - j;
            if (!P.mask[j * b + i]) {
                double dc = 2 * Math.min(Math.min(u, 1 - u), Math.min(v, 1 - v));   // 0 au bord, 1 au centre
                double base = 0.85 * (lev - 1) / depth;
                return (float) (base + (1 - base) * 0.7 * Math.pow(1 - dc, 4));
            }
        }
        return 1f;
    }

    /** Triangle de Sierpinski (et variantes : autres sous-triangles retirés). */
    private float triangle(double x, double y, float[] o) {
        o[0] = -1; o[1] = 0; o[2] = 1;
        final double H = Math.sqrt(3) / 2;
        double t = (y + H / 2) / H, sx = (x + 0.5) - 0.5 * t;
        if (sx < 0 || t < 0 || sx + t > 1) {
            double dd = Math.max(Math.max(-sx, -t), sx + t - 1) * H;
            return (float) (0.35 * Math.exp(-dd * 30));
        }
        for (int lev = 1; lev <= depth; lev++) {
            sx *= 2; t *= 2; int idx;
            if (sx >= 1) { sx -= 1; idx = 1; }
            else if (t >= 1) { t -= 1; idx = 2; }
            else if (sx + t < 1) idx = 0;
            else { sx = 1 - sx; t = 1 - t; idx = 3; }       // triangle du milieu, remis à l'endroit
            if (!P.keep[idx]) {
                double dc = 3 * Math.min(Math.min(sx, t), 1 - sx - t);
                double base = 0.85 * (lev - 1) / depth;
                return (float) (base + (1 - base) * 0.7 * Math.pow(1 - Math.max(0, dc), 4));
            }
        }
        return 1f;
    }

    /** Évaluation brute dans le plan : nu (ou -1) pour les types à fuite, valeur directe sinon. */
    private float raw(double x, double y, float[] o) {
        switch (P.type) {
            case JULIA:      return iterPow(x, y, P.cx, P.cy, 2, maxit, o, true);
            case MULTIJULIA: return iterPow(x, y, P.cx, P.cy, P.deg, maxit, o, true);
            case PHOENIX:    return iterPhoenix(x, y, P.cx, P.cy, P.p, maxit, o);
            case MANDEL:     return iterPow(0, 0, x, y, P.deg, maxit, o, false);
            case SHIP:       return iterShip(x, y, maxit, o, false);
            case TRICORN:    return iterShip(x, y, maxit, o, true);
            case NEWTON:     return newton(x, y, o);
            case LYAPUNOV:   return lyapunov(x, y, o);
            case CARPET:     return carpet(x, y, o);
            case TRIANGLE:   return triangle(x, y, o);
            default:         o[0] = -1; o[1] = 0; o[2] = 1; return 0f;
        }
    }

    /** Pixel de l'image -> plan. En portrait, le cadre "paysage" est tourné de 90°. */
    private void map(double px, double py, double[] z) {
        double lx, ly;
        if (portrait) { lx = py; ly = Wimg - px; } else { lx = px; ly = py; }
        double u = (lx - 0.5 * Wl) * s + uc;
        double v = (0.5 * Hl - ly) * s + vc;
        z[0] = u * cs - v * sn;
        z[1] = u * sn + v * cs;
    }

    /** Valeur d'un échantillon : [0,1] extérieur, >= 2 intérieur ; o[2] = relief. */
    private float sampleValue(double px, double py, float[] o, double[] z) {
        map(px, py, z);
        float r = raw(z[0], z[1], o);
        if (!isEscape(P.type)) return r;
        if (r < 0) {
            float sh = (float) Math.sqrt(Math.sqrt(o[1]));
            return 2f + Math.min(sh, 1f);
        }
        float ti = (float) (Math.log(Math.max(r - nlo, 0) + 1.0) / nhiLog);
        ti = Math.min(ti, 1f);
        ti = ti * ti * (1.6f - 0.6f * ti);
        if (o[0] >= 0) {
            float q = (float) (o[0] / s) * DEK, td = 1f / (1f + q * q);
            ti = Math.max(ti, td * DEW);
        }
        return Math.min(1f, ti);
    }

    private void valueColor(Lut L, float v, float rel, float[] o) {
        if (v >= 2f) {
            float u = Math.min(1f, v - 2f);
            for (int i = 0; i < 3; i++) o[i] = L.in0[i] + (L.in1[i] - L.in0[i]) * u;
            return;
        }
        float f = v * LUT_N; int k = (int) f; if (k >= LUT_N) k = LUT_N - 1; if (k < 0) k = 0; f -= k;
        float m = 1f - RELIEF * v * (1f - rel);
        int a = k * 3, b = a + 3;
        for (int i = 0; i < 3; i++) o[i] = (L.ext[a + i] + (L.ext[b + i] - L.ext[a + i]) * f) * m;
    }

    /* ================================================================ */
    /* Tirage des paramètres                                             */

    static int mandelEscape(double cx, double cy, int d, int maxit) {
        double zx = 0, zy = 0;
        for (int i = 0; i < maxit; i++) {
            if (zx*zx + zy*zy > 4) return i;
            double px = zx, py = zy;
            if (d == 2) { double t = zx*zx - zy*zy + cx; zy = 2*zx*zy + cy; zx = t; continue; }
            for (int k = 2; k < d; k++) { double t = px*zx - py*zy; py = px*zy + py*zx; px = t; }
            double t = px*zx - py*zy + cx; zy = px*zy + py*zx + cy; zx = t;
        }
        return -1;
    }
    static int shipEscape(double cx, double cy, boolean tricorn, int maxit) {
        double zx = 0, zy = 0;
        for (int i = 0; i < maxit; i++) {
            if (zx*zx + zy*zy > 4) return i;
            double nzx;
            if (tricorn) { nzx = zx*zx - zy*zy + cx; zy = -2*zx*zy + cy; }
            else { double ax = Math.abs(zx), ay = Math.abs(zy); nzx = ax*ax - ay*ay + cx; zy = 2*ax*ay + cy; }
            zx = nzx;
        }
        return -1;
    }

    private static final double[][] CLASSICS = {
        {-0.8, 0.156}, {-0.7269, 0.1889}, {0.285, 0.01}, {-0.4, 0.6}, {-0.70176, 0.3842},
        {0.355, 0.355}, {-0.835, 0.2321}, {-0.162, 1.04}, {-0.54, 0.54}, {0.37, 0.1},
        {-0.75, 0.11}, {-0.1, 0.651}, {-1.476, 0.0}, {0.26, 0.0015}, {-0.12, 0.75},
        {0.3, 0.5}, {-0.391, 0.587}, {-0.8, 0.169}, {0.45, 0.1428}, {-0.624, 0.435},
    };
    private static final double[] JFAM_W = {0.28, 0.27, 0.13, 0.17, 0.15};

    /** c pour une Julia de degré 2 (familles de la v1). */
    private void drawJulia2(int fam, Params c) {
        switch (fam) {
            case 0:
                for (int t = 0; t < 200000; t++) {
                    double x = rndIn(-2.0, 0.5), y = rndIn(0.0, 1.15);
                    if (mandelEscape(x, y, 2, 3000) >= 50) { c.cx = x; c.cy = y; c.desc = "poussiere"; return; }
                }
                // fallthrough
            case 1: {
                double th = rndIn(0, 2 * Math.PI), rr = 1.0 + rndIn(-0.06, 0.02);
                double ex = rr * Math.cos(th), ey = rr * Math.sin(th), e2x = ex*ex - ey*ey, e2y = 2*ex*ey;
                c.cx = ex / 2 - e2x / 4; c.cy = ey / 2 - e2y / 4; c.desc = "cardioide"; return;
            }
            case 2: {
                double th = rndIn(0, 2 * Math.PI), rr = 1.0 + rndIn(-0.08, 0.03);
                c.cx = -1 + 0.25 * rr * Math.cos(th); c.cy = 0.25 * rr * Math.sin(th); c.desc = "bulbe-2"; return;
            }
            case 3: {
                int k = rndInt(CLASSICS.length);
                c.cx = CLASSICS[k][0] + rndIn(-0.003, 0.003); c.cy = CLASSICS[k][1] + rndIn(-0.003, 0.003);
                c.desc = "classique"; return;
            }
            default:
                for (int t = 0; t < 200000; t++) {
                    double x = rndIn(-2.0, 0.5), y = rndIn(0.0, 1.15), r = rndIn(0.004, 0.02);
                    if (mandelEscape(x, y, 2, 800) >= 0) continue;
                    boolean out = false;
                    for (int k = 0; k < 6 && !out; k++)
                        out = mandelEscape(x + r * Math.cos(k * Math.PI / 3), y + r * Math.sin(k * Math.PI / 3), 2, 800) >= 0;
                    if (out) { c.cx = x; c.cy = y; c.desc = "interieur-bord"; return; }
                }
                c.cx = -0.123; c.cy = 0.745; c.desc = "lapin";
        }
    }

    /** Point juste à l'extérieur du bord d'un ensemble de type Mandelbrot. */
    private boolean boundaryPoint(int type, int d, double x0, double x1, double y0, double y1, int nmin, int nmax, double[] out) {
        for (int t = 0; t < 300000; t++) {
            double x = rndIn(x0, x1), y = rndIn(y0, y1);
            int n = type == MANDEL ? mandelEscape(x, y, d, nmax) : shipEscape(x, y, type == TRICORN, nmax);
            if (n >= nmin) { out[0] = x; out[1] = y; return true; }
        }
        return false;
    }

    private static final String[] LYAP_SEQ = {"AB", "AABAB", "BBBBBBAAAAAA", "AABB", "ABBAB", "AAAB", "BBABA", "ABAAB", "AABBB"};

    /** Tire les paramètres d'un candidat du type donné (sans cadrage). */
    private void drawParams(int type, Params c, double[] fixedC) {
        c.type = type;
        double[] pt = new double[2];
        switch (type) {
            case JULIA:
                if (fixedC != null) { c.cx = fixedC[0]; c.cy = fixedC[1]; c.desc = "manuel"; }
                else { drawJulia2(pick(JFAM_W), c); if (rnd() < 0.5) c.cy = -c.cy; }
                break;
            case MULTIJULIA: {
                c.deg = 3 + rndInt(4);
                if (rnd() < 0.6) {                          // juste dehors : poussières en étoile
                    boundaryPoint(MANDEL, c.deg, -1.5, 1.5, -1.5, 1.5, 25, 2000, pt);
                } else {                                    // dedans, près du bord
                    pt[0] = 0; pt[1] = 0;
                    for (int t = 0; t < 100000; t++) {
                        double x = rndIn(-1.5, 1.5), y = rndIn(-1.5, 1.5), r = rndIn(0.005, 0.03);
                        if (mandelEscape(x, y, c.deg, 600) >= 0) continue;
                        if (mandelEscape(x + r, y, c.deg, 600) >= 0 || mandelEscape(x, y + r, c.deg, 600) >= 0
                                || mandelEscape(x - r, y, c.deg, 600) >= 0) { pt[0] = x; pt[1] = y; break; }
                    }
                }
                c.cx = pt[0]; c.cy = pt[1]; c.desc = "degre " + c.deg;
                break;
            }
            case PHOENIX:
                c.cx = rndIn(0.50, 0.60); c.cy = rndIn(-0.06, 0.06); c.p = rndIn(-0.56, -0.38);
                c.desc = String.format(Locale.ROOT, "p=%.3f", c.p);
                break;
            case MANDEL: {
                c.plane = true;
                c.deg = rnd() < 0.7 ? 2 : 3 + rndInt(2);
                double w;
                if (rnd() < 0.08) {                          // l'ensemble entier
                    pt[0] = c.deg == 2 ? -0.6 : 0; pt[1] = 0; w = c.deg == 2 ? rndIn(2.8, 3.4) : rndIn(2.6, 3.2);
                } else {
                    if (c.deg == 2) boundaryPoint(MANDEL, 2, -2.1, 0.6, -1.2, 1.2, 25 + rndInt(120), 4000, pt);
                    else boundaryPoint(MANDEL, c.deg, -1.5, 1.5, -1.5, 1.5, 20 + rndInt(80), 3000, pt);
                    w = Math.pow(10, c.deg == 2 ? rndIn(-3.3, -0.4) : rndIn(-2.5, -0.2));
                }
                c.cx = pt[0]; c.cy = pt[1]; c.w = w;
                c.desc = String.format(Locale.ROOT, "degre %d, zoom %.0fx", c.deg, 3.0 / w);
                break;
            }
            case SHIP: case TRICORN: {
                c.plane = true;
                double w;
                if (type == SHIP && rnd() < 0.4) {            // la petite "épave" emblématique
                    pt[0] = -1.762 + rndIn(-0.004, 0.004); pt[1] = -0.028 + rndIn(-0.004, 0.004); w = rndIn(0.03, 0.09);
                } else if (rnd() < (type == TRICORN ? 0.6 : 0.08)) {
                    pt[0] = type == SHIP ? -0.45 : -0.25; pt[1] = type == SHIP ? -0.5 : 0; w = type == SHIP ? rndIn(3.2, 3.8) : rndIn(2.4, 3.6);
                } else {
                    if (type == SHIP) boundaryPoint(SHIP, 2, -2.2, 1.2, -1.9, 0.9, 20 + rndInt(80), 3000, pt);
                    else boundaryPoint(TRICORN, 2, -2.0, 1.6, -1.8, 1.8, 20 + rndInt(80), 3000, pt);
                    w = Math.pow(10, type == TRICORN ? rndIn(-0.8, -0.1) : rndIn(-2.6, -0.2));
                }
                c.cx = pt[0]; c.cy = pt[1]; c.w = w;
                c.desc = String.format(Locale.ROOT, "zoom %.0fx", 3.0 / w);
                break;
            }
            case NEWTON: {
                c.plane = true;
                int n;
                if (rnd() < 0.5) {                           // z^n - 1, tourné
                    n = 3 + rndInt(5); double rot = rndIn(0, 2 * Math.PI);
                    c.rr = new double[n]; c.ri = new double[n];
                    for (int k = 0; k < n; k++) { c.rr[k] = Math.cos(rot + 2 * Math.PI * k / n); c.ri[k] = Math.sin(rot + 2 * Math.PI * k / n); }
                    c.desc = "z^" + n + "-1";
                } else {                                     // racines au hasard
                    n = 3 + rndInt(4);
                    c.rr = new double[n]; c.ri = new double[n];
                    for (int k = 0; k < n; k++) { double r = Math.sqrt(rnd()), a = rndIn(0, 2 * Math.PI); c.rr[k] = r * Math.cos(a); c.ri[k] = r * Math.sin(a); }
                    c.desc = n + " racines";
                }
                if (rnd() < 0.4) { c.ar = 1; c.ai = 0; }
                else { c.ar = rndIn(1.0, 1.7); c.ai = rnd() < 0.5 ? 0 : rndIn(-0.4, 0.4); c.desc += String.format(Locale.ROOT, ", a=%.2f%+.2fi", c.ar, c.ai); }
                if (rnd() < 0.3) { c.cx = 0; c.cy = 0; c.w = rndIn(2.2, 3.6); }
                else { double r = rndIn(0, 1.0), a = rndIn(0, 2 * Math.PI); c.cx = r * Math.cos(a); c.cy = r * Math.sin(a); c.w = Math.pow(10, rndIn(-1.4, 0.1)); }
                break;
            }
            case LYAPUNOV: {
                c.plane = true;
                String sq;
                if (rnd() < 0.7) sq = LYAP_SEQ[rndInt(LYAP_SEQ.length)];
                else { int L = 2 + rndInt(7); StringBuilder sb = new StringBuilder(); for (int k = 0; k < L; k++) sb.append(rnd() < 0.5 ? 'A' : 'B'); if (sb.indexOf("A") < 0) sb.setCharAt(0, 'A'); if (sb.indexOf("B") < 0) sb.setCharAt(L - 1, 'B'); sq = sb.toString(); }
                c.seq = new boolean[sq.length()];
                for (int k = 0; k < sq.length(); k++) c.seq[k] = sq.charAt(k) == 'A';
                c.w = rndIn(0.35, 1.6);
                double half = c.w / 2;
                c.cx = rndIn(2.0 + half, 4.0 - half); c.cy = rndIn(2.0 + half, 4.0 - half);
                c.desc = sq;
                break;
            }
            case CARPET: {
                double r = rnd();
                c.base = r < 0.45 ? 3 : r < 0.75 ? 4 : 5;
                int b = c.base; c.mask = new boolean[b * b];
                int tries = 0;
                do {                                           // masque à symétrie du carré, ni vide ni plein
                    double pk = rndIn(0.35, 0.8);
                    int hb = (b + 1) / 2;
                    boolean[] dec = new boolean[hb * hb];
                    for (int lo = 0; lo < hb; lo++) for (int hi = lo; hi < hb; hi++) dec[lo * hb + hi] = rnd() < pk;
                    for (int j = 0; j < b; j++) for (int i = 0; i < b; i++) {
                        int a = Math.min(i, b - 1 - i), bb = Math.min(j, b - 1 - j);
                        c.mask[j * b + i] = dec[Math.min(a, bb) * hb + Math.max(a, bb)];
                    }
                    if (b == 3 && rnd() < 0.25) {             // classiques : tapis, Vicsek, poussière
                        int k = rndInt(3);
                        for (int q = 0; q < 9; q++) c.mask[q] = k == 0 ? q != 4 : k == 1 ? (q % 2 == 1 || q == 4) : (q % 2 == 0 && q != 4);
                    }
                } while (!carpetOk(c.mask, b) && ++tries < 50);
                if (!carpetOk(c.mask, b)) { c.base = 3; c.mask = new boolean[]{true, true, true, true, false, true, true, true, true}; }
                StringBuilder sb = new StringBuilder();
                for (int q = 0; q < c.mask.length; q++) sb.append(c.mask[q] ? '#' : '.');
                c.desc = c.base + "x" + c.base + " " + sb;
                c.w = c.h = 1; c.uc = c.vc = 0;
                c.ang = rnd() < 0.25 ? Math.PI / 4 : 0;
                if (c.ang != 0) c.w = c.h = Math.sqrt(2);
                break;
            }
            case TRIANGLE: {
                c.keep = new boolean[]{true, true, true, false};
                if (rnd() < 0.4) {
                    int removed = rnd() < 0.7 ? 1 : 2;
                    c.keep = new boolean[]{true, true, true, true};
                    for (int k = 0; k < removed; k++) c.keep[rndInt(4)] = false;
                    if (!c.keep[0] && !c.keep[1] && !c.keep[2] && !c.keep[3]) c.keep[3] = true;
                }
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 4; k++) sb.append(c.keep[k] ? '1' : '0');
                c.desc = sb.toString();
                int q = rndInt(4);
                c.ang = q * Math.PI / 2;
                double H = Math.sqrt(3) / 2;
                if (q % 2 == 0) { c.w = 1; c.h = H; } else { c.w = H; c.h = 1; }
                c.uc = c.vc = 0;
                break;
            }
            default:
                drawIfs(c);
        }
    }

    private static boolean carpetOk(boolean[] m, int b) {
        int k = 0; for (boolean x : m) if (x) k++;
        return k >= Math.max(2, b) && k <= b * b - 1;
    }

    /* ---------------------------------------------------------------- */
    /* IFS : ensembles d'applications affines (jeu du chaos)              */

    private static double[] aff(double sx, double sy, double rot, double shear, double e, double f) {
        double c = Math.cos(rot), s = Math.sin(rot);
        // R(rot) * [[sx, shear], [0, sy]]
        return new double[]{c * sx, c * shear - s * sy, s * sx, s * shear + c * sy, e, f};
    }

    private void drawIfs(Params c) {
        double r = rnd();
        double[][] m;
        String name;
        if (r < 0.14) {
            m = new double[][]{{0, 0, 0, 0.16, 0, 0}, {0.85, 0.04, -0.04, 0.85, 0, 1.6},
                               {0.2, -0.26, 0.23, 0.22, 0, 1.6}, {-0.15, 0.28, 0.26, 0.24, 0, 0.44}};
            double j = rndIn(-0.03, 0.03);
            m[1][1] += j; m[1][2] -= j;                        // fougère plus ou moins courbée
            name = "fougere";
        } else if (r < 0.26) {
            m = new double[][]{{0.5, -0.5, 0.5, 0.5, 0, 0}, {-0.5, -0.5, 0.5, -0.5, 1, 0}}; name = "dragon";
        } else if (r < 0.36) {
            m = new double[][]{{0.5, -0.5, 0.5, 0.5, 0, 0}, {0.5, 0.5, -0.5, 0.5, 0.5, 0.5}}; name = "levy-c";
        } else if (r < 0.44) {
            double t = 1.0 / 3, q = Math.sqrt(3) / 6;
            m = new double[][]{{t, 0, 0, t, 0, 0}, {1.0 / 6, -q, q, 1.0 / 6, t, 0},
                               {1.0 / 6, q, -q, 1.0 / 6, 0.5, q}, {t, 0, 0, t, 2 * t, 0}};
            name = "koch";
        } else if (r < 0.56) {
            int n = 5 + rndInt(4);                            // n-flocon (pentaflake...)
            double sc = n == 5 ? 0.382 : n == 6 ? 1.0 / 3 : n == 7 ? 0.31 : 0.29;
            boolean center = n == 6 || rnd() < 0.3;
            m = new double[n + (center ? 1 : 0)][];
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n;
                m[k] = new double[]{sc, 0, 0, sc, (1 - sc) * Math.cos(a), (1 - sc) * Math.sin(a)};
            }
            if (center) m[n] = new double[]{sc, 0, 0, sc, 0, 0};
            name = n + "-flocon";
        } else if (r < 0.72) {                                // spirale : une grande rotation + petites applications
            int k = 1 + rndInt(2);
            m = new double[k + 1][];
            double sc = rndIn(0.82, 0.93), rot = rndIn(0.15, 0.6) * (rnd() < 0.5 ? -1 : 1);
            m[0] = aff(sc, sc, rot, 0, 0, 0);
            for (int i = 1; i <= k; i++)
                m[i] = aff(rndIn(0.15, 0.4), rndIn(0.15, 0.4), rndIn(0, 2 * Math.PI), rndIn(-0.1, 0.1), rndIn(-1, 1), rndIn(-1, 1));
            name = "spirale";
        } else if (r < 0.86) {                                // rosace : une application et ses rotations
            int n = 3 + rndInt(4);
            double[] base = aff(rndIn(0.3, 0.55), rndIn(0.3, 0.55), rndIn(0, 2 * Math.PI), rndIn(-0.2, 0.2), rndIn(0.3, 1), rndIn(-0.3, 0.3));
            m = new double[n + (rnd() < 0.4 ? 1 : 0)][];
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n, ca = Math.cos(a), sa = Math.sin(a);
                m[k] = new double[]{ca * base[0] - sa * base[2], ca * base[1] - sa * base[3],
                                    sa * base[0] + ca * base[2], sa * base[1] + ca * base[3],
                                    ca * base[4] - sa * base[5], sa * base[4] + ca * base[5]};
            }
            if (m.length > n) m[n] = aff(rndIn(0.25, 0.45), rndIn(0.25, 0.45), rndIn(0, 2 * Math.PI), 0, 0, 0);
            name = "rosace-" + n;
        } else {                                              // IFS aléatoire
            int n = 2 + rndInt(3);
            m = new double[n][];
            for (int k = 0; k < n; k++) {
                double sx = rndIn(0.25, 0.8), sy = rnd() < 0.5 ? sx : rndIn(0.25, 0.8);
                m[k] = aff(sx, sy * (rnd() < 0.2 ? -1 : 1), rndIn(0, 2 * Math.PI), rndIn(-0.25, 0.25), rndIn(-1, 1), rndIn(-1, 1));
            }
            name = "ifs-" + n;
        }
        c.maps = m; c.cum = new double[m.length];
        double tot = 0;
        for (int k = 0; k < m.length; k++) { tot += Math.max(0.02, Math.abs(m[k][0] * m[k][3] - m[k][1] * m[k][2])); c.cum[k] = tot; }
        for (int k = 0; k < m.length; k++) c.cum[k] /= tot;
        if (name.equals("fougere")) { c.cum = new double[]{0.01, 0.86, 0.93, 1.0}; }
        c.desc = name;
    }

    /** Jeu du chaos : nouvelle position (x, y) <- application tirée selon les poids. */
    private static int ifsPick(double[] cum, double r) {
        int k = 0; while (k < cum.length - 1 && r >= cum[k]) k++; return k;
    }

    /* ================================================================ */
    /* Évaluation des candidats et cadrage                               */

    private static float quantile(float[] a, int n, double q) {
        Arrays.sort(a, 0, n);
        int k = (int) (q * (n - 1)); return a[Math.max(0, Math.min(n - 1, k))];
    }

    static final int PV = 200;

    /** Objet entier (Julia, Phoenix) : aperçu sur [-2,2]^2, ACP, score. */
    private void evalObject(final Params C, double maxAspect) {
        P = C; maxit = 1500;
        final float[] val = new float[PV * PV];
        final double s0 = 4.0 / PV;
        parallelFor(PV, new RowJob() {
            public void row(int j, float[] o, double[] z) {
                for (int i = 0; i < PV; i++) val[j * PV + i] = raw(-2 + (i + 0.5) * s0, 2 - (j + 0.5) * s0, o);
            }
        });
        float[] nus = new float[PV * PV]; int next = 0;
        boolean[] ins = new boolean[PV * PV];
        for (int k = 0; k < PV * PV; k++) { ins[k] = val[k] < 0; if (!ins[k]) nus[next++] = val[k]; }
        C.score = 0;
        if (next < PV * PV / 20) return;
        float lo = quantile(nus, next, 0.0), hi = quantile(nus, next, 0.995);
        C.deep = quantile(nus, next, 0.9995);
        double lh = Math.log(hi - lo + 1.0); if (lh < 1e-3) return;
        for (int k = 0; k < PV * PV; k++)
            val[k] = ins[k] ? 1.2f : (float) (Math.log(Math.max(val[k] - lo, 0) + 1.0) / lh);
        framePoints(C, val, PV, PV, -2, 2, s0, ins, maxAspect);
    }

    /** ACP + boîte englobante d'une image d'aperçu (valeurs >= 0.5 = "intéressant"), puis score. */
    private void framePoints(Params C, float[] val, int pw, int ph, double x0, double y0, double s0, boolean[] ins, double maxAspect) {
        double sw = 0, mx = 0, my = 0, sxx = 0, syy = 0, sxy = 0; int nm = 0, nin = 0;
        for (int j = 0; j < ph; j++) for (int i = 0; i < pw; i++) {
            if (val[j * pw + i] < 0.5f) continue;
            double x = x0 + (i + 0.5) * s0, y = y0 - (j + 0.5) * s0;
            sw += 1; mx += x; my += y; sxx += x*x; syy += y*y; sxy += x*y; nm++;
            if (ins != null && ins[j * pw + i]) nin++;
        }
        C.score = 0;
        if (nm < 60) return;
        mx /= sw; my /= sw; sxx = sxx/sw - mx*mx; syy = syy/sw - my*my; sxy = sxy/sw - mx*my;
        double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double c0 = Math.cos(ang), s1 = Math.sin(ang);
        float[] us = new float[nm], vs = new float[nm]; int m = 0;
        for (int j = 0; j < ph; j++) for (int i = 0; i < pw; i++) {
            if (val[j * pw + i] < 0.5f) continue;
            double x = x0 + (i + 0.5) * s0, y = y0 - (j + 0.5) * s0;
            us[m] = (float) (x * c0 + y * s1); vs[m] = (float) (-x * s1 + y * c0); m++;
        }
        float u0 = quantile(us, m, 0.003), u1 = quantile(us, m, 0.997);
        float v0 = quantile(vs, m, 0.003), v1 = quantile(vs, m, 0.997);
        double w = u1 - u0 + 2 * s0, h = v1 - v0 + 2 * s0;
        if (h > w) {
            double t = w; w = h; h = t; ang += Math.PI / 2;
            float a = u0, b = u1; u0 = v0; u1 = v1; v0 = -b; v1 = -a;
        }
        C.ang = ang; C.w = w; C.h = h; C.uc = 0.5 * (u0 + u1); C.vc = 0.5 * (v0 + v1);
        double boxPx = (w / s0) * (h / s0);
        if (w / s0 < 30) return;
        int edges = 0;
        for (int j = 0; j < ph - 1; j++) for (int i = 0; i < pw - 1; i++) {
            float a = val[j*pw+i], b = val[j*pw+i+1], c = val[(j+1)*pw+i];
            if (Math.abs(a - b) + Math.abs(a - c) > 0.22f) edges++;
        }
        double density = edges / boxPx, fill = nm / boxPx, aspect = w / h, interior = nm > 0 ? (double) nin / nm : 0;
        C.density = density; C.fill = fill; C.aspect = aspect; C.interior = interior;
        double sc = Math.pow(density, 0.35);
        if (fill < 0.10) sc *= fill / 0.10;
        if (interior > 0.85) sc *= 0.5;
        if (aspect > maxAspect) sc *= Math.pow(maxAspect / aspect, 2.0);
        C.score = sc;
    }

    /** Zoom plein écran : aperçu de la fenêtre, score sur les détails et la variété des valeurs. */
    private void evalPlane(final Params C) {
        P = C; maxit = C.type == MANDEL ? 2500 : 1500;
        final int pw = 160, ph = Math.max(40, (int) (160.0 * Hl / Wl));
        final double ps = C.w / pw;
        final double ca = Math.cos(C.ang), sa = Math.sin(C.ang);
        final double cu = C.cx * ca + C.cy * sa, cv = -C.cx * sa + C.cy * ca;
        final float[] val = new float[pw * ph];
        parallelFor(ph, new RowJob() {
            public void row(int j, float[] o, double[] z) {
                for (int i = 0; i < pw; i++) {
                    double u = (i + 0.5 - 0.5 * pw) * ps + cu, v = (0.5 * ph - j - 0.5) * ps + cv;
                    val[j * pw + i] = raw(u * ca - v * sa, u * sa + v * ca, o);
                }
            }
        });
        int n = pw * ph, nin = 0;
        if (isEscape(C.type)) {
            float[] nus = new float[n]; int next = 0;
            for (int k = 0; k < n; k++) if (val[k] >= 0) nus[next++] = val[k];
            C.score = 0;
            if (next < n / 10) return;
            float lo = quantile(nus, next, 0.01), hi = quantile(nus, next, 0.995);
            C.deep = quantile(nus, next, 0.9995);
            double lh = Math.log(Math.max(hi - lo, 4) + 1.0);
            for (int k = 0; k < n; k++) val[k] = val[k] < 0 ? 1.2f : (float) (Math.log(Math.max(val[k] - lo, 0) + 1.0) / lh);
        }
        double sum = 0, sum2 = 0;
        for (int k = 0; k < n; k++) { float v = Math.min(val[k], 1.2f); if (val[k] >= 2 || val[k] > 1.1f) nin++; sum += v; sum2 += v * v; }
        double mean = sum / n, sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
        int edges = 0;
        for (int j = 0; j < ph - 1; j++) for (int i = 0; i < pw - 1; i++) {
            float a = Math.min(val[j*pw+i], 1.2f), b = Math.min(val[j*pw+i+1], 1.2f), c = Math.min(val[(j+1)*pw+i], 1.2f);
            if (Math.abs(a - b) + Math.abs(a - c) > 0.15f) edges++;
        }
        C.density = (double) edges / n; C.interior = (double) nin / n; C.fill = sd; C.aspect = 0;
        double sc = Math.pow(C.density + 0.002, 0.4) * Math.min(1, sd / 0.18);
        if (C.interior > 0.6) sc *= Math.max(0.05, (1 - C.interior) / 0.4);
        if (C.density > 0.3) sc *= Math.pow(0.3 / C.density, 2);   // bruit
        C.score = sc;
    }

    /** IFS : nuage de points, ACP, couverture. */
    private void evalIfs(Params C, double maxAspect) {
        int N = 40000; double x = 0, y = 0;
        double[] px = new double[N], py = new double[N];
        for (int i = -50; i < N; i++) {
            double[] f = C.maps[ifsPick(C.cum, rnd())];
            double nx = f[0] * x + f[1] * y + f[4], ny = f[2] * x + f[3] * y + f[5];
            x = nx; y = ny;
            if (!(Math.abs(x) < 1e6 && Math.abs(y) < 1e6)) { C.score = 0; return; }
            if (i >= 0) { px[i] = x; py[i] = y; }
        }
        double mx = 0, my = 0, sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < N; i++) { mx += px[i]; my += py[i]; }
        mx /= N; my /= N;
        for (int i = 0; i < N; i++) { double dx = px[i] - mx, dy = py[i] - my; sxx += dx*dx; syy += dy*dy; sxy += dx*dy; }
        double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy), c0 = Math.cos(ang), s1 = Math.sin(ang);
        float[] us = new float[N], vs = new float[N];
        for (int i = 0; i < N; i++) { us[i] = (float) (px[i] * c0 + py[i] * s1); vs[i] = (float) (-px[i] * s1 + py[i] * c0); }
        float[] uc0 = us.clone(), vc0 = vs.clone();
        float u0 = quantile(uc0, N, 0.002), u1 = quantile(uc0, N, 0.998), v0 = quantile(vc0, N, 0.002), v1 = quantile(vc0, N, 0.998);
        double w = u1 - u0, h = v1 - v0;
        C.score = 0;
        if (!(w > 1e-9)) return;
        if (h < w * 0.02) return;                               // presque une droite
        if (h > w) {
            double t = w; w = h; h = t; ang += Math.PI / 2;
            float a = u0, b = u1; u0 = v0; u1 = v1; v0 = -b; v1 = -a;
            for (int i = 0; i < N; i++) { float t2 = us[i]; us[i] = vs[i]; vs[i] = -t2; }
        }
        C.ang = ang; C.w = w * 1.02; C.h = h * 1.02; C.uc = 0.5 * (u0 + u1); C.vc = 0.5 * (v0 + v1);
        final int G = 96; int gw = G, gh = Math.max(4, (int) (G * h / w));
        boolean[] cell = new boolean[gw * gh]; int hit = 0;
        for (int i = 0; i < N; i++) {
            int gx = (int) ((us[i] - u0) / w * gw), gy = (int) ((vs[i] - v0) / h * gh);
            if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) continue;
            if (!cell[gy * gw + gx]) { cell[gy * gw + gx] = true; hit++; }
        }
        double cov = (double) hit / (gw * gh), aspect = w / h;
        C.fill = cov; C.aspect = aspect; C.density = cov;
        double sc = 1;
        if (cov < 0.06) sc *= cov / 0.06;
        if (cov > 0.6) sc *= 0.6 / cov;
        if (aspect > maxAspect) sc *= Math.pow(maxAspect / aspect, 2.0);
        C.score = sc;
    }

    /* ================================================================ */
    /* Parallélisme                                                      */

    interface RowJob { void row(int y, float[] o, double[] z); }

    private int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private ThreadFactory threadFactory = new ThreadFactory() {
        public Thread newThread(Runnable r) { return new Thread(r, "fractal-render"); }
    };

    private void parallelFor(final int rows, final RowJob job) {
        final AtomicInteger next = new AtomicInteger(0);
        final CountDownLatch done = new CountDownLatch(nThreads);
        final Throwable[] err = new Throwable[1];
        for (int t = 0; t < nThreads; t++) {
            threadFactory.newThread(new Runnable() {
                public void run() {
                    float[] o = new float[3]; double[] z = new double[2];
                    try {
                        int y;
                        while ((y = next.getAndIncrement()) < rows) job.row(y, o, z);
                    } catch (Throwable e) { err[0] = e; }
                    finally { done.countDown(); }
                }
            }).start();
        }
        try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (err[0] != null) throw new RuntimeException(err[0]);
    }

    /* ================================================================ */
    /* PNG en flux                                                       */

    static final class PngWriter implements Closeable {
        private final OutputStream out; private final File tmp, dst;
        private final Deflater def = new Deflater(4);
        private final byte[] zbuf = new byte[1 << 16];
        private final CRC32 crc = new CRC32();
        private final int stride; private final byte[] prev; private final byte[][] cand; private final byte[] rowOut;

        PngWriter(File dst, int w, int h, String comment) throws IOException {
            if (dst.exists() && !dst.isFile()) throw new IOException(dst + " n'est pas un fichier ordinaire");
            this.dst = dst; this.tmp = new File(dst.getPath() + ".tmp");
            out = new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
            stride = w * 3; prev = new byte[stride]; cand = new byte[5][stride]; rowOut = new byte[stride + 1];
            out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            byte[] ihdr = new byte[13];
            put32(ihdr, 0, w); put32(ihdr, 4, h); ihdr[8] = 8; ihdr[9] = 2;
            chunk("IHDR", ihdr, 0, 13);
            if (comment != null) {
                byte[] t = ("Comment\0" + comment).getBytes("ISO-8859-1");
                chunk("tEXt", t, 0, t.length);
            }
        }
        private static void put32(byte[] b, int o, int v) { b[o] = (byte) (v >>> 24); b[o+1] = (byte) (v >>> 16); b[o+2] = (byte) (v >>> 8); b[o+3] = (byte) v; }
        private void chunk(String type, byte[] d, int off, int len) throws IOException {
            byte[] hdr = new byte[8]; put32(hdr, 0, len);
            byte[] ty = type.getBytes("ISO-8859-1"); System.arraycopy(ty, 0, hdr, 4, 4);
            crc.reset(); crc.update(ty); if (len > 0) crc.update(d, off, len);
            out.write(hdr); if (len > 0) out.write(d, off, len);
            byte[] c = new byte[4]; put32(c, 0, (int) crc.getValue()); out.write(c);
        }
        private void drain(boolean finish) throws IOException {
            while (finish ? !def.finished() : !def.needsInput()) {
                int n = def.deflate(zbuf);
                if (n > 0) chunk("IDAT", zbuf, 0, n);
                else if (!finish) break;
            }
        }
        void row(byte[] src, int off, boolean first) throws IOException {
            long best = -1; int bf = 0;
            for (int ft = 0; ft < 5; ft++) {
                long sum = 0; byte[] cd = cand[ft];
                for (int x = 0; x < stride; x++) {
                    int cur = src[off + x] & 255;
                    int a = x >= 3 ? src[off + x - 3] & 255 : 0;
                    int b = first ? 0 : prev[x] & 255;
                    int c = (!first && x >= 3) ? prev[x - 3] & 255 : 0;
                    int p;
                    switch (ft) {
                        case 0: p = 0; break;
                        case 1: p = a; break;
                        case 2: p = b; break;
                        case 3: p = (a + b) >> 1; break;
                        default: { int pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                                   p = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); }
                    }
                    int r = (cur - p) & 255; cd[x] = (byte) r;
                    sum += r < 128 ? r : 256 - r;
                }
                if (best < 0 || sum < best) { best = sum; bf = ft; }
            }
            rowOut[0] = (byte) bf; System.arraycopy(cand[bf], 0, rowOut, 1, stride);
            System.arraycopy(src, off, prev, 0, stride);
            def.setInput(rowOut);
            drain(false);
        }
        public void close() throws IOException {
            try {
                def.finish(); drain(true); chunk("IEND", new byte[0], 0, 0);
                out.close();
            } catch (IOException e) {
                try { out.close(); } catch (IOException ignored) {}
                tmp.delete(); throw e;
            } finally { def.end(); }
            if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("rename " + dst); }
        }
        void abort() { try { out.close(); } catch (IOException ignored) {} def.end(); tmp.delete(); }
    }

    /* ================================================================ */
    /* Quantification sRGB + tramage                                     */

    private static final int[] LIN2SRGB = new int[4097];
    static {
        for (int i = 0; i <= 4096; i++) {
            double c = i / 4096.0;
            c = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
            LIN2SRGB[i] = (int) Math.round(c * 255.0);
        }
    }
    private static int hash2(int x, int y) {
        int h = x * 0x8da6b343 ^ y * 0xd8163841; h ^= h >>> 15; h *= 0x2c1b3c6d; h ^= h >>> 12; h *= 0x297a2d39; h ^= h >>> 15;
        return h;
    }
    private static byte enc(float v, int h) {
        v = Math.max(0f, Math.min(1f, v));
        float f = v * 4096f; int k = (int) f; if (k >= 4096) k = 4095; f -= k;
        float s = LIN2SRGB[k] + (LIN2SRGB[k + 1] - LIN2SRGB[k]) * f;
        float n = ((h & 0xffff) + ((h >>> 16) & 0xffff)) * (1f / 65536f) - 1f;
        int r = Math.round(s + n);
        return (byte) Math.max(0, Math.min(255, r));
    }

    /* ================================================================ */
    /* Point d'entrée                                                    */

    public static final class Result {
        public String type, params, palette; public long seed; public int width, height; public double seconds;
        public String info() { return type + " | " + params + " | " + palette; }
    }

    public Renderer threads(int n, ThreadFactory tf) {
        if (n > 0) nThreads = n;
        if (tf != null) threadFactory = tf;
        return this;
    }

    private int forceType = -1;
    /** Pour les tests : impose un type de fractale. */
    public Renderer type(int t) { forceType = t; return this; }

    /**
     * Rend une fractale W x H (portrait accepté) et écrit day/night en PNG.
     * @param fixedC paramètre c imposé (Julia), ou null
     * @param theme  index de thème prédéfini, ou -1 pour une palette tirée au hasard
     */
    public Result render(int W, int H, long seed, File day, File night, double[] fixedC, int theme) throws IOException {
        long t0 = System.nanoTime();
        rngState = seed;
        portrait = H > W;
        Wimg = W; Wl = Math.max(W, H); Hl = Math.min(W, H);
        double maxAspect = Math.max(1.3, (double) Wl / Hl);

        int type = fixedC != null ? JULIA : forceType >= 0 ? forceType : pick(TYPE_W);
        Pal pal = makePalette(theme);
        multiPal = pal.multi;

        /* 1-2. candidats */
        int ncand = fixedC != null ? 1 : type == JULIA || type == MULTIJULIA || type == PHOENIX ? 10
                  : type == IFS ? 12 : type == CARPET || type == TRIANGLE ? 1 : 6;
        Params best = null;
        for (int k = 0; k < ncand; k++) {
            Params C = new Params();
            if (type == IFS && k > 0 && best != null && !best.desc.startsWith("spirale") && !best.desc.startsWith("rosace")
                    && !best.desc.startsWith("ifs")) break;           // modèle fixe : un seul essai
            drawParams(type, C, fixedC);
            if (type == JULIA || type == MULTIJULIA || type == PHOENIX) evalObject(C, maxAspect);
            else if (C.plane) {
                C.ang = type == LYAPUNOV ? (rnd() < 0.5 ? 0 : Math.PI / 2) : rndIn(0, 2 * Math.PI);
                evalPlane(C);
            }
            else if (type == IFS) evalIfs(C, maxAspect);
            else C.score = 1;
            double jitter = rndIn(0.8, 1.25);
            if (best == null || C.score * jitter > best.score) { best = C; best.score = C.score * jitter; }
        }
        if (best.score <= 0 && type != CARPET && type != TRIANGLE) {           // filet de sécurité
            best = new Params(); best.type = JULIA; best.cx = -0.8; best.cy = 0.156; best.desc = "secours";
            evalObject(best, maxAspect);
            if (best.w <= 0) { best.ang = 0; best.uc = best.vc = 0; best.w = 3.6; best.h = 2.2; }
        }
        P = best; type = best.type;

        /* 3. cadrage */
        double ang = best.ang;
        if (!best.plane) {
            if (type != CARPET && type != TRIANGLE && rnd() < 0.5) { ang += Math.PI; uc = -best.uc; vc = -best.vc; }
            else { uc = best.uc; vc = best.vc; }
            double margin = rnd() < 0.75 ? rndIn(1.06, 1.22) : rndIn(1.22, 1.45);
            s = Math.max(best.w / Wl, best.h / Hl) * margin;
        } else {
            double ca = Math.cos(ang), sa = Math.sin(ang);
            uc = best.cx * ca + best.cy * sa; vc = -best.cx * sa + best.cy * ca;
            s = best.w / Wl;
        }
        cs = Math.cos(ang); sn = Math.sin(ang);
        depth = Math.min(14, (int) Math.ceil(Math.log(1.0 / s) / Math.log(type == CARPET ? best.base : 2)) + 1);
        maxit = isEscape(type) ? (int) Math.min(type == MANDEL ? 2500 : 1500, Math.max(300, 1.6 * best.deep + 100)) : 1500;
        DEW = type == MANDEL ? 0.7f : best.interior > 0.05 ? 0.92f : 0.5f;

        if (isEscape(type)) {                                   // normalisation des couleurs
            boolean pSave = portrait; portrait = false;
            int pw = 400, ph = (int) (400.0 * Hl / Wl) + 1, n = 0;
            float[] nus = new float[pw * ph]; float[] o = new float[3]; double[] z = new double[2];
            double sx = (double) Wl / pw;
            for (int j = 0; j < ph; j++) for (int i = 0; i < pw; i++) {
                map((i + 0.5) * sx, (j + 0.5) * sx, z);
                float nu = raw(z[0], z[1], o);
                if (nu >= 0) nus[n++] = nu;
            }
            float lo = n > 0 ? quantile(nus, n, 0.01) : 0, hi = n > 0 ? quantile(nus, n, 0.998) : 50;
            nlo = lo; nhiLog = Math.log(Math.max(hi - lo, 4.0) + 1.0);
            portrait = pSave;
        }

        Result res = new Result();
        res.type = TYPE_NAME[type];
        res.params = isEscape(type) && type != MANDEL && type != SHIP && type != TRICORN
                ? String.format(Locale.ROOT, "c = %.5f %+.5fi, %s", best.cx, best.cy, best.desc)
                : best.desc;
        if (type == MANDEL || type == SHIP || type == TRICORN)
            res.params = String.format(Locale.ROOT, "centre %.6f %+.6fi, %s", best.cx, best.cy, best.desc);
        res.palette = pal.name; res.seed = seed; res.width = W; res.height = H;

        /* 4. passe 1 : valeur + relief (4 octets par pixel) */
        final int fW = W, fH = H;
        final char[] val = new char[W * H], rel = new char[W * H];
        final boolean noAA = type == IFS;
        if (type == IFS) renderIfs(val, rel);
        else parallelFor(H, new RowJob() {
            public void row(int y, float[] o, double[] z) {
                int base = y * fW;
                for (int x = 0; x < fW; x++) {
                    float v = sampleValue(x + 0.5, y + 0.5, o, z);
                    val[base + x] = (char) Math.round(Math.min(v, 3f) * 20000f);
                    rel[base + x] = (char) Math.round(o[2] * 65535f);
                }
            }
        });

        /* 5. passe 2 : couleur par bandes, compressées au fil de l'eau */
        final Lut Ld = pal.day, Ln = pal.night;
        final int BAND = 32;
        final byte[] bd = new byte[BAND * W * 3], bn = new byte[BAND * W * 3];
        final float[] OX = {0.125f, 0.625f, 0.375f, 0.875f}, OY = {0.375f, 0.125f, 0.875f, 0.625f};
        PngWriter pd = new PngWriter(day, W, H, res.info());
        PngWriter pn = null;
        try {
            pn = new PngWriter(night, W, H, res.info());
            for (int y0 = 0; y0 < H; y0 += BAND) {
                final int by0 = y0, rows = Math.min(BAND, H - y0);
                parallelFor(rows, new RowJob() {
                    public void row(int r, float[] o, double[] z) {
                        int y = by0 + r;
                        float[] cd = new float[3], cn = new float[3], ad = new float[3], an = new float[3];
                        for (int x = 0; x < fW; x++) {
                            int k = y * fW + x;
                            float v = val[k] / 20000f, rv = rel[k] / 65535f, dmax = 0;
                            if (!noAA) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx, yy = y + dy;
                                if (xx < 0 || yy < 0 || xx >= fW || yy >= fH) continue;
                                int kk = yy * fW + xx;
                                float w = val[kk] / 20000f, rw = rel[kk] / 65535f;
                                float d = ((v >= 2) != (w >= 2)) ? 1f
                                        : (v >= 2) ? 0.3f * Math.abs(v - w)
                                        : Math.abs(v - w) + RELIEF * Math.max(v, w) * Math.abs(rv - rw);
                                if (d > dmax) dmax = d;
                            }
                            if (dmax > 0.12f) {
                                ad[0] = ad[1] = ad[2] = an[0] = an[1] = an[2] = 0;
                                for (int q = 0; q < 4; q++) {
                                    float sv = sampleValue(x + OX[q], y + OY[q], o, z);
                                    valueColor(Ld, sv, o[2], cd); valueColor(Ln, sv, o[2], cn);
                                    for (int i = 0; i < 3; i++) { ad[i] += cd[i]; an[i] += cn[i]; }
                                }
                                for (int i = 0; i < 3; i++) { cd[i] = ad[i] * 0.25f; cn[i] = an[i] * 0.25f; }
                            } else { valueColor(Ld, v, rv, cd); valueColor(Ln, v, rv, cn); }
                            int off = (r * fW + x) * 3;
                            for (int i = 0; i < 3; i++) {
                                int h = hash2(x * 3 + i, y);
                                bd[off + i] = enc(cd[i], h);
                                bn[off + i] = enc(cn[i], h);
                            }
                        }
                    }
                });
                for (int r = 0; r < rows; r++) {
                    pd.row(bd, r * W * 3, by0 + r == 0);
                    pn.row(bn, r * W * 3, by0 + r == 0);
                }
            }
            pd.close(); pd = null;
            pn.close(); pn = null;
        } finally {
            if (pd != null) pd.abort();
            if (pn != null) pn.abort();
        }
        res.seconds = (System.nanoTime() - t0) / 1e9;
        return res;
    }

    /** IFS : jeu du chaos, densité de points (log) -> valeurs. */
    private void renderIfs(char[] val, char[] rel) {
        int W = Wimg, H = val.length / W, N = W * H;
        float[] dens = new float[N];
        long iters = Math.min(60_000_000L, 12L * N);
        double x = 0, y = 0;
        double[][] m = P.maps; double[] cum = P.cum;
        double is = 1.0 / s;
        for (long it = -100; it < iters; it++) {
            double[] f = m[ifsPick(cum, (u64() >>> 11) * (1.0 / 9007199254740992.0))];
            double nx = f[0] * x + f[1] * y + f[4], ny = f[2] * x + f[3] * y + f[5];
            x = nx; y = ny;
            if (it < 0) continue;
            double u = x * cs + y * sn, v = -x * sn + y * cs;
            double lx = (u - uc) * is + 0.5 * Wl, ly = 0.5 * Hl - (v - vc) * is;
            double px, py;
            if (portrait) { px = Wimg - ly; py = lx; } else { px = lx; py = ly; }
            px -= 0.5; py -= 0.5;
            int ix = (int) Math.floor(px), iy = (int) Math.floor(py);
            if (ix < 0 || iy < 0 || ix >= W - 1 || iy >= H - 1) continue;
            float fx = (float) (px - ix), fy = (float) (py - iy);
            int k = iy * W + ix;
            dens[k] += (1 - fx) * (1 - fy); dens[k + 1] += fx * (1 - fy);
            dens[k + W] += (1 - fx) * fy; dens[k + W + 1] += fx * fy;
        }
        int nz = 0;
        for (int k = 0; k < N; k++) if (dens[k] > 0) nz++;
        float[] tmp = new float[Math.max(1, nz)]; int j = 0;
        for (int k = 0; k < N; k++) if (dens[k] > 0) tmp[j++] = dens[k];
        float q = nz > 0 ? quantile(tmp, nz, 0.995) : 1;
        double lq = Math.log(1 + q);
        for (int k = 0; k < N; k++) {
            double v = dens[k] > 0 ? Math.min(1.0, Math.log(1 + dens[k]) / lq) : 0;
            v = Math.pow(v, 0.8);
            val[k] = (char) Math.round(v * 20000f);
            rel[k] = 65535;
        }
    }

    /** Outil en ligne de commande pour tester sur une JVM : W H graine jour.png nuit.png [type] */
    public static void main(String[] a) throws IOException {
        int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
        long seed = Long.parseLong(a[2]);
        Renderer r = new Renderer();
        if (a.length > 5) r.type(Integer.parseInt(a[5]));
        Result res = r.render(w, h, seed, new File(a[3]), new File(a[4]), null, -1);
        System.out.printf(Locale.ROOT, "%s  (%.2f s)%n", res.info(), res.seconds);
    }
}
