# ag-ui-adk

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

[Google ADK](https://google.github.io/adk-docs/) (Agent Development Kit) integration
for the [**AG-UI protocol**](https://docs.ag-ui.com), built on the framework-agnostic
`ag-ui` Java library.

It adapts a Google ADK agent into an AG-UI
[`Agent`](https://docs.ag-ui.com), so any ADK agent (Gemini and the models ADK
supports) can drive an AG-UI front end.

## What's inside

| Type | Purpose |
|------|---------|
| [`AdkAgent`](src/main/java/com/agui/community/adk/ai/AdkAgent.java) | Wraps an ADK agent (via an ADK `Runner`). Sends the latest user message, streams the run's ADK events and emits the AG-UI event lifecycle. |

## Event mapping

A run maps ADK's streamed events to the AG-UI lifecycle:

```
RUN_STARTED
  TEXT_MESSAGE_START                  (when the agent produces text)
    TEXT_MESSAGE_CONTENT*             (streamed as partial deltas)
  TEXT_MESSAGE_END
  TOOL_CALL_START                     (per tool the ADK agent calls)
    TOOL_CALL_ARGS
  TOOL_CALL_END
  TOOL_CALL_RESULT                    (the tool's result, executed by ADK)
RUN_FINISHED
```

ADK streams a turn as incremental **partial** events followed by a final aggregated
event that repeats the whole text. Partial chunks are emitted as
`TEXT_MESSAGE_CONTENT` deltas and the trailing aggregate is dropped, so text is not
duplicated; when ADK is not streaming, the single complete event's text is emitted
once. The run uses `RunConfig`'s `StreamingMode.SSE` by default.

**Tool calling.** An ADK agent runs its own (backend) tools — the ones registered on
the agent, e.g. `LlmAgent.builder().tools(...)`. When the model calls one, its
`functionCall` is surfaced as `TOOL_CALL_START` / `TOOL_CALL_ARGS` (the arguments as
JSON) / `TOOL_CALL_END`, and the tool's `functionResponse` (which ADK executes
server-side) as a `TOOL_CALL_RESULT` — so the front end can render the call and its
result. Each result is its own message (a distinct id, role `tool`), and any open
text message is closed before tool events; a text message that resumes afterwards
opens under a fresh id. Non-text, non-function parts are ignored.

> Client-side / human-in-the-loop tools (advertising `RunAgentInput.tools` to the
> model and pausing the run for the front end to execute them) are not yet wired up —
> today the ADK agent executes its tools itself and the calls are surfaced for
> display.

If the ADK stream fails, or an ADK event reports an `errorMessage`, a terminal
`RUN_ERROR` event is emitted instead of propagating the failure — matching the
protocol's in-band error handling.

**Conversation state** lives in the ADK
[`Session`](https://google.github.io/adk-docs/sessions/session/), keyed by the run's
`threadId`: the session is created on the first run for a thread and reused on later
runs, so ADK accumulates history server-side. Each run sends only the latest user
message from `RunAgentInput.messages()`.

## Usage

Build an ADK agent and hand it to `AdkAgent` (an `InMemoryRunner` is created for you):

```java
BaseAgent agent = LlmAgent.builder()
        .name("assistant")
        .model("gemini-2.0-flash")
        .instruction("You are a helpful assistant.")
        .build();

Agent aguiAgent = new AdkAgent(agent);
```

Or drive an ADK `Runner` you configured yourself (custom session / artifact / memory
services), and tune the user id, message-id generator or `RunConfig` via the builder:

```java
Agent aguiAgent = AdkAgent.builder(runner)
        .userId("alice")
        .runConfig(RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build())
        .build();
```

Combine with an AG-UI server (for example the Spring server modules) to expose it
over HTTP, and point the `HttpAgent` client at it.

## Requirements

- **Java 17+**
- **Google ADK for Java** (`com.google.adk:google-adk`) — brings `google-genai`,
  RxJava 3 and reactive-streams transitively.
- The `ag-ui` artifact (`com.ag-ui.community:java-core`) — resolved from Maven Central.

## Building

```bash
mvn clean install
```

## License

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
