/*
 * New file author: Touch Chanchoronaiy (a1978787), with additional help from codex.
 * PURPOSE: one background decoder generation. 
 * WHY: native decoding, seeking and reused buffers need a clear owner separate from JavaFX presentation.
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

/** Background decoder and resource owner for one play/seek generation. */
final class PlaybackSession implements Runnable {
//----------RUNNABLE/ MESSAGE TYPES----------
    // implements promises the Runnable interface's run() method. DOES NOT start a thread by itself: the model submits this task to its serial executor.
    // record generates a data constructor/accessors. 
    // Metadata contains values, AudioChunk contains a mutable byte[] that we copy and transfer, not share.
    record Metadata(int width, int height, double fps, long durationUs, int sampleRate, int channels, boolean hasAudio) { }
    record AudioChunk(long timestampUs, byte[] samples) { }

//----------ONE SESSION'S SETTINGS/ PUBLISHED STATE----------
    // final fixes construction-time references/settings. 
    // Package-private members (no public/private keyword) are used by the model in the same package.
    private final File file;
    private final long startUs;
    private final List<FrameProcessor> effects;
    private final int workerBudget;
    final boolean previewOnly;
    final BlockingQueue<AudioChunk> audio = new ArrayBlockingQueue<>(256);
    // volatile means changes become visible across threads. 
    // DOES NOT make a compound read/change/write atomic; use ownership or a lock for that.
    volatile Metadata metadata;
    volatile Throwable error;
    volatile FramePipeline pipeline;
    volatile boolean cancelled;
    volatile boolean audioEnabled;
    volatile long firstDecodedAtNs;

//----------CONSTRUCTOR OVERLOADS----------
    // Same constructor name, different parameter lists. Ordinary playback defaults previewOnly to false.
    PlaybackSession(File file, long startUs, List<FrameProcessor> effects,
                    boolean audioEnabled, int workerBudget) {
        this(file, startUs, effects, audioEnabled, workerBudget, false);
    }

