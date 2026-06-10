package ch.corrcalc.lib.prep;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparersTest {

    @Test
    void factories_CalledTwice_ReturnSharedInstances() {
        assertSame(Preparers.dropMissingRows(), Preparers.dropMissingRows());
        assertSame(Preparers.imputeMean(), Preparers.imputeMean());
        assertSame(Preparers.center(), Preparers.center());
        assertSame(Preparers.standardize(), Preparers.standardize());
    }

    @Test
    void andThen_TwoSteps_AppliesThemInOrder() {
        // dropping the NaN row first means the mean of the remaining values is used nowhere,
        // while imputing first would have kept three rows — order is observable
        DataPreparer dropThenImpute = Preparers.dropMissingRows().andThen(Preparers.imputeMean());
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {Double.NaN, 4},
                {5, 6}
        });
        DoubleMatrix result = dropThenImpute.prepare(input);

        assertEquals(2, result.rows());
    }

    @Test
    void pipeline_MultipleSteps_AppliesAllInOrder() {
        DataPreparer pipeline = Preparers.pipeline(
                Preparers.imputeMean(),
                Preparers.standardize());
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 10},
                {Double.NaN, 20},
                {3, 30}
        });
        DoubleMatrix result = pipeline.prepare(input);

        assertEquals(3, result.rows());
        // imputed value equals the column mean, so it standardizes to exactly zero
        assertEquals(0.0, result.get(1, 0), 1e-12);
    }

    @Test
    void pipeline_SingleStep_BehavesLikeTheStepItself() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}});

        assertEquals(Preparers.center().prepare(input),
                Preparers.pipeline(Preparers.center()).prepare(input));
    }

    @Test
    void pipeline_EmptyOrNullSteps_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, Preparers::pipeline);
        assertThrows(InvalidInputException.class, () -> Preparers.pipeline((DataPreparer[]) null));
        assertThrows(InvalidInputException.class, () -> Preparers.pipeline(Preparers.center(), null));
        assertThrows(InvalidInputException.class, () -> Preparers.pipeline((DataPreparer) null));
    }
}
