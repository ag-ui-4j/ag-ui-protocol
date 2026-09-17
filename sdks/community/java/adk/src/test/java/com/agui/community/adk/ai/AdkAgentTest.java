package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.message.UserMessage;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.reactivestreams.FlowAdapters;

/**
 * Drives {@link AdkAgent} end to end over an {@code InMemoryRunner} wrapping a fake
 * ADK agent that emits canned events, so the run lifecycle and event translation are
 * exercised without a real model.
 */
class AdkAgentTest {

    @Test
    void streamsAdkOutputThroughTheRunLifecycle() {
        BaseAgent agent = fakeAgent(Flowable.just(partial("Hel"), partial("lo"), complete("Hello")));
        AdkAgent adkAgent = new AdkAgent(agent);
        RunAgentInput input = new RunAgentInput("t1", "r1",
                List.of(new UserMessage("m1", "hi")), List.of());

        List<Event> events = collect(adkAgent.run(input));

        assertEquals(EventType.RUN_STARTED, events.get(0).type());
        assertEquals(EventType.RUN_FINISHED, events.get(events.size() - 1).type());
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TEXT_MESSAGE_START));
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TEXT_MESSAGE_END));
        assertTrue(events.stream().noneMatch(e -> e.type() == EventType.RUN_ERROR));

        String text = events.stream()
                .filter(e -> e instanceof TextMessageContentEvent)
                .map(e -> ((TextMessageContentEvent) e).delta())
                .collect(Collectors.joining());
        assertEquals("Hello", text);
    }

    @Test
    void emitsRunErrorWhenAdkReportsAnError() {
        BaseAgent agent = fakeAgent(Flowable.just(com.google.adk.events.Event.builder()
                .author("model")
                .errorMessage("boom")
                .build()));
        AdkAgent adkAgent = new AdkAgent(agent);
        RunAgentInput input = new RunAgentInput("t1", "r1",
                List.of(new UserMessage("m1", "hi")), List.of());

        List<Event> events = collect(adkAgent.run(input));

        assertEquals(EventType.RUN_STARTED, events.get(0).type());
        RunErrorEvent error = (RunErrorEvent) events.get(events.size() - 1);
        assertEquals("boom", error.message());
        // A terminal error replaces the run tail — no RUN_FINISHED after it.
        assertTrue(events.stream().noneMatch(e -> e.type() == EventType.RUN_FINISHED));
    }

    private static BaseAgent fakeAgent(Flowable<com.google.adk.events.Event> events) {
        return new BaseAgent("fake_agent", "A fake ADK agent for tests", List.of(), List.of(), List.of()) {
            @Override
            protected Flowable<com.google.adk.events.Event> runAsyncImpl(InvocationContext context) {
                return events;
            }

            @Override
            protected Flowable<com.google.adk.events.Event> runLiveImpl(InvocationContext context) {
                return Flowable.empty();
            }
        };
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

    private static List<Event> collect(Flow.Publisher<Event> publisher) {
        return Flowable.fromPublisher(FlowAdapters.toPublisher(publisher)).toList().blockingGet();
    }
}
