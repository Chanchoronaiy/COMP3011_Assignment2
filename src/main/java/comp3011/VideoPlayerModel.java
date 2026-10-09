/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787). Comments and modified code, with help from codex.
 *
 * Copyright 2026 Simon Ratcliffe
 *
 * Before: the UI heartbeat decoded media and ran every hard-coded effect.
 * After: CLI-selected effects run in background sessions/stages. 
 * UI keeps presentation, the clock and sound scheduling. 
 * WHY: expensive pixels must not prevent controls, resizing and timely presentation on other CPU cores.
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
 * ownership argument, bounded buffers, measured comparisons and known limits.
 */
// !! MODIFIED- Major refactor of the supplied model: retain public playback controls,
// but move decoding/effects into background sessions and keep presentation on JavaFX.
public class VideoPlayerModel {
//----------CONSTANTS/VIEW CALLBACKS----------
    // recap: static final = one class-level value that cannot be reassigned. US = microseconds,
    // NS = nanoseconds where 1 microsecond = 1,000 nanoseconds. 
    private static final long FIVE_SECONDS_US = 5_000_000;
    private static final long AUDIO_LEAD_NS = 30_000_000;
    private final List<FrameProcessor> effects;
    // Consumer<T> accepts one T; BiConsumer<A,B> accepts two arguments. These
    // callbacks return nothing bc the model need not depend on UI widget types
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
//----------SERIAL RESOURCE LIFECYCLE----------
    // ExecutorService manages Runnable tasks. One decoder thread serialises
    // cleanup -> next session, so reused effect instances cannot be initialised while an older session still processes frames with them.
    // !! MODIFIED - Serialise old-session cleanup and new decoding so shared effects cannot overlap
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("video-decoder").factory());
    private final JavaFXFrameConverter converter = new JavaFXFrameConverter();
    // new AnimationTimer() { ... } is anonymous subclass overriding handle().
    // JavaFX supplies the pulse time. !! UI callback, NOT an effect thread
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override public void handle(long now) { pumpPlayback(now); }
    };

//----------UI-OWNED PLAYBACK STATE----------
    // Everything below is confined to the JavaFX application thread. 
    // Worker communications uses session queues/volatile fields, never these UI fields.
    private File videoFile;
    private PlaybackSession session;
    private PlaybackSession.Metadata metadata;
    private AudioPlayer audioPlayer;
    private PlaybackSession.AudioChunk pendingAudio;
    private int audioOffset;
    private PreparedImage preparedFrame;
    private boolean playbackOpen, pauseRequested, audioOutputEnabled, closed;
    private boolean hasDisplayedFrame, endReached;
    // -1 = "not set" bc zero is a real media timestamp
    // logicalBaseUs is the requested seek position.
    // firstTimestampUs anchors the decoded source clock, which can start after an audio-only lead-in.
    private long currentTimestampUs, logicalBaseUs, firstTimestampUs = -1;
    private long playbackStartNs, pauseStartedNs, relativeSeekBaseUs = -1;
    private long videoDurationUs = -1;
    private int videoFrameDurationUs, intFrameRate;

//----------CONSTRUCTOR / DEPENDENCY INJECTION----------
    // Inject = receive the selected effects and view actions as parameters, rather than constructing UI controls or hard-coding effects in this model
    // !!. MODIFIED - Receive CLI-selected effects instead of constructing a hard-coded effect list.
    public VideoPlayerModel(boolean audioEnabled, List<FrameProcessor> effects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler, Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        // The list order cannot change, but its effect objects are still mutable.
        // Safety comes from exclusive stage ownership, not List.copyOf alone.
        this.effects = List.copyOf(effects);
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }

//----------PLAY / REWIND / SEEK COMMANDS----------
    // Public control methods are called on the UI thread by the controller.
    // A session is one generation of playback; seeking replaces that generation.
    public void play(File file) {
        requireUiThread();
        videoFile = file;
        videoDurationUs = -1;
        startPlayback(0, false);
    }
    public void startOver() { seekTo(0); }
    public void backFiveSeconds() { seekRelative(-FIVE_SECONDS_US); }
    public void forwardFiveSeconds() { seekRelative(FIVE_SECONDS_US); }

