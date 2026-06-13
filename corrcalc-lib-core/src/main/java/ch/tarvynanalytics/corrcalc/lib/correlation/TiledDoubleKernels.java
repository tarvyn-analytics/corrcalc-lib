package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * {@link Profile#HIGH_PERFORMANCE} kernels for double-precision storage:
 * 4x4 register-blocked tiles. One pass over the rows produces 16 dot
 * products from 8 loads per row, so each loaded value is reused four times —
 * a 4x cut in memory traffic against the pairwise loop, which is what the
 * bandwidth-bound large inputs are limited by. Accumulation stays in double;
 * only the summation order differs from {@link DoubleKernels}.
 */
class TiledDoubleKernels extends DoubleKernels {

    @Override
    public int tileSize() {
        return 4;
    }

    @Override
    // the grouped 4x4 accumulator declarations deliberately mirror the register-block tile layout
    @SuppressWarnings("java:S1659")
    public void dotTile(double[] data, int n, int colI0, int countI, int colJ0, int countJ, double[] dots) {
        if (countI != 4 || countJ != 4) {
            // edge tiles fall back to plain pairwise dots
            super.dotTile(data, n, colI0, countI, colJ0, countJ, dots);
            return;
        }
        int i0 = colI0 * n;
        int i1 = i0 + n;
        int i2 = i1 + n;
        int i3 = i2 + n;
        int j0 = colJ0 * n;
        int j1 = j0 + n;
        int j2 = j1 + n;
        int j3 = j2 + n;
        double s00 = 0, s01 = 0, s02 = 0, s03 = 0;
        double s10 = 0, s11 = 0, s12 = 0, s13 = 0;
        double s20 = 0, s21 = 0, s22 = 0, s23 = 0;
        double s30 = 0, s31 = 0, s32 = 0, s33 = 0;
        for (int row = 0; row < n; row++) {
            double a0 = data[i0 + row];
            double a1 = data[i1 + row];
            double a2 = data[i2 + row];
            double a3 = data[i3 + row];
            double b0 = data[j0 + row];
            double b1 = data[j1 + row];
            double b2 = data[j2 + row];
            double b3 = data[j3 + row];
            s00 += a0 * b0;
            s01 += a0 * b1;
            s02 += a0 * b2;
            s03 += a0 * b3;
            s10 += a1 * b0;
            s11 += a1 * b1;
            s12 += a1 * b2;
            s13 += a1 * b3;
            s20 += a2 * b0;
            s21 += a2 * b1;
            s22 += a2 * b2;
            s23 += a2 * b3;
            s30 += a3 * b0;
            s31 += a3 * b1;
            s32 += a3 * b2;
            s33 += a3 * b3;
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
