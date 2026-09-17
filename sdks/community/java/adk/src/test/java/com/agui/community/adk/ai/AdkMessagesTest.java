package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.message.AssistantMessage;
import com.agui.community.core.message.DeveloperMessage;
import com.agui.community.core.message.FunctionCall;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.SystemMessage;
import com.agui.community.core.message.ToolCall;
import com.agui.community.core.message.ToolMessage;
import com.agui.community.core.message.UserMessage;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies mapping of AG-UI input messages into ADK content and seed events. */
class AdkMessagesTest {

    @Test
    void latestUserContentIsTheLastUserMessage() {
        Content content = AdkMessages.latestUserContent(List.of(
                new UserMessage("u1", "first"),
                new AssistantMessage("a1", "reply"),
                new UserMessage("u2", "second")));

        List<Part> parts = content.parts().orElseThrow();
        assertEquals("second", parts.get(0).text().orElseThrow());
        assertEquals("user", content.role().orElseThrow());
    }

    @Test
    void latestUserContentIsNullWithoutAUserMessage() {
        assertNull(AdkMessages.latestUserContent(List.of(new AssistantMessage("a1", "hi"))));
        assertNull(AdkMessages.latestUserContent(null));
    }

    @Test
    void historyBeforeMapsUserAndAssistantText() {
        List<com.google.adk.events.Event> events = AdkMessages.historyBefore(List.of(
                new UserMessage("u1", "hello"),
                new AssistantMessage("a1", "hi there"),
                new UserMessage("u2", "latest")));

        assertEquals(2, events.size());
        assertEquals("user", role(events.get(0)));
        assertEquals("hello", text(events.get(0)));
        assertEquals("model", role(events.get(1)));
        assertEquals("hi there", text(events.get(1)));
        // The latest user message is not part of the seed (it is the turn input).
        assertTrue(events.stream().noneMatch(e -> "latest".equals(text(e))));
    }

    @Test
    void historyBeforeMapsToolCallsAndResults() {
        List<com.google.adk.events.Event> events = AdkMessages.historyBefore(List.of(
                new AssistantMessage("a1", null,
                        null, List.of(new ToolCall("c1", new FunctionCall("getWeather", "{\"city\":\"Paris\"}")))),
                new ToolMessage("t1", "{\"tempC\":21}", "c1"),
                new UserMessage("u2", "latest")));

        assertEquals(2, events.size());
        com.google.genai.types.FunctionCall call =
                events.get(0).content().flatMap(Content::parts).orElseThrow().get(0).functionCall().orElseThrow();
        assertEquals("c1", call.id().orElseThrow());
        assertEquals("getWeather", call.name().orElseThrow());
        assertEquals("Paris", call.args().orElseThrow().get("city"));

        com.google.genai.types.FunctionResponse response =
                events.get(1).content().flatMap(Content::parts).orElseThrow().get(0).functionResponse().orElseThrow();
        assertEquals("c1", response.id().orElseThrow());
        assertEquals(21, response.response().orElseThrow().get("tempC"));
    }

    @Test
    void historyBeforeSkipsSystemAndDeveloperMessages() {
        List<com.google.adk.events.Event> events = AdkMessages.historyBefore(List.of(
                new SystemMessage("s1", "be terse"),
                new DeveloperMessage("d1", "debug on"),
                new UserMessage("u1", "hi"),
                new UserMessage("u2", "latest")));

        // Only the earlier user message seeds; system/developer have no ADK content role.
        assertEquals(1, events.size());
        assertEquals("hi", text(events.get(0)));
    }

    @Test
    void historyBeforeIsEmptyWithOnlyTheLatestUserMessage() {
        assertTrue(AdkMessages.historyBefore(List.of(new UserMessage("u1", "only"))).isEmpty());
        assertTrue(AdkMessages.historyBefore(List.<Message>of()).isEmpty());
    }

    private static String role(com.google.adk.events.Event event) {
        return event.content().flatMap(Content::role).orElseThrow();
    }

    private static String text(com.google.adk.events.Event event) {
        return event.content().flatMap(Content::parts).orElseThrow().get(0).text().orElse(null);
    }
}
