package io.muserver;

/**
 * Temporary features that might be removed without notice.
 */
public class Toggles {

    /**
     * An obsolete toggle that does nothing.
     * @deprecated This is now unused and fixed length is always enabled
     */
    public static boolean fixedLengthResponsesEnabled = false;

    /**
     * An obsolete toggle that does nothing.
     * @deprecated Use Http2ConfigBuilder instead
     */
    public static boolean http2 = false;

}
