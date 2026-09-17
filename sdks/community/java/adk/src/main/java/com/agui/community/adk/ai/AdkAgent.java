package com.agui.community.adk.ai;

import com.agui.community.core.agent.Agent;
import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.MessagesSnapshotEvent;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.RunStartedEvent;
import com.agui.community.core.interrupt.Interrupt;
import com.agui.community.core.interrupt.InterruptOutcome;
import com.agui.community.core.interrupt.Resume;
import com.agui.community.core.interrupt.ResumeStatus;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import org.reactivestreams.FlowAdapters;

/**
 * An AG-UI {@link Agent} backed by a Google ADK agent. It drives an ADK
 * {@link Runner} and maps the run's streamed ADK
 * {@link com.google.adk.events.Event}s onto the AG-UI event lifecycle:
 *
 * <pre>
 * RUN_STARTED
 *   MESSAGES_SNAPSHOT                   (the thread's prior history, if any)
 *   STATE_SNAPSHOT                      (the session's state as the turn begins)
 *   STATE_DELTA                         (per ADK event that changes session state)
 *   REASONING_START                     (when a thinking model reasons)
 *     REASONING_MESSAGE_*
 *   REASONING_END
 *   TEXT_MESSAGE_START                  (when the agent produces text)
 *     TEXT_MESSAGE_CONTENT*             (streamed as partial deltas)
 *   TEXT_MESSAGE_END
 *   TOOL_CALL_START                     (per tool the ADK agent calls)
 *     TOOL_CALL_ARGS
 *   TOOL_CALL_END
 *   TOOL_CALL_RESULT                    (the tool's result, executed by ADK)
 * RUN_FINISHED
 * </pre>
 *
 * <p>The ADK agent's (backend) tools are run by ADK itself; their calls and results
 * are surfaced as {@code TOOL_CALL_*} / {@code TOOL_CALL_RESULT} events so the front
 * end can display them. A thinking model's chain of thought (ADK
 * {@link com.google.genai.types.Part#thought() thought} parts) becomes a reasoning
 * message distinct from the assistant text. See {@link AdkEventTranslator}.
 *
 * <p><strong>Human-in-the-loop.</strong> An ADK
 * {@link com.google.adk.tools.LongRunningFunctionTool} does not resolve within the
 * run; its call is reported as an AG-UI {@link Interrupt} and the run ends with an
 * {@link InterruptOutcome} (bound by tool-call id). The front end resolves the call
 * and starts a new run whose {@link RunAgentInput#resume()} carries the result; the
 * agent feeds it back to ADK as a {@code functionResponse} on the same thread's
 * session, and ADK continues from the pending call. A run that resumes ignores the
 * message history and sends only the function responses.
 *
 * <p>Conversation state lives in the ADK {@link com.google.adk.sessions.Session},
 * keyed by the run's {@code threadId}: the session is created on the first run for a
 * thread and reused on later runs, so ADK accumulates the history server-side. Each
 * run sends only the latest user message from {@link RunAgentInput#messages()}. The
 * run streams with {@code StreamingMode.SSE} by default so text arrives as deltas.
 * The session's shared state is surfaced as a {@code STATE_SNAPSHOT} at the start of
 * the turn and {@code STATE_DELTA} events as ADK mutates it; see
 * {@link AdkEventTranslator}. When the thread already has history, a
 * {@code MESSAGES_SNAPSHOT} reconstructed from the session's stored events (see
 * {@link AdkHistory}) precedes it, so a reconnecting client recovers the prior turns.
 *
 * <p>See <a href="https://google.github.io/adk-docs/get-started/streaming/quickstart-streaming-java/">ADK
 * streaming (Java)</a>. Text, reasoning (thought), function calls/responses and
 * session-state changes are mapped; other part kinds are ignored. If the ADK stream
 * fails (or an ADK event
 * reports an error), a terminal {@link RunErrorEvent} is emitted instead of
 * propagating the failure, matching the protocol's in-band error handling.
 */
public final class AdkAgent implements Agent {

    /** Used when the run input carries no user identity of its own. */
    static final String DEFAULT_USER_ID = "user";

    private final Runner runner;
    private final String userId;
    private final Supplier<String> messageIdGenerator;
    private final RunConfig runConfig;

