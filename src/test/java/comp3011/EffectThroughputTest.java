/* Test generated with Codex for Touch Chanchoronaiy (a1978787); review before submission. */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Component load comparison, not a claim that the entire GUI/audio player has
 * been benchmarked. Both paths use the unchanged supplied Bleeder implementation
 * and identical 720p input. The reference applies effects serially, as the
 * starter does. Full-player comparisons are still listed in README.
 */
class EffectThroughputTest {
    private static final int FRAMES = 32;

    @Test void reportWarmedSequentialAndPipelineThroughput() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
            List<FrameProcessor> effects = List.of(new FrameBleeder(), new FrameBleeder(),
                    new FrameBleeder(), new FrameBleeder());
            try (Frame input = new Frame(1280, 720, Frame.DEPTH_UBYTE, 3)) {
                ByteBuffer pixels = (ByteBuffer) input.image[0];
                byte[] pattern = new byte[pixels.capacity()];
                for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 31);
                pixels.put(pattern).clear();
                InfoVideo info = new InfoVideo("throughput", FRAMES, 1280, 720,
                        input.imageDepth, 3, input.imageStride, 24, 24, 41667, 0);
                for (FrameProcessor effect : effects) effect.initialise(info);
                sequential(input, effects); // Warm up JIT/native allocation before measured trials.
                parallel(input, effects, workers);
                long[] serial = new long[3], pipeline = new long[3];
                for (int i = 0; i < 3; i++) {
                    // Alternate order to reduce one-sided warm-up advantage.
                    if (i % 2 == 0) {
                        serial[i] = sequential(input, effects);
                        pipeline[i] = parallel(input, effects, workers);
                    } else {
                        pipeline[i] = parallel(input, effects, workers);
                        serial[i] = sequential(input, effects);
                    }
                }
                Arrays.sort(serial);
                Arrays.sort(pipeline);
                System.out.printf("720p component benchmark: %d frames, four bleed effects, %d workers; "
                        + "sequential %.1f fps, pipeline %.1f fps, median speedup %.2fx%n",
                        FRAMES, workers, FRAMES * 1e9 / serial[1], FRAMES * 1e9 / pipeline[1],
                        serial[1] / (double) pipeline[1]);
                // Speed depends on the host/load. Record a comparison, rather
                // than turn ordinary machine variability into a flaky failure.
            }
        });
    }
    private long sequential(Frame input, List<FrameProcessor> effects) throws Exception {
        long start = System.nanoTime();
        for (int n = 0; n < FRAMES; n++) {
            try (Frame frame = input.clone()) {
                for (FrameProcessor effect : effects) effect.process(frame, new InfoFrame(n, n * 41667L));
            }
        }
        return System.nanoTime() - start;
    }
    private long parallel(Frame input, List<FrameProcessor> effects, int workers) throws Exception {
        long start = System.nanoTime();
        try (FramePipeline pipeline = new FramePipeline(effects, workers)) {
            CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
                try {
                    for (int n = 0; n < FRAMES; n++) pipeline.submit(
                            new FramePipeline.Work(input.clone(), new InfoFrame(n, n * 41667L), 0, false));
                    pipeline.submit(FramePipeline.Work.endMarker());
                } catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            int count = 0;
            while (true) {
                assertNull(pipeline.failure());
                FramePipeline.Work output = pipeline.poll();
                if (output == null) { Thread.sleep(1); continue; }
                if (output.end()) break;
                try { assertEquals(count++, output.info().frameNumber()); }
                finally { output.release(); }
            }
            assertEquals(FRAMES, count);
            producer.get(5, TimeUnit.SECONDS);
            pipeline.awaitWorkers();
        }
        return System.nanoTime() - start;
    }
}
