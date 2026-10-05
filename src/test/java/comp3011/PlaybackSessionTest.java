/* Touch Chanchoronaiy (a1978787) */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TEST PURPOSE - Exercise a real native decoder with generated media, seeking and EOF.
 * Future.get waits for background completion with a deadline. finally cancels and
 * disposes sessions even when an assertion fails, so tests do not leak worker threads.
 * COVERS - The new PlaybackSession working with FramePipeline, effects, InfoFrame,
 * InfoVideo and AudioPlayer copying. Checks timestamps, frame counts and BGR layout.
 * Audio bytes are discarded here; these checks do not establish audible continuity.
 */
class PlaybackSessionTest {
    @TempDir Path directory;

    @Test void realDecoderProducesOwnedFramesInOrderAndTerminatesAfterCancellation() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            Path file = directory.resolve("synthetic.mp4");
            GeneratedVideo.create(file, 160, 90, 36, true);
            try (ExecutorService decoder = Executors.newSingleThreadExecutor()) {
                PlaybackSession session = new PlaybackSession(file.toFile(), 0,
                        List.of(new FrameNumberer(), new FrameBlackAndWhiter()), true, 2);
                Future<?> task = decoder.submit(session);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    int count = 0;
                    long previousTimestamp = -1;
                    boolean end = false;
                    while (!end && System.nanoTime() < deadline) {
                        assertNull(session.error);
                        session.audio.clear(); // Test consumer deliberately discards sound, not video.
                        FramePipeline.Work work = session.pipeline == null ? null : session.pipeline.poll();
                        if (work == null) { Thread.sleep(1); continue; }
                        if (work.end()) { end = true; continue; }
                        try {
                            assertTrue(work.info().timestampUs() >= previousTimestamp);
                            previousTimestamp = work.info().timestampUs();
                            ByteBuffer pixels = (ByteBuffer) work.frame().image[0];
                            // Black-and-white was applied after numbering: B = G = R.
                            assertEquals(pixels.get(0), pixels.get(1));
                            assertEquals(pixels.get(1), pixels.get(2));
                            count++;
                        } finally { work.release(); }
                    }
                    assertTrue(end, "EOF marker missing");
                    assertEquals(36, count, "A frame was lost or repeated");
                    task.get(5, TimeUnit.SECONDS);
                    assertTrue(session.metadata.hasAudio());
                } finally { session.cancel(); task.get(5, TimeUnit.SECONDS); session.dispose(); }

                // Seek to one second using another native decoder session.
                PlaybackSession seek = new PlaybackSession(file.toFile(), 1_000_000, List.of(), false, 1);
                Future<?> seekTask = decoder.submit(seek);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    FramePipeline.Work work = null;
                    while (work == null && System.nanoTime() < deadline) {
                        assertNull(seek.error);
                        if (seek.pipeline != null) work = seek.pipeline.poll();
                        if (work == null) Thread.sleep(1);
                    }
                    assertNotNull(work);
                    try { assertTrue(work.info().timestampUs() >= 950_000,
                            "Actual seek timestamp: " + work.info().timestampUs()); }
                    finally { work.release(); }
                } finally { seek.cancel(); seekTask.get(5, TimeUnit.SECONDS); seek.dispose(); }
            }
        });
    }

    @Test void decoderReachesFrame720WithoutLosingOrRepeatingFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(40), () -> {
            Path file = directory.resolve("long-synthetic.mp4");
            GeneratedVideo.create(file, 96, 54, 744, true);
            // TITAN Part 2 watches for frame 720. This fast, non-real-time
            // decoder test verifies the entire 31-second stream reaches EOF.
            try (ExecutorService decoder = Executors.newSingleThreadExecutor()) {
                PlaybackSession session = new PlaybackSession(file.toFile(), 0,
                        List.of(new FrameFlickerer(), new FrameMottler(), new FrameNumberer()), true, 3);
                Future<?> task = decoder.submit(session);
                try {
                    int count = 0;
                    long previous = -1;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    boolean end = false;
                    while (!end && System.nanoTime() < deadline) {
                        assertNull(session.error);
                        session.audio.clear();
                        FramePipeline.Work work = session.pipeline == null ? null : session.pipeline.poll();
                        if (work == null) { Thread.sleep(1); continue; }
                        if (work.end()) { end = true; continue; }
                        try {
                            assertEquals(count++, work.info().frameNumber()); // FFmpeg numbers from zero.
                            assertEquals(3, work.frame().imageChannels, "Row padding is not a fourth BGR component");
                            assertTrue(work.info().timestampUs() > previous);
                            previous = work.info().timestampUs();
                        } finally { work.release(); }
                    }
                    assertTrue(end, "Decoder/pipeline did not reach EOF");
                    assertEquals(744, count);
                    task.get(5, TimeUnit.SECONDS);
                } finally { session.cancel(); task.get(5, TimeUnit.SECONDS); session.dispose(); }
            }
        });
    }
}
