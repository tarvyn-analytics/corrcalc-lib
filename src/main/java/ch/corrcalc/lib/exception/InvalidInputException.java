package ch.corrcalc.lib.exception;

/**
 * Raised when the provided input (matrix dimensions, file content, parameters)
 * is structurally invalid and the requested operation cannot be performed.
 */
public class InvalidInputException extends CorrCalcException {

    public InvalidInputException(String message) {
        super(message);
    }

    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
