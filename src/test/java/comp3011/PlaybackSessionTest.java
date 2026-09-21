/* Touch Chanchoronaiy (a1978787) */
// Test implementation and learning notes developed with Codex assistance.
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
 *  PURPOSE - Exercise a real native decoder with generated media, seeking and EOF.
 * COVERS - The new PlaybackSession working with FramePipeline, effects, InfoFrame,
 * InfoVideo and AudioPlayer copying. Checks timestamps, frame counts and BGR layout.
 * Audio bytes are discarded here; these checks do not establish audible continuity.
 */
class PlaybackSessionTest {
    // GeneratedVideo supplies files here. These tests run a native decoder and effect pipeline, not a GUI.
    // The test thread drains output while the executor runs decoding. This simulates a consumer without JavaFX.
    @TempDir Path directory;

//----------TEST 1: DECODE A SHORT CLIP, THEN SEEK IN A NEW SESSION----------
    // A: decode 36 frames with sound, numbering and black-and-white
    // EXPECTED: EOF, 36 frames, non-decreasing timestamps and equal sampled B/G/R bytes after black-and-white processing.
    // B: create another session starting at one second
    // EXPECTED: frame at/after 950,000 microseconds.
    // The seek check uses tolerance, not an assertion of the exact selected image/timestamp.
    @Test void realDecoderProducesOwnedFramesInOrderAndTerminatesAfterCancellation() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            Path file = directory.resolve("synthetic.mp4");
            GeneratedVideo.create(file, 160, 90, 36, true);

            // ExecutorService runs submitted work; this single-thread executor gives decoding one serial owner
            // try-with-resources closes the executor at the end. This waiting/cleanup is not on a UI thread
            try (ExecutorService decoder = Executors.newSingleThreadExecutor()) {
                PlaybackSession session = new PlaybackSession(file.toFile(), 0,
                        List.of(new FrameNumberer(), new FrameBlackAndWhiter()), true, 2);

                // Future<?> tracks Runnable completion, not the decoded pictures. They arrive via pipeline queues.
                Future<?> task = decoder.submit(session);

                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    int count = 0;
                    long previousTimestamp = -1;
                    boolean end = false;

                    //null pipeline/output means startup or processing is not ready yet, not end of file.\
                    //nanoTime supplies an elapsed-time deadline so a stalled decoder does not wait forever.
                    while (!end && System.nanoTime() < deadline) {
                        assertNull(session.error);
                        session.audio.clear(); // Test consumer deliberately discards sound, not video.

                        // ?: chooses null until a pipeline exists; poll() then reads its final queue without blocking
                        FramePipeline.Work work = session.pipeline == null ? null : session.pipeline.poll();

                        if (work == null) {
                            Thread.sleep(1);
                            continue;
                        }

                        //end marker has no image. continue skips image assertions for that message
                        if (work.end()) {
                            end = true;
                            continue;
                        }

                        try {
                            assertTrue(work.info().timestampUs() >= previousTimestamp);
                            previousTimestamp = work.info().timestampUs();
                            ByteBuffer pixels = (ByteBuffer) work.frame().image[0];
                            // Black-and-white was applied after numbering: B = G = R.
                            assertEquals(pixels.get(0), pixels.get(1));
                            assertEquals(pixels.get(1), pixels.get(2));
                            count++;
                        }

                        finally {
                            // Once received, this test owns the native frame and must release it, even on assertion failure.
                            work.release();
                        }
                    }

                    assertTrue(end, "EOF marker missing");
                    assertEquals(36, count, "A frame was lost or repeated");
                    task.get(5, TimeUnit.SECONDS);
                    assertTrue(session.metadata.hasAudio());
                }

                // Cleanup order matters: ask decoding to stop, wait for it, THEN dispose its owned resources.
                // get(...,5,...) propagates failure/timeout rather than hiding it; finally also runs on an assertion failure.
                finally {
                    session.cancel();
                    task.get(5, TimeUnit.SECONDS);
                    session.dispose();
                }

                //----------SECOND PART: SEEK WITHOUT MUTATING AN ACTIVE GRABBER----------
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

                    try {
                        assertTrue(work.info().timestampUs() >= 950_000,
                            "Actual seek timestamp: " + work.info().timestampUs());
                    }

                    finally {
                        work.release();
                    }
                }

                finally {
                    seek.cancel();
                    seekTask.get(5, TimeUnit.SECONDS);
                    seek.dispose();
                }
            }
        });
    }

//----------TEST 2: LONGER DECODING, FRAME ORDER AND PADDED BGR LAYOUT----------
    // decode all 744 frames and apply flicker/mottle/number effects, while draining the output queues.
    // EXPECTED: frame numbers 0..743, strictly increasing timestamps, three colour channels and an EOF marker.
    // T fast decoder test, NOT real-time presentation; DesktopPlaybackIT checks actual playback timing.
    @Test void decoderReachesFrame720WithoutLosingOrRepeatingFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(40), () -> {
            Path file = directory.resolve("long-synthetic.mp4");
            GeneratedVideo.create(file, 96, 54, 744, true);
            // Chatgpt help: TITAN Part 2 watches for frame 720. This fast, non-real-time decoder test verifies the entire 31-second stream reaches EOF.
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
                        // Make room in the bounded audio queue without trying to play sound in this non-GUI test.
                        session.audio.clear();
                        FramePipeline.Work work = session.pipeline == null ? null : session.pipeline.poll();

                        if (work == null) {
                            Thread.sleep(1);
                            continue;
                        }

                        if (work.end()) {
                            end = true;
                            continue;
                        }

                        try {
                            assertEquals(count++, work.info().frameNumber()); // FFmpeg numbers from zero.
                            // Padding affects row stride, not the number of B/G/R colour components.
                            assertEquals(3, work.frame().imageChannels, "Row padding is not a fourth BGR component");
                            assertTrue(work.info().timestampUs() > previous);
                            previous = work.info().timestampUs();
                        }

                        finally {
                            work.release();
                        }
                    }

                    assertTrue(end, "Decoder/pipeline did not reach EOF");
                    assertEquals(744, count);
                    task.get(5, TimeUnit.SECONDS);
                }

                finally {
                    session.cancel();
                    task.get(5, TimeUnit.SECONDS);
                    session.dispose();
                }
            }
        });
    }
}
