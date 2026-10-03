package com.orbitwatch.astro;

import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.pow;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;

/**
 * SGP4 / SDP4 analytical orbit propagator.
 *
 * <p>A line-for-line Java port of David Vallado's reference implementation (sgp4unit.cpp, 2006 AIAA
 * paper "Revisiting Spacetrack Report #3", with all subsequent "sgp4fix" corrections). The port is
 * validated against the reference Python package {@code sgp4} (which wraps Vallado's C++) in
 * {@code Sgp4ReferenceTest}.
 *
 * <p>Design notes for reviewers:
 * <ul>
 *   <li>Pure Java, no dependencies, allocation-free in the hot path once initialised.</li>
 *   <li>One {@code Sgp4} instance per element set. Initialisation is the expensive part; propagation
 *       to any time since epoch is a few hundred floating point operations.</li>
 *   <li>Instances are NOT thread-safe for concurrent {@link #propagate(double)} calls because the
 *       deep-space integrator keeps state ({@code atime, xli, xni}). Callers that fan out across
 *       threads must use one instance per thread or one per call.</li>
 *   <li>Output frame is TEME (True Equator, Mean Equinox) — the native frame of SGP4. Use
 *       {@link Frames} to convert to ECEF / geodetic.</li>
 * </ul>
 */
public final class Sgp4 {

    /** Gravitational constant set. WGS-72 is what the element sets are fitted against; use it. */
    public enum GravityModel {
        WGS72(398600.8, 6378.135, 0.001082616, -0.00000253881, -0.00000165597),
        WGS84(398600.5, 6378.137, 0.00108262998905, -0.00000253215306, -0.00000161098761);

        final double mu, radiusEarthKm, j2, j3, j4, xke, tumin, j3oj2;

        GravityModel(double mu, double radiusEarthKm, double j2, double j3, double j4) {
            this.mu = mu;
            this.radiusEarthKm = radiusEarthKm;
            this.j2 = j2;
            this.j3 = j3;
            this.j4 = j4;
            this.xke = 60.0 / sqrt(radiusEarthKm * radiusEarthKm * radiusEarthKm / mu);
            this.tumin = 1.0 / xke;
            this.j3oj2 = j3 / j2;
        }
    }

    /** Result of a propagation: position (km) and velocity (km/s) in TEME. */
    public record StateVector(double x, double y, double z, double vx, double vy, double vz) {
        public double speedKmS() { return sqrt(vx * vx + vy * vy + vz * vz); }
        public double radiusKm() { return sqrt(x * x + y * y + z * z); }
    }

    /** Thrown when the mean elements cannot be propagated (decayed / non-physical). */
    public static final class PropagationException extends RuntimeException {
        public final int code;
        PropagationException(int code, String msg) { super(msg); this.code = code; }
    }

    private static final double TWOPI = 2.0 * PI;
    private static final double X2O3 = 2.0 / 3.0;
    private static final double TEMP4 = 1.5e-12;

    // ---- constants ----
    private final GravityModel g;
    private final char opsmode;

    // ---- near-earth ----
    private int isimp;
    private char method;
    private double aycof, con41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof, sinmao,
            t, t2cof, t3cof, t4cof, t5cof, x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf;

    // ---- deep space ----
    private int irez;
    private double d2201, d2211, d3210, d3222, d4410, d4422, d5220, d5232, d5421, d5433, dedt,
            del1, del2, del3, didt, dmdt, dnodt, domdt, e3, ee2, peo, pgho, pho, pinco, plo, se2,
            se3, sgh2, sgh3, sgh4, sh2, sh3, si2, si3, sl2, sl3, sl4, gsto, xfact, xgh2, xgh3,
            xgh4, xh2, xh3, xi2, xi3, xl2, xl3, xl4, xlamo, zmol, zmos, atime, xli, xni;

    // ---- elements ----
    private double bstar, ecco, argpo, inclo, mo, noKozai, nodeo, noUnkozai;
    private char init;

    /** Un-Kozai'd mean motion in rad/min. */
    public double meanMotionRadPerMin() { return noUnkozai; }

    /** Whether the deep-space (SDP4) branch is active (period >= 225 min). */
    public boolean isDeepSpace() { return method == 'd'; }

