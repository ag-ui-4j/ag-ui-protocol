package com.agui.community.adk.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.event.MessagesSnapshotEvent;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.agui.community.core.interrupt.Interrupt;
import com.agui.community.core.interrupt.InterruptOutcome;
import com.agui.community.core.interrupt.Resume;
import com.agui.community.core.interrupt.ResumeStatus;
import com.agui.community.core.message.AssistantMessage;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.UserMessage;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void surfacesToolCallsAndResults() {
        BaseAgent agent = fakeAgent(Flowable.just(
                functionCall("call-1", "getWeather", Map.of("city", "Paris")),
                functionResponse("call-1", Map.of("tempC", 21)),
                complete("It is 21C in Paris")));
        AdkAgent adkAgent = new AdkAgent(agent);
        RunAgentInput input = new RunAgentInput("t1", "r1",
                List.of(new UserMessage("m1", "weather in Paris?")), List.of());

        List<Event> events = collect(adkAgent.run(input));

        ToolCallStartEvent start = (ToolCallStartEvent) events.stream()
                .filter(e -> e.type() == EventType.TOOL_CALL_START)
                .findFirst()
                .orElseThrow();
        assertEquals("getWeather", start.toolCallName());
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TOOL_CALL_ARGS));
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TOOL_CALL_END));
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TOOL_CALL_RESULT));

        String text = events.stream()
                .filter(e -> e instanceof TextMessageContentEvent)
                .map(e -> ((TextMessageContentEvent) e).delta())
                .collect(Collectors.joining());
        assertTrue(text.contains("21C"), text);
        assertEquals(EventType.RUN_FINISHED, events.get(events.size() - 1).type());
    }

    @Test
    void endsWithAnInterruptOutcomeForALongRunningTool() {
        BaseAgent agent = fakeAgent(Flowable.just(longRunningCall("call-1", "askUser", Map.of("q", "ok?"))));
        AdkAgent adkAgent = new AdkAgent(agent);
        RunAgentInput input = new RunAgentInput("t1", "r1",
                List.of(new UserMessage("m1", "please confirm")), List.of());

        List<Event> events = collect(adkAgent.run(input));

        // The call is still surfaced for display.
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TOOL_CALL_START));

        RunFinishedEvent finished = (RunFinishedEvent) events.get(events.size() - 1);
        InterruptOutcome outcome = (InterruptOutcome) finished.outcome();
        assertEquals(1, outcome.interrupts().size());
        Interrupt interrupt = outcome.interrupts().get(0);
        assertEquals("call-1", interrupt.toolCallId());
        assertEquals("tool_call", interrupt.reason());
    }

    @Test
    void resumesALongRunningToolByFeedingItsResultToAdk() {
        AtomicReference<Content> seen = new AtomicReference<>();
        BaseAgent agent = capturingAgent(seen, Flowable.just(complete("Done, it is confirmed")));
        AdkAgent adkAgent = new AdkAgent(agent);
        // A resume run carries no user message, only the resolved interrupt.
        RunAgentInput input = new RunAgentInput("t1", "r2", null,
                List.of(), List.of(), List.of(), null,
                List.of(new Resume("call-1", ResumeStatus.RESOLVED, Map.of("approved", true))));

        List<Event> events = collect(adkAgent.run(input));

        // ADK was run with a functionResponse for the resolved call.
        Content content = seen.get();
        assertNotNull(content);
        FunctionResponse response = content.parts().orElseThrow().get(0).functionResponse().orElseThrow();
        assertEquals("call-1", response.id().orElseThrow());
        assertTrue(response.response().orElseThrow().containsKey("approved"));

        // The continuation streamed and the run finished normally (no interrupt).
        String text = events.stream()
                .filter(e -> e instanceof TextMessageContentEvent)
                .map(e -> ((TextMessageContentEvent) e).delta())
                .collect(Collectors.joining());
        assertTrue(text.contains("confirmed"), text);
        RunFinishedEvent finished = (RunFinishedEvent) events.get(events.size() - 1);
        assertNull(finished.outcome());
    }

    @Test
    void emitsStateSnapshotAtRunStartAndDeltaWhenStateChanges() {
        BaseAgent agent = fakeAgent(Flowable.just(
                stateChange(Map.of("count", 1)),
                complete("Counted")));
        AdkAgent adkAgent = new AdkAgent(agent);
        RunAgentInput input = new RunAgentInput("t1", "r1",
                List.of(new UserMessage("m1", "count")), List.of());

        List<Event> events = collect(adkAgent.run(input));

        // The snapshot is the first event after RUN_STARTED, before any content.
        assertEquals(EventType.RUN_STARTED, events.get(0).type());
        assertEquals(EventType.STATE_SNAPSHOT, events.get(1).type());
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.STATE_DELTA));
        assertEquals(EventType.RUN_FINISHED, events.get(events.size() - 1).type());
    }

    @Test
    void emitsMessagesSnapshotOfPriorHistoryOnALaterRun() {
        // One runner reused across two runs on the same thread; the fake agent's cold
        // Flowable re-emits on each run, and the InMemory session accumulates history.
        AdkAgent adkAgent = new AdkAgent(fakeAgent(Flowable.just(complete("Hello"))));

        List<Event> first = collect(adkAgent.run(new RunAgentInput("t1", "r1",
                List.of(new UserMessage("m1", "hi")), List.of())));
        // A fresh thread has no history: no snapshot to avoid clearing the client.
        assertTrue(first.stream().noneMatch(e -> e.type() == EventType.MESSAGES_SNAPSHOT));

        List<Event> second = collect(adkAgent.run(new RunAgentInput("t1", "r2",
                List.of(new UserMessage("m2", "again")), List.of())));

        // The history snapshot leads the turn, before the state snapshot.
        assertEquals(EventType.RUN_STARTED, second.get(0).type());
        assertEquals(EventType.MESSAGES_SNAPSHOT, second.get(1).type());
        assertEquals(EventType.STATE_SNAPSHOT, second.get(2).type());

        List<Message> history = ((MessagesSnapshotEvent) second.get(1)).messages();
        assertTrue(history.stream().anyMatch(m ->
                m instanceof UserMessage && "hi".equals(m.content())), history.toString());
        assertTrue(history.stream().anyMatch(m ->
                m instanceof AssistantMessage && "Hello".equals(m.content())), history.toString());
    }

    @Test
    void seedsANewSessionWithPriorHistoryFromTheMessageList() {
        AdkAgent adkAgent = new AdkAgent(fakeAgent(Flowable.just(complete("Sure"))));
        RunAgentInput input = new RunAgentInput("t1", "r1", List.of(
                new UserMessage("u1", "hello"),
                new AssistantMessage("a1", "hi there"),
                new UserMessage("u2", "what's up")), List.of());

        List<Event> events = collect(adkAgent.run(input));

        // Seeding the new session put the prior turns into ADK, so they come back in the
        // history snapshot; the latest user message is the turn input, not part of it.
        List<Message> history = ((MessagesSnapshotEvent) events.stream()
                .filter(e -> e.type() == EventType.MESSAGES_SNAPSHOT)
                .findFirst()
                .orElseThrow()).messages();
        assertTrue(history.stream().anyMatch(m -> "hello".equals(m.content())), history.toString());
        assertTrue(history.stream().anyMatch(m -> "hi there".equals(m.content())), history.toString());
        assertTrue(history.stream().noneMatch(m -> "what's up".equals(m.content())), history.toString());
    }

    private static com.google.adk.events.Event stateChange(Map<String, Object> delta) {
        return com.google.adk.events.Event.builder()
                .author("model")
                .actions(EventActions.builder().stateDelta(delta).build())
                .build();
    }

    private static com.google.adk.events.Event functionCall(String id, String name, Map<String, Object> args) {
        return contentEvent(Part.builder()
                .functionCall(FunctionCall.builder().id(id).name(name).args(args).build())
                .build());
    }

    private static com.google.adk.events.Event longRunningCall(String id, String name, Map<String, Object> args) {
        com.google.adk.events.Event event = functionCall(id, name, args);
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

    /** A fake agent that records the content it is run with, so resume input can be asserted. */
    private static BaseAgent capturingAgent(AtomicReference<Content> seen, Flowable<com.google.adk.events.Event> events) {
        return new BaseAgent("fake_agent", "A fake ADK agent for tests", List.of(), List.of(), List.of()) {
            @Override
            protected Flowable<com.google.adk.events.Event> runAsyncImpl(InvocationContext context) {
                context.userContent().ifPresent(seen::set);
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
