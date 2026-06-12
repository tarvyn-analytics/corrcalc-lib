package ch.tarvynanalytics.corrcalc.lib.correlation;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The float-result variant of the {@link Profile#VECTORIZED} float kernels:
 * full-width float lanes with chunked accumulation. Within a
 * {@value #CHUNK_ROWS}-row chunk the sixteen tile sums accumulate in float
 * vectors; each chunk total is folded into a double running sum, which caps
 * the rounding error well below the float result's own rounding step
 * (measured max 8e-9 against the double-input reference vs the ~6e-8 float
 * ulp at |r|=1 — COR-324). Only the float→float API may use these kernels;
 * {@code calculateToDouble} runs on {@link VectorizedFloatKernels}, which
 * accumulates fully in double.
 * <p>
 * Requires {@code --add-modules jdk.incubator.vector} on a Java 25+ JVM;
 * {@link Correlations} loads this class reflectively and fails fast when the
 * module is missing.
 */
final class VectorizedFastFloatKernels extends TiledFloatKernels {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * Rows accumulated in float before folding into double. Must be a
     * multiple of {@code SPECIES.length()}; the error bound scales with
     * {@code sqrt(CHUNK_ROWS)}, the fold cost with its inverse.
     */
    private static final int CHUNK_ROWS = 1024;

    @Override
    public void dotTile(float[] data, int n, int colI0, int countI, int colJ0, int countJ, double[] dots) {
        if (countI != 4 || countJ != 4) {
            // edge tiles fall back to plain pairwise dots (double accumulation;
            // the precision difference is below the float result rounding)
            super.dotTile(data, n, colI0, countI, colJ0, countJ, dots);
            return;
        }
        int ia = colI0 * n;
        int ib = ia + n;
        int ic = ib + n;
        int id = ic + n;
        int j0 = colJ0 * n;
        int j1 = j0 + n;
        int j2 = j1 + n;
        int j3 = j2 + n;
        int lanes = SPECIES.length();
        int loopBound = n - (n % lanes);
        double t00 = 0, t01 = 0, t02 = 0, t03 = 0;
        double t10 = 0, t11 = 0, t12 = 0, t13 = 0;
        double t20 = 0, t21 = 0, t22 = 0, t23 = 0;
        double t30 = 0, t31 = 0, t32 = 0, t33 = 0;
        for (int chunk = 0; chunk < loopBound; chunk += CHUNK_ROWS) {
            int end = Math.min(chunk + CHUNK_ROWS, loopBound);
            FloatVector f00 = FloatVector.zero(SPECIES);
            FloatVector f01 = FloatVector.zero(SPECIES);
            FloatVector f02 = FloatVector.zero(SPECIES);
            FloatVector f03 = FloatVector.zero(SPECIES);
            FloatVector f10 = FloatVector.zero(SPECIES);
            FloatVector f11 = FloatVector.zero(SPECIES);
            FloatVector f12 = FloatVector.zero(SPECIES);
            FloatVector f13 = FloatVector.zero(SPECIES);
            FloatVector f20 = FloatVector.zero(SPECIES);
            FloatVector f21 = FloatVector.zero(SPECIES);
            FloatVector f22 = FloatVector.zero(SPECIES);
            FloatVector f23 = FloatVector.zero(SPECIES);
            FloatVector f30 = FloatVector.zero(SPECIES);
            FloatVector f31 = FloatVector.zero(SPECIES);
            FloatVector f32 = FloatVector.zero(SPECIES);
            FloatVector f33 = FloatVector.zero(SPECIES);
            for (int row = chunk; row < end; row += lanes) {
                FloatVector va = FloatVector.fromArray(SPECIES, data, ia + row);
                FloatVector vb = FloatVector.fromArray(SPECIES, data, ib + row);
                FloatVector vc = FloatVector.fromArray(SPECIES, data, ic + row);
                FloatVector vd = FloatVector.fromArray(SPECIES, data, id + row);
                FloatVector v0 = FloatVector.fromArray(SPECIES, data, j0 + row);
                FloatVector v1 = FloatVector.fromArray(SPECIES, data, j1 + row);
                FloatVector v2 = FloatVector.fromArray(SPECIES, data, j2 + row);
                FloatVector v3 = FloatVector.fromArray(SPECIES, data, j3 + row);
                f00 = va.fma(v0, f00);
                f01 = va.fma(v1, f01);
                f02 = va.fma(v2, f02);
                f03 = va.fma(v3, f03);
                f10 = vb.fma(v0, f10);
                f11 = vb.fma(v1, f11);
                f12 = vb.fma(v2, f12);
                f13 = vb.fma(v3, f13);
                f20 = vc.fma(v0, f20);
                f21 = vc.fma(v1, f21);
                f22 = vc.fma(v2, f22);
                f23 = vc.fma(v3, f23);
                f30 = vd.fma(v0, f30);
                f31 = vd.fma(v1, f31);
                f32 = vd.fma(v2, f32);
                f33 = vd.fma(v3, f33);
            }
            t00 += f00.reduceLanes(VectorOperators.ADD);
            t01 += f01.reduceLanes(VectorOperators.ADD);
            t02 += f02.reduceLanes(VectorOperators.ADD);
            t03 += f03.reduceLanes(VectorOperators.ADD);
            t10 += f10.reduceLanes(VectorOperators.ADD);
            t11 += f11.reduceLanes(VectorOperators.ADD);
            t12 += f12.reduceLanes(VectorOperators.ADD);
            t13 += f13.reduceLanes(VectorOperators.ADD);
            t20 += f20.reduceLanes(VectorOperators.ADD);
            t21 += f21.reduceLanes(VectorOperators.ADD);
            t22 += f22.reduceLanes(VectorOperators.ADD);
            t23 += f23.reduceLanes(VectorOperators.ADD);
            t30 += f30.reduceLanes(VectorOperators.ADD);
            t31 += f31.reduceLanes(VectorOperators.ADD);
            t32 += f32.reduceLanes(VectorOperators.ADD);
            t33 += f33.reduceLanes(VectorOperators.ADD);
        }
        for (int row = loopBound; row < n; row++) {
            double a = data[ia + row];
            double b = data[ib + row];
            double c = data[ic + row];
            double d = data[id + row];
            t00 += a * data[j0 + row];
            t01 += a * data[j1 + row];
            t02 += a * data[j2 + row];
            t03 += a * data[j3 + row];
            t10 += b * data[j0 + row];
            t11 += b * data[j1 + row];
            t12 += b * data[j2 + row];
            t13 += b * data[j3 + row];
            t20 += c * data[j0 + row];
            t21 += c * data[j1 + row];
            t22 += c * data[j2 + row];
            t23 += c * data[j3 + row];
            t30 += d * data[j0 + row];
            t31 += d * data[j1 + row];
            t32 += d * data[j2 + row];
            t33 += d * data[j3 + row];
        }
        dots[0] = t00;
        dots[1] = t01;
        dots[2] = t02;
        dots[3] = t03;
        dots[4] = t10;
        dots[5] = t11;
        dots[6] = t12;
        dots[7] = t13;
        dots[8] = t20;
        dots[9] = t21;
        dots[10] = t22;
        dots[11] = t23;
        dots[12] = t30;
        dots[13] = t31;
        dots[14] = t32;
        dots[15] = t33;
    }
}
