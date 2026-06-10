package ch.corrcalc.lib.io;

import ch.corrcalc.lib.matrix.Matrix;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an observations matrix of known dimensions from a stream.
 */
public interface MatrixReader {

    /**
     * @param inputStream the source to read from; the caller stays responsible for closing it
     * @param numRows     the expected number of rows
     * @param numCols     the expected number of columns
     * @return the parsed {@code numRows x numCols} matrix
     * @throws IOException if reading from the stream fails
     */
    Matrix read(InputStream inputStream, int numRows, int numCols) throws IOException;
}
