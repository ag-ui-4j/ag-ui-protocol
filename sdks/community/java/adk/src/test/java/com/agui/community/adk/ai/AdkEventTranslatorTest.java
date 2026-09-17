package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.event.ReasoningMessageContentEvent;
import com.agui.community.core.event.ReasoningMessageStartEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallResultEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.agui.community.core.interrupt.Interrupt;
import com.agui.community.core.message.Role;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void streamsReasoningAsItsOwnMessageAheadOfTheText() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = new ArrayList<>();
        events.addAll(translator.onEvent(thought("Plan", true)));
        events.addAll(translator.onEvent(thought("ning", true)));
        events.addAll(translator.onEvent(partial("Ans")));
        events.addAll(translator.onEvent(partial("wer")));
        // ADK repeats the whole turn (thought + text) as a final, non-partial event.
        events.addAll(translator.onEvent(both("Planning", "Answer")));
        events.addAll(translator.finish());

        assertEquals(
                List.of(
                        EventType.REASONING_START,
                        EventType.REASONING_MESSAGE_START,
                        EventType.REASONING_MESSAGE_CONTENT,
                        EventType.REASONING_MESSAGE_CONTENT,
                        EventType.REASONING_MESSAGE_END,
                        EventType.REASONING_END,
                        EventType.TEXT_MESSAGE_START,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_CONTENT,
                        EventType.TEXT_MESSAGE_END),
                events.stream().map(Event::type).toList());

        String reasoning = events.stream()
                .filter(e -> e instanceof ReasoningMessageContentEvent)
                .map(e -> ((ReasoningMessageContentEvent) e).delta())
                .collect(Collectors.joining());
        assertEquals("Planning", reasoning);
        String text = events.stream()
                .filter(e -> e instanceof TextMessageContentEvent)
                .map(e -> ((TextMessageContentEvent) e).delta())
                .collect(Collectors.joining());
        assertEquals("Answer", text);

        // Reasoning and text are separate messages: distinct, non-colliding ids.
        String reasoningId = ((ReasoningMessageStartEvent) events.get(1)).messageId();
        String textId = ((TextMessageStartEvent) events.get(6)).messageId();
        assertEquals("msg-1-reasoning", reasoningId);
        assertEquals("msg-1", textId);
    }

    @Test
    void emitsReasoningOnceWhenNotStreaming() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = new ArrayList<>();
        events.addAll(translator.onEvent(thought("I should greet them", false)));
        events.addAll(translator.finish());

        assertEquals(
                List.of(
                        EventType.REASONING_START,
                        EventType.REASONING_MESSAGE_START,
                        EventType.REASONING_MESSAGE_CONTENT,
                        EventType.REASONING_MESSAGE_END,
                        EventType.REASONING_END),
                events.stream().map(Event::type).toList());
        ReasoningMessageContentEvent content = (ReasoningMessageContentEvent) events.get(2);
        assertEquals("I should greet them", content.delta());
    }

    @Test
    void recordsLongRunningToolCallsAsInterrupts() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        List<Event> events = translator.onEvent(longRunningCall("call-1", "askUser", Map.of("q", "ok?")));

        // A long-running call is still surfaced as tool events for display.
        assertEquals(
                List.of(EventType.TOOL_CALL_START, EventType.TOOL_CALL_ARGS, EventType.TOOL_CALL_END),
                events.stream().map(Event::type).toList());

        List<Interrupt> interrupts = translator.interrupts();
        assertEquals(1, interrupts.size());
        Interrupt interrupt = interrupts.get(0);
        assertEquals("call-1", interrupt.id());
        assertEquals("call-1", interrupt.toolCallId());
        assertEquals("tool_call", interrupt.reason());
        assertEquals("askUser", interrupt.message());
    }

    @Test
    void doesNotRecordAnInterruptForAnOrdinaryToolCall() {
        AdkEventTranslator translator = new AdkEventTranslator("msg-1");

        translator.onEvent(functionCall("call-1", "getWeather", Map.of("city", "Paris")));

        assertTrue(translator.interrupts().isEmpty());
    }

    private static com.google.adk.events.Event functionCall(String id, String name, Map<String, Object> args) {
        FunctionCall.Builder call = FunctionCall.builder().name(name).args(args);
        if (id != null) {
            call = call.id(id);
        }
        return contentEvent(Part.builder().functionCall(call.build()).build());
    }

    private static com.google.adk.events.Event longRunningCall(String id, String name, Map<String, Object> args) {
        com.google.adk.events.Event event = functionCall(id, name, args);
        // ADK marks a LongRunningFunctionTool's call id here so the run pauses for it.
        event.setLongRunningToolIds(Set.of(id));
        return event;
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

    /** A thinking model marks a chain-of-thought part with {@code thought=true}. */
    private static com.google.adk.events.Event thought(String text, boolean partial) {
        return com.google.adk.events.Event.builder()
                .author("model")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.builder().text(text).thought(true).build()))
                        .build())
                .partial(partial)
                .build();
    }

    /** The final aggregate event ADK emits: the whole turn's thought and text repeated. */
    private static com.google.adk.events.Event both(String thoughtText, String text) {
        return com.google.adk.events.Event.builder()
                .author("model")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(
                                Part.builder().text(thoughtText).thought(true).build(),
                                Part.fromText(text)))
                        .build())
                .partial(false)
                .build();
    }
}
