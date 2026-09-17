package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.event.TextMessageContentEvent;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
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