    PlaybackSession(File file, long startUs, List<FrameProcessor> effects,
                    boolean audioEnabled, int workerBudget, boolean previewOnly) {
        this.file = file;
        this.startUs = startUs;
        this.effects = effects;
        this.audioEnabled = audioEnabled;
        this.workerBudget = workerBudget;
        this.previewOnly = previewOnly;
    }

//----------OPEN, DECODE AND PUBLISH----------
    // recap: @Override checks the interface method signature. This runs on video-decoder, not the JavaFX thread. 
    // No view controls/callbacks are invoked, just view on javafx thread.
    @Override public void run() {
        if (cancelled) return;
        //----------DECODER / NATIVE BUFFER OWNERSHIP----------
        // Only this thread starts, reads, seeks and closes FFmpeg. Seek requests create another session, NOT a concurrent setTimestamp() on this grabber.
        // try-with-resources automatically closes the native grabber, including on failure. Unlike C++, Java GC alone does not time native resource release.
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
            grabber.setPixelFormat(AV_PIX_FMT_BGR24);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();
            //PUBLISH metadata through volatile. the UI observes a fully-built record
            metadata = new Metadata(grabber.getImageWidth(), grabber.getImageHeight(), grabber.getFrameRate(), 
                        grabber.getLengthInTime(), grabber.getSampleRate(), grabber.getAudioChannels(), grabber.hasAudio());
            // ASK JavaCV to check decoded timestamps as part of native seeking.
            if (startUs > 0) grabber.setTimestamp(startUs, true);
            AudioPlayer sampleCopier = new AudioPlayer(); // Never opens a sound device; decoder-owned scratch buffer.
            boolean initialised = false;
            // grab() can return audio OR an image. Null marks EOF; "no image" on a non-null frame is not EOF and must not reach pixel effects
            while (!cancelled) {
                Frame frame = metadata.hasAudio() ? grabber.grab() : grabber.grabImage();
                if (frame == null) break;
                long timestampUs = grabber.getTimestamp();
                // A codec may still land at an EARLIER keyframe (observed with the generated H.264 test). 
                //decode forward, but do not publish pre-seek pixels/audio as if they belonged to the new position.
                if (timestampUs < startUs) continue;
                if (frame.samples != null && audioEnabled && !previewOnly) {
                    byte[] samples = sampleCopier.copySamples(frame); // Copy BEFORE FFmpeg reuses its buffer.
                    if (samples.length > 0) {
                        AudioChunk chunk = new AudioChunk(timestampUs, samples);
                        // Bounded PCM queue: timed offer waits efficiently while full. The empty body deliberately retries only after the wait.
                        while (!cancelled && audioEnabled && !audio.offer(chunk, 50, TimeUnit.MILLISECONDS)) { }
                    }
                }
                if (frame.image == null) continue;
                // JavaCV 1.5.13 infers channels as stride/width. Padding can make that four even for BGR24, ex. a 96-pixel-wide image
                // BGR24 has THREE colour components. keep the padded row stride unchanged so effects and JavaFX address the actual pixels.
                frame.imageChannels = 3;
                //Initialise once per generation using the first real image's layout
                // Thread.start in FramePipeline publishes those initialised effects.
                if (!initialised) {
                    firstDecodedAtNs = System.nanoTime();
                    initialiseEffects(grabber, frame);
                    pipeline = new FramePipeline(effects, workerBudget);
                    initialised = true;
                }
                if (pipeline.failure() != null) throw new Exception("Effect stage failed", pipeline.failure());
                // clone() copies pixels; duplicate() alone would share storage!
                // FFmpeg may overwrite its frame on the next grab() call.
                pipeline.submit(new FramePipeline.Work(frame.clone(),
                        new InfoFrame(grabber.getFrameNumber(), timestampUs), 0, false));
                //a paused seek needs ONE image, not queued sound.
                // Otherwise a long audio lead-in can fill the bounded PCM queue before decoder finds an image, while the paused UI cannot drain it.
                // resuming creates a normal session at the same logical time
                if (previewOnly) break;
            }
            //----------NORMAL END / ERROR CLEANUP----------
            // The marker follows all frames through FIFO stages. 
            // It is not a null frame to display. Waiting here is safe: this is NOT the JavaFX thread.
            if (!initialised && !cancelled) throw new Exception("No video frames found in this media");
            if (pipeline != null && !cancelled) {
                pipeline.submit(FramePipeline.Work.endMarker());
                pipeline.awaitWorkers();
            }
        } catch (Throwable e) {
            // Cancellation is expected; other failures are published for the UI.
            if (!cancelled) error = e;
        } finally {
            if (pipeline != null && (cancelled || error != null || pipeline.failure() != null)) {
                pipeline.close();
            }
        }
    }

//----------DETERMINISTIC EFFECT INITIALISATION----------
    //keep starter's media-name/instance seed rules, metadata and original effect order. Replacing effects with new per-frame instances changes pixels.
    private void initialiseEffects(FFmpegFrameGrabber grabber, Frame frame) throws Exception {
        String name = file.getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0) name = name.substring(0, extension);
        double fps = grabber.getFrameRate();
        // (int) is a narrowing cast; Math.round avoids simply discarding decimals.
        // The zero-fps guard prevents division by zero for invalid/unknown rates.
        InfoVideo info = new InfoVideo(name, grabber.getLengthInVideoFrames(), frame.imageWidth,
                frame.imageHeight, frame.imageDepth, frame.imageChannels, frame.imageStride,
                fps, (int) Math.round(fps), fps > 0 ? (int) Math.round(1_000_000 / fps) : 0,
                grabber.getPixelFormat());
        for (FrameProcessor effect : effects) effect.initialise(info);
    }

//----------REQUEST STOP; DISPOSE ONLY AFTER RUN RETURNS----------
    // volatile publishes the stop request. 
    // Interrupting stage waits does not justify closing FFmpeg from a second thread while grab() may still use it.
    void cancel() {
        cancelled = true;
        if (pipeline != null) pipeline.cancel();
    }

    // Called on the serial decoder executor AFTER run() returns. 
    // Therefore no decoder or worker still uses these buffers when they are disposed.
    void dispose() {
        if (pipeline != null) pipeline.close();
        audio.clear();
    }
}
