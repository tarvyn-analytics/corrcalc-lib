package ch.corrcalc.lib.correlation;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.Matrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorrelationsTest {

    @Test
    void pearson_ReturnsSharedPearsonCalculator() {
        CorrelationCalculator calculator = Correlations.pearson();

        assertInstanceOf(PearsonCorrelationCalculator.class, calculator);
        assertSame(calculator, Correlations.pearson());
    }

    @Test
    void of_PearsonType_ReturnsSameInstanceAsPearsonFactory() {
        assertSame(Correlations.pearson(), Correlations.of(CorrelationType.PEARSON));
    }

    @Test
    void of_NullType_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> Correlations.of(null));
    }

    @Test
    void pearson_SimpleInput_CalculatesCorrelation() {
        Matrix input = Matrix.fromRows(new double[][]{
                {1, 2},
                {2, 4},
                {3, 6}
        });
        Matrix result = Correlations.pearson().calculate(input);

        assertEquals(1.0, result.get(0, 1), 1e-9);
    }
}
