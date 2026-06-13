package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;
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
    void pearson_StandardProfile_ReturnsSameInstanceAsDefaultFactory() {
        assertSame(Correlations.pearson(), Correlations.pearson(Profile.STANDARD));
    }

    @Test
    void pearson_SameProfileTwice_ReturnsSharedInstance() {
        for (Profile profile : Profile.values()) {
            assertSame(Correlations.pearson(profile), Correlations.pearson(profile));
        }
    }

    @Test
    void pearson_NullProfile_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> Correlations.pearson(null));
    }

    @Test
    void of_PearsonTypeWithProfile_ReturnsSameInstanceAsProfiledPearsonFactory() {
        assertSame(Correlations.pearson(Profile.STANDARD),
                Correlations.of(CorrelationType.PEARSON, Profile.STANDARD));
    }

    @Test
    void of_TypeWithNullProfile_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class,
                () -> Correlations.of(CorrelationType.PEARSON, null));
    }

    @Test
    void partial_ReturnsSharedPartialCalculator() {
        CorrelationCalculator calculator = Correlations.partial();

        assertInstanceOf(PartialCorrelationCalculator.class, calculator);
        assertSame(calculator, Correlations.partial());
    }

    @Test
    void of_PartialType_ReturnsSameInstanceAsPartialFactory() {
        assertSame(Correlations.partial(), Correlations.of(CorrelationType.PARTIAL));
    }

    @Test
    void partial_StandardProfile_ReturnsSameInstanceAsDefaultFactory() {
        assertSame(Correlations.partial(), Correlations.partial(Profile.STANDARD));
    }

    @Test
    void partial_SameProfileTwice_ReturnsSharedInstance() {
        for (Profile profile : Profile.values()) {
            assertSame(Correlations.partial(profile), Correlations.partial(profile));
        }
    }

    @Test
    void partial_NullProfile_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> Correlations.partial(null));
    }

    @Test
    void of_PartialTypeWithProfile_ReturnsSameInstanceAsProfiledPartialFactory() {
        assertSame(Correlations.partial(Profile.HIGH_PERFORMANCE),
                Correlations.of(CorrelationType.PARTIAL, Profile.HIGH_PERFORMANCE));
    }

    @Test
    void pearson_SimpleInput_CalculatesCorrelation() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {2, 4},
                {3, 6}
        });
        DoubleMatrix result = Correlations.pearson().calculate(input);

        assertEquals(1.0, result.get(0, 1), 1e-9);
    }

    @Test
    void pearson_SimpleFloatInput_CalculatesCorrelation() {
        FloatMatrix input = FloatMatrix.fromRows(new float[][]{
                {1, 2},
                {2, 4},
                {3, 6}
        });
        FloatMatrix result = Correlations.pearson().calculate(input);

        assertEquals(1.0f, result.get(0, 1), 1e-6f);
    }
}
