package io.github.louisbrusset.juliawallpaper;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Moteur de rendu des ensembles de Julia (portage Java du moteur C de
 * PROJ-julia-wallpaper-linux). Java pur, sans dépendance Android, pour pouvoir
 * être testé sur une JVM de bureau.
 *
 * Étapes : tirage biaisé de c près du bord de Mandelbrot, score sur aperçu,
 * cadrage par ACP, rendu multi-thread, coloration jour + nuit en une seule
 * passe, anti-crénelage adaptatif, écriture PNG en flux.
 *
 * Mémoire : 4 octets par pixel (valeur + relief sur 16 bits), plus des
 * tampons de bande de quelques centaines de Ko. Les images couleur complètes
 * ne sont jamais gardées en mémoire : chaque bande est compressée dès qu'elle
 * est colorée.
 */
public final class Renderer {

    /* ---------------------------------------------------------------- */
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

    /* ---------------------------------------------------------------- */
    /* Palettes                                                          */

    static final class Theme {
        final String name; final float[] nPos; final int[] nCol; final int[] nIn;
        final float[] dPos; final int[] dCol; final int[] dIn;
        Theme(String name, float[] nPos, int[] nCol, int[] nIn, float[] dPos, int[] dCol, int[] dIn) {
            this.name = name; this.nPos = nPos; this.nCol = nCol; this.nIn = nIn;
            this.dPos = dPos; this.dCol = dCol; this.dIn = dIn;
        }
    }
    private static final float[] P = {0f, .30f, .55f, .75f, .90f, 1f};
    /* t = 0 : fond, t = 1 : au contact de l'ensemble */
    static final Theme[] THEMES = {
        new Theme("aurore",
            P, new int[]{0x04070c, 0x08202c, 0x0d5560, 0x2fae94, 0xa8f0c8, 0xf4fff8}, new int[]{0x02060a, 0x082629},
            P, new int[]{0xc6d6cf, 0xb2cbc2, 0x7fb0a4, 0x3a8580, 0x15485a, 0x061824}, new int[]{0xbdd3ca, 0x8fbcb0}),
        new Theme("braise",
            P, new int[]{0x070305, 0x220810, 0x6e1418, 0xd0501a, 0xffbb4a, 0xfff4da}, new int[]{0x0b0306, 0x2a0a0c},
            P, new int[]{0xdcc9b2, 0xd9b795, 0xcd8c5a, 0xa8432a, 0x5c1620, 0x2a0810}, new int[]{0xe0c19f, 0xcb926b}),
        new Theme("nebuleuse",
            P, new int[]{0x05040d, 0x160c33, 0x4a1a6e, 0xb33680, 0xff98b4, 0xfff2f6}, new int[]{0x070516, 0x1d0c38},
            P, new int[]{0xcbc2d8, 0xbdaed4, 0x9a7dc4, 0x64399a, 0x31155a, 0x150830}, new int[]{0xc6b9de, 0xa38acb}),
        new Theme("ocean",
            P, new int[]{0x02040b, 0x061c38, 0x0c4c86, 0x2f98d0, 0x9fe0f4, 0xf2fdff}, new int[]{0x020610, 0x08203c},
            P, new int[]{0xc2d0d9, 0xabc4d4, 0x7eaacb, 0x2f6d9f, 0x123f6c, 0x061a34}, new int[]{0xb9cedb, 0x8ab4ce}),
        new Theme("jade",
            P, new int[]{0x040706, 0x0e2019, 0x285640, 0xa58f44, 0xf0d888, 0xfffbe8}, new int[]{0x030806, 0x112a1f},
            P, new int[]{0xd3ccb8, 0xc9c0a0, 0xb09e66, 0x4f775a, 0x1d3d2f, 0x0b1a14}, new int[]{0xcfc6a6, 0xb2a16c}),
    };

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
    private static void oklabLin(float[] L, float[] o, int off) {
        float l = L[0] + 0.3963377774f*L[1] + 0.2158037573f*L[2];
        float m = L[0] - 0.1055613458f*L[1] - 0.0638541728f*L[2];
        float s = L[0] - 0.0894841775f*L[1] - 1.2914855480f*L[2];
        l = l*l*l; m = m*m*m; s = s*s*s;
        o[off]     =  4.0767416621f*l - 3.3077115913f*m + 0.2309699292f*s;
        o[off + 1] = -1.2684380046f*l + 2.6097574011f*m - 0.3413193965f*s;
        o[off + 2] = -0.0041960863f*l - 0.7034186147f*m + 1.7076147010f*s;
        for (int i = 0; i < 3; i++) o[off + i] = Math.max(0f, Math.min(1f, o[off + i]));
    }
    static Lut buildLut(float[] pos, int[] col, int[] in) {
        Lut L = new Lut(); int n = pos.length;
        float[][] lab = new float[n][3]; float[] c = new float[3];
        for (int i = 0; i < n; i++) { hexLin(col[i], c); linOklab(c, lab[i]); }
        float[] m = new float[3];
        for (int k = 0; k <= LUT_N; k++) {
            float t = (float) k / LUT_N; int j = 0;
            while (j < n - 2 && t > pos[j + 1]) j++;
            float u = (t - pos[j]) / (pos[j + 1] - pos[j]);
            u = Math.max(0f, Math.min(1f, u));
            for (int i = 0; i < 3; i++) m[i] = lab[j][i] + (lab[j + 1][i] - lab[j][i]) * u;
            oklabLin(m, L.ext, k * 3);
        }
        hexLin(in[0], L.in0); hexLin(in[1], L.in1);
        return L;
    }

