package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.message.AssistantMessage;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.agui.community.core.message.ToolCall;
import com.agui.community.core.message.ToolMessage;
import com.agui.community.core.message.UserMessage;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies reconstruction of AG-UI message history from ADK session events. */
class AdkHistoryTest {

    @Test
    void reconstructsUserAndAssistantText() {
        List<Message> messages = AdkHistory.fromEvents(List.of(
                userEvent("u1", Part.fromText("hi")),
                modelEvent("a1", Part.fromText("hello"))));

        assertEquals(2, messages.size());
        UserMessage user = (UserMessage) messages.get(0);
        assertEquals("u1", user.id());
        assertEquals(Role.USER, user.role());
        assertEquals("hi", user.content());
        AssistantMessage assistant = (AssistantMessage) messages.get(1);
        assertEquals("a1", assistant.id());
        assertEquals(Role.ASSISTANT, assistant.role());
        assertEquals("hello", assistant.content());
    }

    @Test
    void reconstructsAssistantToolCallAndToolResult() {
        List<Message> messages = AdkHistory.fromEvents(List.of(
                modelEvent("a1", Part.builder()
                        .functionCall(FunctionCall.builder().id("c1").name("getWeather").args(Map.of("city", "Paris")).build())
                        .build()),
                userEvent("t1", Part.builder()
                        .functionResponse(FunctionResponse.builder().id("c1").response(Map.of("tempC", 21)).build())
                        .build())));

        assertEquals(2, messages.size());
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertNull(assistant.content());
        assertEquals(1, assistant.toolCalls().size());
        ToolCall call = assistant.toolCalls().get(0);
        assertEquals("c1", call.id());
        assertEquals("getWeather", call.function().name());
        assertTrue(call.function().arguments().contains("Paris"), call.function().arguments());

        ToolMessage result = (ToolMessage) messages.get(1);
        assertEquals(Role.TOOL, result.role());
        assertEquals("c1", result.toolCallId());
        assertTrue(result.content().contains("21"), result.content());
    }

    @Test
    void skipsThoughtParts() {
        List<Message> messages = AdkHistory.fromEvents(List.of(
                modelEvent("a1",
                        Part.builder().text("thinking...").thought(true).build(),
                        Part.fromText("the answer"))));

        assertEquals(1, messages.size());
        assertEquals("the answer", ((AssistantMessage) messages.get(0)).content());
    }

    @Test
    void returnsEmptyForNoEvents() {
        assertTrue(AdkHistory.fromEvents(null).isEmpty());
        assertTrue(AdkHistory.fromEvents(List.of()).isEmpty());
    }

    private static com.google.adk.events.Event userEvent(String id, Part... parts) {
        return event(id, "user", parts);
    }

    private static com.google.adk.events.Event modelEvent(String id, Part... parts) {
        return event(id, "model", parts);
    }

    private static com.google.adk.events.Event event(String id, String role, Part... parts) {
        return com.google.adk.events.Event.builder()
                .id(id)
                .author(role)
                .content(Content.builder().role(role).parts(List.of(parts)).build())
                .build();
    }
}