    /**
     * Creates an agent that drives the given ADK agent through an
     * {@link InMemoryRunner}, generating random message ids.
     *
     * @param agent the ADK agent to run (required)
     */
    public AdkAgent(BaseAgent agent) {
        this(new InMemoryRunner(Objects.requireNonNull(agent, "agent must not be null")));
    }

    /**
     * Creates an agent over an existing ADK {@link Runner} (letting the caller
     * configure the session, artifact and memory services), generating random
     * message ids.
     *
     * @param runner the ADK runner to drive (required)
     */
    public AdkAgent(Runner runner) {
        this(runner, DEFAULT_USER_ID, defaultMessageIds(), defaultRunConfig());
    }

    private AdkAgent(Runner runner, String userId, Supplier<String> messageIdGenerator, RunConfig runConfig) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.messageIdGenerator =
                Objects.requireNonNull(messageIdGenerator, "messageIdGenerator must not be null");
        this.runConfig = Objects.requireNonNull(runConfig, "runConfig must not be null");
    }

    /**
     * Starts building an agent over the given ADK agent (wrapped in an
     * {@link InMemoryRunner}).
     *
     * @param agent the ADK agent to run (required)
     * @return a new builder
     */
    public static Builder builder(BaseAgent agent) {
        return new Builder(new InMemoryRunner(Objects.requireNonNull(agent, "agent must not be null")));
    }

    /**
     * Starts building an agent over the given ADK {@link Runner}.
     *
     * @param runner the ADK runner to drive (required)
     * @return a new builder
     */
    public static Builder builder(Runner runner) {
        return new Builder(Objects.requireNonNull(runner, "runner must not be null"));
    }

    private static Supplier<String> defaultMessageIds() {
        return () -> UUID.randomUUID().toString();
    }

    private static RunConfig defaultRunConfig() {
        return RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();
    }

    /** Builder for {@link AdkAgent}. */
    public static final class Builder {

        private final Runner runner;
        private String userId = DEFAULT_USER_ID;
        private Supplier<String> messageIdGenerator = defaultMessageIds();
        private RunConfig runConfig = defaultRunConfig();

        private Builder(Runner runner) {
            this.runner = runner;
        }

        /**
         * Sets the ADK user id used for the session (defaults to {@code "user"}).
         *
         * @param userId the user id (required)
         * @return this builder
         */
        public Builder userId(String userId) {
            this.userId = Objects.requireNonNull(userId, "userId must not be null");
            return this;
        }

        /**
         * Sets the assistant-message id generator (defaults to random UUIDs).
         *
         * @param messageIdGenerator the generator (required)
         * @return this builder
         */
        public Builder messageIdGenerator(Supplier<String> messageIdGenerator) {
            this.messageIdGenerator =
                    Objects.requireNonNull(messageIdGenerator, "messageIdGenerator must not be null");
            return this;
        }

        /**
         * Overrides the ADK {@link RunConfig} (defaults to {@code StreamingMode.SSE}).
         *
         * @param runConfig the run config (required)
         * @return this builder
         */
        public Builder runConfig(RunConfig runConfig) {
            this.runConfig = Objects.requireNonNull(runConfig, "runConfig must not be null");
            return this;
        }

        /**
         * @return the configured agent
         */
        public AdkAgent build() {
            return new AdkAgent(runner, userId, messageIdGenerator, runConfig);
        }
    }

    @Override
    public Flow.Publisher<Event> run(RunAgentInput input) {
        Objects.requireNonNull(input, "input must not be null");
        String threadId = input.threadId();
        String runId = input.runId();
        // A run either resumes pending long-running tool calls (feeding their results
        // back as function responses) or sends the latest user message. Resuming takes
        // precedence: the front end has resolved an interrupt from a prior run.
        Content turnInput = resumeContent(input.resume());
        if (Objects.isNull(turnInput)) {
            turnInput = latestUserContent(input.messages());
        }
        Content input0 = turnInput;
        AdkEventTranslator translator = new AdkEventTranslator(messageIdGenerator.get());

        Flowable<Event> events = Flowable.<Event>defer(() -> Flowable.concat(
                        Flowable.just(new RunStartedEvent(threadId, runId)),
                        Objects.isNull(input0) ? Flowable.empty() : turn(threadId, input0, translator),
                        Flowable.defer(() -> Flowable.just(finished(threadId, runId, translator)))))
                .onErrorResumeNext(throwable -> Flowable.just(new RunErrorEvent(describe(throwable))));

        return FlowAdapters.toFlowPublisher(events);
    }

    /**
     * Streams one model turn: resolve the thread's session, emit its history and state
     * snapshots, run it and translate the events.
     */
    private Flowable<Event> turn(String threadId, Content turnInput, AdkEventTranslator translator) {
        return session(threadId).flatMapPublisher(session -> Flowable.concat(
                Flowable.fromIterable(prelude(session, translator)),
                runner.runAsync(userId, session.id(), turnInput, runConfig)
                        .concatMapIterable(translator::onEvent),
                Flowable.defer(() -> Flowable.fromIterable(translator.finish()))));
    }

    /**
     * The snapshot events emitted before a turn streams: the conversation history the
     * ADK session already holds (only when non-empty, so a fresh thread does not clear
     * a client's optimistic messages), then the session's state.
     */
    private static List<Event> prelude(Session session, AdkEventTranslator translator) {
        List<Event> events = new ArrayList<>();
        List<Message> history = AdkHistory.fromEvents(session.events());
        if (!history.isEmpty()) {
            events.add(new MessagesSnapshotEvent(history));
        }
        events.addAll(translator.snapshot(session.state()));
        return events;
    }

    /**
     * The event that ends the run: a plain {@code RUN_FINISHED}, or one carrying an
     * {@link InterruptOutcome} when the model called long-running tools that the front
     * end must resolve.
     */
    private static RunFinishedEvent finished(String threadId, String runId, AdkEventTranslator translator) {
        List<Interrupt> interrupts = translator.interrupts();
        if (interrupts.isEmpty()) {
            return new RunFinishedEvent(threadId, runId);
        }
        return new RunFinishedEvent(threadId, runId, new InterruptOutcome(interrupts), null, null, null);
    }

    /**
     * Builds the ADK {@link Content} that resumes pending long-running tool calls from
     * the run input's {@code resume} entries, or {@code null} when there is nothing to
     * resume. Each resolved entry becomes a {@code functionResponse} keyed by its
     * interrupt (tool-call) id; ADK matches it to the pending call in the thread's
     * session and continues. ADK supplies function responses under the {@code user}
     * role.
     */
    private static Content resumeContent(List<Resume> resume) {
        if (Objects.isNull(resume) || resume.isEmpty()) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        for (Resume entry : resume) {
            String callId = entry.interruptId();
            if (Objects.isNull(callId) || callId.isEmpty()) {
                continue;
            }
            parts.add(Part.builder()
                    .functionResponse(FunctionResponse.builder()
                            .id(callId)
                            .response(responseMap(entry))
                            .build())
                    .build());
        }
        return parts.isEmpty() ? null : Content.builder().role("user").parts(parts).build();
    }

    /**
     * Coerces a {@link Resume} payload into the map ADK's {@code functionResponse}
     * expects: a map payload is used as-is, any other value is wrapped under
     * {@code "output"}, and a cancelled resume reports {@code "cancelled": true}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseMap(Resume entry) {
        if (entry.status() == ResumeStatus.CANCELLED) {
            Map<String, Object> cancelled = new LinkedHashMap<>();
            cancelled.put("cancelled", true);
            if (Objects.nonNull(entry.payload())) {
                cancelled.put("output", entry.payload());
            }
            return cancelled;
        }
        Object payload = entry.payload();
        if (payload instanceof Map) {
            return (Map<String, Object>) payload;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("output", payload);
        return wrapped;
    }

    /** The ADK session for a thread: reuse it if present, otherwise create it. */
    private Single<Session> session(String threadId) {
        String appName = runner.appName();
        return runner.sessionService()
                .getSession(appName, userId, threadId, Optional.empty())
                .switchIfEmpty(runner.sessionService()
                        .createSession(appName, userId, new ConcurrentHashMap<>(), threadId));
    }

    /**
     * Builds an ADK user {@link Content} from the most recent user message in the run
     * input, or {@code null} if there is none (or it is blank).
     */
    private static Content latestUserContent(List<Message> messages) {
        if (Objects.isNull(messages)) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == Role.USER) {
                String text = message.content();
                if (Objects.nonNull(text) && !text.isEmpty()) {
                    return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
                }
                return null;
            }
        }
        return null;
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return Objects.nonNull(message) ? message : throwable.getClass().getSimpleName();
    }
}