//----------PAUSE / RESUME----------
    // A pause keeps its displayed image and remembers !! wall-clock delay.
    // resume shift the clock, so !! paused time is not mistaken for late playback.
    // !! MODIFIED -Coordinate pause/resume with background decoding and the single-image preview.
    public void togglePause() {
        requireUiThread();
        if (!playbackOpen) {
            if (videoFile != null) startPlayback(currentTimestampUs, false);
            return;
        }
        if ((!pauseRequested && !hasDisplayedFrame) || (pauseRequested && session.previewOnly)) {
            // before the first image, switch to a bounded single-image preview.
            // Resume  preview with fresh decoding so its skipped PCM is read normally rather than producing missing sound after a seek
            startPlayback(currentTimestampUs, !pauseRequested);
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

//----------MUTE / UNMUTE----------
    // !! MODIFIED- Restart at the current position when toggling audio to realign queued sound/video
    public void toggleAudioOutput() {
        requireUiThread();
        audioOutputEnabled = !audioOutputEnabled;
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
        // Restart decoding at the displayed position. Otherwise buffered video
        // could be ahead of newly enabled audio after a long muted interval.
        if (playbackOpen) startPlayback(currentTimestampUs, pauseRequested);
    }
    public boolean isAudioOutputEnabled() { return audioOutputEnabled; }

//----------STOP / APPLICATION SHUTDOWN----------
    // Stop resets position but keeps the chosen file. 
    // Shutdown additionally refuses future playback and closes the executor/converter for application exit
    public void stopPlayback() {
        requireUiThread();
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        statusChangedHandler.accept("Stopped");
        frameReadyHandler.accept(null);
        notifyPlaybackStateChanged();
    }
    // !! MODIFIED - Request background cleanup and close the executor/converter without joining on the UI.
    public void shutdown() {
        requireUiThread();
        if (closed) return;
        closePlaybackResources();
        closed = true;
        decoder.shutdown(); // Queued cleanup still runs; the window does not wait for join().
        converter.close();
    }

    // Preserve last requested seek as a base while decoding catches up.
    // Makes repeated Right/Left presses accumulate instead of losing steps.
    private void seekRelative(long offset) {
        long base = relativeSeekBaseUs >= 0 ? relativeSeekBaseUs : currentTimestampUs;
        seekTo(base + offset);
    }
    // MODIFIED - Seek by replacing the session, not by mutating a grabber used by another thread.
    private void seekTo(long requestedUs) {
        requireUiThread();
        if (videoFile == null || closed) return;
        long clamped = Math.max(0, requestedUs);
        if (videoDurationUs > 0) clamped = Math.min(clamped, videoDurationUs);
        startPlayback(clamped, pauseRequested);
    }

//----------CREATE A NEW PLAYBACK GENERATION----------
    // Cleanup is queued before decoder.execute(session) on the SAME serial executor. 
    // That ordering prevents new initialise() calls racing old effects.
    // !! MODIFIED -Start a bounded-worker playback generation after scheduling the previous cleanup
    private void startPlayback(long logicalUs, boolean initiallyPaused) {
        if (closed) return;
        closePlaybackResources();
        currentTimestampUs = logicalUs;
        logicalBaseUs = logicalUs;
        relativeSeekBaseUs = logicalUs;
        pauseRequested = initiallyPaused;
        // At EOF request the last frame's decode position, while retaining the user's logical end position.
        long decodeUs = videoDurationUs > 0 && logicalUs >= videoDurationUs ? Math.max(0, videoDurationUs - Math.max(1, videoFrameDurationUs)) : logicalUs;
        // Leave nominal capacity for decode/UI; this is a thread budget, 
        //  !!NOT CPU affinity or a guarantee the OS reserves a core. Stages also cap by effects
        int workerBudget = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        session = new PlaybackSession(videoFile, decodeUs, effects, audioOutputEnabled, workerBudget, initiallyPaused);
        decoder.execute(session);
        playbackOpen = true;
        statusChangedHandler.accept(videoFile.getName());
        notifyPlaybackStateChanged();
        playbackTimer.start();
    }

    //----------UI HEARTBEAT: ONLY NON-BLOCKING QUEUE READS----------
    // Topic 7: separating background work improves responsiveness, even when more cores do not make a PARTICULAR effect faster. 
    // No decoding/effects or blocking queue operations run here. Existing view/hotkeys are retained.
    // !!!! MODIFIED - Poll completed work and present it. no native decoding or effect loops run.
    private void pumpPlayback(long now) {
        if (!playbackOpen) return;
        try {
            // 1. Observe published worker errors/metadata without blocking.
            if (session.error != null) throw new Exception("Decoder failed", session.error);
            if (session.pipeline != null && session.pipeline.failure() != null) {
                throw new Exception("Frame processing failed", session.pipeline.failure());
            }
            if (metadata == null && session.metadata != null) installMetadata(session.metadata);
            // 2. Poll at most one processed frame, null = none ready yet.
            // A paused seek may still show its one preview image.
            if (preparedFrame == null && session.pipeline != null && (!pauseRequested || !hasDisplayedFrame)) {
                FramePipeline.Work next = session.pipeline.poll();
                if (next != null && next.end()) endReached = true;
                else if (next != null) {
                    try {
                        // Prepare the independent image BEFORE its display deadline, so headroom trace measures preparation, not pulse lateness.
                        preparedFrame = new PreparedImage(converter.convert(next.frame()),
                                next.info(), System.nanoTime());
                        // finally runs even if conversion throws. After conversion, the UI image is independent of this native frame's storage.
                    } finally { next.release(); }
                }
            }
            // 3. Present only when its source timestamp is due. 
            // Waiting = returning until another pulse, NOT sleeping on the application thread.
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
            // 4. Schedule due PCM, then recognise EOF after pending output drains.
            writeDueAudio(now);
            // The source may begin with audio before its first image. 
            // When muted,  clock starts at that image, not at container time zero, OTHERWISE EOF unnecessarily waits out the skipped audio again
            long clockOriginUs = firstTimestampUs >= 0 ? firstTimestampUs : logicalBaseUs;
            long endTimeNs = playbackStartNs + Math.max(0, videoDurationUs - clockOriginUs) * 1000;
            if (endReached && !pauseRequested && now >= endTimeNs
                    && pendingAudio == null && session.audio.isEmpty()) finishPlayback();
        } catch (Exception e) {
            // recap: exception = failed operation, not a normal EOF. Release the current generation and report the cause instead of leaving workers live.
            e.printStackTrace();
            closePlaybackResources();
            pauseRequested = false;
            statusChangedHandler.accept("Error: " + e.getMessage());
            notifyPlaybackStateChanged();
        }
    }

    //----------INSTALL MEDIA INFORMATION----------
    // throws Exception propagates an opening failure to pumpPlayback's catch.
    // Metadata record accessors use info.width(), not a public mutable field
    // !! MODIFIED - Install metadata published by the decoder instead of reading its native grabber.
    private void installMetadata(PlaybackSession.Metadata info) throws Exception {
        metadata = info;
        videoDurationUs = info.durationUs() > 0 ? info.durationUs() : -1;
        intFrameRate = (int) Math.round(info.fps());
        videoFrameDurationUs = info.fps() > 0 ? (int) Math.round(1_000_000 / info.fps()) : 0;
        videoSizeChangedHandler.accept(info.width(), info.height());
        if (info.hasAudio() && audioOutputEnabled && !session.previewOnly) {
            audioPlayer = new AudioPlayer();
            audioPlayer.open(info.sampleRate(), info.channels());
        }
    }

    //----------SOURCE TIME -> PRESENTATION DEADLINE----------
    // nanoTime measures elapsed time, not calendar time. 
    // Relative source times keep display timing linear without assuming decoding runs at real time
    private long targetTimeNs(PreparedImage frame) {
        return playbackStartNs + Math.max(0, frame.info().timestampUs() - firstTimestampUs) * 1000;
    }
    // Hand the completed image to the view and update the seek base. 
    // Log once per source second (the % remainder check), not on every rendered frame
    private void displayPreparedFrame() {
        PreparedImage owned = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = logicalBaseUs + Math.max(0, owned.info().timestampUs() - firstTimestampUs);
        relativeSeekBaseUs = -1;
        hasDisplayedFrame = true;
        if (intFrameRate > 0 && owned.info().frameNumber() % intFrameRate == 0) {
            //Includes image conversion, rather than measuring UI pulse jitter
            long remainingUs = (targetTimeNs(owned) - owned.preparedAtNs()) / 1000;
            //Negative headroom means preparation missed the deadline. 
            //ANSI escape codes colour the console only; they dont alter the video pixels
            String colour = remainingUs < 0 ? "\u001B[31m" : "\u001B[32m";
            System.out.printf("Frame headroom is %s%5dus\u001B[0m out of %dus spare.%n",
                    colour, remainingUs, videoFrameDurationUs);
        }
        frameReadyHandler.accept(owned.image()); //all callbacks update the view on the FX thread.
    }

    //----------AUDIO SCHEDULING----------
    // decoder owns PCM copying. FX owns the output device and partial-write offset. 
    // queue transfers a freshly copied byte[] (no shared scratch).
    // !! MODIFIED - Consume copied PCM from the session queue and limit output work per UI pulse
    private void writeDueAudio(long now) {
        if (!audioOutputEnabled || audioPlayer == null) {
            session.audio.clear();
            pendingAudio = null;
            return;
        }
        if (pauseRequested) return;
        if (firstTimestampUs < 0) {
            // audio may precede the first video frame. 
            // starting its clock lets the UI drain PCM while the decoder searches for that frame, avoids a full audio queue waiting for video that cannot be decoded.
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

    //----------NORMAL EOF----------
    // Keep the logical end position for the retained rewind/replay controls.
    // !!! MODIFIED-finish only after queued output drains, retaining the end position for replay/seek
    private void finishPlayback() {
        long endUs = videoDurationUs > 0 ? videoDurationUs : currentTimestampUs;
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endUs;
        statusChangedHandler.accept("Playback finished");
        notifyPlaybackStateChanged();
    }

    // !! MODIFIED - Cancel old work and queue its disposal before any replacement session starts
    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        //----------SEEK / STOP GENERATION BARRIER----------
        // Topic 8 safety: old work must not affect the new playback state.
        // UI immediately forgets the old session, serial background cleanup waits for its workers before ANY new effect initialisation can start
        if (session != null) {
            PlaybackSession old = session;
            old.cancel();
            // old::dispose passes cleanup as a Runnable; execute queues it for
            // the decoder thread instead of running a blocking join on the UI.
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
    //----------CALLBACK / THREAD-CONFINEMENT HELPERS----------
    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }
    private void requireUiThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Playback controls must run on the JavaFX application thread");
        }
    }
    // record generates a constructor/accessors/equals/hashCode/toString for a small data carrier. 
    // components are final references, NOT deep immutability.
    // Workers never mutate this already-converted Image; the UI owns presentation.
    private record PreparedImage(Image image, InfoFrame info, long preparedAtNs) { }
}
