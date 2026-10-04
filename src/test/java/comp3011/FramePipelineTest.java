/* Touch Chanchoronaiy (a1978787) */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TEST PURPOSE - Check pipeline behaviour, not whether an image merely looks plausible.
 * Pixel equality/order detect altered effects; latches coordinate an overlap test
 * without relying on a lucky thread schedule; cancellation tests exercise full queues.
 * @ParameterizedTest reruns one method for each supplied flag sequence.
 * COVERS - The new FramePipeline, effect registry, frame metadata and all eleven
 * supplied effects together, including the modified Mottler cache reset.
 * One combined pixel test can detect failures across several effect classes;
 * it is not necessary to create a separate test file for each effect.
 */
class FramePipelineTest {
    private Frame frame(int number) {
        Frame frame = new Frame(96, 54, Frame.DEPTH_UBYTE, 3);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < pixels.capacity(); i++) pixels.put(i, (byte) (i * 31 + number));
        return frame;
    }
    private byte[] pixels(Frame frame) {
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        buffer.clear();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    private InfoVideo metadata(Frame sample) {
        return new InfoVideo("generated-test", 80, sample.imageWidth, sample.imageHeight,
                sample.imageDepth, sample.imageChannels, sample.imageStride, 24, 24, 41667, 0);
    }
    private FramePipeline.Work awaitOutput(FramePipeline pipeline) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertNull(pipeline.failure());
            FramePipeline.Work result = pipeline.poll();
            if (result != null) return result;
            Thread.sleep(1);
        }
        fail("No output: possible stalled stage");
        return null;
    }

    @Test void allSuppliedEffectsMatchSequentialPixelsAndFrameOrder() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            // Reuse the SAME instances for reference then pipeline, preserving
            // each supplied effect's instance-index seed. New instances would
            // intentionally produce a different scratch/dust pattern.
            List<FrameProcessor> effects = new ArrayList<>();
            for (FrameProcessorOption option : FrameProcessorOption.values()) effects.add(option.createProcessor());
            try (Frame sample = frame(0)) {
                for (FrameProcessor effect : effects) effect.initialise(metadata(sample));
            }
            List<byte[]> expected = new ArrayList<>();
            for (int n = 0; n < 12; n++) {
                try (Frame input = frame(n)) {
                    for (FrameProcessor effect : effects) effect.process(input, new InfoFrame(n, n * 41667L));
                    expected.add(pixels(input));
                }
            }
            try (FramePipeline pipeline = new FramePipeline(effects, 4)) {
                CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
                    try {
                        for (int n = 0; n < 12; n++) pipeline.submit(new FramePipeline.Work(frame(n), new InfoFrame(n, n * 41667L), 0, false));
                        pipeline.submit(FramePipeline.Work.endMarker());
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                });
                for (int n = 0; n < 12; n++) {
                    FramePipeline.Work output = awaitOutput(pipeline);
                    try {
                        assertEquals(n, output.info().frameNumber());
                        assertArrayEquals(expected.get(n), pixels(output.frame()));
                    } finally { output.release(); }
                }
                assertTrue(awaitOutput(pipeline).end());
                producer.get(5, TimeUnit.SECONDS);
                pipeline.awaitWorkers();
            }
        });
    }

    @Test void differentStagesOverlapButNeverRunTheSameEffectConcurrently() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch stageTwoEntered = new CountDownLatch(1);
            CountDownLatch stageOneSecondFrameEntered = new CountDownLatch(1);
            FrameProcessor first = new FrameProcessor() {
                @Override public void process(Frame frame, InfoFrame info) throws Exception {
                    if (info.frameNumber() == 1) {
                        stageOneSecondFrameEntered.countDown();
                        assertTrue(stageTwoEntered.await(5, TimeUnit.SECONDS));
                    }
                }
            };
            FrameProcessor second = new FrameProcessor() {
                @Override public void process(Frame frame, InfoFrame info) throws Exception {
                    if (info.frameNumber() == 0) {
                        stageTwoEntered.countDown();
                        assertTrue(stageOneSecondFrameEntered.await(5, TimeUnit.SECONDS));
                    }
                }
            };
            try (FramePipeline pipeline = new FramePipeline(List.of(first, second), 2)) {
                pipeline.submit(new FramePipeline.Work(frame(0), new InfoFrame(0, 0), 0, false));
                pipeline.submit(new FramePipeline.Work(frame(1), new InfoFrame(1, 41667), 0, false));
                for (int n = 0; n < 2; n++) awaitOutput(pipeline).release();
                assertNull(pipeline.failure());
            }
        });
    }

    // Ed staff require pixel equivalence, not merely an image that looks right.
    // These positions cross the supplied effects' scratch/mottle cache boundaries.
    @ParameterizedTest
    @ValueSource(strings = {"", "n", "ffn", "mnn", "njn", "wyw", "nssnfwyvdjmbp", "bbmmffn"})
    void repeatedAndReorderedEffectsMatchAtCacheBoundaries(String flags) throws Exception {
        List<FrameProcessor> effects = new ArrayList<>();
        for (char flag : flags.toCharArray()) {
            effects.add(FrameProcessorOption.fromShort(flag).createProcessor());
        }
        int[] numbers = {0, 1, 47, 48, 71, 72, 73, 130, 143, 144, 145, 720};
        InfoVideo info;
        try (Frame sample = frame(0)) { info = metadata(sample); }
        for (FrameProcessor effect : effects) effect.initialise(info);
        List<byte[]> expected = new ArrayList<>();
        for (int n : numbers) {
            try (Frame input = frame(n)) {
                for (FrameProcessor effect : effects) effect.process(input, new InfoFrame(n, n * 41667L));
                expected.add(pixels(input));
            }
        }
        for (int workers : new int[]{1, 4, 7}) {
            // Reuse the effect objects to preserve instance-index random seeds,
            // but reset their media-specific state as playback/rewind does.
            for (FrameProcessor effect : effects) effect.initialise(info);
            try (FramePipeline pipeline = new FramePipeline(effects, workers)) {
                CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
                    try {
                        for (int n : numbers) pipeline.submit(new FramePipeline.Work(
                                frame(n), new InfoFrame(n, n * 41667L), 0, false));
                        pipeline.submit(FramePipeline.Work.endMarker());
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                });
                for (int i = 0; i < numbers.length; i++) {
                    FramePipeline.Work work = awaitOutput(pipeline);
                    try {
                        assertEquals(numbers[i], work.info().frameNumber());
                        assertArrayEquals(expected.get(i), pixels(work.frame()),
                                "flags=" + flags + ", frame=" + numbers[i] + ", workers=" + workers);
                    } finally { work.release(); }
                }
                assertTrue(awaitOutput(pipeline).end());
                producer.get(5, TimeUnit.SECONDS);
                pipeline.awaitWorkers();
            }
        }
    }

    @Test void cancellationReleasesFullQueuesAndWorkerFailureIsReported() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            FramePipeline full = new FramePipeline(List.of(), 1);
            CountDownLatch buffersFilled = new CountDownLatch(1);
            CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
                try {
                    for (int n = 0; n < 100; n++) {
                        full.submit(new FramePipeline.Work(frame(n), new InfoFrame(n, n), 0, false));
                        if (n == 4) buffersFilled.countDown();
                    }
                } catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            assertTrue(buffersFilled.await(5, TimeUnit.SECONDS));
            full.cancel();
            producer.get(5, TimeUnit.SECONDS); // Stop the only producer BEFORE draining buffers.
            full.close();
            FrameProcessor broken = new FrameProcessor() {
                @Override public void process(Frame frame, InfoFrame info) { throw new IllegalStateException("test failure"); }
            };
            try (FramePipeline pipeline = new FramePipeline(List.of(broken), 1)) {
                pipeline.submit(new FramePipeline.Work(frame(0), new InfoFrame(0, 0), 0, false));
                pipeline.awaitWorkers();
                assertEquals("test failure", pipeline.failure().getMessage());
            }
        });
    }
}
