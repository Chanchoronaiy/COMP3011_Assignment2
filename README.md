# COMP3011 Assignment 2 — Multi-threaded Video Player

Working implementation milestone, not yet the final assessed submission.

This desktop JavaFX player applies film effects selected on the command line.
It extends Simon Ratcliffe's supplied `VideoPlayerSingleThread` project. It is
not the Spring Boot website from Assignment 1.

[Assignment brief and rubric](https://learn.adelaide.edu.au/courses/30747/assignments/94505)

## Build and run

VS Code or Eclipse is fine. Open the folder containing this `pom.xml`, not an
individual Java file. Use JDK 26 or newer for the supplied JavaFX 26 dependency
(the source compilation release remains 25), and Maven 3.9.x.

```sh
mvn validate
mvn compile
mvn javafx:run '-Djavafx.args=--help'
mvn javafx:run '-Djavafx.args=-anssnfwyvdjmbp /absolute/path/to/movie.mp4'
mvn verify
```

`-a` enables audio. Effect flags can be short, long, stacked or mixed. Repeated
flags create distinct effects, and order matters. `-ss` scratches twice.
Quote a movie path containing spaces inside `javafx.args`.

`mvn package` builds `target/VideoPlayerMultiThreaded-0.1.jar`. The ordinary
JAR does not bundle JavaFX/JavaCV; the assignment resolves dependencies through
Maven. Do not use Assignment 1's standalone Spring JAR instructions here.
The supplied optional `fat-jar` profile is retained but is not needed for the
normal assessment workflow. `target/` is generated and ignored by Git.

The existing controls remain: Home (rewind), Left/Right (seek five seconds),
Space (pause/play), A (audio), Escape (stop/exit). Original monitor, window-size,
maximise, button and tooltip code is retained in the app/view/controller.

## Which file does what?

| File | Responsibility |
| --- | --- |
| `CommandLineController` | Parses launch/effect flags without reordering them |
| `FrameProcessorOption` | Registers flags, help descriptions and effect constructors |
| `VideoPlayerApp` / `VideoPlayerController` / `VideoPlayerView` | Window, existing controls and view callbacks |
| `VideoPlayerModel` | UI-owned playback clock, presentation and audio output |
| `PlaybackSession` | Background FFmpeg decoder and one play/seek generation |
| `FramePipeline` | Bounded, ordered effect stages with explicit frame ownership |
| Supplied `Frame...` subclasses | Actual pixel effects; only Mottler's reinitialisation cache was changed |

## Threading design and why it should be safe

```text
serial decoder -> bounded queue -> effect stage(s) -> bounded queue -> JavaFX presentation
      |
      +-- copied PCM -> bounded audio queue -> JavaFX audio scheduling
```

This follows Practical 7's production-line idea. Stage 2 can process frame 1
while stage 1 processes frame 2. A particular frame still visits all selected
effects in order. No independent worker pool may mutate the same effect's
scratch arrays concurrently.

1. **Native decoder:** Only the serial decoder thread creates, starts, grabs,
   seeks and closes its FFmpeg grabber. `Frame.clone()` copies decoded pixels
   before the next native grab can reuse its buffer. Audio samples are copied
   before that reuse too.
2. **Mutable effects:** One stage owns each effect instance. Bleeder's masks,
   Jitterer's source array and Mottler's cached fields are not shared between
   simultaneous stage calls. Repeated options create different instances.
   Effect initialisation happens before the stage threads start.
3. **Frame transfer:** Successful queue insertion transfers exclusive ownership
   to the receiver. The sender does not access those pixels again. Java's
   BlockingQueue guarantee publishes earlier writes to the receiving thread.
   FIFO queues and one consumer per stage preserve frame order by induction.
4. **UI:** The model's clock/control fields and all view callbacks belong to the
   JavaFX application thread. Its heartbeat uses `poll()`, never `take()` or
   `join()`. Image conversion creates a separate WritableImage in JavaCV
   1.5.13; the native frame is released after conversion and never backs an image
   being modified by an effect worker.
5. **Seek/stop:** The UI cancels and forgets the old session immediately. Queue
   waits wake through interrupts/cancellation. Serial background cleanup joins
   old workers and frees queued frames before another session can initialise
   the same effects. This is the generation/clearance barrier, analogous to
   waiting for old occupants to leave in Practical 8. Native seek can land
   early, so pre-target frames/audio are decoded but not published.
6. **Deadlock reasoning:** There is no application lock held across a queue wait
   or worker join. Data moves down an acyclic pipeline. Normal back pressure is
   released by the UI consumer. During cancellation every waiting stage is
   interrupted and the decoder checks cancellation between timed offers.
   This argument assumes native calls and each supplied effect eventually
   return; it does not prove arbitrary native libraries can never hang.
7. **Bounded memory:** Each of `S + 1` frame queues holds at most two frames;
   each of `S` stages owns at most one. Add a decoder-side frame and one UI
   image. Native frame memory therefore scales with stage count, not movie
   length. The audio queue holds at most 256 copied chunks. Full queues slow
   producers rather than allocating indefinitely.

Worker count is bounded by selected effects and `availableProcessors() - 1`
(at least one), leaving nominal capacity for decoding/UI. These CPU-bound
effects use platform threads: virtual threads do not create extra CPU cores.
Stages have contiguous groups of effects so the command-line order remains
unchanged. Equal-count grouping is deliberately simple, **not** optimal load
balancing: one expensive effect can be a bottleneck, and a single selected
effect is not made internally parallel.

## Tests and current evidence

`mvn verify` runs nine tests across four chosen areas. Reports are generated in
`target/surefire-reports/`.

| Test class | What it checks |
| --- | --- |
| `CommandLineControllerTest` (4) | Equivalent flag forms, repeats, original options, errors and help |
| `FramePipelineTest` (3) | All supplied effects match sequential pixels/order; stages overlap; cancellation/failure |
| `PlaybackSessionTest` (1) | Real generated H.264/AAC decode, every frame present, timestamp order, seek and cancellation |
| `EffectThroughputTest` (1) | Warmed component throughput: supplied effects sequentially vs the pipeline |

The test fixture is synthetic moving colour and a tone, not a personal or
copyrighted recording. The component comparison uses the same 720p input,
four unchanged Bleeder effects, three alternating measured trials and median
timings. On 14 October 2026 one run gave 33.2 fps sequentially and 112.6 fps
through the four-worker pipeline (3.39x). Timings vary with machine/load.
The benchmark reports speed rather than making a fragile universal timing
assertion. Pixel correctness is checked separately against all eleven effects.

This is **not** an automated comparison against the entire original JavaFX
player. It excludes presentation, audio and interactive resizing and does not
establish the full-player High Distinction performance criterion.

## Remaining work before calling this finished

- Run a longer HD video on both the unchanged Desktop starter and this player,
  with exactly the same effect sequence and hardware. Compare headroom,
  audio continuity and how many effects can be stacked before sustained stalls.
- Exercise rapid pause/seek/rewind/audio changes, EOF, window dragging/resizing,
  maximise and both monitor settings. Retained code alone is not test evidence.
- Extend the automated comparison to the full player if aiming for the highest
  performance rubric band. Tune stage grouping only after measurement.
- Review unusual media, including audio-only lead-ins and seeking while paused.
  The audio clock can start before the first image to drain a playing lead-in;
  paused lead-in preview still needs further testing. Device opening/closing
  and image conversion run on the UI thread. Native cancellation latency is
  also platform-dependent.
- Check Windows/TITAN and the final submission instructions. No TITAN result
  has been obtained for this project yet; no mark is promised.
- Revisit Topic 9 when accessible; its overview was denied to this account on
  14 October. Do not cite unseen content as a design source.
- Student: read and explain the modifications, adjust them as appropriate and
  use genuine incremental commits. No commits or pushes were created here.

## Concepts and attribution

Course concepts: Topic 5 separation of responsibilities and completion ordering;
Topic 6 evidence-backed claims and attribution; Topic 7 processes/threads,
CPU-bound parallelism, blocking and bounded resources; Topic 8 ownership,
interleavings, safety and deadlock/clearance reasoning. FSP/LTSA is a modelling
tool, not a library used by this player. No formal LTSA proof is claimed.

Implementation references:

- [Java SE BlockingQueue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/BlockingQueue.html)
- [JavaFX Node thread rules](https://openjfx.io/javadoc/26/javafx.graphics/javafx/scene/Node.html)
- Supplied JavaCV 1.5.13 bytecode: JavaFXFrameConverter constructs a fresh
  WritableImage; FFmpegFrameGrabber timestamp overloads were inspected.

Original source: Simon Ratcliffe in collaboration with GPT-5.6 Terra,
copyright 2026 Simon Ratcliffe. Original headers remain. New and modified
code was generated with Codex for Touch Chanchoronaiy (a1978787), and is labelled
as such in headers. That does not imply the student has already reviewed or
understood it; that review is required before submission.
