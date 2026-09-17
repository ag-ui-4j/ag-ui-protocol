package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallResultEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.agui.community.core.message.Role;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ADK-event-to-AG-UI-event mapping deterministically by feeding
 * {@link com.google.adk.events.Event}s straight to the translator.
 */
class AdkEventTranslatorTest {

    @Test
    void streamsPartialDeltasAndDropsTheTrailingAggregate() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = new ArrayList<>();
        events.addAll(translator.onEvent(partial("Hel")));
        events.addAll(translator.onEvent(partial("lo")));
        // ADK repeats the whole turn as a final, non-partial event; it must be dropped.
        events.addAll(translator.onEvent(complete("Hello")));
        events.addAll(translator.finish());

        assertEquals(
                List.of(
                        EventType.TEXT_MESSAGE_START,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_END),
                events.stream().map(Event::type).toList());

        String text = events.stream()
                .filter(e -> e instanceof TextMessageContentEvent)
                .map(e -> ((TextMessageContentEvent) e).delta())
                .collect(Collectors.joining());
        assertEquals("Hello", text);
    }

    @Test
    void emitsTheSingleCompleteEventWhenNotStreaming() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = new ArrayList<>();
        events.addAll(translator.onEvent(complete("Hi there")));
        events.addAll(translator.finish());

        assertEquals(
                List.of(
                        EventType.TEXT_MESSAGE_START,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_END),
                events.stream().map(Event::type).toList());
        TextMessageContentEvent content = (TextMessageContentEvent) events.get(1);
        assertEquals("Hi there", content.delta());
    }

    @Test
    void ignoresEventsWithoutText() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        assertTrue(translator.onEvent(com.google.adk.events.Event.builder().author("model").build()).isEmpty());
        // No text ever opened a message, so finishing emits nothing.
        assertTrue(translator.finish().isEmpty());
    }

    @Test
    void throwsOnAnAdkErrorEvent() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        AdkRunException error = assertThrows(AdkRunException.class,
                () -> translator.onEvent(com.google.adk.events.Event.builder()
                        .author("model")
                        .errorMessage("quota exceeded")
                        .build()));
        assertEquals("quota exceeded", error.getMessage());
    }

    @Test
    void surfacesFunctionCallAndResponseAsToolEvents() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = new ArrayList<>();
        events.addAll(translator.onEvent(functionCall("call-1", "getWeather", Map.of("city", "Paris"))));
        events.addAll(translator.onEvent(functionResponse("call-1", Map.of("tempC", 21))));
        events.addAll(translator.finish());

        assertEquals(
                List.of(
                        EventType.TOOL_CALL_START,
                        EventType.TOOL_CALL_ARGS,
                        EventType.TOOL_CALL_END,
                        EventType.TOOL_CALL_RESULT),
                events.stream().map(Event::type).toList());

        ToolCallStartEvent start = (ToolCallStartEvent) events.get(0);
        assertEquals("call-1", start.toolCallId());
        assertEquals("getWeather", start.toolCallName());
        assertEquals("msg-1", start.parentMessageId());
        ToolCallArgsEvent args = (ToolCallArgsEvent) events.get(1);
        assertTrue(args.delta().contains("Paris"), args.delta());
        ToolCallResultEvent result = (ToolCallResultEvent) events.get(3);
        assertEquals("call-1", result.toolCallId());
        assertEquals(Role.TOOL, result.role());
        assertTrue(result.content().contains("21"), result.content());
        // The result is its own message, distinct from the assistant turn id.
        assertTrue(!"msg-1".equals(result.messageId()), result.messageId());
    }

    @Test
    void closesOpenTextBeforeAToolCallAndResumesUnderANewId() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = new ArrayList<>();
        events.addAll(translator.onEvent(partial("Let me check")));
        events.addAll(translator.onEvent(functionCall("call-1", "getWeather", Map.of())));
        events.addAll(translator.onEvent(partial("It is sunny")));
        events.addAll(translator.finish());

        assertEquals(
                List.of(
                        EventType.TEXT_MESSAGE_START,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_END,
                        EventType.TOOL_CALL_START,
                        EventType.TOOL_CALL_ARGS,
                        EventType.TOOL_CALL_END,
                        EventType.TEXT_MESSAGE_START,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_END),
                events.stream().map(Event::type).toList());

        // The resumed text segment must use a fresh id (a client keys messages by id).
        List<String> textStartIds = events.stream()
                .filter(e -> e instanceof TextMessageStartEvent)
                .map(e -> ((TextMessageStartEvent) e).messageId())
                .toList();
        assertEquals(2, textStartIds.size());
        assertTrue(!textStartIds.get(0).equals(textStartIds.get(1)), textStartIds.toString());
    }

    @Test
    void synthesizesAToolCallIdWhenTheModelOmitsIt() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = translator.onEvent(functionCall(null, "getWeather", Map.of()));

        ToolCallStartEvent start = (ToolCallStartEvent) events.get(0);
        assertTrue(start.toolCallId().startsWith("msg-1-tool-"), start.toolCallId());
    }

    private static com.google.adk.events.Event functionCall(String id, String name, Map<String, Object> args) {
        FunctionCall.Builder call = FunctionCall.builder().name(name).args(args);
        if (id != null) {
            call = call.id(id);
        }
        return contentEvent(Part.builder().functionCall(call.build()).build());
    }

    private static com.google.adk.events.Event functionResponse(String id, Map<String, Object> response) {
        return contentEvent(Part.builder()
                .functionResponse(FunctionResponse.builder().id(id).response(response).build())
                .build());
    }

    private static com.google.adk.events.Event contentEvent(Part part) {
        return com.google.adk.events.Event.builder()
                .author("model")
                .content(Content.builder().role("model").parts(List.of(part)).build())
                .build();
    }

    private static com.google.adk.events.Event partial(String text) {
        return textEvent(text, true);
    }

    private static com.google.adk.events.Event complete(String text) {
        return textEvent(text, false);
    }

    private static com.google.adk.events.Event textEvent(String text, boolean partial) {
        return com.google.adk.events.Event.builder()
                .author("model")
                .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                .partial(partial)
                .build();
    }
}