    /**
     * Initialise from mean elements.
     *
     * @param epochDaysSince1950 epoch as days since 1949-12-31 00:00 UTC (i.e. JD - 2433281.5)
     * @param bstar   drag term (1/earth radii)
     * @param ecco    eccentricity
     * @param argpoRad argument of perigee (rad)
     * @param incloRad inclination (rad)
     * @param moRad   mean anomaly (rad)
     * @param noKozaiRadPerMin Kozai mean motion (rad/min)
     * @param nodeoRad RAAN (rad)
     */
    public Sgp4(GravityModel model, double epochDaysSince1950, double bstar, double ecco,
                double argpoRad, double incloRad, double moRad, double noKozaiRadPerMin,
                double nodeoRad) {
        this.g = model;
        this.opsmode = 'i';
        sgp4init(epochDaysSince1950, bstar, ecco, argpoRad, incloRad, moRad, noKozaiRadPerMin, nodeoRad);
    }

    /** Convenience factory from the CelesTrak / CCSDS OMM (JSON) field conventions. */
    public static Sgp4 fromOmm(double epochDaysSince1950, double meanMotionRevPerDay, double eccentricity,
                               double inclinationDeg, double raanDeg, double argPerigeeDeg,
                               double meanAnomalyDeg, double bstar) {
        double d2r = PI / 180.0;
        return new Sgp4(GravityModel.WGS72, epochDaysSince1950, bstar, eccentricity,
                argPerigeeDeg * d2r, inclinationDeg * d2r, meanAnomalyDeg * d2r,
                meanMotionRevPerDay / 720.0 * PI, raanDeg * d2r);
    }

    // =====================================================================================
    // dpper - deep space long period periodic contributions to the mean elements
    // =====================================================================================
    private double[] dpper(double inclo, char init, double ep, double inclp, double nodep, double argpp, double mp) {
        double zns = 1.19459e-5, zes = 0.01675, znl = 1.5835218e-4, zel = 0.05490;

        double zm = zmos + zns * t;
        if (init == 'y') zm = zmos;
        double zf = zm + 2.0 * zes * sin(zm);
        double sinzf = sin(zf);
        double f2 = 0.5 * sinzf * sinzf - 0.25;
        double f3 = -0.5 * sinzf * cos(zf);
        double ses = se2 * f2 + se3 * f3;
        double sis = si2 * f2 + si3 * f3;
        double sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf;
        double sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf;
        double shs = sh2 * f2 + sh3 * f3;
        zm = zmol + znl * t;
        if (init == 'y') zm = zmol;
        zf = zm + 2.0 * zel * sin(zm);
        sinzf = sin(zf);
        f2 = 0.5 * sinzf * sinzf - 0.25;
        f3 = -0.5 * sinzf * cos(zf);
        double sel = ee2 * f2 + e3 * f3;
        double sil = xi2 * f2 + xi3 * f3;
        double sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf;
        double sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf;
        double shll = xh2 * f2 + xh3 * f3;
        double pe = ses + sel;
        double pinc = sis + sil;
        double pl = sls + sll;
        double pgh = sghs + sghl;
        double ph = shs + shll;

        if (init == 'n') {
            pe = pe - peo;
            pinc = pinc - pinco;
            pl = pl - plo;
            pgh = pgh - pgho;
            ph = ph - pho;
            inclp = inclp + pinc;
            ep = ep + pe;
            double sinip = sin(inclp);
            double cosip = cos(inclp);

            if (inclp >= 0.2) {
                ph /= sinip;
                pgh -= cosip * ph;
                argpp += pgh;
                nodep += ph;
                mp += pl;
            } else {
                double sinop = sin(nodep);
                double cosop = cos(nodep);
                double alfdp = sinip * sinop;
                double betdp = sinip * cosop;
                double dalf = ph * cosop + pinc * cosip * sinop;
                double dbet = -ph * sinop + pinc * cosip * cosop;
                alfdp = alfdp + dalf;
                betdp = betdp + dbet;
                nodep = nodep % TWOPI; // truncated modulo, matches reference
                if (nodep < 0.0 && opsmode == 'a') nodep = nodep + TWOPI;
                double xls = mp + argpp + pl + pgh + (cosip - pinc * sinip) * nodep;
                double xnoh = nodep;
                nodep = atan2(alfdp, betdp);
                if (nodep < 0.0 && opsmode == 'a') nodep = nodep + TWOPI;
                if (abs(xnoh - nodep) > PI) {
                    if (nodep < xnoh) nodep = nodep + TWOPI;
                    else nodep = nodep - TWOPI;
                }
                mp += pl;
                argpp = xls - mp - cosip * nodep;
            }
        }
        return new double[]{ep, inclp, nodep, argpp, mp};
    }

