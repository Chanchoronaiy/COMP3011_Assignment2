/* Touch Chanchoronaiy (a1978787) */
// Test implementation and learning notes developed with Codex assistance.
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
 * TEST PURPOSE: Component load comparison, not a claim that the entire GUI/audio player has been benchmarked. 
 * Both paths use the unchanged supplied Bleeder implementation and identical 720p input. 
 * The reference applies effects serially, as the starter does. 
 * DesktopPlaybackIT separately measures the actual original and threaded players under identical load during ordinary mvn verify.
 * 
 * COVERS - FramePipeline under repeated CPU-heavy Bleeder work. Reports measured
 * speed rather than asserting that every machine must reach one fixed speedup.
 */
class EffectThroughputTest {
    //----------BENCHMARK INPUT SIZE----------
    // A component benchmark times processing work; it does not decode a movie, open a GUI or play sound
    private static final int FRAMES = 32;

//----------TEST: MEASURE BOTH APPROACHES UNDER THE SAME EFFECT LOAD----------
    //build one 720p pattern, initialise four Bleeders, warm both paths, then time three trials each.
    // EXPECTED: processing completes within the outer timeout, and the pipeline's order/count checks pass.
    // Report the median speedup; no assertion requires a particular speed on every machine.
    // assertTimeoutPreemptively runs the body with a time limit; () -> { ... } supplies that body as a lambda.

    @Test void reportWarmedSequentialAndPipelineThroughput() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            // At least one and at most four effect workers. This is a thread budget, not reserved CPU cores.
            int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
            List<FrameProcessor> effects = List.of(new FrameBleeder(), new FrameBleeder(),
                    new FrameBleeder(), new FrameBleeder());

            // try-with-resources closes the native Frame even when processing/assertions fail
            // DEPTH_UBYTE and 3 describe an 8-bit, three-channel picture; (ByteBuffer) is a type cast.
            try (Frame input = new Frame(1280, 720, Frame.DEPTH_UBYTE, 3)) {
                ByteBuffer pixels = (ByteBuffer) input.image[0];
                byte[] pattern = new byte[pixels.capacity()];

                //Deterministic bytes give both paths the same input. clear() resets buffer position/limit,
                // not the stored pixels. InfoVideo describes dimensions/stride/frame rate for the effects.
                for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 31);
                pixels.put(pattern).clear();
                InfoVideo info = new InfoVideo("throughput", FRAMES, 1280, 720,
                        input.imageDepth, 3, input.imageStride, 24, 24, 41667, 0);

                for (FrameProcessor effect : effects) effect.initialise(info);

                // Warm-up gives the JVM's just-in-time compiler/native allocation paths a first run.
                sequential(input, effects); // Warm up JIT/native allocation before measured trials.
                parallel(input, effects, workers);
                long[] serial = new long[3], pipeline = new long[3];

                for (int i = 0; i < 3; i++) {
                    //alternate order to reduce one-sided warm-up advantage.
                    if (i % 2 == 0) {
                        serial[i] = sequential(input, effects);
                        pipeline[i] = parallel(input, effects, workers);
                    }

                    else {
                        pipeline[i] = parallel(input, effects, workers);
                        serial[i] = sequential(input, effects);
                    }
                }

                // Sort the three elapsed times: [1] is their median, reducing one outlier's influence.
                //fps = frames * 1,000,000,000 / elapsed nanoseconds. Old time / new time gives the speedup
                Arrays.sort(serial);
                Arrays.sort(pipeline);
                System.out.printf("720p component benchmark: %d frames, four bleed effects, %d workers; "
                        + "sequential %.1f fps, pipeline %.1f fps, median speedup %.2fx%n",
                        FRAMES, workers, FRAMES * 1e9 / serial[1], FRAMES * 1e9 / pipeline[1],
                        serial[1] / (double) pipeline[1]);
                // Speed depends on the host/load. Record a comparison, rather than turn ordinary machine variability into a flaky failure.
            }
        });
    }

    //----------BENCHMARK HELPER: SERIAL EFFECT PROCESSING----------
    //Apply every effect to one frame before moving to the next frame, all on the calling thread.
    //clone() gives each trial frame its own pixels; closing it releases the native allocation.
    // EXPECTED: return total elapsed nanoseconds, including cloning/cleanup, not a whole-player frame rate.
    private long sequential(Frame input, List<FrameProcessor> effects) throws Exception {
        long start = System.nanoTime();

        for (int n = 0; n < FRAMES; n++) {
            try (Frame frame = input.clone()) {
                for (FrameProcessor effect : effects) effect.process(frame, new InfoFrame(n, n * 41667L));
            }
        }

        return System.nanoTime() - start;
    }

    //----------BENCHMARK HELPER: PIPELINED EFFECT PROCESSING----------
    // One common-pool producer submits frames; separate pipeline stages process different frames concurrently.
    // CompletableFuture represents that background producer; get(...,5,...) waits for it with a deadline.
    // EXPECTED: frames 0..31 arrive in order, then the end marker, with no reported worker failure.
    // Timing includes pipeline construction, hand-offs and cleanup; it is not pure effect-loop timing.
    private long parallel(Frame input, List<FrameProcessor> effects, int workers) throws Exception {
        long start = System.nanoTime();

        try (FramePipeline pipeline = new FramePipeline(effects, workers)) {
            CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {

                try {
                    for (int n = 0; n < FRAMES; n++) pipeline.submit(
                            new FramePipeline.Work(input.clone(), new InfoFrame(n, n * 41667L), 0, false));

                    // End marker follows the frames through the FIFO queues; it has no image to display.
                    pipeline.submit(FramePipeline.Work.endMarker());
                }

                catch (InterruptedException e) {
                    // Propagate the producer failure through its future instead of silently ignoring it.
                    throw new RuntimeException(e);
                }
            });
            
            int count = 0;
            while (true) {
                assertNull(pipeline.failure());
                FramePipeline.Work output = pipeline.poll();

                // !!! poll() does not wait. Briefly yield this consumer thread if no output is ready
                if (output == null) {
                    Thread.sleep(1);
                    continue;
                }

                if (output.end()) break;

                try {
                    // count++ compares the current expected number, then increments for the next frame
                    assertEquals(count++, output.info().frameNumber());
                }

                finally {
                    //release the consumer-owned native frame even if the assertion fails.
                    output.release();
                }
            }

            assertEquals(FRAMES, count);
            producer.get(5, TimeUnit.SECONDS);
            pipeline.awaitWorkers();
        }

        return System.nanoTime() - start;
    }
}
