/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787), revised architecture generated with Codex;
 *      student review required before submission.
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bytedeco.javacv.JavaFXFrameConverter;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * JavaFX-owned playback clock and presentation. Heavy decoding/effects live in
 * PlaybackSession/FramePipeline, not in this UI heartbeat. See README for the
 * ownership argument, bounded buffers and remaining performance checks.
 */
public class VideoPlayerModel {
    private static final long FIVE_SECONDS_US = 5_000_000;
    private static final long AUDIO_LEAD_NS = 30_000_000;
    private final List<FrameProcessor> effects;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    //----------SERIAL RESOURCE LIFECYCLE----------
    // ExecutorService manages Runnable tasks. One decoder thread serialises
    // cleanup -> next session, so reused effect instances cannot be initialised
    // while an older session still processes frames with them.
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("video-decoder").factory());
    private final JavaFXFrameConverter converter = new JavaFXFrameConverter();
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override public void handle(long now) { pumpPlayback(now); }
    };

    // Everything below is confined to the JavaFX application thread. Worker
    // communication uses session queues/volatile fields, never these UI fields.
    private File videoFile;
    private PlaybackSession session;
    private PlaybackSession.Metadata metadata;
    private AudioPlayer audioPlayer;
    private PlaybackSession.AudioChunk pendingAudio;
    private int audioOffset;
    private PreparedImage preparedFrame;
    private boolean playbackOpen, pauseRequested, audioOutputEnabled, closed;
    private boolean hasDisplayedFrame, endReached;
    private long currentTimestampUs, logicalBaseUs, firstTimestampUs = -1;
    private long playbackStartNs, pauseStartedNs, relativeSeekBaseUs = -1;
    private long videoDurationUs = -1;
    private int videoFrameDurationUs, intFrameRate;

    public VideoPlayerModel(boolean audioEnabled, List<FrameProcessor> effects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler, Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        this.effects = List.copyOf(effects);
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }

    public void play(File file) {
        requireUiThread();
        videoFile = file;
        videoDurationUs = -1;
        startPlayback(0, false);
    }
    public void startOver() { seekTo(0); }
    public void backFiveSeconds() { seekRelative(-FIVE_SECONDS_US); }
    public void forwardFiveSeconds() { seekRelative(FIVE_SECONDS_US); }

    public void togglePause() {
        requireUiThread();
        if (!playbackOpen) {
            if (videoFile != null) startPlayback(currentTimestampUs, false);
            return;
        }
        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = -1;
        long now = System.nanoTime();
        if (pauseRequested) {
            pauseStartedNs = now;
            if (audioPlayer != null) audioPlayer.flush();
        } else {
            if (playbackStartNs > 0 && pauseStartedNs > 0) playbackStartNs += now - pauseStartedNs;
            pauseStartedNs = 0;
        }
        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        requireUiThread();
        audioOutputEnabled = !audioOutputEnabled;
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
        // Restart decoding at the displayed position. Otherwise buffered video
        // could be ahead of newly enabled audio after a long muted interval.
        if (playbackOpen) startPlayback(currentTimestampUs, pauseRequested);
    }
    public boolean isAudioOutputEnabled() { return audioOutputEnabled; }

    public void stopPlayback() {
        requireUiThread();
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        statusChangedHandler.accept("Stopped");
        frameReadyHandler.accept(null);
        notifyPlaybackStateChanged();
    }
    public void shutdown() {
        requireUiThread();
        if (closed) return;
        closePlaybackResources();
        closed = true;
        decoder.shutdown(); // Queued cleanup still runs; the window does not wait for join().
        converter.close();
    }

    private void seekRelative(long offset) {
        long base = relativeSeekBaseUs >= 0 ? relativeSeekBaseUs : currentTimestampUs;
        seekTo(base + offset);
    }
    private void seekTo(long requestedUs) {
        requireUiThread();
        if (videoFile == null || closed) return;
        long clamped = Math.max(0, requestedUs);
        if (videoDurationUs > 0) clamped = Math.min(clamped, videoDurationUs);
        startPlayback(clamped, pauseRequested);
    }

    private void startPlayback(long logicalUs, boolean initiallyPaused) {
        if (closed) return;
        closePlaybackResources();
        currentTimestampUs = logicalUs;
        logicalBaseUs = logicalUs;
        relativeSeekBaseUs = logicalUs;
        pauseRequested = initiallyPaused;
        long decodeUs = videoDurationUs > 0 && logicalUs >= videoDurationUs
                ? Math.max(0, videoDurationUs - Math.max(1, videoFrameDurationUs)) : logicalUs;
        int workerBudget = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        session = new PlaybackSession(videoFile, decodeUs, effects, audioOutputEnabled, workerBudget);
        decoder.execute(session);
        playbackOpen = true;
        statusChangedHandler.accept(videoFile.getName());
        notifyPlaybackStateChanged();
        playbackTimer.start();
    }

    //----------UI HEARTBEAT: ONLY NON-BLOCKING QUEUE READS----------
    // Topic 7: separating background work improves responsiveness, even when
    // more cores do not make a PARTICULAR effect faster. No decoding/effects
    // or blocking queue operations run here. Existing view/hotkeys are retained.
    private void pumpPlayback(long now) {
        if (!playbackOpen) return;
        try {
            if (session.error != null) throw new Exception("Decoder failed", session.error);
            if (session.pipeline != null && session.pipeline.failure() != null) {
                throw new Exception("Frame processing failed", session.pipeline.failure());
            }
            if (metadata == null && session.metadata != null) installMetadata(session.metadata);
            if (preparedFrame == null && session.pipeline != null && (!pauseRequested || !hasDisplayedFrame)) {
                FramePipeline.Work next = session.pipeline.poll();
                if (next != null && next.end()) endReached = true;
                else if (next != null) {
                    try {
                        // Prepare the independent image BEFORE its display deadline,
                        // so the headroom trace measures preparation, not pulse lateness.
                        preparedFrame = new PreparedImage(converter.convert(next.frame()),
                                next.info(), System.nanoTime());
                    } finally { next.release(); }
                }
            }
            if (preparedFrame != null) {
                if (firstTimestampUs < 0) {
                    firstTimestampUs = preparedFrame.info().timestampUs();
                    playbackStartNs = pauseRequested ? now : session.firstDecodedAtNs;
                    if (pauseRequested) pauseStartedNs = now;
                }
                if ((!pauseRequested || !hasDisplayedFrame) && targetTimeNs(preparedFrame) <= now) {
                    displayPreparedFrame();
                }
            }
            writeDueAudio(now);
            long endTimeNs = playbackStartNs + Math.max(0, videoDurationUs - logicalBaseUs) * 1000;
            if (endReached && !pauseRequested && now >= endTimeNs
                    && pendingAudio == null && session.audio.isEmpty()) finishPlayback();
        } catch (Exception e) {
            e.printStackTrace();
            closePlaybackResources();
            statusChangedHandler.accept("Error: " + e.getMessage());
            notifyPlaybackStateChanged();
        }
    }

    private void installMetadata(PlaybackSession.Metadata info) throws Exception {
        metadata = info;
        videoDurationUs = info.durationUs() > 0 ? info.durationUs() : -1;
        intFrameRate = (int) Math.round(info.fps());
        videoFrameDurationUs = info.fps() > 0 ? (int) Math.round(1_000_000 / info.fps()) : 0;
        videoSizeChangedHandler.accept(info.width(), info.height());
        if (info.hasAudio() && audioOutputEnabled) {
            audioPlayer = new AudioPlayer();
            audioPlayer.open(info.sampleRate(), info.channels());
        }
    }

    private long targetTimeNs(PreparedImage frame) {
        return playbackStartNs + Math.max(0, frame.info().timestampUs() - firstTimestampUs) * 1000;
    }
    private void displayPreparedFrame() {
        PreparedImage owned = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = logicalBaseUs + Math.max(0, owned.info().timestampUs() - firstTimestampUs);
        relativeSeekBaseUs = -1;
        hasDisplayedFrame = true;
        if (intFrameRate > 0 && owned.info().frameNumber() % intFrameRate == 0) {
            // Includes image conversion, rather than measuring UI pulse jitter.
            long remainingUs = (targetTimeNs(owned) - owned.preparedAtNs()) / 1000;
            String colour = remainingUs < 0 ? "\u001B[31m" : "\u001B[32m";
            System.out.printf("Frame headroom is %s%5dus\u001B[0m out of %dus spare.%n",
                    colour, remainingUs, videoFrameDurationUs);
        }
        frameReadyHandler.accept(owned.image()); // All callbacks update the view on the FX thread.
    }

    //----------AUDIO SCHEDULING----------
    // Decoder owns PCM copying; FX owns the output device and partial-write
    // offset. The queue transfers a freshly copied byte[] (no shared scratch).
    private void writeDueAudio(long now) {
        if (!audioOutputEnabled || audioPlayer == null) {
            session.audio.clear();
            pendingAudio = null;
            return;
        }
        if (pauseRequested) return;
        if (firstTimestampUs < 0) {
            // Audio may precede the first video frame. Starting its clock lets
            // the UI drain PCM while the decoder searches for that frame; it
            // avoids a full audio queue waiting for video that cannot be decoded.
            PlaybackSession.AudioChunk first = session.audio.peek();
            if (first == null) return;
            firstTimestampUs = first.timestampUs();
            playbackStartNs = now;
        }
        if (playbackStartNs == 0) return;
        long dueUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1000;
        // Limit work per heartbeat so a delayed stream cannot monopolise the UI.
        for (int i = 0; i < 16; i++) {
            if (pendingAudio == null) {
                pendingAudio = session.audio.poll();
                audioOffset = 0;
            }
            if (pendingAudio == null || pendingAudio.timestampUs() > dueUs) return;
            audioOffset += audioPlayer.write(pendingAudio.samples(), audioOffset,
                    pendingAudio.samples().length - audioOffset);
            if (audioOffset < pendingAudio.samples().length) return;
            pendingAudio = null;
        }
    }

    private void finishPlayback() {
        long endUs = videoDurationUs > 0 ? videoDurationUs : currentTimestampUs;
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endUs;
        statusChangedHandler.accept("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        //----------SEEK / STOP GENERATION BARRIER----------
        // Topic 8 safety: old work must not affect the new playback state.
        // UI immediately forgets the old session; serial background cleanup
        // waits for its workers before ANY new effect initialisation can start.
        if (session != null) {
            PlaybackSession old = session;
            old.cancel();
            decoder.execute(old::dispose);
            session = null;
        }
        if (audioPlayer != null) audioPlayer.close();
        audioPlayer = null;
        pendingAudio = null;
        metadata = null;
        firstTimestampUs = -1;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        hasDisplayedFrame = false;
        endReached = false;
    }
    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }
    private void requireUiThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Playback controls must run on the JavaFX application thread");
        }
    }
    private record PreparedImage(Image image, InfoFrame info, long preparedAtNs) { }
}
