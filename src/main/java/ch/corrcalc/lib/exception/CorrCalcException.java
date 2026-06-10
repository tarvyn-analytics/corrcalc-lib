package ch.corrcalc.lib.exception;

/**
 * Base exception for all errors raised by the corrcalc library.
 */
public class CorrCalcException extends RuntimeException {

    public CorrCalcException(String message) {
        super(message);
    }

    public CorrCalcException(String message, Throwable cause) {
        super(message, cause);
    }
}
