package com.agui.community.adk.ai;

/**
 * Raised when a Google ADK event reports an {@code errorMessage} during a run. The
 * {@link AdkAgent} maps it to a terminal AG-UI {@code RUN_ERROR} event rather than
 * propagating the failure, matching the protocol's in-band error handling.
 */
final class AdkRunException extends RuntimeException {

    AdkRunException(String message) {
        super(message);
    }
}
