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
Home rewinds, Left/Right seek five seconds, Space pauses/resumes, A toggles audio,
and Escape stops/exits. The visible window title is **Video Player**.

Packaging creates `target/VideoPlayerMultiThreaded-0.1.jar`. This is a standard
Maven JAR with external JavaFX/JavaCV dependencies, not a standalone fat JAR.
`target/` is generated and ignored by Git.

## Main components and design choices

| Component | Responsibility |
| --- | --- |
| `CommandLineController` and `FrameProcessorOption` | Parse settings and construct ordered effect instances; the enum documents how to add an effect |
| `VideoPlayerApp`, controller and view | Retain window management, buttons/hotkeys and display callbacks |
| `VideoPlayerModel` | Own the UI clock, presentation, audio scheduling and session lifecycle requests |
| `PlaybackSession` | Own one generation of native decoding and initialise its effects |
| `FramePipeline` | Apply contiguous groups of effects in ordered, bounded stages |
| Supplied effects, metadata records and `AudioPlayer` | Retain pixel algorithms and media/audio support; Mottler reinitialisation now invalidates its old segment cache |

Previously the UI heartbeat decoded media and applied hard-coded effects on the
same thread. Now the CLI selects effects, while background decoding/stages leave
the UI available for presentation and controls. The parser creates a separate
processor for every occurrence, so repeated flags do not share mutable effect state.

```text
serial decoder -> bounded FIFO -> effect stages -> bounded FIFO -> JavaFX presentation
       |
       +-> copied PCM -> bounded audio queue -> JavaFX audio scheduling
```

Stages process different frames concurrently, following Practical 7's production
line idea. Each frame still visits its selected effects in order. A general pool
sharing the supplied mutable effects would risk races; one global lock would
serialize the expensive work again. Contiguous stage groups preserve order.
Equal-count grouping is simple rather than optimal: one expensive effect can
remain a bottleneck. CPU-bound stages use platform threads, with a budget bounded
by effect count and nominal available processor capacity.

## Overall concurrency correctness

The critical regions are native decoder access, effect state, frame mutation and
lifetime, cross-thread hand-offs, and UI state. Protection comes from ownership
and safe publication rather than synchronizing every method:

- **Native buffers:** only the serial decoder accesses its FFmpeg grabber. It
  clones image pixels and copies PCM before another grab can reuse their storage.
- **Effect state:** one stage owns each effect instance. Initialisation completes
  before stage threads start; no two workers concurrently mutate its scratch arrays.
- **Frame transfer:** successful queue insertion transfers exclusive ownership.
  The sender stops accessing the pixels. BlockingQueue publication makes prior
  writes visible; FIFO queues and one consumer per stage preserve frame order.
- **UI confinement:** controls, clocks, displayed images and callbacks belong to
  the JavaFX thread. It polls completed work without blocking queue waits or joins.
  Conversion creates an independent image before the native frame is released.
- **Seek and cleanup:** cancel the old session, then serialise its cleanup before
  starting the next generation. Old workers finish before shared effects are
  reinitialised. The UI never waits for their termination. A paused preview queues
  one image without audio, preventing an audio lead-in from blocking that image.
- **Visibility:** volatile fields publish session state and cancellation; atomic
  compare-and-set preserves the first failure. Neither replaces frame/effect ownership.

Frame queues hold at most two messages each and the PCM queue holds at most 256
chunks. Full queues slow producers rather than allowing memory to grow with movie
length. No application lock is held across queue waits or joins; the pipeline is
acyclic, and cancellation interrupts stage waits while the decoder checks timed
offers. This reasoning assumes native calls and effect operations eventually
return; it is not a formal proof that external libraries can never hang.

## Verification and evidence

Ordinary `mvn verify` runs **all 31 checks** without an enabling flag. Surefire runs
23 unit/component checks before packaging; Failsafe runs eight real JavaFX
integration checks afterwards. **Full verification needs a graphical desktop and
sound output.** It opens/resizes test windows, plays a tone, and takes roughly two
minutes. `mvn package` does not run those integration windows.

Tests cover exact CLI help and ordered/repeated flags; pixel equality and frame
order; cache boundaries; PCM copying/layout; seeking, cancellation and worker
cleanup; real playback, controls and the original-versus-threaded load comparison.
The original model is isolated under `src/test/java/comp3011/reference/` with only
class/package/effect-injection adaptations, and is excluded from the production JAR.

The full run on 20 October 2026 passed 31 checks without failures, errors or skips.
The actual-player comparison used identical 72-frame 720p media, sixteen Bleeder
effects, alternating trials and resizing: **10.56 fps baseline versus 24.88 fps
threaded, a 2.36x median improvement**. All 744 frames of a separate real-time
playback completed in 31.31 seconds. These are workload/machine-dependent results,
not universal speed guarantees. Later window-title-only edits were compilation checked.

The student reported TITAN Part 1 **38/38** and Part 2 **306% performance**, and
confirmed continuous sound while dragging/resizing a separate one-minute clip.
Physical multi-monitor/maximise configurations still need a manual check where
available. Image conversion and sound-device operations remain on the UI thread;
driver/native latency is platform-dependent.

Generated reports are in `target/surefire-reports/` and `target/failsafe-reports/`.

## Sources and attribution

Original project: Simon Ratcliffe in collaboration with GPT-5.6 Terra, copyright
2026 Simon Ratcliffe. Source headers retain original credit and identify modified
or new code. Codex assisted with the implementation changes, fixes, tests,
documentation and learning comments for Touch Chanchoronaiy (a1978787). Student
review and understanding remain necessary before submission.

- [Assignment brief and rubric](https://learn.adelaide.edu.au/courses/30747/assignments/94505)
- [Java BlockingQueue publication guarantees](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingQueue.html)
- [JavaFX thread rules](https://openjfx.io/javadoc/26/javafx.graphics/javafx/scene/Node.html)
- [Maven Failsafe lifecycle](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html)
- Supplied JavaCV 1.5.13 bytecode was inspected for frame conversion and seeking.
