package com.agui.community.adk.ai;

import com.agui.community.core.message.AssistantMessage;
import com.agui.community.core.message.FunctionCall;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.ToolCall;
import com.agui.community.core.message.ToolMessage;
import com.agui.community.core.message.UserMessage;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs the AG-UI conversation history from an ADK session's stored events, so
 * the agent can emit a {@code MESSAGES_SNAPSHOT}. ADK persists only complete (non
 * partial) events to the session, so each stored event maps to one message:
 *
 * <pre>
 * user text            -> UserMessage
 * model text           -> AssistantMessage
 * model functionCall+  -> AssistantMessage (with its toolCalls)
 * functionResponse+    -> one ToolMessage each
 * </pre>
 *
 * <p>Thought (reasoning) parts are omitted, matching the protocol's message history
 * (reasoning is streamed but not carried as a durable message). An event's ADK id is
 * reused as the message id so ids are stable across runs.
 */
final class AdkHistory {

    private AdkHistory() {}

    /**
     * Maps a session's stored ADK events to AG-UI messages, in order.
     *
     * @param events the session's events (may be {@code null})
     * @return the reconstructed messages (never {@code null})
     */
    static List<Message> fromEvents(List<com.google.adk.events.Event> events) {
        List<Message> messages = new ArrayList<>();
        if (events == null) {
            return messages;
        }
        for (int index = 0; index < events.size(); index++) {
            com.google.adk.events.Event event = events.get(index);
            if (event.partial().orElse(false)) {
                // Defensive: ADK does not persist partial events, but never duplicate one.
                continue;
            }
            appendEvent(event, index, messages);
        }
        return messages;
    }

    private static void appendEvent(com.google.adk.events.Event event, int index, List<Message> messages) {
        List<Part> parts = event.content().flatMap(content -> content.parts()).orElse(List.of());
        if (parts.isEmpty()) {
            return;
        }
        String role = event.content().flatMap(content -> content.role()).orElse(event.author());
        boolean user = "user".equalsIgnoreCase(role);
        // ADK events normally carry an id; fall back to the position so a message id is
        // always present (an AG-UI message requires one) and stable for a given session.
        String id = event.id() == null || event.id().isEmpty() ? "adk-" + index : event.id();

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<ToolMessage> toolResults = new ArrayList<>();
        for (Part part : parts) {
            if (part.thought().orElse(false)) {
                continue;
            }
            part.text().filter(t -> !t.isEmpty()).ifPresent(text::append);
            part.functionCall().ifPresent(call -> toolCalls.add(toToolCall(call)));
            part.functionResponse().ifPresent(response ->
                    toolResults.add(toToolMessage(id + "-result-" + (toolResults.size() + 1), response)));
        }

        // Tool results are their own (tool-role) messages.
        messages.addAll(toolResults);
        if (!toolCalls.isEmpty()) {
            messages.add(new AssistantMessage(id, text.length() == 0 ? null : text.toString(), null, toolCalls));
        } else if (text.length() > 0) {
            messages.add(user ? new UserMessage(id, text.toString()) : new AssistantMessage(id, text.toString()));
        }
    }

    private static ToolCall toToolCall(com.google.genai.types.FunctionCall call) {
        String callId = call.id().filter(value -> !value.isEmpty()).orElse("");
        String name = call.name().orElse("");
        String arguments = JsonSerializable.toJsonString(call.args().orElse(Map.of()));
        return new ToolCall(callId, new FunctionCall(name, arguments));
    }

    private static ToolMessage toToolMessage(String id, com.google.genai.types.FunctionResponse response) {
        String callId = response.id().filter(value -> !value.isEmpty()).orElse("");
        String content = JsonSerializable.toJsonString(response.response().orElse(Map.of()));
        return new ToolMessage(id, content, callId);
    }
}
