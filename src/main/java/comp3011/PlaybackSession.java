/*
 * New Assignment 2 code generated with Codex for Touch Chanchoronaiy (a1978787).
 * Student review and understanding are required before submission.
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

/** Background decoder and resource owner for one play/seek generation. */
final class PlaybackSession implements Runnable {
    record Metadata(int width, int height, double fps, long durationUs,
                    int sampleRate, int channels, boolean hasAudio) { }
    record AudioChunk(long timestampUs, byte[] samples) { }

    private final File file;
    private final long startUs;
    private final List<FrameProcessor> effects;
    private final int workerBudget;
    final BlockingQueue<AudioChunk> audio = new ArrayBlockingQueue<>(256);
    // volatile means changes become visible across threads. It does NOT make
    // a compound read/change/write atomic; use ownership or a lock for that.
    volatile Metadata metadata;
    volatile Throwable error;
    volatile FramePipeline pipeline;
    volatile boolean cancelled;
    volatile boolean audioEnabled;
    volatile long firstDecodedAtNs;

    PlaybackSession(File file, long startUs, List<FrameProcessor> effects,
                    boolean audioEnabled, int workerBudget) {
        this.file = file;
        this.startUs = startUs;
        this.effects = effects;
        this.audioEnabled = audioEnabled;
        this.workerBudget = workerBudget;
    }

    @Override public void run() {
        if (cancelled) return;
        //----------DECODER / NATIVE BUFFER OWNERSHIP----------
        // Only this thread starts, reads, seeks and closes FFmpeg. Seek requests
        // create another session, NOT a concurrent setTimestamp() on this grabber.
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();
            metadata = new Metadata(grabber.getImageWidth(), grabber.getImageHeight(),
                    grabber.getFrameRate(), grabber.getLengthInTime(), grabber.getSampleRate(),
                    grabber.getAudioChannels(), grabber.hasAudio());
            // Ask JavaCV to check decoded timestamps as part of native seeking.
            if (startUs > 0) grabber.setTimestamp(startUs, true);
            AudioPlayer sampleCopier = new AudioPlayer(); // Never opens a sound device; decoder-owned scratch buffer.
            boolean initialised = false;
            while (!cancelled) {
                Frame frame = metadata.hasAudio() ? grabber.grab() : grabber.grabImage();
                if (frame == null) break;
                long timestampUs = grabber.getTimestamp();
                // A codec may still land at an EARLIER keyframe (observed with
                // the generated H.264 test). Decode forward, but do not publish
                // pre-seek pixels/audio as if they belonged to the new position.
                if (timestampUs < startUs) continue;
                if (frame.samples != null && audioEnabled) {
                    byte[] samples = sampleCopier.copySamples(frame); // Copy BEFORE FFmpeg reuses its buffer.
                    if (samples.length > 0) {
                        AudioChunk chunk = new AudioChunk(timestampUs, samples);
                        while (!cancelled && audioEnabled && !audio.offer(chunk, 50, TimeUnit.MILLISECONDS)) { }
                    }
                }
                if (frame.image == null) continue;
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
            }
            if (!initialised && !cancelled) throw new Exception("No video frames found in this media");
            if (pipeline != null && !cancelled) {
                pipeline.submit(FramePipeline.Work.endMarker());
                pipeline.awaitWorkers();
            }
        } catch (Throwable e) {
            if (!cancelled) error = e;
        } finally {
            if (pipeline != null && (cancelled || error != null || pipeline.failure() != null)) {
                pipeline.close();
            }
        }
    }

    private void initialiseEffects(FFmpegFrameGrabber grabber, Frame frame) throws Exception {
        String name = file.getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0) name = name.substring(0, extension);
        double fps = grabber.getFrameRate();
        InfoVideo info = new InfoVideo(name, grabber.getLengthInVideoFrames(), frame.imageWidth,
                frame.imageHeight, frame.imageDepth, frame.imageChannels, frame.imageStride,
                fps, (int) Math.round(fps), fps > 0 ? (int) Math.round(1_000_000 / fps) : 0,
                grabber.getPixelFormat());
        for (FrameProcessor effect : effects) effect.initialise(info);
    }

    void cancel() {
        cancelled = true;
        if (pipeline != null) pipeline.cancel();
    }

    // Called on the serial decoder executor AFTER run() returns. Therefore no
    // decoder or worker still uses these buffers when they are disposed.
    void dispose() {
        if (pipeline != null) pipeline.close();
        audio.clear();
    }
}
