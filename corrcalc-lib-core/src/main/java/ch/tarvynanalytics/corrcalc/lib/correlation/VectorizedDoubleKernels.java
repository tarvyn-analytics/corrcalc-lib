package ch.tarvynanalytics.corrcalc.lib.correlation;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * {@link Profile#VECTORIZED} kernels for double-precision storage: the 4x4
 * tile of {@link TiledDoubleKernels} computed with explicit SIMD + FMA in a
 * single pass over the rows.
 * <p>
 * The single pass keeps 16 accumulator vectors plus 8 loads live — more than
 * AVX2's 16 vector registers, so some accumulators spill to the stack. That
 * was measured FASTER than a register-fitting two-half-pass variant (COR-322:
 * 87 vs 124 ms double 100000x100), because the spills hit L1 while the second
 * pass re-streams the B columns through L3/DRAM exactly where bandwidth is
 * the bottleneck. Re-measure before restructuring this loop.
 * <p>
 * Accumulation stays in double (invariant: lanes are double-width).
 * Requires {@code --add-modules jdk.incubator.vector} on a Java 25+ JVM;
 * {@link Correlations} loads this class reflectively and fails fast when the
 * module is missing.
 */
final class VectorizedDoubleKernels extends TiledDoubleKernels {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    @Override
    public void dotTile(double[] data, int n, int colI0, int countI, int colJ0, int countJ, double[] dots) {
        if (countI != 4 || countJ != 4) {
            // edge tiles fall back to plain pairwise dots
            super.dotTile(data, n, colI0, countI, colJ0, countJ, dots);
            return;
        }
        fullTile(data, n, colI0, colJ0, dots);
    }

    private static void fullTile(double[] data, int n, int colI0, int colJ0, double[] dots) {
        int ia = colI0 * n;
        int ib = ia + n;
        int ic = ib + n;
        int id = ic + n;
        int j0 = colJ0 * n;
        int j1 = j0 + n;
        int j2 = j1 + n;
        int j3 = j2 + n;
        DoubleVector a00 = DoubleVector.zero(SPECIES);
        DoubleVector a01 = DoubleVector.zero(SPECIES);
        DoubleVector a02 = DoubleVector.zero(SPECIES);
        DoubleVector a03 = DoubleVector.zero(SPECIES);
        DoubleVector a10 = DoubleVector.zero(SPECIES);
        DoubleVector a11 = DoubleVector.zero(SPECIES);
        DoubleVector a12 = DoubleVector.zero(SPECIES);
        DoubleVector a13 = DoubleVector.zero(SPECIES);
        DoubleVector a20 = DoubleVector.zero(SPECIES);
        DoubleVector a21 = DoubleVector.zero(SPECIES);
        DoubleVector a22 = DoubleVector.zero(SPECIES);
        DoubleVector a23 = DoubleVector.zero(SPECIES);
        DoubleVector a30 = DoubleVector.zero(SPECIES);
        DoubleVector a31 = DoubleVector.zero(SPECIES);
        DoubleVector a32 = DoubleVector.zero(SPECIES);
        DoubleVector a33 = DoubleVector.zero(SPECIES);
        int loopBound = SPECIES.loopBound(n);
        for (int row = 0; row < loopBound; row += SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(SPECIES, data, ia + row);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, data, ib + row);
            DoubleVector vc = DoubleVector.fromArray(SPECIES, data, ic + row);
            DoubleVector vd = DoubleVector.fromArray(SPECIES, data, id + row);
            DoubleVector v0 = DoubleVector.fromArray(SPECIES, data, j0 + row);
            DoubleVector v1 = DoubleVector.fromArray(SPECIES, data, j1 + row);
            DoubleVector v2 = DoubleVector.fromArray(SPECIES, data, j2 + row);
            DoubleVector v3 = DoubleVector.fromArray(SPECIES, data, j3 + row);
            a00 = va.fma(v0, a00);
            a01 = va.fma(v1, a01);
            a02 = va.fma(v2, a02);
            a03 = va.fma(v3, a03);
            a10 = vb.fma(v0, a10);
            a11 = vb.fma(v1, a11);
            a12 = vb.fma(v2, a12);
            a13 = vb.fma(v3, a13);
            a20 = vc.fma(v0, a20);
            a21 = vc.fma(v1, a21);
            a22 = vc.fma(v2, a22);
            a23 = vc.fma(v3, a23);
            a30 = vd.fma(v0, a30);
            a31 = vd.fma(v1, a31);
            a32 = vd.fma(v2, a32);
            a33 = vd.fma(v3, a33);
        }
        double s00 = a00.reduceLanes(VectorOperators.ADD);
        double s01 = a01.reduceLanes(VectorOperators.ADD);
        double s02 = a02.reduceLanes(VectorOperators.ADD);
        double s03 = a03.reduceLanes(VectorOperators.ADD);
        double s10 = a10.reduceLanes(VectorOperators.ADD);
        double s11 = a11.reduceLanes(VectorOperators.ADD);
        double s12 = a12.reduceLanes(VectorOperators.ADD);
        double s13 = a13.reduceLanes(VectorOperators.ADD);
        double s20 = a20.reduceLanes(VectorOperators.ADD);
        double s21 = a21.reduceLanes(VectorOperators.ADD);
        double s22 = a22.reduceLanes(VectorOperators.ADD);
        double s23 = a23.reduceLanes(VectorOperators.ADD);
        double s30 = a30.reduceLanes(VectorOperators.ADD);
        double s31 = a31.reduceLanes(VectorOperators.ADD);
        double s32 = a32.reduceLanes(VectorOperators.ADD);
        double s33 = a33.reduceLanes(VectorOperators.ADD);
        for (int row = loopBound; row < n; row++) {
            double a = data[ia + row];
            double b = data[ib + row];
            double c = data[ic + row];
            double d = data[id + row];
            s00 += a * data[j0 + row];
            s01 += a * data[j1 + row];
            s02 += a * data[j2 + row];
            s03 += a * data[j3 + row];
            s10 += b * data[j0 + row];
            s11 += b * data[j1 + row];
            s12 += b * data[j2 + row];
            s13 += b * data[j3 + row];
            s20 += c * data[j0 + row];
            s21 += c * data[j1 + row];
            s22 += c * data[j2 + row];
            s23 += c * data[j3 + row];
            s30 += d * data[j0 + row];
            s31 += d * data[j1 + row];
            s32 += d * data[j2 + row];
            s33 += d * data[j3 + row];
        }
        dots[0] = s00;
        dots[1] = s01;
        dots[2] = s02;
        dots[3] = s03;
        dots[4] = s10;
        dots[5] = s11;
        dots[6] = s12;
        dots[7] = s13;
        dots[8] = s20;
        dots[9] = s21;
        dots[10] = s22;
        dots[11] = s23;
        dots[12] = s30;
        dots[13] = s31;
        dots[14] = s32;
        dots[15] = s33;
    }
}
