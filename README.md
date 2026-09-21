# COMP3011 Assignment 2 Video Player

JavaFX video player with ordered command-line film effects and a multi-threaded
processing pipeline, extending the supplied `VideoPlayerSingleThread` project.

## Build and run

Use JDK 26 or newer for JavaFX 26, and Maven 3.9.x. The source compilation release
remains 25. Run these commands from the folder containing `pom.xml`:

```sh
mvn validate
mvn compile
mvn package
mvn javafx:run '-Djavafx.args=--help'
mvn javafx:run '-Djavafx.args=-anssnfwyvdjmbp /absolute/path/to/movie.mp4'
mvn clean verify
```

`-a` enables sound. Effect flags may be short, long, stacked or mixed; order and
repetitions are preserved. Quote video paths containing spaces inside `javafx.args`.
The visible window title is **Video Player**.

Packaging creates `target/VideoPlayerMultiThreaded-0.1.jar`. This is a standard
Maven JAR with external JavaFX/JavaCV dependencies, not a standalone fat JAR.
`target/` is generated and ignored by Git.

## Main components and design choices

| Component | Responsibility |
| --- | --- |
| `CommandLineController` and `FrameProcessorOption` | Parse settings and construct ordered effect instances. the enum documents how to add an effect |
| `VideoPlayerApp`, controller and view | buttons/hotkeys and display callbacks |
| `VideoPlayerModel` | Own the UI clock, presentation, audio scheduling and session lifecycle requests |
| `PlaybackSession` | Own one generation of native decoding and initialise its effects |
| `FramePipeline` | Apply contiguous groups of effects in ordered, bounded stages |
| Supplied effects, metadata records and `AudioPlayer` | Retain pixel algorithms and media/audio support. Mottler reinitialisation now invalidates its old segment cache |

Previously, the UI heartbeat decoded media and applied hard-coded effects on the same thread. Now the CLI selects effects, while background decoding/stages leave
the UI available for presentation and controls. The parser creates a separate processor for every occurrence, so repeated flags do not share mutable effect state.

```text
serial decoder -> bounded FIFO -> effect stages -> bounded FIFO -> JavaFX presentation
       |
       +-> copied PCM -> bounded audio queue -> JavaFX audio scheduling
```

Design choice: Different stages process different frames concurrently, while each frame’s effects remain in the requested order. Each stage owns a consecutive group of effects, avoiding unsafe sharing between workers. One global lock around processing would make the expensive work run one task at a time again. Trade off: Effects are grouped by number rather than processing cost, so one slow stage can limit overall speed. The pipeline uses platform threads, with worker numbers limited by the effect count and available processors.

## Concurrency correctness

We protect decoding, effect state, frame data and UI updates through clear ownership and safe data hand-offs between threads:

- **Native buffers:** only the serial decoder accesses its FFmpeg grabber. It
  clones image pixels and copies PCM before another grab can reuse their storage.
- **Effect state:** one stage owns each effect instance. Initialisation completes
  before stage threads start; no two workers concurrently mutate its scratch arrays.
- **Frame transfer:** successful queue insertion transfers exclusive ownership.
  The sender stops accessing the pixels. BlockingQueue publication makes prior
  writes visible. FIFO queues and one consumer per stage preserve frame order.
- **UI confinement:** controls, clocks, displayed images and callbacks belong to
  the JavaFX thread. It polls completed work without blocking queue waits or joins.
  Conversion creates an independent image before the native frame is released.
- **Seek and cleanup:** cancel the old session, then serialise its cleanup before
  starting the next generation. Old workers finish before shared effects are
  reinitialised. The UI never waits for their termination. A paused preview queues
  one image without audio, preventing an audio lead-in from blocking that image.
- **Visibility:** volatile fields publish session state and cancellation; atomic
  compare-and-set preserves the first failure. Neither replaces frame/effect ownership.

Each frame queue holds up to 2 messages, and the audio queue holds up to 256 chunks. When a queue is full, the producer waits rather than continually storing more data. The stages pass work forward without a circular dependency. Workers do not hold application locks while waiting for queues or other workers to finish. Cancellation wakes waiting workers, and the decoder regularly checks whether it should stop. This assumes the media-library calls and effects eventually finish.

## Testing and evidence

Ordinary `mvn verify` runs **all 31 checks** without an enabling flag. Surefire runs
23 unit/component checks before packaging; Failsafe runs eight real JavaFX integration checks afterwards. **Full verification needs a graphical desktop and
sound output.** It opens/resizes test windows, plays a tone, and takes roughly two
minutes. `mvn package` does not run those integration windows.

## Attribution

Original project: Simon Ratcliffe in collaboration with GPT-5.6 Terra, copyright
2026 Simon Ratcliffe.
Student: Touch Chanchoronaiy