    /* ---------------------------------------------------------------- */
    /* Vue et itération                                                  */

    static final double BAIL2 = 1.0e6;
    static final double LOG_BAIL = 0.5 * Math.log(BAIL2);

    /* état du rendu final */
    private double cx, cy, uc, vc, cs, sn, s, nlo, nhiLog;
    private int Wl, Hl, Wimg, maxit;
    private boolean portrait;
    private float RELIEF = 0.45f, DEW = 0.5f, DEK = 2.0f;

    /** Itération de Julia. o[0] = distance estimée, o[1] = piège d'orbite, o[2] = relief.
     *  Renvoie nu >= 0 (temps de fuite lissé) ou -1 si le point ne s'échappe pas. */
    static float juliaIter(double zx, double zy, double cx, double cy, int maxit, float[] o) {
        double dx = 1, dy = 0, r2 = zx*zx + zy*zy, tr = r2;
        double hx = zx, hy = zy; int lam = 0, pw = 8;
        int i;
        for (i = 0; i < maxit; i++) {
            if (r2 > BAIL2) break;
            double ndx = 2 * (zx*dx - zy*dy), ndy = 2 * (zx*dy + zy*dx);
            dx = ndx; dy = ndy;
            double nzx = zx*zx - zy*zy + cx; zy = 2*zx*zy + cy; zx = nzx;
            r2 = zx*zx + zy*zy;
            if (r2 < tr) tr = r2;
            if (Math.abs(zx - hx) < 1e-9 && Math.abs(zy - hy) < 1e-9) { i = maxit; break; }
            if (++lam == pw) { hx = zx; hy = zy; lam = 0; pw <<= 1; }
        }
        o[1] = (float) tr;
        if (i >= maxit || r2 <= BAIL2) { o[0] = 0; o[2] = 1; return -1f; }
        double lr = 0.5 * Math.log(r2);
        double d = Math.sqrt(r2) * lr / Math.sqrt(dx*dx + dy*dy);
        o[0] = (d >= 0 && d < 1e30) ? (float) d : 0f;
        double ux = zx*dx + zy*dy, uy = zy*dx - zx*dy, un = Math.sqrt(ux*ux + uy*uy);
        if (un > 0 && un < 1e300) { ux /= un; uy /= un; } else { ux = uy = 0; }
        o[2] = (float) ((ux * 0.7071 + uy * 0.7071 + 1.2) / 2.2);
        return (float) (i + 1 - Math.log(lr / LOG_BAIL) / Math.log(2));
    }

