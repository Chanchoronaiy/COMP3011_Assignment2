/* Touch Chanchoronaiy (a1978787) */
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
 * Verification opens temporary windows and needs a graphical desktop and sound
 * output. Plain mvn package still builds without opening those test windows.
 *  COVERS - The modified model with the new session/pipeline, supplied view,
 * effects and audio support: presentation pixels/counts, controls, EOF and cleanup.
 *  LIMIT - Calls model controls directly rather than clicking buttons or sending
 * keys through VideoPlayerController. It does not launch VideoPlayerApp or its
 * launcher, or verify physical monitor selection. Those need separate/manual checks.
 * 
 * @BeforeAll starts the toolkit once; @AfterEach checks worker termination;
 * @AfterAll exits JavaFX. fx(...) posts a Callable to the UI thread and waits
 * from the test thread; a worker must not directly mutate a displayed view.
 * 
 */
class DesktopPlaybackIT {
    @TempDir Path directory;

    @BeforeAll static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }
    @AfterAll static void stopJavaFx() { Platform.exit(); }
    @AfterEach void playbackWorkersTerminateAfterThePlayerCloses() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                        && (thread.getName().equals("video-decoder") || thread.getName().startsWith("video-effects-"))),
                5, "A playback worker survived shutdown");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition, long seconds, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), message);
    }
    private final class Player implements AutoCloseable {
        final AtomicInteger frames = new AtomicInteger();
        final List<int[]> presentedPixels = new ArrayList<>();
        volatile String status = "";
        volatile Image lastImage;
        volatile long firstPresentedNs, lastPresentedNs;
        Stage stage;
        VideoPlayerModel model;
        OriginalVideoPlayerModel original;
        Player(boolean audio, List<FrameProcessor> effects) throws Exception {
            this(audio, effects, false);
        }
        Player(boolean audio, List<FrameProcessor> effects, boolean reference) throws Exception {
            fx(() -> {
                VideoPlayerView view = new VideoPlayerView();
                stage = new Stage();
                stage.setTitle("Video Player");
                stage.setScene(new Scene(view.getRoot(), 500, 330));
                stage.show();
                java.util.function.Consumer<Image> present = image -> {
                            assertTrue(Platform.isFxApplicationThread());
                            view.setImage(image);
                            if (image != null) {
                                long now = System.nanoTime();
                                if (frames.get() == 0) firstPresentedNs = now;
                                lastPresentedNs = now;
                                lastImage = image;
                                if (image.getWidth() <= 256) presentedPixels.add(imagePixels(image));
                                frames.incrementAndGet();
                            }
                        };
                java.util.function.Consumer<String> report = text -> { status = text; view.setStatus(text); };
                if (reference) original = new OriginalVideoPlayerModel(audio, effects, view::setVideoDisplaySize,
                        present, report, (playing, paused) -> {}, enabled -> {});
                else model = new VideoPlayerModel(audio, effects, view::setVideoDisplaySize,
                        present, report, (playing, paused) -> {}, enabled -> {});
                return null;
            });
        }
        void play(Path file) throws Exception {
            fx(() -> { if (model != null) model.play(file.toFile()); else original.play(file.toFile()); return null; });
        }
        @Override public void close() throws Exception {
            fx(() -> {
                if (model != null) { model.shutdown(); model.shutdown(); }
                else original.shutdown();
                stage.close(); return null;
            });
        }
    }

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

    @Test void pauseSeekResizeAndRapidReplacementStayResponsive() throws Exception {
        Path file = directory.resolve("controls.mp4");
        GeneratedVideo.create(file, 160, 90, 192, false);
        try (Player player = new Player(false, List.of(new FrameMottler(), new FrameNumberer()))) {
            player.play(file);
            await(() -> player.frames.get() >= 3, 10, "No initial frames");
            fx(() -> { player.model.togglePause(); return null; });
            int pausedCount = player.frames.get();
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
        Arrays.sort(reference);
        Arrays.sort(threaded);
        System.out.printf("Whole-player 720p playback: 72 frames, sixteen bleed effects; "
                        + "baseline %.2f fps, threaded %.2f fps, median playback speedup %.2fx%n",
                71e9 / reference[1], 71e9 / threaded[1], reference[1] / (double) threaded[1]);
        // Scheduling, graphics and machine load vary. Report measured speed;
        // correctness/frame counts are assertions, not a promised speed ratio.
    }

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

    private long runLoadedPlayer(Path file, boolean reference) throws Exception {
        List<FrameProcessor> effects = new ArrayList<>();
        for (int i = 0; i < 16; i++) effects.add(new FrameBleeder());
        try (Player player = new Player(false, effects, reference)) {
            player.play(file);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            long maxUiDelay = 0;
            int tick = 0;
            while (!player.status.equals("Playback finished") && !player.status.startsWith("Error:")
                    && System.nanoTime() < deadline) {
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
