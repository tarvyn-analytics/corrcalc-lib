package ch.tarvynanalytics.corrcalc.lib.io;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads a whitespace-separated values file (the corrcalc "CSV" format): one
 * observation row per line, values separated by any run of spaces or tabs.
 * {@code NaN} tokens are accepted and mark missing values for the {@code prep}
 * package to handle.
 * <p>
 * The parser streams the input line by line and writes each value straight
 * into the final column-major array — no intermediate token lists or row
 * objects are allocated. Blank lines are skipped.
 */
public final class CsvMatrixReader implements MatrixReader {

    @Override
    public DoubleMatrix read(InputStream inputStream, int numRows, int numCols) throws IOException {
        if (inputStream == null) {
            throw new InvalidInputException("Input stream must not be null");
        }
        if (numRows < 1 || numCols < 1) {
            throw new InvalidInputException(
                    "Expected dimensions must be positive, got [" + numRows + "x" + numCols + "]");
        }

        double[] data = new double[Math.multiplyExact(numRows, numCols)];
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        int row = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (isBlank(line)) {
                continue;
            }
            if (row >= numRows) {
                throw new InvalidInputException("Expected [" + numRows + "] rows but found more");
            }
            parseLine(line, row, data, numRows, numCols);
            row++;
        }
        if (row < numRows) {
            throw new InvalidInputException("Expected [" + numRows + "] rows but found only [" + row + "]");
        }
        return DoubleMatrix.columnMajor(data, numRows, numCols);
    }

    /**
     * Tokenizes one line on runs of spaces/tabs and stores the parsed values
     * into the column-major array at the given row.
     */
    private static void parseLine(String line, int row, double[] data, int numRows, int numCols) {
        int col = 0;
        int length = line.length();
        int cursor = 0;
        while (cursor < length) {
            while (cursor < length && isSeparator(line.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) {
                break;
            }
            int tokenStart = cursor;
            while (cursor < length && !isSeparator(line.charAt(cursor))) {
                cursor++;
            }
            if (col >= numCols) {
                throw new InvalidInputException(
                        "Row [" + row + "] has more than the expected [" + numCols + "] columns");
            }
            data[col * numRows + row] = parseValue(line, tokenStart, cursor, row, col);
            col++;
        }
        if (col < numCols) {
            throw new InvalidInputException(
                    "Row [" + row + "] has only [" + col + "] of the expected [" + numCols + "] columns");
        }
    }

    private static double parseValue(String line, int start, int end, int row, int col) {
        String token = line.substring(start, end);
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new InvalidInputException(
                    "Invalid number [" + token + "] at row [" + row + "], column [" + col + "]", e);
        }
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }

    private static boolean isBlank(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!isSeparator(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
