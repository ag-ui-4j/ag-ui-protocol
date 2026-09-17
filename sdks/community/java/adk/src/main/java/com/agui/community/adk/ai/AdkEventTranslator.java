package com.agui.community.adk.ai;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.JsonPatchOperation;
import com.agui.community.core.event.ReasoningEndEvent;
import com.agui.community.core.event.ReasoningMessageContentEvent;
import com.agui.community.core.event.ReasoningMessageEndEvent;
import com.agui.community.core.event.ReasoningMessageStartEvent;
import com.agui.community.core.event.ReasoningStartEvent;
import com.agui.community.core.event.StateDeltaEvent;
import com.agui.community.core.event.StateSnapshotEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallEndEvent;
import com.agui.community.core.event.ToolCallResultEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.agui.community.core.interrupt.Interrupt;
import com.agui.community.core.message.Role;
import com.google.adk.events.EventActions;
import com.google.genai.JsonSerializable;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a stream of Google ADK {@link com.google.adk.events.Event}s into the
 * AG-UI event lifecycle for a single run. It is <strong>stateful</strong> and not
 * thread-safe: one instance handles one run, and its methods are invoked
 * sequentially as ADK events arrive.
 *
 * <p>Content parts are mapped by kind:
 *
 * <pre>
 * thought text     -> REASONING_START, REASONING_MESSAGE_START, REASONING_MESSAGE_CONTENT*,
 *                     REASONING_MESSAGE_END, REASONING_END
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
 * <p><strong>Reasoning.</strong> A thinking model emits its chain of thought as
 * parts flagged {@link com.google.genai.types.Part#thought() thought}. Their text is
 * mapped to the AG-UI reasoning sub-stream (a single reasoning message per turn, with
 * an id distinct from the assistant text so a client keeps them as separate messages)
 * and is deduplicated against the trailing aggregate the same way text is. Switching
 * between reasoning, text and tool calls closes whichever message is open first.
 *
 * <p><strong>Tools.</strong> When the ADK agent calls one of its (backend) tools,
 * the model's {@code functionCall} is surfaced as {@code TOOL_CALL_START/ARGS/END}
 * and the tool's {@code functionResponse} (which ADK executes server-side) as a
 * {@code TOOL_CALL_RESULT}, so the front end can display the call and its result.
 * Any open text message is closed before tool events are emitted; a text message
 * that resumes afterwards is opened under a fresh id (a client keys messages by id).
 *
 * <p><strong>Long-running tools.</strong> A call to an ADK
 * {@link com.google.adk.tools.LongRunningFunctionTool} does not resolve within the
 * run: ADK marks its id in {@link com.google.adk.events.Event#longRunningToolIds()}.
 * Such a call is still surfaced as {@code TOOL_CALL_START/ARGS/END}, and in addition
 * recorded as an {@link Interrupt} (bound by {@code toolCallId}) so the agent can end
 * the run with an interrupt outcome and let the front end resolve the call. See
 * {@link #interrupts()}.
 *
 * <p><strong>State.</strong> ADK keeps conversation state in the session. The agent
 * emits a {@code STATE_SNAPSHOT} of the session's state when a turn begins (see
 * {@link #snapshot(Map)}), and each ADK event that changes state
 * ({@link com.google.adk.events.EventActions#stateDelta()}) becomes a
 * {@code STATE_DELTA} carrying JSON Patch (RFC 6902) {@code add} operations, one per
 * changed key. The ADK session is authoritative; state keys are forwarded verbatim
 * (including any {@code app:}/{@code user:} prefixes).
 *
 * <p>An ADK event carrying an {@code errorMessage} aborts the run: the message is
 * thrown so the agent maps it to a terminal {@code RUN_ERROR}.
 */
final class AdkEventTranslator {

    private final String messageId;
    // Reasoning is a separate AG-UI message from the assistant text, so it needs its
    // own id: a client keys messages by their id, and reusing the assistant id would
    // fold the reasoning into the answer (or drop one of them).
    private final String reasoningMessageId;

    // The currently-open assistant text message id, or null when none is open. Each
    // text segment gets its own id (the base id for the first, suffixed after that)
    // so a segment that resumes after a tool call never collides with an earlier one.
    private String textId;
    private int textSeq;
    // Whether a partial (streaming) delta was emitted for the open text segment. Once
    // true, the trailing aggregated (non-partial) event for that segment is dropped.
    private boolean streamedPartial;

    // Whether the reasoning phase / message is currently open (the reasoning message
    // may open and close more than once in a turn as the model interleaves thinking
    // with text or tool calls; it always reuses reasoningMessageId).
    private boolean reasoningPhaseOpen;
    private boolean reasoningMessageOpen;
    // Whether a partial reasoning delta was emitted this turn. Once true the trailing
    // aggregated (non-partial) thought is dropped so the reasoning is not duplicated.
    private boolean reasoningStreamed;

    private int toolSeq;
    private int resultSeq;
    // The most recent tool-call id, used to correlate a function response that arrives
    // without its own id (as when the model omits function-call ids).
    private String lastToolCallId;

    // Long-running tool calls seen in this run, as AG-UI interrupts bound by tool-call
    // id. The agent reads these after the stream to decide the run's outcome.
    private final List<Interrupt> interrupts = new ArrayList<>();

    AdkEventTranslator(String messageId) {
        this.messageId = messageId;
        this.reasoningMessageId = messageId + "-reasoning";
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
        emitStateDelta(event, out);
        List<Part> parts = event.content().flatMap(content -> content.parts()).orElse(List.of());
        boolean partial = event.partial().orElse(false);
        Set<String> longRunning = event.longRunningToolIds().orElse(Set.of());
        for (Part part : parts) {
            String text = part.text().orElse("");
            boolean thought = part.thought().orElse(false);
            if (!text.isEmpty() && thought) {
                emitReasoning(text, partial, out);
            } else if (!text.isEmpty()) {
                closeReasoning(out);
                emitText(text, partial, out);
            } else if (part.functionCall().isPresent()) {
                closeReasoning(out);
                closeText(out);
                emitFunctionCall(part.functionCall().get(), longRunning, out);
            } else if (part.functionResponse().isPresent()) {
                closeReasoning(out);
                closeText(out);
                emitFunctionResponse(part.functionResponse().get(), out);
            }
        }
        return out;
    }

    /**
     * The long-running tool calls observed in this run, as AG-UI {@link Interrupt}s
     * (each bound by its {@code toolCallId}). Empty when the run had none. The agent
     * reads this once the stream completes to decide whether the run finished normally
     * or paused waiting for the front end to resolve these calls.
     *
     * @return the interrupts, in the order the calls appeared (never {@code null})
     */
    List<Interrupt> interrupts() {
        return interrupts;
    }

    /**
     * A {@code STATE_SNAPSHOT} of the ADK session's state at the start of a turn, so a
     * client syncs to the authoritative state before the run streams. The state is
     * copied defensively, as the session map is live and mutated during the run.
     *
     * @param state the session's current state (may be {@code null})
     * @return the snapshot event, as a single-element list
     */
    List<Event> snapshot(Map<String, Object> state) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (state != null) {
            copy.putAll(state);
        }
        return List.of(new StateSnapshotEvent(copy));
    }

    private void emitStateDelta(com.google.adk.events.Event event, List<Event> out) {
        EventActions actions = event.actions();
        Map<String, Object> delta = actions == null ? null : actions.stateDelta();
        if (delta == null || delta.isEmpty()) {
            return;
        }
        List<JsonPatchOperation> operations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : delta.entrySet()) {
            // RFC 6902 "add" replaces the target if it already exists, so it applies to
            // both new and updated keys without tracking prior state.
            operations.add(new JsonPatchOperation("add", "/" + escapePointer(entry.getKey()), entry.getValue()));
        }
        out.add(new StateDeltaEvent(operations));
    }

    /** Escapes a state key for a JSON Pointer path segment (RFC 6901). */
    private static String escapePointer(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Closes the assistant text message if one is open when the stream completes.
     *
     * @return the terminal events, in order (possibly empty)
     */
    List<Event> finish() {
        List<Event> out = new ArrayList<>();
        closeReasoning(out);
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

    private void emitReasoning(String text, boolean partial, List<Event> out) {
        if (partial) {
            enterReasoning(out);
            out.add(new ReasoningMessageContentEvent(reasoningMessageId, text));
            reasoningStreamed = true;
        } else if (!reasoningStreamed) {
            // Non-streaming: no partial deltas preceded this, so emit the full thought once.
            enterReasoning(out);
            out.add(new ReasoningMessageContentEvent(reasoningMessageId, text));
        }
        // Otherwise this is the trailing aggregate of already-streamed reasoning: drop it.
    }

    private void enterReasoning(List<Event> out) {
        closeText(out);
        if (!reasoningPhaseOpen) {
            out.add(new ReasoningStartEvent(reasoningMessageId));
            reasoningPhaseOpen = true;
        }
        if (!reasoningMessageOpen) {
            out.add(new ReasoningMessageStartEvent(reasoningMessageId));
            reasoningMessageOpen = true;
        }
    }

    private void closeReasoning(List<Event> out) {
        if (reasoningMessageOpen) {
            out.add(new ReasoningMessageEndEvent(reasoningMessageId));
            reasoningMessageOpen = false;
        }
        if (reasoningPhaseOpen) {
            out.add(new ReasoningEndEvent(reasoningMessageId));
            reasoningPhaseOpen = false;
        }
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

    private void emitFunctionCall(FunctionCall call, Set<String> longRunning, List<Event> out) {
        String name = call.name().orElse("");
        String callId = call.id().filter(id -> !id.isEmpty())
                .orElseGet(() -> messageId + "-tool-" + (++toolSeq));
        lastToolCallId = callId;
        String args = JsonSerializable.toJsonString(call.args().orElse(Map.of()));
        out.add(new ToolCallStartEvent(callId, name, messageId, null, null));
        out.add(new ToolCallArgsEvent(callId, args));
        out.add(new ToolCallEndEvent(callId));
        // A long-running tool does not resolve in this run: record it as an interrupt
        // (bound by the tool-call id) so the run pauses for the front end to resolve it.
        // ADK marks the call's own id, so only calls the model gave an id can be matched.
        if (call.id().filter(id -> !id.isEmpty()).map(longRunning::contains).orElse(false)) {
            interrupts.add(new Interrupt(callId, "tool_call", name, callId, null, null, null));
        }
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
