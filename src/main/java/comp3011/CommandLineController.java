/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787). Comments and modified code, with help from GPT-5.6 Terra.
 *
 * Copyright 2026 Simon Ratcliffe
 *
 * Change summary: previously parsed only launch settings; now builds an ordered
 * effect list from short/long/stacked flags and extends the exact help text.
 * WHY: separate input parsing from effect registration and playback execution.
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    //----------PARSED SETTINGS----------
    // recap: String[] is an array. List<FrameProcessor> is anordered collection.
    // ArrayList is its resizable implementation. !! NO use a Set: repeats matter.
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    // MODIFIED - store every selected effect in order, including repeated flags
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        // clone() copies argument array so later caller edits cannot alter it
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    //----------READ THE PARSED RESULT----------
    // Getters expose the launch configuration, they do not start threads/playback
    public File getVideoFile() {
        return videoFile;
    }

    // !! MODIFIED- Expose the selected effects without allowing the caller to reorder the list
    public List<FrameProcessor> getFrameProcessors() {
        // Copy list structure so callers cannot change the parsed order
        // model takes ownership of the mutable effect objects themselves
        return List.copyOf(frameProcessors);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // condition ? valueIfTrue : valueIfFalse 
    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    //----------LONG OPTIONS/FILE ARGUMENT-------
    // Enhanced for (String arg : args) visits each argument in its OG order
    // Collect filenames throughout: the file may be before/between/after options
    // !! MODIFIED- PARSE all the short/long/stacked flags and build ordered effect list
    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            FrameProcessorOption effect = FrameProcessorOption.fromLong(arg);
            if (effect != null) {
                frameProcessors.add(effect.createProcessor());
            } else if (arg.startsWith("-") && !arg.startsWith("--") && arg.length() > 1) {
                //----------STACKED SHORT OPTIONS----------
                // Read left to right: -jfddn (jitter, flicker, dust.hair mask, dusk, draw frame number)must not become a set or be sorted.
                for (int i = 1; i < arg.length(); i++) parseShortOption(arg.charAt(i));
            } else if ("--help".equals(arg)) {
                helpRequested = true;
            } else if ("--audio".equals(arg)) {
                audioRequested = true;
            } else if ("--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg;
            } else {
                videoFiles.add(arg);
            }
        }

        // VALIDATE after scan: exactly one real file is needed for playback
        // --help alone is valid and exits successfully without launching the UI.
        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    //----------ONE SHORT FLAG----------
    // First, try an effect factory, otherwise switch handles the launch settings
    // case ... -> is a switch rule: no fall-through and no break is needed
    // !!MODIFIED- New helper reads each stacked flag separately, creates its effect instance
    private void parseShortOption(char flag) {
        FrameProcessorOption effect = FrameProcessorOption.fromShort(flag);
        if (effect != null) {
            frameProcessors.add(effect.createProcessor());
            return;
        }
        switch (flag) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> errorMessage = "Unknown option: -" + flag;
        }
    }

    // null = unspecified. Conflicting -1/-2 flags = errors, not a silent last-one-wins choice that could change the supplied launch behaviour.
    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    //----------EXACT HELP OUTPUT----------
    // rubric/TITAN require this text and spacing
    // enum generates effect lines in registration order
    // the regression test checks the whole output
    // !! MODIFIED- Extend the supplied help with the exact required effect names and examples.
    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            System.out.println(option.helpLine());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
