package com.milesight.beaveriot.integrations.edgeaistream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * Swallows the write failure that happens when a viewer closes a stream.
 * <p>
 * This is not merely tidying the logs. A viewer leaving makes the next write throw
 * {@code IOException: Broken pipe}, which the relay itself handles - but the async error
 * is still dispatched, and the application's catch-all handler then tries to serialise a
 * JSON error body onto a response already committed as {@code multipart/x-mixed-replace}.
 * That throws {@code HttpMessageNotWritableException: No converter}, whose own failure
 * throws again, so one departing viewer produces a three-exception cascade - each one
 * occupying an Undertow worker thread to unwind.
 * <p>
 * Measured, not assumed: with thirty viewers leaving at once the cascade consumed the
 * worker pool and ordinary API calls went from 17ms to HTTP 500 after five seconds.
 * Handling the exception here, with a void return so nothing tries to negotiate a body
 * for a content type that has no JSON converter, ends it at the first step.
 * <p>
 * Deliberately scoped to the relay controller: elsewhere an IOException is worth
 * surfacing, and this must not become a blanket suppressor.
 *
 * @author cytron
 */
@RestControllerAdvice(assignableTypes = EdgeAiStreamRelayController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class StreamErrorHandler {
    @ExceptionHandler(IOException.class)
    public void viewerDisconnected(IOException e) {
        // Debug, not warn: a viewer closing a tab is the normal way a stream ends, not a
        // fault. The response is already committed, so there is nothing to send back.
        log.debug("Stream ended by viewer: {}", e.getMessage());
    }
}
