package cn.pianzi.liarbar.paperplugin.util;

/**
 * Shared exception helpers.
 */
public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    /**
     * Walk the cause chain and return the deepest root-cause message,
     * falling back to the simple class name when the message is blank.
     */
    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