    // Scratch outputs of dscom consumed by dsinit (only needed during init).
    private double snodm, cnodm, sinim, cosim, sinomm, cosomm, day, em, emsq, gam, rtemsq,
            s1, s2, s3, s4, s5, s6, s7, ss1, ss2, ss3, ss4, ss5, ss6, ss7, sz1, sz2, sz3,
            sz11, sz12, sz13, sz21, sz22, sz23, sz31, sz32, sz33, nm, z1, z2, z3, z11, z12, z13,
            z21, z22, z23, z31, z32, z33;

    // =====================================================================================
    // dscom - deep space common items
    // =====================================================================================
    private void dscom(double epoch, double ep, double argpp, double tc, double inclp, double nodep, double np) {
        double zes = 0.01675, zel = 0.05490, c1ss = 2.9864797e-6, c1l = 4.7968065e-7,
                zsinis = 0.39785416, zcosis = 0.91744867, zcosgs = 0.1945905, zsings = -0.98088458;

        nm = np;
        em = ep;
        snodm = sin(nodep);
        cnodm = cos(nodep);
        sinomm = sin(argpp);
        cosomm = cos(argpp);
        sinim = sin(inclp);
        cosim = cos(inclp);
        emsq = em * em;
        double betasq = 1.0 - emsq;
        rtemsq = sqrt(betasq);

        peo = 0.0; pinco = 0.0; plo = 0.0; pgho = 0.0; pho = 0.0;
        day = epoch + 18261.5 + tc / 1440.0;
        double xnodce = floorMod2pi(4.5236020 - 9.2422029e-4 * day);
        double stem = sin(xnodce);
        double ctem = cos(xnodce);
        double zcosil = 0.91375164 - 0.03568096 * ctem;
        double zsinil = sqrt(1.0 - zcosil * zcosil);
        double zsinhl = 0.089683511 * stem / zsinil;
        double zcoshl = sqrt(1.0 - zsinhl * zsinhl);
        gam = 5.8351514 + 0.0019443680 * day;
        double zx = 0.39785416 * stem / zsinil;
        double zy = zcoshl * ctem + 0.91744867 * zsinhl * stem;
        zx = atan2(zx, zy);
        zx = gam + zx - xnodce;
        double zcosgl = cos(zx);
        double zsingl = sin(zx);

        double zcosg = zcosgs, zsing = zsings, zcosi = zcosis, zsini = zsinis, zcosh = cnodm, zsinh = snodm;
        double cc = c1ss;
        double xnoi = 1.0 / nm;

        for (int lsflg = 1; lsflg <= 2; lsflg++) {
            double a1 = zcosg * zcosh + zsing * zcosi * zsinh;
            double a3 = -zsing * zcosh + zcosg * zcosi * zsinh;
            double a7 = -zcosg * zsinh + zsing * zcosi * zcosh;
            double a8 = zsing * zsini;
            double a9 = zsing * zsinh + zcosg * zcosi * zcosh;
            double a10 = zcosg * zsini;
            double a2 = cosim * a7 + sinim * a8;
            double a4 = cosim * a9 + sinim * a10;
            double a5 = -sinim * a7 + cosim * a8;
            double a6 = -sinim * a9 + cosim * a10;

            double x1 = a1 * cosomm + a2 * sinomm;
            double x2 = a3 * cosomm + a4 * sinomm;
            double x3 = -a1 * sinomm + a2 * cosomm;
            double x4 = -a3 * sinomm + a4 * cosomm;
            double x5 = a5 * sinomm;
            double x6 = a6 * sinomm;
            double x7 = a5 * cosomm;
            double x8 = a6 * cosomm;

            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3;
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4;
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4;
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq;
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq;
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq;
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5);
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5));
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6);
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7);
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8));
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8);
            z1 = z1 + z1 + betasq * z31;
            z2 = z2 + z2 + betasq * z32;
            z3 = z3 + z3 + betasq * z33;
            s3 = cc * xnoi;
            s2 = -0.5 * s3 / rtemsq;
            s4 = s3 * rtemsq;
            s1 = -15.0 * em * s4;
            s5 = x1 * x3 + x2 * x4;
            s6 = x2 * x3 + x1 * x4;
            s7 = x2 * x4 - x1 * x3;

            if (lsflg == 1) {
                ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7;
                sz1 = z1; sz2 = z2; sz3 = z3;
                sz11 = z11; sz12 = z12; sz13 = z13;
                sz21 = z21; sz22 = z22; sz23 = z23;
                sz31 = z31; sz32 = z32; sz33 = z33;
                zcosg = zcosgl; zsing = zsingl; zcosi = zcosil; zsini = zsinil;
                zcosh = zcoshl * cnodm + zsinhl * snodm;
                zsinh = snodm * zcoshl - cnodm * zsinhl;
                cc = c1l;
            }
        }

        zmol = floorMod2pi(4.7199672 + 0.22997150 * day - gam);
        zmos = floorMod2pi(6.2565837 + 0.017201977 * day);

        se2 = 2.0 * ss1 * ss6;
        se3 = 2.0 * ss1 * ss7;
        si2 = 2.0 * ss2 * sz12;
        si3 = 2.0 * ss2 * (sz13 - sz11);
        sl2 = -2.0 * ss3 * sz2;
        sl3 = -2.0 * ss3 * (sz3 - sz1);
        sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes;
        sgh2 = 2.0 * ss4 * sz32;
        sgh3 = 2.0 * ss4 * (sz33 - sz31);
        sgh4 = -18.0 * ss4 * zes;
        sh2 = -2.0 * ss2 * sz22;
        sh3 = -2.0 * ss2 * (sz23 - sz21);

        ee2 = 2.0 * s1 * s6;
        e3 = 2.0 * s1 * s7;
        xi2 = 2.0 * s2 * z12;
        xi3 = 2.0 * s2 * (z13 - z11);
        xl2 = -2.0 * s3 * z2;
        xl3 = -2.0 * s3 * (z3 - z1);
        xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel;
        xgh2 = 2.0 * s4 * z32;
        xgh3 = 2.0 * s4 * (z33 - z31);
        xgh4 = -18.0 * s4 * zel;
        xh2 = -2.0 * s2 * z22;
        xh3 = -2.0 * s2 * (z23 - z21);
    }

    // =====================================================================================
    // dsinit - deep space contributions to mean motion dot due to geopotential resonance
    // =====================================================================================
    private void dsinit(double tc, double xpidot, double eccsq) {
        double q22 = 1.7891679e-6, q31 = 2.1460748e-6, q33 = 2.2123015e-7,
                root22 = 1.7891679e-6, root44 = 7.3636953e-9, root54 = 2.1765803e-9,
                rptim = 4.37526908801129966e-3, root32 = 3.7393792e-7, root52 = 1.1428639e-7,
                znl = 1.5835218e-4, zns = 1.19459e-5;

        double inclm = inclo;
        double argpm = 0.0, nodem = 0.0, mm = 0.0;

        irez = 0;
        if (0.0034906585 < nm && nm < 0.0052359877) irez = 1;
        if (8.26e-3 <= nm && nm <= 9.24e-3 && em >= 0.5) irez = 2;

        double ses = ss1 * zns * ss5;
        double sis = ss2 * zns * (sz11 + sz13);
        double sls = -zns * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq);
        double sghs = ss4 * zns * (sz31 + sz33 - 6.0);
        double shs = -zns * ss2 * (sz21 + sz23);
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0;
        if (sinim != 0.0) shs = shs / sinim;
        double sgs = sghs - cosim * shs;

        dedt = ses + s1 * znl * s5;
        didt = sis + s2 * znl * (z11 + z13);
        dmdt = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq);
        double sghl = s4 * znl * (z31 + z33 - 6.0);
        double shll = -znl * s2 * (z21 + z23);
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0;
        domdt = sgs + sghl;
        dnodt = shs;
        if (sinim != 0.0) {
            domdt = domdt - cosim / sinim * shll;
            dnodt = dnodt + shll / sinim;
        }

        double dndt = 0.0;
        double theta = floorMod2pi(gsto + tc * rptim);
        em = em + dedt * t;
        inclm = inclm + didt * t;
        argpm = argpm + domdt * t;
        nodem = nodem + dnodt * t;
        mm = mm + dmdt * t;

        if (irez != 0) {
            double aonv = pow(nm / g.xke, X2O3);

            if (irez == 2) {
                double cosisq = cosim * cosim;
                double emo = em;
                em = ecco;
                double emsqo = emsq;
                emsq = eccsq;
                double eoc = em * emsq;
                double g201 = -0.306 - (em - 0.64) * 0.440;
                double g211, g310, g322, g410, g422, g520, g533, g521, g532;

                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq;
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc;
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc;
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc;
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc;
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc;
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc;
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc;
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc;
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc;
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc;
                    if (em > 0.715) g520 = -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc;
                    else g520 = 1464.74 - 4664.75 * em + 3763.64 * emsq;
                }
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc;
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc;
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc;
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc;
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc;
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc;
                }

                double sini2 = sinim * sinim;
                double f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq);
                double f221 = 1.5 * sini2;
                double f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq);
                double f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq);
                double f441 = 35.0 * sini2 * f220;
                double f442 = 39.3750 * sini2 * sini2;
                double f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq)
                        + 0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq));
                double f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq)
                        + 6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq));
                double f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq));
                double f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq));
                double xno2 = nm * nm;
                double ainv2 = aonv * aonv;
                double temp1 = 3.0 * xno2 * ainv2;
                double temp = temp1 * root22;
                d2201 = temp * f220 * g201;
                d2211 = temp * f221 * g211;
                temp1 = temp1 * aonv;
                temp = temp1 * root32;
                d3210 = temp * f321 * g310;
                d3222 = temp * f322 * g322;
                temp1 = temp1 * aonv;
                temp = 2.0 * temp1 * root44;
                d4410 = temp * f441 * g410;
                d4422 = temp * f442 * g422;
                temp1 = temp1 * aonv;
                temp = temp1 * root52;
                d5220 = temp * f522 * g520;
                d5232 = temp * f523 * g532;
                temp = 2.0 * temp1 * root54;
                d5421 = temp * f542 * g521;
                d5433 = temp * f543 * g533;
                xlamo = floorMod2pi(mo + nodeo + nodeo - theta - theta);
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - noUnkozai;
                em = emo;
                emsq = emsqo;
            }

            if (irez == 1) {
                double g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq);
                double g310 = 1.0 + 2.0 * emsq;
                double g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq);
                double f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim);
                double f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim);
                double f330 = 1.0 + cosim;
                f330 = 1.875 * f330 * f330 * f330;
                del1 = 3.0 * nm * nm * aonv * aonv;
                del2 = 2.0 * del1 * f220 * g200 * q22;
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv;
                del1 = del1 * f311 * g310 * q31 * aonv;
                xlamo = floorMod2pi(mo + nodeo + argpo - theta);
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - noUnkozai;
            }

            xli = xlamo;
            xni = noUnkozai;
            atime = 0.0;
            nm = noUnkozai + dndt;
        }
    }

    // =====================================================================================
    // dspace - deep space contributions to mean elements for perturbing third body
    // returns {em, argpm, inclm, mm, nodem, nm}
    // =====================================================================================
    private double[] dspace(double tc, double em, double argpm, double inclm, double mm, double nodem, double nm) {
        double fasx2 = 0.13130908, fasx4 = 2.8843198, fasx6 = 0.37448087, g22 = 5.7686396,
                g32 = 0.95240898, g44 = 1.8014998, g52 = 1.0508330, g54 = 4.4108898,
                rptim = 4.37526908801129966e-3, stepp = 720.0, stepn = -720.0, step2 = 259200.0;

        double dndt = 0.0;
        double theta = floorMod2pi(gsto + tc * rptim);
        em = em + dedt * t;
        inclm = inclm + didt * t;
        argpm = argpm + domdt * t;
        nodem = nodem + dnodt * t;
        mm = mm + dmdt * t;

        double ft = 0.0;
        if (irez != 0) {
            if (atime == 0.0 || t * atime <= 0.0 || abs(t) < abs(atime)) {
                atime = 0.0;
                xni = noUnkozai;
                xli = xlamo;
            }
            double delt = t > 0.0 ? stepp : stepn;

            double xndt = 0, xldot = 0, xnddt = 0;
            int iretn = 381;
            while (iretn == 381) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - fasx2) + del2 * sin(2.0 * (xli - fasx4)) + del3 * sin(3.0 * (xli - fasx6));
                    xldot = xni + xfact;
                    xnddt = del1 * cos(xli - fasx2) + 2.0 * del2 * cos(2.0 * (xli - fasx4)) + 3.0 * del3 * cos(3.0 * (xli - fasx6));
                    xnddt = xnddt * xldot;
                } else {
                    double xomi = argpo + argpdot * atime;
                    double x2omi = xomi + xomi;
                    double x2li = xli + xli;
                    xndt = (d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22)
                            + d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32)
                            + d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44)
                            + d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52)
                            + d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54));
                    xldot = xni + xfact;
                    xnddt = (d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22)
                            + d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32)
                            + d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52)
                            + 2.0 * (d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44)
                            + d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54)));
                    xnddt = xnddt * xldot;
                }

                if (abs(t - atime) >= stepp) {
                    iretn = 381;
                } else {
                    ft = t - atime;
                    iretn = 0;
                }
                if (iretn == 381) {
                    xli = xli + xldot * delt + xndt * step2;
                    xni = xni + xndt * delt + xnddt * step2;
                    atime = atime + delt;
                }
            }

            nm = xni + xndt * ft + xnddt * ft * ft * 0.5;
            double xl = xli + xldot * ft + xndt * ft * ft * 0.5;
            if (irez != 1) {
                mm = xl - 2.0 * nodem + 2.0 * theta;
                dndt = nm - noUnkozai;
            } else {
                mm = xl - nodem - argpm + theta;
                dndt = nm - noUnkozai;
            }
            nm = noUnkozai + dndt;
        }
        return new double[]{em, argpm, inclm, mm, nodem, nm};
    }

    // =====================================================================================
    // sgp4init
    // =====================================================================================
    private void sgp4init(double epoch, double xbstar, double xecco, double xargpo, double xinclo,
                          double xmo, double xnoKozai, double xnodeo) {
        isimp = 0; method = 'n';
        irez = 0;

        bstar = xbstar; ecco = xecco; argpo = xargpo; inclo = xinclo; mo = xmo; noKozai = xnoKozai; nodeo = xnodeo;

        double ss = 78.0 / g.radiusEarthKm + 1.0;
        double qzms2ttemp = (120.0 - 78.0) / g.radiusEarthKm;
        double qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp;

        init = 'y';
        t = 0.0;

        // ---- initl ----
        double eccsq = ecco * ecco;
        double omeosq = 1.0 - eccsq;
        double rteosq = sqrt(omeosq);
        double cosio = cos(inclo);
        double cosio2 = cosio * cosio;

        double ak = pow(g.xke / noKozai, X2O3);
        double d1 = 0.75 * g.j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0));
        del = d1 / (adel * adel);
        noUnkozai = noKozai / (1.0 + del);

        double ao = pow(g.xke / noUnkozai, X2O3);
        double sinio = sin(inclo);
        double po = ao * omeosq;
        double con42 = 1.0 - 5.0 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po;
        double rp = ao * (1.0 - ecco);

        if (opsmode == 'a') {
            double ts70 = epoch - 7305.0;
            double ds70 = Math.floor(ts70 + 1.0e-8);
            double tfrac = ts70 - ds70;
            double c1 = 1.72027916940703639e-2;
            double thgr70 = 1.7321343856509374;
            double fk5r = 5.07551419432269442e-15;
            double c1p2p = c1 + TWOPI;
            gsto = floorMod2pi(thgr70 + c1 * ds70 + c1p2p * tfrac + ts70 * ts70 * fk5r);
        } else {
            gsto = Frames.gstime(epoch + 2433281.5);
        }
        // ---- end initl ----

        if (omeosq >= 0.0 || noUnkozai >= 0.0) {
            isimp = 0;
            if (rp < 220.0 / g.radiusEarthKm + 1.0) isimp = 1;
            double sfour = ss;
            double qzms24 = qzms2t;
            double perige = (rp - 1.0) * g.radiusEarthKm;

            if (perige < 156.0) {
                sfour = perige - 78.0;
                if (perige < 98.0) sfour = 20.0;
                double qzms24temp = (120.0 - sfour) / g.radiusEarthKm;
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp;
                sfour = sfour / g.radiusEarthKm + 1.0;
            }
            double pinvsq = 1.0 / posq;

            double tsi = 1.0 / (ao - sfour);
            eta = ao * ecco * tsi;
            double etasq = eta * eta;
            double eeta = ecco * eta;
            double psisq = abs(1.0 - etasq);
            double coef = qzms24 * pow(tsi, 4.0);
            double coef1 = coef / pow(psisq, 3.5);
            double cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq))
                    + 0.375 * g.j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)));
            cc1 = bstar * cc2;
            double cc3 = 0.0;
            if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * g.j3oj2 * noUnkozai * sinio / ecco;
            x1mth2 = 1.0 - cosio2;
            cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq)
                    - g.j2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta))
                    + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)));
            cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq);
            double cosio4 = cosio2 * cosio2;
            double temp1 = 1.5 * g.j2 * pinvsq * noUnkozai;
            double temp2 = 0.5 * temp1 * g.j2 * pinvsq;
            double temp3 = -0.46875 * g.j4 * pinvsq * pinvsq * noUnkozai;
            mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
            argpdot = (-0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4)
                    + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4));
            double xhdot1 = -temp1 * cosio;
            nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;
            double xpidot = argpdot + nodedot;
            omgcof = bstar * cc3 * cos(argpo);
            xmcof = 0.0;
            if (ecco > 1.0e-4) xmcof = -X2O3 * coef * bstar / eeta;
            nodecf = 3.5 * omeosq * xhdot1 * cc1;
            t2cof = 1.5 * cc1;
            if (abs(cosio + 1.0) > 1.5e-12) xlcof = -0.25 * g.j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio);
            else xlcof = -0.25 * g.j3oj2 * sinio * (3.0 + 5.0 * cosio) / TEMP4;
            aycof = -0.5 * g.j3oj2 * sinio;
            double delmotemp = 1.0 + eta * cos(mo);
            delmo = delmotemp * delmotemp * delmotemp;
            sinmao = sin(mo);
            x7thm1 = 7.0 * cosio2 - 1.0;

            if (TWOPI / noUnkozai >= 225.0) {
                method = 'd';
                isimp = 1;
                double tc = 0.0;
                dscom(epoch, ecco, argpo, tc, inclo, nodeo, noUnkozai);
                double[] p = dpper(inclo, init, ecco, inclo, nodeo, argpo, mo);
                ecco = p[0]; inclo = p[1]; nodeo = p[2]; argpo = p[3]; mo = p[4];
                dsinit(tc, xpidot, eccsq);
            }

            if (isimp != 1) {
                double cc1sq = cc1 * cc1;
                d2 = 4.0 * ao * tsi * cc1sq;
                double temp = d2 * tsi * cc1 / 3.0;
                d3 = (17.0 * ao + sfour) * temp;
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1;
                t3cof = d2 + 2.0 * cc1sq;
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq));
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq));
            }
        }

        // propagate to zero epoch to initialise all others (errors here are deliberately ignored,
        // matching the reference: a decayed object still initialises, and fails on propagate)
        try { propagate(0.0); } catch (PropagationException ignored) { }
        init = 'n';
    }

    // =====================================================================================
    // sgp4 - propagate to tsince minutes since epoch
    // =====================================================================================
    /**
     * Propagate to {@code tsince} minutes after the element epoch.
     *
     * @return position (km) and velocity (km/s) in the TEME frame
     * @throws PropagationException if the elements are non-physical at that time (decayed, e >= 1...)
     */
    public StateVector propagate(double tsince) {
        double mrt;
        double vkmpersec = g.radiusEarthKm * g.xke / 60.0;

        t = tsince;

        double xmdf = mo + mdot * t;
        double argpdf = argpo + argpdot * t;
        double nodedf = nodeo + nodedot * t;
        double argpm = argpdf;
        double mm = xmdf;
        double t2 = t * t;
        double nodem = nodedf + nodecf * t2;
        double tempa = 1.0 - cc1 * t;
        double tempe = bstar * cc4 * t;
        double templ = t2cof * t2;

        if (isimp != 1) {
            double delomg = omgcof * t;
            double delmtemp = 1.0 + eta * cos(xmdf);
            double delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * t;
            double t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }

        double nm = noUnkozai;
        double em = ecco;
        double inclm = inclo;
        if (method == 'd') {
            double tc = t;
            double[] r = dspace(tc, em, argpm, inclm, mm, nodem, nm);
            em = r[0]; argpm = r[1]; inclm = r[2]; mm = r[3]; nodem = r[4]; nm = r[5];
        }

        if (nm <= 0.0) throw new PropagationException(2, "mean motion " + nm + " is less than zero");

        double am = pow(g.xke / nm, X2O3) * tempa * tempa;
        nm = g.xke / pow(am, 1.5);
        em = em - tempe;

        if (em >= 1.0 || em < -0.001)
            throw new PropagationException(1, "mean eccentricity " + em + " not within range 0.0 <= e < 1.0");
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + noUnkozai * templ;
        double xlm = mm + argpm + nodem;
        double emsq = em * em;
        double temp = 1.0 - emsq;

        nodem = nodem % TWOPI; // truncated modulo (matches reference sign handling)
        argpm = floorMod2pi(argpm);
        xlm = floorMod2pi(xlm);
        mm = floorMod2pi(xlm - argpm - nodem);

        double sinim = sin(inclm);
        double cosim = cos(inclm);

        double ep = em, xincp = inclm, argpp = argpm, nodep = nodem, mp = mm;
        double sinip = sinim, cosip = cosim;
        if (method == 'd') {
            double[] p = dpper(inclo, 'n', ep, xincp, nodep, argpp, mp);
            ep = p[0]; xincp = p[1]; nodep = p[2]; argpp = p[3]; mp = p[4];
            if (xincp < 0.0) {
                xincp = -xincp;
                nodep = nodep + PI;
                argpp = argpp - PI;
            }
            if (ep < 0.0 || ep > 1.0)
                throw new PropagationException(3, "perturbed eccentricity " + ep + " not within range 0.0 <= e <= 1.0");
        }

        if (method == 'd') {
            sinip = sin(xincp);
            cosip = cos(xincp);
            aycof = -0.5 * g.j3oj2 * sinip;
            if (abs(cosip + 1.0) > 1.5e-12) xlcof = -0.25 * g.j3oj2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip);
            else xlcof = -0.25 * g.j3oj2 * sinip * (3.0 + 5.0 * cosip) / TEMP4;
        }

        double axnl = ep * cos(argpp);
        temp = 1.0 / (am * (1.0 - ep * ep));
        double aynl = ep * sin(argpp) + temp * aycof;
        double xl = mp + argpp + nodep + temp * xlcof * axnl;

        // ---- solve Kepler's equation ----
        double u = floorMod2pi(xl - nodep);
        double eo1 = u;
        double tem5 = 9999.9;
        int ktr = 1;
        double sineo1 = 0, coseo1 = 0;
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1);
            coseo1 = cos(eo1);
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (abs(tem5) >= 0.95) tem5 = tem5 > 0.0 ? 0.95 : -0.95;
            eo1 = eo1 + tem5;
            ktr = ktr + 1;
        }

        // ---- short period preliminary quantities ----
        double ecose = axnl * coseo1 + aynl * sineo1;
        double esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1.0 - el2);
        if (pl < 0.0) throw new PropagationException(4, "semilatus rectum " + pl + " is less than zero");

        double rl = am * (1.0 - ecose);
        double rdotl = sqrt(am) * esine / rl;
        double rvdotl = sqrt(pl) / rl;
        double betal = sqrt(1.0 - el2);
        temp = esine / (1.0 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp);
        double cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = atan2(sinu, cosu);
        double sin2u = (cosu + cosu) * sinu;
        double cos2u = 1.0 - 2.0 * sinu * sinu;
        temp = 1.0 / pl;
        double temp1 = 0.5 * g.j2 * temp;
        double temp2 = temp1 * temp;

        if (method == 'd') {
            double cosisq = cosip * cosip;
            con41 = 3.0 * cosisq - 1.0;
            x1mth2 = 1.0 - cosisq;
            x7thm1 = 7.0 * cosisq - 1.0;
        }

        mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodep + 1.5 * temp2 * cosip * sin2u;
        double xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u;
        double mvt = rdotl - nm * temp1 * x1mth2 * sin2u / g.xke;
        double rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / g.xke;

        double sinsu = sin(su), cossu = cos(su), snod = sin(xnode), cnod = cos(xnode), sini = sin(xinc), cosi = cos(xinc);
        double xmx = -snod * cosi;
        double xmy = cnod * cosi;
        double ux = xmx * sinsu + cnod * cossu;
        double uy = xmy * sinsu + snod * cossu;
        double uz = sini * sinsu;
        double vx = xmx * cossu - cnod * sinsu;
        double vy = xmy * cossu - snod * sinsu;
        double vz = sini * cossu;

        double mr = mrt * g.radiusEarthKm;
        StateVector sv = new StateVector(mr * ux, mr * uy, mr * uz,
                (mvt * ux + rvdot * vx) * vkmpersec,
                (mvt * uy + rvdot * vy) * vkmpersec,
                (mvt * uz + rvdot * vz) * vkmpersec);

        if (mrt < 1.0) throw new PropagationException(6, "mrt " + mrt + " is less than 1.0 indicating the satellite has decayed");
        return sv;
    }

    /** Python-style modulo into [0, 2π). */
    static double floorMod2pi(double x) {
        double r = x % TWOPI;
        if (r < 0) r += TWOPI;
        return r;
    }
}
