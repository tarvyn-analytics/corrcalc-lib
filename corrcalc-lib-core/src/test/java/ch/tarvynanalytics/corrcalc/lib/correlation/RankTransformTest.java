package ch.tarvynanalytics.corrcalc.lib.correlation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RankTransformTest {

    @Test
    void rankColumn_DistinctValuesInOrder_ReturnsSequentialRanks() {
        double[] src = {10, 20, 30, 40};
        double[] dst = new double[4];

        RankTransform.rankColumn(src, dst, 0, 4);

        assertArrayEquals(new double[]{1, 2, 3, 4}, dst, 0.0);
    }

    @Test
    void rankColumn_DistinctValuesOutOfOrder_ReturnsPositionRanks() {
        double[] src = {30, 10, 40, 20};
        double[] dst = new double[4];

        RankTransform.rankColumn(src, dst, 0, 4);

        assertArrayEquals(new double[]{3, 1, 4, 2}, dst, 0.0);
    }

    @Test
    void rankColumn_Ties_ReturnsAveragedRanks() {
        // values {5, 5, 8} -> the two 5s share ranks 1 and 2, averaging to 1.5
        double[] src = {5, 5, 8};
        double[] dst = new double[3];

        RankTransform.rankColumn(src, dst, 0, 3);

        assertArrayEquals(new double[]{1.5, 1.5, 3}, dst, 0.0);
    }

    @Test
    void rankColumn_AllEqual_ReturnsCommonAverageRank() {
        double[] src = {7, 7, 7, 7};
        double[] dst = new double[4];

        RankTransform.rankColumn(src, dst, 0, 4);

        assertArrayEquals(new double[]{2.5, 2.5, 2.5, 2.5}, dst, 0.0);
    }

    @Test
    void rankColumn_SingleElement_ReturnsRankOne() {
        double[] dst = new double[1];

        RankTransform.rankColumn(new double[]{42}, dst, 0, 1);

        assertArrayEquals(new double[]{1}, dst, 0.0);
    }

    @Test
    void rankColumn_RespectsOffsetAndLeavesOtherColumnsUntouched() {
        // two columns of length 3 packed column-major; rank only the second
        double[] src = {0, 0, 0, 90, 30, 60};
        double[] dst = new double[6];

        RankTransform.rankColumn(src, dst, 3, 3);

        assertArrayEquals(new double[]{0, 0, 0, 3, 1, 2}, dst, 0.0);
    }

    @Test
    void rankColumn_RandomData_MatchesCountingOracle() {
        Random random = new Random(7L);
        int n = 200;
        double[] src = new double[n];
        for (int i = 0; i < n; i++) {
            // deliberately small integer range so ties occur
            src[i] = random.nextInt(20);
        }
        double[] dst = new double[n];

        RankTransform.rankColumn(src, dst, 0, n);

        assertArrayEquals(averageRanksByCounting(src), dst, 0.0);
    }

    @Test
    void rankColumn_FloatTies_ReturnsAveragedRanks() {
        float[] src = {5, 5, 8};
        float[] dst = new float[3];

        RankTransform.rankColumn(src, dst, 0, 3);

        assertArrayEquals(new float[]{1.5f, 1.5f, 3f}, dst, 0.0f);
    }

    @Test
    void rankColumn_FloatRandomData_MatchesDoubleRanks() {
        Random random = new Random(11L);
        int n = 150;
        float[] floatSrc = new float[n];
        double[] doubleSrc = new double[n];
        for (int i = 0; i < n; i++) {
            int value = random.nextInt(30);
            floatSrc[i] = value;
            doubleSrc[i] = value;
        }
        float[] floatDst = new float[n];
        double[] doubleDst = new double[n];

        RankTransform.rankColumn(floatSrc, floatDst, 0, n);
        RankTransform.rankColumn(doubleSrc, doubleDst, 0, n);

        for (int i = 0; i < n; i++) {
            assertArrayEquals(new double[]{doubleDst[i]}, new double[]{floatDst[i]}, 0.0);
        }
    }

    /** Independent oracle: rank by counting strictly-smaller and equal values. */
    private static double[] averageRanksByCounting(double[] values) {
        int n = values.length;
        double[] ranks = new double[n];
        for (int i = 0; i < n; i++) {
            int less = 0;
            int equal = 0;
            for (double value : values) {
                if (value < values[i]) {
                    less++;
                } else if (value == values[i]) {
                    equal++;
                }
            }
            ranks[i] = less + (equal + 1) / 2.0;
        }
        return ranks;
    }
}