    /** Pixel de l'image -> plan complexe. En portrait, le cadre "paysage" est tourné de 90°. */
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
        float nu = juliaIter(z[0], z[1], cx, cy, maxit, o);
        if (nu < 0) {
            float sh = (float) Math.sqrt(Math.sqrt(o[1]));
            return 2f + Math.min(sh, 1f);
        }
        float ti = (float) (Math.log(Math.max(nu - nlo, 0) + 1.0) / nhiLog);
        ti = Math.min(ti, 1f);
        ti = ti * ti * (1.6f - 0.6f * ti);
        float dpx = (float) (o[0] / s);
        float q = dpx * DEK, td = 1f / (1f + q * q);
        return Math.min(1f, Math.max(ti, td * DEW));
    }

    private void valueColor(Lut L, float v, float rel, float[] o) {
        if (v >= 2f) {
            float u = v - 2f;
            for (int i = 0; i < 3; i++) o[i] = L.in0[i] + (L.in1[i] - L.in0[i]) * u;
            return;
        }
        float f = v * LUT_N; int k = (int) f; if (k >= LUT_N) k = LUT_N - 1; f -= k;
        float m = 1f - RELIEF * v * (1f - rel);
        int a = k * 3, b = a + 3;
        for (int i = 0; i < 3; i++) o[i] = (L.ext[a + i] + (L.ext[b + i] - L.ext[a + i]) * f) * m;
    }

    /* ---------------------------------------------------------------- */
    /* Choix de c et cadrage                                             */

    static int mandelEscape(double cx, double cy, int maxit) {
        double zx = 0, zy = 0;
        for (int i = 0; i < maxit; i++) {
            double x2 = zx*zx, y2 = zy*zy;
            if (x2 + y2 > 4) return i;
            zy = 2*zx*zy + cy; zx = x2 - y2 + cx;
        }
        return -1;
    }

    private static final double[][] CLASSICS = {
        {-0.8, 0.156}, {-0.7269, 0.1889}, {0.285, 0.01}, {-0.4, 0.6}, {-0.70176, 0.3842},
        {0.355, 0.355}, {-0.835, 0.2321}, {-0.162, 1.04}, {-0.54, 0.54}, {0.37, 0.1},
        {-0.75, 0.11}, {-0.1, 0.651}, {-1.476, 0.0}, {0.26, 0.0015}, {-0.12, 0.75},
        {0.3, 0.5}, {-0.391, 0.587}, {-0.8, 0.169}, {0.45, 0.1428}, {-0.624, 0.435},
    };
    static final String[] FAMILY = {"poussiere", "cardioide", "bulbe-2", "classique", "interieur-bord"};
    private static final double[] FAMILY_W = {0.28, 0.27, 0.13, 0.17, 0.15};

    private int pickFamily() {
        double r = rnd(), acc = 0;
        for (int f = 0; f < FAMILY_W.length; f++) { acc += FAMILY_W[f]; if (r < acc) return f; }
        return 0;
    }

    private void drawC(int fam, double[] c) {
        switch (fam) {
            case 0:
                for (int t = 0; t < 200000; t++) {
                    double x = rndIn(-2.0, 0.5), y = rndIn(0.0, 1.15);
                    if (mandelEscape(x, y, 3000) >= 50) { c[0] = x; c[1] = y; return; }
                }
                // fallthrough
            case 1: {
                double th = rndIn(0, 2 * Math.PI), rr = 1.0 + rndIn(-0.06, 0.02);
                double ex = rr * Math.cos(th), ey = rr * Math.sin(th);
                double e2x = ex*ex - ey*ey, e2y = 2*ex*ey;
                c[0] = ex / 2 - e2x / 4; c[1] = ey / 2 - e2y / 4;
                return;
            }
            case 2: {
                double th = rndIn(0, 2 * Math.PI), rr = 1.0 + rndIn(-0.08, 0.03);
                c[0] = -1 + 0.25 * rr * Math.cos(th); c[1] = 0.25 * rr * Math.sin(th);
                return;
            }
            case 3: {
                int k = (int) (rnd() * CLASSICS.length);
                c[0] = CLASSICS[k][0] + rndIn(-0.003, 0.003);
                c[1] = CLASSICS[k][1] + rndIn(-0.003, 0.003);
                return;
            }
            default:
                for (int t = 0; t < 200000; t++) {
                    double x = rndIn(-2.0, 0.5), y = rndIn(0.0, 1.15), r = rndIn(0.004, 0.02);
                    if (mandelEscape(x, y, 800) >= 0) continue;
                    boolean out = false;
                    for (int k = 0; k < 6 && !out; k++)
                        out = mandelEscape(x + r * Math.cos(k * Math.PI / 3), y + r * Math.sin(k * Math.PI / 3), 800) >= 0;
                    if (out) { c[0] = x; c[1] = y; return; }
                }
                c[0] = -0.123; c[1] = 0.745;
        }
    }

    static final class Cand {
        double cx, cy, ang, uc, vc, w, h, score, density, fill, aspect, deep, interior;
        int family;
    }

    private static float quantile(float[] a, int n, double q) {
        Arrays.sort(a, 0, n);
        int k = (int) (q * (n - 1)); return a[Math.max(0, Math.min(n - 1, k))];
    }

    static final int PV = 200;

    /** Aperçu basse résolution d'un candidat et calcul de son score. */
    private void evaluate(final Cand C, double maxAspect) {
        final float[] val = new float[PV * PV];
        final double s0 = 4.0 / PV;
        parallelFor(PV, new RowJob() {
            public void row(int j, float[] o, double[] z) {
                for (int i = 0; i < PV; i++)
                    val[j * PV + i] = juliaIter(-2 + (i + 0.5) * s0, 2 - (j + 0.5) * s0, C.cx, C.cy, 1500, o);
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

        double sw = 0, mx = 0, my = 0, sxx = 0, syy = 0, sxy = 0; int nm = 0, nin = 0;
        for (int j = 0; j < PV; j++) for (int i = 0; i < PV; i++) {
            if (val[j * PV + i] < 0.5f) continue;
            double x = -2 + (i + 0.5) * s0, y = 2 - (j + 0.5) * s0;
            sw += 1; mx += x; my += y; sxx += x*x; syy += y*y; sxy += x*y; nm++;
            if (ins[j * PV + i]) nin++;
        }
        if (nm < 60) return;
        mx /= sw; my /= sw; sxx = sxx/sw - mx*mx; syy = syy/sw - my*my; sxy = sxy/sw - mx*my;
        double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double c0 = Math.cos(ang), s1 = Math.sin(ang);
        float[] us = new float[nm], vs = new float[nm]; int m = 0;
        for (int j = 0; j < PV; j++) for (int i = 0; i < PV; i++) {
            if (val[j * PV + i] < 0.5f) continue;
            double x = -2 + (i + 0.5) * s0, y = 2 - (j + 0.5) * s0;
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
        for (int j = 0; j < PV - 1; j++) for (int i = 0; i < PV - 1; i++) {
            float a = val[j*PV+i], b = val[j*PV+i+1], c = val[(j+1)*PV+i];
            if (Math.abs(a - b) + Math.abs(a - c) > 0.22f) edges++;
        }
        double density = edges / boxPx, fill = nm / boxPx, aspect = w / h, interior = (double) nin / nm;
        C.density = density; C.fill = fill; C.aspect = aspect; C.interior = interior;
        double sc = Math.pow(density, 0.35);
        if (fill < 0.10) sc *= fill / 0.10;
        if (interior > 0.85) sc *= 0.5;
        if (aspect > maxAspect) sc *= Math.pow(maxAspect / aspect, 2.0);
        C.score = sc;
    }

    /* ---------------------------------------------------------------- */
    /* Parallélisme : N threads qui se partagent les lignes               */

    interface RowJob { void row(int y, float[] o, double[] z); }

    private int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private ThreadFactory threadFactory = new ThreadFactory() {
        public Thread newThread(Runnable r) { return new Thread(r, "julia-render"); }
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

    /* ---------------------------------------------------------------- */
    /* PNG en flux                                                        */

    static final class PngWriter implements Closeable {
        private final OutputStream out; private final File tmp, dst;
        private final Deflater def = new Deflater(4);
        private final byte[] zbuf = new byte[1 << 16];
        private final CRC32 crc = new CRC32();
        private final int stride; private byte[] prev; private final byte[][] cand; private final byte[] rowOut;

        PngWriter(File dst, int w, int h, String comment) throws IOException {
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
        /** Ajoute une ligne RVB (filtre adaptatif choisi par somme minimale). */
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
            boolean ok = false;
            try {
                def.finish(); drain(true); chunk("IEND", new byte[0], 0, 0);
                out.close(); ok = true;
            } finally {
                def.end();
                if (!ok) { try { out.close(); } catch (IOException ignored) {} tmp.delete(); }
            }
            if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("rename " + dst); }
        }
        void abort() { try { out.close(); } catch (IOException ignored) {} def.end(); tmp.delete(); }
    }

    /* ---------------------------------------------------------------- */
    /* Quantification sRGB + tramage                                      */

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

    /* ---------------------------------------------------------------- */
    /* Point d'entrée                                                     */

    public static final class Result {
        public double cx, cy; public String theme, family; public long seed; public int width, height;
        public String info() {
            return String.format(java.util.Locale.ROOT, "c = %.6f %+.6fi | %s | %s", cx, cy, theme, family);
        }
    }

    /** Options : thread factory (priorité basse sur Android), nombre de threads. */
    public Renderer threads(int n, ThreadFactory tf) {
        if (n > 0) nThreads = n;
        if (tf != null) threadFactory = tf;
        return this;
    }

    /**
     * Rend une fractale de taille W x H (portrait accepté) et écrit day/night en PNG.
     * @param fixedC paramètre imposé {re, im}, ou null pour un tirage
     * @param theme  index de thème, ou -1 pour un tirage
     */
    public Result render(int W, int H, long seed, File day, File night, double[] fixedC, int theme) throws IOException {
        rngState = seed;
        portrait = H > W;
        Wimg = W;
        Wl = Math.max(W, H); Hl = Math.min(W, H);
        double screenAspect = (double) Wl / Hl;
        double maxAspect = Math.max(16.0 / 9.0, screenAspect);

        Cand best = new Cand(); best.score = -1;
        int ncand = fixedC != null ? 1 : 10, fam = pickFamily();
        double[] c = new double[2];
        for (int k = 0; k < ncand; k++) {
            Cand C = new Cand();
            if (fixedC != null) { C.cx = fixedC[0]; C.cy = fixedC[1]; C.family = -1; }
            else { C.family = fam; drawC(fam, c); C.cx = c[0]; C.cy = rnd() < 0.5 ? -c[1] : c[1]; }
            evaluate(C, maxAspect);
            double jitter = fixedC != null ? 1 : rndIn(0.8, 1.25);
            if (C.score * jitter > best.score) { best = C; best.score = C.score * jitter; }
        }
        if (best.score <= 0 && fixedC == null) {
            best = new Cand(); best.cx = -0.8; best.cy = 0.156; best.family = 3; evaluate(best, maxAspect);
        }
        if (best.w <= 0) { best.ang = 0; best.uc = best.vc = 0; best.w = 3.6; best.h = 2.2; }

        DEW = best.interior > 0.05 ? 0.92f : 0.5f;
        cx = best.cx; cy = best.cy;
        maxit = (int) Math.min(1200, Math.max(300, 1.6 * best.deep + 100));
        double ang = best.ang;
        if (rnd() < 0.5) { ang += Math.PI; uc = -best.uc; vc = -best.vc; } else { uc = best.uc; vc = best.vc; }
        cs = Math.cos(ang); sn = Math.sin(ang);
        double margin = rndIn(1.10, 1.22);
        s = Math.max(best.w / Wl, best.h / Hl) * margin;

        /* normalisation des couleurs (aperçu cadré, repère paysage) */
        {
            boolean pSave = portrait; portrait = false;
            int pw = 400, ph = (int) (400.0 * Hl / Wl) + 1, n = 0;
            float[] nus = new float[pw * ph]; float[] o = new float[3]; double[] z = new double[2];
            double sx = (double) Wl / pw;
            for (int j = 0; j < ph; j++) for (int i = 0; i < pw; i++) {
                map((i + 0.5) * sx, (j + 0.5) * sx, z);
                float nu = juliaIter(z[0], z[1], cx, cy, maxit, o);
                if (nu >= 0) nus[n++] = nu;
            }
            float lo = n > 0 ? quantile(nus, n, 0.01) : 0, hi = n > 0 ? quantile(nus, n, 0.998) : 50;
            nlo = lo; nhiLog = Math.log(Math.max(hi - lo, 4.0) + 1.0);
            portrait = pSave;
        }

        if (theme < 0 || theme >= THEMES.length) theme = (int) (rnd() * THEMES.length);
        final Lut Ld = buildLut(THEMES[theme].dPos, THEMES[theme].dCol, THEMES[theme].dIn);
        final Lut Ln = buildLut(THEMES[theme].nPos, THEMES[theme].nCol, THEMES[theme].nIn);

        Result res = new Result();
        res.cx = cx; res.cy = cy; res.theme = THEMES[theme].name; res.seed = seed;
        res.family = best.family >= 0 ? FAMILY[best.family] : "manuel"; res.width = W; res.height = H;

        /* passe 1 : valeur + relief au centre des pixels (4 octets/pixel) */
        final int fW = W, fH = H;
        final char[] val = new char[W * H], rel = new char[W * H];
        parallelFor(H, new RowJob() {
            public void row(int y, float[] o, double[] z) {
                int base = y * fW;
                for (int x = 0; x < fW; x++) {
                    float v = sampleValue(x + 0.5, y + 0.5, o, z);
                    val[base + x] = (char) Math.round(v * 20000f);
                    rel[base + x] = (char) Math.round(o[2] * 65535f);
                }
            }
        });

        /* passe 2 : couleur par bandes, compressées au fil de l'eau */
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
                            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
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
        return res;
    }

    /** Petit outil en ligne de commande pour tester sur une JVM de bureau. */
    public static void main(String[] a) throws IOException {
        int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
        long seed = Long.parseLong(a[2]);
        long t0 = System.nanoTime();
        Result r = new Renderer().render(w, h, seed, new File(a[3]), new File(a[4]), null, -1);
        System.out.printf(java.util.Locale.ROOT, "%s  (%.2f s)%n", r.info(), (System.nanoTime() - t0) / 1e9);
    }
}
