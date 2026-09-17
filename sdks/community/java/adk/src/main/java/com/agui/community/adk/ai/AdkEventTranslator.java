package com.agui.community.adk.ai;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallEndEvent;
import com.agui.community.core.event.ToolCallResultEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.agui.community.core.message.Role;
import com.google.genai.JsonSerializable;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a stream of Google ADK {@link com.google.adk.events.Event}s into the
 * AG-UI event lifecycle for a single run. It is <strong>stateful</strong> and not
 * thread-safe: one instance handles one run, and its methods are invoked
 * sequentially as ADK events arrive.
 *
 * <p>Three kinds of content part are mapped:
 *
 * <pre>
 * text             -> TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT*, TEXT_MESSAGE_END
 * functionCall     -> TOOL_CALL_START, TOOL_CALL_ARGS, TOOL_CALL_END
 * functionResponse -> TOOL_CALL_RESULT
 * </pre>
 *
 * <p><strong>Text.</strong> ADK streams a turn as incremental <em>partial</em>
 * events (each carrying the next chunk) followed by a final aggregated event that
 * repeats the whole text. Partial chunks become {@code TEXT_MESSAGE_CONTENT} deltas
 * and the trailing aggregate is dropped so the text is not duplicated; a
 * non-streaming turn emits its single complete text once.
 *
 * <p><strong>Tools.</strong> When the ADK agent calls one of its (backend) tools,
 * the model's {@code functionCall} is surfaced as {@code TOOL_CALL_START/ARGS/END}
 * and the tool's {@code functionResponse} (which ADK executes server-side) as a
 * {@code TOOL_CALL_RESULT}, so the front end can display the call and its result.
 * Any open text message is closed before tool events are emitted; a text message
 * that resumes afterwards is opened under a fresh id (a client keys messages by id).
 *
 * <p>An ADK event carrying an {@code errorMessage} aborts the run: the message is
 * thrown so the agent maps it to a terminal {@code RUN_ERROR}.
 */
final class AdkEventTranslator {

    private final String messageId;

    // The currently-open assistant text message id, or null when none is open. Each
    // text segment gets its own id (the base id for the first, suffixed after that)
    // so a segment that resumes after a tool call never collides with an earlier one.
    private String textId;
    private int textSeq;
    // Whether a partial (streaming) delta was emitted for the open text segment. Once
    // true, the trailing aggregated (non-partial) event for that segment is dropped.
    private boolean streamedPartial;

    private int toolSeq;
    private int resultSeq;
    // The most recent tool-call id, used to correlate a function response that arrives
    // without its own id (as when the model omits function-call ids).
    private String lastToolCallId;

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
        List<Part> parts = event.content().flatMap(content -> content.parts()).orElse(List.of());
        boolean partial = event.partial().orElse(false);
        for (Part part : parts) {
            String text = part.text().orElse("");
            if (!text.isEmpty()) {
                emitText(text, partial, out);
            } else if (part.functionCall().isPresent()) {
                closeText(out);
                emitFunctionCall(part.functionCall().get(), out);
            } else if (part.functionResponse().isPresent()) {
                closeText(out);
                emitFunctionResponse(part.functionResponse().get(), out);
            }
        }
        return out;
    }

    /**
     * Closes the assistant text message if one is open when the stream completes.
     *
     * @return the terminal events, in order (possibly empty)
     */
    List<Event> finish() {
        List<Event> out = new ArrayList<>();
        closeText(out);
        return out;
    }

    private void emitText(String text, boolean partial, List<Event> out) {
        if (partial) {
            openText(out);
            out.add(new TextMessageContentEvent(textId, text));
            streamedPartial = true;
        } else if (!streamedPartial) {
            // Non-streaming: no partial deltas preceded this, so emit the full text once.
            openText(out);
            out.add(new TextMessageContentEvent(textId, text));
        }
        // Otherwise this is the trailing aggregate of an already-streamed segment: drop it.
    }

    private void openText(List<Event> out) {
        if (textId == null) {
            textId = textSeq == 0 ? messageId : messageId + "-" + (textSeq + 1);
            textSeq++;
            streamedPartial = false;
            out.add(new TextMessageStartEvent(textId, Role.ASSISTANT));
        }
    }

    private void closeText(List<Event> out) {
        if (textId != null) {
            out.add(new TextMessageEndEvent(textId));
            textId = null;
        }
    }

    private void emitFunctionCall(FunctionCall call, List<Event> out) {
        String name = call.name().orElse("");
        String callId = call.id().filter(id -> !id.isEmpty())
                .orElseGet(() -> messageId + "-tool-" + (++toolSeq));
        lastToolCallId = callId;
        String args = JsonSerializable.toJsonString(call.args().orElse(Map.of()));
        out.add(new ToolCallStartEvent(callId, name, messageId, null, null));
        out.add(new ToolCallArgsEvent(callId, args));
        out.add(new ToolCallEndEvent(callId));
    }

    private void emitFunctionResponse(FunctionResponse response, List<Event> out) {
        String callId = response.id().filter(id -> !id.isEmpty())
                .orElseGet(() -> lastToolCallId != null ? lastToolCallId : messageId + "-tool-" + (++toolSeq));
        String content = JsonSerializable.toJsonString(response.response().orElse(Map.of()));
        // Each tool result is its own conversation message (a fresh id, role TOOL), so a
        // front end keeps the assistant tool call - and any generative UI - alongside it.
        String resultMessageId = messageId + "-result-" + (++resultSeq);
        out.add(new ToolCallResultEvent(resultMessageId, callId, content, Role.TOOL, null, null));
    }
}
