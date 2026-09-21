/* Touch Chanchoronaiy (a1978787) */
// Test implementation and learning notes developed with Codex assistance.
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import comp3011.reference.OriginalVideoPlayerModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *  TEST PURPOSE: Real JavaFX integration tests, including the supplied-player load comparison.
 * Run with: mvn verify (no enabling flag). 
 * Failsafe discovers the IT suffix and runs this class after package. Surefire runs the smaller unit tests earlier.
 * Verification opens temporary windows and needs a graphical desktop and sound output. 
 * Plain mvn package still builds without opening those test windows.
 * 
 *  COVERS: The modified model with the new session/pipeline, supplied view, 
 * effects and audio support: presentation pixels/counts, controls, EOF and cleanup.
 *  LIMIT: Calls model controls directly rather than clicking buttons or sending keys through VideoPlayerController.
 * It does not launch VideoPlayerApp or its launcher, or verify physical monitor selection. Those need separate/manual checks.
 */

class DesktopPlaybackIT {
    //----------TEMPORARY TEST MEDIA----------
    // Path represents a filesystem location; directory.resolve("name.mp4") chooses a file inside it.
    // GeneratedVideo creates repeatable clips here instead of relying on someone's Downloads folder.
    // @TempDir supplies an isolated temporary path for each test method
    @TempDir Path directory;
    //----------TEST SETUP: START JAVAFX ONCE----------
    // TEST PURPOSE: make the real GUI toolkit available before tests create any windows.
    // CountDownLatch(1) is a one-off readiness signal. countDown() releases await().
    // The callback runs on JavaFX; the TEST thread waits up to ten seconds, not the UI thread.
    // setImplicitExit(false) keeps JavaFX alive when one test window closes between tests.
    // EXPECTED: startup signals readiness; otherwise assertTrue fails rather than waiting forever.
    //  @BeforeAll starts the toolkit once
    @BeforeAll static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }
    //----------SUITE CLEANUP----------
    // EXPECTED: release the toolkit after every test in this class has finished.
    // @AfterAll exits JavaFX. fx(...) posts a Callable to the UI thread and waits from the test thread; a worker must not directly mutate a displayed view.
    @AfterAll static void stopJavaFx() { Platform.exit(); }
    //----------AFTER EACH TEST: CHECK FOR LEAKED PLAYBACK THREADS----------
    // TEST PURPOSE: closing a window must not leave our decoder/effect workers running.
    // stream().noneMatch(...) means no live thread may satisfy the name condition.
    // -> supplies a predicate (a true/false check) for each thread; &&/|| mean AND/OR.
    // EXPECTED: our named workers stop within five seconds. This does not inspect every JavaFX/native thread.
    // @AfterEach checks worker termination;
    @AfterEach void playbackWorkersTerminateAfterThePlayerCloses() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                        && (thread.getName().equals("video-decoder") || thread.getName().startsWith("video-effects-"))),
                5, "A playback worker survived shutdown");
    }

    //----------TEST HELPER: RUN AN ACTION ON THE UI THREAD----------
    // WHY: the JUnit thread must not directly create/update JavaFX windows or UI-owned model state.
    // Callable<T> is work that can return a result; <T> lets this helper support different result types.
    // FutureTask stores the result/error. runLater QUEUES it on JavaFX; get waits on the TEST thread.
    // Call this helper from the test thread, not JavaFX itself (which would wait for its own queued task).
    // EXPECTED: return the result within ten seconds, or propagate failure. "return null" in a callback
    // means that action has no useful result, but still fulfils Callable's return requirement.
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    //----------TEST HELPER: WAIT FOR AN OBSERVABLE RESULT, WITH A DEADLINE----------
    // BooleanSupplier supplies a condition such as () -> player.frames.get() >= 3.
    // nanoTime measures elapsed time; TimeUnit converts seconds to nanoseconds.
    // Recheck every ten milliseconds on the TEST thread, leaving the UI free to work.
    // EXPECTED: condition becomes true before the deadline; otherwise fail with the supplied message.
    private static void await(BooleanSupplier condition, long seconds, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), message);
    }
    //----------TEST FIXTURE: ONE TEMPORARY PLAYER WINDOW----------
    // TEST HELPER, not a second production player: wraps either the old OR new model for the tests.
    // "implements AutoCloseable" lets try (Player p = ...) call close() even when an assertion fails.
    // It does not itself make the fixture thread-safe. final prevents subclassing here.
    private final class Player implements AutoCloseable {
        // The UI increments the counter; the test reads it. AtomicInteger provides safe cross-thread access.
        // volatile publishes the status/image/timing fields. It does not make arbitrary compound actions atomic.
        // presentedPixels is UI-written and inspected after playback completion; ArrayList itself is not thread-safe.
        final AtomicInteger frames = new AtomicInteger();
        final List<int[]> presentedPixels = new ArrayList<>();
        volatile String status = "";
        volatile Image lastImage;
        volatile long firstPresentedNs, lastPresentedNs;
        Stage stage;
        VideoPlayerModel model;
        OriginalVideoPlayerModel original;
        //----------FIXTURE CONSTRUCTORS: CHOOSE THE MODEL----------
        // The shorter overload defaults to the NEW model. this(...) delegates to the longer constructor.
        // reference=true selects OriginalVideoPlayerModel; false selects the production VideoPlayerModel.
        Player(boolean audio, List<FrameProcessor> effects) throws Exception {
            this(audio, effects, false);
        }
        Player(boolean audio, List<FrameProcessor> effects, boolean reference) throws Exception {
            // Arrange a real window on JavaFX. A lambda, () -> { ... }, supplies the action for fx().
            fx(() -> {
                VideoPlayerView view = new VideoPlayerView();
                stage = new Stage();
                // Match the supplied title used by the real application window.
                stage.setTitle("COMP3011 Video Player");
                stage.setScene(new Scene(view.getRoot(), 500, 330));
                stage.show();
                // PRESENTATION CALLBACK: the model calls this when it has an Image ready for the view.
                // Consumer<Image> accepts an image and returns nothing; image -> { ... } defines that action.
                // EXPECTED: run on JavaFX, count displayed frames and record first/last presentation times.
                java.util.function.Consumer<Image> present = image -> {
                            assertTrue(Platform.isFxApplicationThread());
                            view.setImage(image);
                            if (image != null) {
                                long now = System.nanoTime();
                                if (frames.get() == 0) firstPresentedNs = now;
                                lastPresentedNs = now;
                                lastImage = image;
                                // Save exact pixels only for small clips; storing every HD frame would waste memory.
                                // HD load tests use counts/timing instead; a separate small-clip test compares pixels.
                                if (image.getWidth() <= 256) presentedPixels.add(imagePixels(image));
                                frames.incrementAndGet();
                            }
                        };
                // STATUS CALLBACK: keep the visible label and the test's observable status in step.
                java.util.function.Consumer<String> report = text -> { status = text; view.setStatus(text); };
                // view::setVideoDisplaySize is a method reference: pass that action for the model to call later.
                // The empty callbacks ignore UI notifications not needed by this fixture; production controls
                // are wired elsewhere. Exactly one of "original"/"model" is constructed for this fixture.
                if (reference) original = new OriginalVideoPlayerModel(audio, effects, view::setVideoDisplaySize,
                        present, report, (playing, paused) -> {}, enabled -> {});
                else model = new VideoPlayerModel(audio, effects, view::setVideoDisplaySize,
                        present, report, (playing, paused) -> {}, enabled -> {});
                return null;
            });
        }
        // TEST HELPER: start the chosen model using the same input file, through the UI thread.
        // Path.toFile() adapts the test's Path to the File parameter expected by either model.
        void play(Path file) throws Exception {
            fx(() -> { if (model != null) model.play(file.toFile()); else original.play(file.toFile()); return null; });
        }
        // TEST HELPER: release the selected model and close its window on JavaFX.
        // Calling the new model's shutdown() twice deliberately checks that repeated cleanup is safe.
        // @Override checks that this method fulfils AutoCloseable.close().
        @Override public void close() throws Exception {
            fx(() -> {
                if (model != null) { model.shutdown(); model.shutdown(); }
                else original.shutdown();
                stage.close(); return null;
            });
        }
    }

    //----------TEST 1: FRAME COUNT AND REPLAY AFTER EOF----------
    // TEST PURPOSE: a finished player must be able to play again. EOF = end of file.
    // ARRANGE/ACT: generate 36 frames, play to completion, then replay the same file.
    // EXPECTED: count 36, then 72 total, with no playback error. Counts alone do not prove pixel correctness.
    // try (...) closes the fixture automatically; List.of(...) creates the selected effects list.
    @Test void everyFrameIsPresentedAndPlaybackCanReplayAfterEof() throws Exception {
        Path file = directory.resolve("short.mp4");
        GeneratedVideo.create(file, 160, 90, 36, false);
        try (Player player = new Player(false, List.of(new FrameNumberer()))) {
            player.play(file);
            await(() -> player.status.equals("Playback finished") || player.status.startsWith("Error:"), 10, "Playback stalled");
            assertEquals("Playback finished", player.status);
            assertEquals(36, player.frames.get());
            player.play(file);
            await(() -> player.frames.get() == 72 || player.status.startsWith("Error:"), 10, "Replay stalled");
            assertFalse(player.status.startsWith("Error:"), player.status);
            assertEquals(72, player.frames.get());
        }
    }

    //----------TEST HELPER: TURN A DISPLAYED IMAGE INTO COMPARABLE PIXEL VALUES----------
    // TEST PURPOSE: compare actual output, not whether two windows look similar by eye.
    // getArgb(x,y) packs opacity/red/green/blue into one int; y * width + x flattens the 2D position.
    // var lets Java infer PixelReader's type. (int) casts the Image's numeric dimensions to integers.
    // EXPECTED: return one value per pixel in row order, suitable for assertArrayEquals below.
    private static int[] imagePixels(Image image) {
        int width = (int) image.getWidth();
        int[] pixels = new int[width * (int) image.getHeight()];
        var reader = image.getPixelReader();
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = reader.getArgb(x, y);
            }
        }
        return pixels;
    }

    //----------TEST 2: OLD AND NEW PLAYERS MUST PRODUCE THE SAME PICTURES----------
    // TEST PURPOSE: threading must not change effect order, repeated effects or the displayed colours.
    // ARRANGE/ACT: run a 36-frame clip in the OLD model, save its pixels, then run the NEW model.
    // EXPECTED: equal frame counts and identical pixel arrays for each corresponding displayed frame.
    // This is the picture-correctness comparison; the later HD benchmark checks speed separately.
    @Test void displayedPixelsMatchTheSuppliedPlayerWithOrderedRepeatedEffects() throws Exception {
        Path file = directory.resolve("pixel-reference.mp4");
        GeneratedVideo.create(file, 160, 90, 36, false);
        List<FrameProcessor> effects = new ArrayList<>();
        for (char flag : "nssnfwyvdjmbp".toCharArray()) {
            effects.add(FrameProcessorOption.fromShort(flag).createProcessor());
        }
        List<int[]> expected;
        try (Player baseline = new Player(false, effects, true)) {
            baseline.play(file);
            await(() -> baseline.status.equals("Playback finished") || baseline.status.startsWith("Error:"),
                    10, "Baseline pixel playback stalled");
            assertEquals("Playback finished", baseline.status);
            assertEquals(36, baseline.frames.get());
            expected = List.copyOf(baseline.presentedPixels);
        }
        // Reuse the SAME effect instances so randomized effects keep their
        // original instance-index seeds, rather than introducing different art.
        try (Player threaded = new Player(false, effects)) {
            threaded.play(file);
            await(() -> threaded.status.equals("Playback finished") || threaded.status.startsWith("Error:"),
                    10, "Threaded pixel playback stalled");
            assertEquals("Playback finished", threaded.status);
            assertEquals(expected.size(), threaded.presentedPixels.size());
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), threaded.presentedPixels.get(i),
                        "Rendered pixels differ from the supplied player at frame " + i);
            }
        }
    }

    //----------TEST 3: PAUSE, SEEK, RESIZE AND QUICK SESSION REPLACEMENT----------
    // TEST PURPOSE: background processing must still cooperate with playback controls and cleanup.
    // ACT: pause, request a five-second seek/resize, rapidly restart/seek, resume, then stop.
    // EXPECTED: no advancing while paused, a seek preview, resumed frames, no error/stall, then "Stopped".
    // This invokes model methods, not real button/key events; it does not assert the exact seek timestamp.
    @Test void pauseSeekResizeAndRapidReplacementStayResponsive() throws Exception {
        Path file = directory.resolve("controls.mp4");
        GeneratedVideo.create(file, 160, 90, 192, false);
        try (Player player = new Player(false, List.of(new FrameMottler(), new FrameNumberer()))) {
            player.play(file);
            await(() -> player.frames.get() >= 3, 10, "No initial frames");
            fx(() -> { player.model.togglePause(); return null; });
            int pausedCount = player.frames.get();
            // Only the test thread sleeps; the UI is free to run and would reveal unwanted extra frames.
            Thread.sleep(200);
            assertEquals(pausedCount, player.frames.get(), "A paused player advanced");
            fx(() -> { player.model.forwardFiveSeconds(); player.stage.setWidth(620); player.stage.setHeight(410); return null; });
            await(() -> player.frames.get() > pausedCount || player.status.startsWith("Error:"), 10, "Paused seek had no preview");
            assertFalse(player.status.startsWith("Error:"), player.status);
            int seekCount = player.frames.get();
            fx(() -> {
                // New generations must cancel/clean up old native resources
                // without joining their workers on the JavaFX application thread.
                for (int i = 0; i < 8; i++) { player.model.startOver(); player.model.forwardFiveSeconds(); }
                player.model.startOver();
                return null;
            });
            await(() -> player.frames.get() > seekCount || player.status.startsWith("Error:"), 10, "Replacement seek stalled");
            assertFalse(player.status.startsWith("Error:"), player.status);
            fx(() -> { player.model.togglePause(); return null; });
            int resumedCount = player.frames.get();
            await(() -> player.frames.get() > resumedCount + 2, 10, "Resume stalled");
            fx(() -> { player.model.stopPlayback(); return null; });
            assertEquals("Stopped", player.status);
        }
    }

    //----------TEST 4: COMPARE REAL OLD/NEW PLAYERS UNDER THE SAME HD LOAD----------
    // TEST PURPOSE: retain an automated performance comparison with the supplied single-threaded player.
    // ARRANGE/ACT: same 72-frame 720p clip and sixteen Bleeders, three trials per player, with resizing.
    // EXPECTED: runLoadedPlayer checks completion/counts/UI waits; this method reports the measured ratio.
    // It does NOT require a fixed speedup, since hardware and load vary. This is not a TITAN score.
    @Test void compareActualSequentialAndThreadedHdPlaybackUnderIdenticalLoad() throws Exception {
        Path file = directory.resolve("hd-load.mp4");
        GeneratedVideo.create(file, 1280, 720, 72, false);
        long[] reference = new long[3], threaded = new long[3];
        for (int trial = 0; trial < 3; trial++) {
            // Alternate which player runs first; do not compare different media,
            // resolutions, effect counts or hardware. Both show a real FX view.
            if (trial % 2 == 0) {
                reference[trial] = runLoadedPlayer(file, true);
                threaded[trial] = runLoadedPlayer(file, false);
            } else {
                threaded[trial] = runLoadedPlayer(file, false);
                reference[trial] = runLoadedPlayer(file, true);
            }
        }
        // Sort elapsed times: index 1 is the middle (median) of three, reducing one outlier's influence.
        // 72 displayed frames span 71 first-to-last intervals. 1e9 converts nanoseconds to seconds.
        // Speedup = old elapsed time / new elapsed time; a result above 1 means the new player was faster.
        Arrays.sort(reference);
        Arrays.sort(threaded);
        System.out.printf("Whole-player 720p playback: 72 frames, sixteen bleed effects; "
                        + "baseline %.2f fps, threaded %.2f fps, median playback speedup %.2fx%n",
                71e9 / reference[1], 71e9 / threaded[1], reference[1] / (double) threaded[1]);
        // Scheduling, graphics and machine load vary. Report measured speed;
        // correctness/frame counts are assertions, not a promised speed ratio.
    }

    //----------TEST 5: PAUSED STARTUP WITH SOUND BEFORE THE FIRST PICTURE----------
    // TEST PURPOSE: catch a bounded-queue stall when a paused UI is not consuming queued audio.
    // ARRANGE/ACT: generate eight seconds of audio lead-in and request pause immediately on play.
    // EXPECTED: one preview picture arrives within five seconds, without playing the entire video or reporting an error.
    @Test void pausingBeforeTheFirstImageDoesNotDeadlockBehindLeadingAudio() throws Exception {
        Path file = directory.resolve("audio-before-video.mp4");
        GeneratedVideo.createWithAudioLeadIn(file, 8);
        try (Player player = new Player(true, List.of(new FrameNumberer()))) {
            fx(() -> { player.model.play(file.toFile()); player.model.togglePause(); return null; });
            await(() -> player.frames.get() > 0 || player.status.startsWith("Error:"),
                    5, "Paused preview is blocked behind a full audio queue");
            assertFalse(player.status.startsWith("Error:"), player.status);
            assertEquals(1, player.frames.get(), "Preview must not play the entire video");
        }
    }

    //----------TEST 6: LONGER PLAYBACK AND APPROXIMATE REAL-TIME DURATION----------
    // TEST PURPOSE: exercise sustained playback past the frame-720 region used by TITAN.
    // ARRANGE/ACT: generate 744 frames at 24 fps (about 31 seconds), play and wait for completion.
    // EXPECTED: 744 presentation callbacks, no playback error, completion within 40 seconds and not before 29 seconds.
    // This is a local count/timing check, not TITAN's screen capture or an exact 720p-pixel test.
    @Test void actualPlayerReachesFrame720AtRealTimeAndThenFinishes() throws Exception {
        Path file = directory.resolve("thirty-one-seconds.mp4");
        GeneratedVideo.create(file, 96, 54, 744, false);
        try (Player player = new Player(false, List.of(new FrameFlickerer(), new FrameNumberer()))) {
            long started = System.nanoTime();
            player.play(file);
            await(() -> player.status.equals("Playback finished") || player.status.startsWith("Error:"),
                    40, "Player did not reach frame 720 and EOF within 40 seconds");
            assertEquals("Playback finished", player.status);
            assertEquals(744, player.frames.get());
            double seconds = (System.nanoTime() - started) / 1e9;
            assertTrue(seconds >= 29, "Video ran too fast: " + seconds + "s");
            System.out.printf("Real-time 744-frame playback completed in %.2f seconds%n", seconds);
        }
    }

    //----------TEST 7: MUTE / UNMUTE WITHOUT STOPPING VIDEO----------
    // TEST PURPOSE: changing the audio setting must not stall playback or prevent completion.
    // ARRANGE/ACT: play a clip with generated sound, disable sound, then enable it again.
    // EXPECTED: audio flags change, video continues while muted, and playback finishes without error.
    // LIMIT: assertions do not listen to the speakers or prove audible sound quality/continuity.
    @Test void audioCanBeMutedAndEnabledAgainWhilePlaybackContinues() throws Exception {
        Path file = directory.resolve("audio-controls.mp4");
        GeneratedVideo.create(file, 160, 90, 36, true);
        try (Player player = new Player(true, List.of(new FrameNumberer()))) {
            player.play(file);
            await(() -> player.frames.get() >= 3 || player.status.startsWith("Error:"), 10, "Audio playback stalled");
            assertFalse(player.status.startsWith("Error:"), player.status);
            fx(() -> { player.model.toggleAudioOutput(); assertFalse(player.model.isAudioOutputEnabled()); return null; });
            int mutedFrames = player.frames.get();
            await(() -> player.frames.get() > mutedFrames + 2 || player.status.startsWith("Error:"), 10, "Muting stalled video");
            assertFalse(player.status.startsWith("Error:"), player.status);
            fx(() -> { player.model.toggleAudioOutput(); assertTrue(player.model.isAudioOutputEnabled()); return null; });
            await(() -> player.status.equals("Playback finished") || player.status.startsWith("Error:"), 10, "Audio resume stalled");
            assertEquals("Playback finished", player.status);
        }
    }

    //----------TEST 8: MUTED AUDIO LEAD-IN MUST NOT ADD AN EXTRA EOF WAIT----------
    // TEST PURPOSE: when leading sound is skipped, the video clock must not count that skipped time twice.
    // ARRANGE/ACT: create an eight-second audio lead-in, then play with sound disabled.
    // EXPECTED: all 36 video frames finish within five seconds, not after an unnecessary extra eight-second wait.
    @Test void mutedPlaybackDoesNotWaitForAnAudioOnlyLeadInAfterItsLastImage() throws Exception {
        Path file = directory.resolve("muted-audio-lead-in.mp4");
        GeneratedVideo.createWithAudioLeadIn(file, 8);
        try (Player player = new Player(false, List.of(new FrameNumberer()))) {
            player.play(file);
            await(() -> player.status.equals("Playback finished") || player.status.startsWith("Error:"),
                    5, "Muted video waited for the skipped audio lead-in after EOF");
            assertEquals("Playback finished", player.status);
            assertEquals(36, player.frames.get());
        }
    }

    //----------BENCHMARK HELPER: PLAY ONE VERSION AND SAMPLE UI RESPONSIVENESS----------
    // reference=true chooses the OLD model; false chooses the NEW one. Use the same sixteen costly effects.
    // Resize requests run on JavaFX; the test measures the wait until each request finishes, then sleeps briefly.
    // EXPECTED: completion, 72 presentation callbacks and no sampled UI-request wait of two seconds or more.
    // The two-second bound is deliberately loose, not a promise of 24 fps or a universal latency limit.
    // Return first-to-last presentation duration; startup before the first picture is not measured here.
    private long runLoadedPlayer(Path file, boolean reference) throws Exception {
        List<FrameProcessor> effects = new ArrayList<>();
        for (int i = 0; i < 16; i++) effects.add(new FrameBleeder());
        try (Player player = new Player(false, effects, reference)) {
            player.play(file);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            long maxUiDelay = 0;
            int tick = 0;
            // Stop waiting on completion, an error, or the deadline. Assertions below decide whether the run passed.
            while (!player.status.equals("Playback finished") && !player.status.startsWith("Error:")
                    && System.nanoTime() < deadline) {
                // tick++ advances the counter; % 2 alternates 0/1 so the window changes between two sizes.
                int size = tick++ % 2;
                long posted = System.nanoTime();
                fx(() -> { player.stage.setWidth(500 + size * 60); player.stage.setHeight(330 + size * 40); return null; });
                maxUiDelay = Math.max(maxUiDelay, System.nanoTime() - posted);
                Thread.sleep(150);
            }
            assertEquals("Playback finished", player.status);
            assertEquals(72, player.frames.get(), "Dropped/duplicated presentation frames");
            assertTrue(maxUiDelay < TimeUnit.SECONDS.toNanos(2), "UI stalled under load");
            System.out.printf("Loaded playback baseline=%s, maximum UI event latency %.1f ms%n",
                    reference, maxUiDelay / 1e6);
            return player.lastPresentedNs - player.firstPresentedNs;
        }
    }
}
