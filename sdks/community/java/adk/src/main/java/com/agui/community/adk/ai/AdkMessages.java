package com.agui.community.adk.ai;

import com.agui.community.core.message.AssistantMessage;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.agui.community.core.message.ToolCall;
import com.agui.community.core.message.ToolMessage;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps AG-UI input messages into ADK inputs. Two things are derived from a run's
 * message list:
 *
 * <ul>
 *   <li>the {@linkplain #latestUserContent(List) latest user message}, sent as the
 *       turn's new {@link Content}; and
 *   <li>the {@linkplain #historyBefore(List) history before it}, mapped to ADK events
 *       to seed a brand-new session so a first run can carry prior context.
 * </ul>
 *
 * <p>This is the inward mirror of {@link AdkHistory}. Only user text, assistant text,
 * assistant tool calls and tool results are mapped (ADK content roles are
 * {@code user}/{@code model}/{@code function}); system and developer messages have no
 * ADK content role (an ADK agent's instruction is set on the agent) and reasoning is
 * not seeded, so those are skipped.
 */
final class AdkMessages {

    private AdkMessages() {}

    /**
     * Builds the ADK user {@link Content} from the most recent user message, or
     * {@code null} if there is none (or it is blank).
     */
    static Content latestUserContent(List<Message> messages) {
        int index = lastUserIndex(messages);
        if (index < 0) {
            return null;
        }
        String text = messages.get(index).content();
        if (Objects.isNull(text) || text.isEmpty()) {
            return null;
        }
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    /**
     * Maps the messages before the latest user message to ADK events, in order, for
     * seeding a new session. Returns an empty list when there is no prior history.
     */
    static List<com.google.adk.events.Event> historyBefore(List<Message> messages) {
        List<com.google.adk.events.Event> events = new ArrayList<>();
        int end = lastUserIndex(messages);
        if (end <= 0) {
            return events;
        }
        for (int i = 0; i < end; i++) {
            com.google.adk.events.Event event = toEvent(messages.get(i));
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    private static int lastUserIndex(List<Message> messages) {
        if (Objects.isNull(messages)) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return i;
            }
        }
        return -1;
    }

    private static com.google.adk.events.Event toEvent(Message message) {
        if (message instanceof ToolMessage tool) {
            return event(message.id(), "user", Part.builder()
                    .functionResponse(FunctionResponse.builder()
                            .id(tool.toolCallId() == null ? "" : tool.toolCallId())
                            .response(asResponse(tool.content()))
                            .build())
                    .build());
        }
        if (message instanceof AssistantMessage assistant) {
            List<Part> parts = new ArrayList<>();
            String text = assistant.content();
            if (Objects.nonNull(text) && !text.isEmpty()) {
                parts.add(Part.fromText(text));
            }
            if (Objects.nonNull(assistant.toolCalls())) {
                for (ToolCall call : assistant.toolCalls()) {
                    parts.add(Part.builder().functionCall(toFunctionCall(call)).build());
                }
            }
            return parts.isEmpty() ? null : event(message.id(), "model", parts.toArray(new Part[0]));
        }
        if (message.role() == Role.USER) {
            String text = message.content();
            return Objects.isNull(text) || text.isEmpty()
                    ? null
                    : event(message.id(), "user", Part.fromText(text));
        }
        // System / developer / reasoning messages have no ADK content role: skip them.
        return null;
    }

    private static FunctionCall toFunctionCall(ToolCall call) {
        return FunctionCall.builder()
                .id(call.id() == null ? "" : call.id())
                .name(call.function().name())
                .args(asObject(call.function().arguments()))
                .build();
    }

    private static com.google.adk.events.Event event(String id, String role, Part... parts) {
        com.google.adk.events.Event.Builder builder = com.google.adk.events.Event.builder()
                .author(role)
                .content(Content.builder().role(role).parts(List.of(parts)).build());
        if (Objects.nonNull(id) && !id.isEmpty()) {
            builder = builder.id(id);
        }
        return builder.build();
    }

    /** A tool result's content is JSON of the response object; wrap a non-object under {@code output}. */
    private static Map<String, Object> asResponse(String content) {
        Map<String, Object> object = asObject(content);
        if (!object.isEmpty() || content == null || content.isEmpty()) {
            return object;
        }
        return Map.of("output", content);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(String json) {
        if (Objects.isNull(json) || json.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode node = JsonSerializable.stringToJsonNode(json);
            if (Objects.nonNull(node) && node.isObject()) {
                return JsonSerializable.objectMapper().convertValue(node, Map.class);
            }
        } catch (RuntimeException ignored) {
            // Not JSON (or not an object): treat as having no structured fields.
        }
        return Map.of();
    }
}
