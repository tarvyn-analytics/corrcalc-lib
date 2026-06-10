package ch.tarvynanalytics.corrcalc.lib.io;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvMatrixReaderTest {

    private final CsvMatrixReader reader = new CsvMatrixReader();

    @Test
    void read_SpaceSeparatedValues_ParsesIntoMatrix() throws IOException {
        DoubleMatrix result = reader.read(stream("""
                1 2 3
                4 5 6
                """), 2, 3);

        assertEquals(DoubleMatrix.fromRows(new double[][]{
                {1, 2, 3},
                {4, 5, 6}
        }), result);
    }

    @Test
    void read_TabsAndRepeatedSpaces_AreTreatedAsOneSeparator() throws IOException {
        DoubleMatrix result = reader.read(stream("  1.5\t\t-2.5   3e2 \n4 5.25 -6E-1\r\n"), 2, 3);

        assertEquals(1.5, result.get(0, 0), 0.0);
        assertEquals(-2.5, result.get(0, 1), 0.0);
        assertEquals(300.0, result.get(0, 2), 0.0);
        assertEquals(-0.6, result.get(1, 2), 1e-12);
    }

    @Test
    void read_BlankLines_AreSkipped() throws IOException {
        DoubleMatrix result = reader.read(stream("\n1 2\n\n  \t\n3 4\n\n"), 2, 2);

        assertEquals(DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}}), result);
    }

    @Test
    void read_NaNTokens_AreParsedAsMissingValues() throws IOException {
        DoubleMatrix result = reader.read(stream("1 NaN\n2 3\n"), 2, 2);

        assertTrue(Double.isNaN(result.get(0, 1)));
        assertEquals(2.0, result.get(1, 0), 0.0);
    }

    @Test
    void read_TooFewRows_ThrowsInvalidInput() {
        InputStream input = stream("1 2\n");

        assertThrows(InvalidInputException.class, () -> reader.read(input, 2, 2));
    }

    @Test
    void read_TooManyRows_ThrowsInvalidInput() {
        InputStream input = stream("1 2\n3 4\n5 6\n");

        assertThrows(InvalidInputException.class, () -> reader.read(input, 2, 2));
    }

    @Test
    void read_TooFewColumns_ThrowsInvalidInput() {
        InputStream input = stream("1 2\n3\n");

        assertThrows(InvalidInputException.class, () -> reader.read(input, 2, 2));
    }

    @Test
    void read_TooManyColumns_ThrowsInvalidInput() {
        InputStream input = stream("1 2 3\n4 5 6\n");

        assertThrows(InvalidInputException.class, () -> reader.read(input, 2, 2));
    }

    @Test
    void read_NonNumericToken_ThrowsInvalidInputWithLocation() {
        InputStream input = stream("1 2\n3 abc\n");

        InvalidInputException exception =
                assertThrows(InvalidInputException.class, () -> reader.read(input, 2, 2));
        assertTrue(exception.getMessage().contains("abc"));
        assertTrue(exception.getMessage().contains("row [1]"));
        assertTrue(exception.getMessage().contains("column [1]"));
    }

    @Test
    void read_EmptyStream_ThrowsInvalidInput() {
        InputStream input = stream("");

        assertThrows(InvalidInputException.class, () -> reader.read(input, 1, 1));
    }

    @Test
    void read_NullStream_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> reader.read(null, 1, 1));
    }

    @Test
    void read_NonPositiveDimensions_ThrowsInvalidInput() {
        InputStream input = stream("1\n");

        assertThrows(InvalidInputException.class, () -> reader.read(input, 0, 1));
        assertThrows(InvalidInputException.class, () -> reader.read(input, 1, 0));
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
