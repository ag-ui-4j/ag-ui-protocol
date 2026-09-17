package com.agui.community.adk.ai;

import com.agui.community.core.agent.Agent;
import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.RunStartedEvent;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
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
 *   TEXT_MESSAGE_START                  (when the agent produces text)
 *     TEXT_MESSAGE_CONTENT*             (streamed as partial deltas)
 *   TEXT_MESSAGE_END
 * RUN_FINISHED
 * </pre>
 *
 * <p>Conversation state lives in the ADK {@link com.google.adk.sessions.Session},
 * keyed by the run's {@code threadId}: the session is created on the first run for a
 * thread and reused on later runs, so ADK accumulates the history server-side. Each
 * run sends only the latest user message from {@link RunAgentInput#messages()}. The
 * run streams with {@code StreamingMode.SSE} by default so text arrives as deltas.
 *
 * <p>See <a href="https://google.github.io/adk-docs/get-started/streaming/quickstart-streaming-java/">ADK
 * streaming (Java)</a>. Only text is mapped; function calls and other non-text parts
 * are ignored. If the ADK stream fails (or an ADK event reports an error), a terminal
 * {@link RunErrorEvent} is emitted instead of propagating the failure, matching the
 * protocol's in-band error handling.
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
        Content userMessage = latestUserContent(input.messages());

        Flowable<Event> events = Flowable.<Event>defer(() -> Flowable.concat(
                        Flowable.just(new RunStartedEvent(threadId, runId)),
                        Objects.isNull(userMessage) ? Flowable.empty() : turn(threadId, userMessage),
                        Flowable.defer(() -> Flowable.just(new RunFinishedEvent(threadId, runId)))))
                .onErrorResumeNext(throwable -> Flowable.just(new RunErrorEvent(describe(throwable))));

        return FlowAdapters.toFlowPublisher(events);
    }

    /** Streams one model turn: resolve the thread's session, run it, translate the events. */
    private Flowable<Event> turn(String threadId, Content userMessage) {
        AdkEventTranslator translator = new AdkEventTranslator(messageIdGenerator.get());
        return session(threadId)
                .flatMapPublisher(session -> runner.runAsync(userId, session.id(), userMessage, runConfig))
                .concatMapIterable(translator::onEvent)
                .concatWith(Flowable.defer(() -> Flowable.fromIterable(translator.finish())));
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
