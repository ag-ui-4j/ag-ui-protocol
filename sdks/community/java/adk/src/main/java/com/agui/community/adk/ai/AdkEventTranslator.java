package com.agui.community.adk.ai;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.message.Role;
import com.google.genai.types.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Translates a stream of Google ADK {@link com.google.adk.events.Event}s into the
 * AG-UI text-message event lifecycle for a single run. It is
 * <strong>stateful</strong> and not thread-safe: one instance handles one run, and
 * its methods are invoked sequentially as ADK events arrive.
 *
 * <p>ADK streams a turn as incremental <em>partial</em> events (each carrying the
 * next chunk of text) followed by a final aggregated event that repeats the whole
 * text. This maps that to:
 *
 * <pre>
 * TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT*, TEXT_MESSAGE_END
 * </pre>
 *
 * <p>Partial chunks are emitted as {@code TEXT_MESSAGE_CONTENT} deltas; the trailing
 * aggregated event is dropped so its text is not duplicated. When ADK is not
 * streaming (no partials), the single complete event's text is emitted once. Only
 * text parts are surfaced — function calls and other non-text parts are ignored.
 * An ADK event carrying an {@code errorMessage} aborts the run: the message is
 * thrown so the agent maps it to a terminal {@code RUN_ERROR}.
 */
final class AdkEventTranslator {

    private final String messageId;
    private boolean textOpen;
    // Whether any partial (streaming) delta has been emitted this run. Once true, the
    // trailing aggregated (non-partial) event is a repeat and is dropped.
    private boolean streamedPartial;

    AdkEventTranslator(String messageId) {
        this.messageId = messageId;
    }

    /**
     * Maps a single ADK event to zero or more AG-UI events.
     *
     * @param event the ADK event to translate
     * @return the events to emit, in order
     * @throws AdkRunException if the ADK event reports an error
     */
    List<Event> onEvent(com.google.adk.events.Event event) {
        event.errorMessage().ifPresent(message -> {
            throw new AdkRunException(message);
        });

        List<Event> out = new ArrayList<>();
        String text = textOf(event);
        if (text.isEmpty()) {
            return out;
        }
        boolean partial = event.partial().orElse(false);
        if (partial) {
            openText(out);
            out.add(new TextMessageContentEvent(messageId, text));
            streamedPartial = true;
        } else if (!streamedPartial) {
            // Non-streaming: no partial deltas preceded this, so emit the full text once.
            openText(out);
            out.add(new TextMessageContentEvent(messageId, text));
        }
        // Otherwise this is the trailing aggregate of an already-streamed turn: drop it.
        return out;
    }

    /**
     * Closes the assistant text message if one is open when the stream completes.
     *
     * @return the terminal events, in order (possibly empty)
     */
    List<Event> finish() {
        List<Event> out = new ArrayList<>();
        if (textOpen) {
            out.add(new TextMessageEndEvent(messageId));
            textOpen = false;
        }
        return out;
    }

    private void openText(List<Event> out) {
        if (!textOpen) {
            out.add(new TextMessageStartEvent(messageId, Role.ASSISTANT));
            textOpen = true;
        }
    }

    /** The concatenated text of an ADK event's content parts (non-text parts ignored). */
    private static String textOf(com.google.adk.events.Event event) {
        return event.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .collect(Collectors.joining()))
                .orElse("");
    }
}
