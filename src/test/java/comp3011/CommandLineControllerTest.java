// Test implementation and learning notes developed with Codex assistance.
package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI regression tests: parsing/help must preserve the specified output or effect order. 
 * COVER:  CommandLineController and FrameProcessorOption together, including repeated instances, original launch options, invalid input and exact help.
 *  does not launch VideoPlayerApp or test its physical window placement.
 * 
 */
class CommandLineControllerTest {
    // assertNotSame checks object identity: repeated flags must create separate effect objects.
    // @TempDir supplies an isolated temporary path
    @TempDir Path directory;

    // resolve chooses a path inside the temporary folder, createFile creates it, toString supplies a CLI argument.
    private String movie() throws Exception {
        return Files.createFile(directory.resolve("movie.mp4")).toString();
    }

    // TEST HELPER: extract effect TYPES in their existing list order, without running any effects.
    // stream/map/toList transforms each object into its class, Object::getClass is a method reference.
    // Class<?> means a Class representing an unspecified type, the explicit map type keeps this list compatible.
    private List<Class<?>> classes(CommandLineController command) {
        return command.getFrameProcessors().stream().<Class<?>>map(Object::getClass).toList();
    }

//----------TEST 1: LONG/ SHORT/ STACKED/ MIXED FLAGS----------
    // TEST PURPOSE: equivalent argument forms must produce the same ordered effect list
    // parse four spellings of jitter, flicker, dust, dust, number.
    // EXPECTED: no error, the same type order, distinct dust instances and permission to launch.
    //@Test marks a method JUnit executes
    @Test void shortLongStackedAndMixedFormsHaveIdenticalOrder() throws Exception {
        String file = movie();
        List<Class<?>> expected = List.of(FrameJitterer.class, FrameFlickerer.class,
                FrameDuster.class, FrameDuster.class, FrameNumberer.class);
        for (String[] args : List.of(
                new String[]{"--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames", "--number-frames", file},
                new String[]{"-j", "-f", "-d", "-d", "-n", file},
                new String[]{"-jfddn", file}, new String[]{"-jf", "--dust-frames", "-d", "--number-frames", file})) {
            CommandLineController command = new CommandLineController(args);
            // assertEquals compares an expected result with the actual one
            assertNull(command.getErrorMessage());
            assertEquals(expected, classes(command));
            //assertNotSame checks object identity: repeated flags must create separate effect objects.
            assertNotSame(command.getFrameProcessors().get(2), command.getFrameProcessors().get(3));
            assertTrue(command.shouldLaunchApplication());
        }
    }

//----------TEST 2: ALL EFFECTS AND ORIGINAL LAUNCH SETTINGS----------
    // combine audio (-a), maximise (-x), display 2 (-2) and all effects, including repeats
    // EXPECTED: settings retained and 13 effects in total. The returned list cannot be cleared.
    @Test void everyEffectAndOriginalLaunchOptionsRemainAvailable() throws Exception {
        CommandLineController command = new CommandLineController(new String[]{"-ax2nssnfwyvdjmbp", movie()});
        assertNull(command.getErrorMessage());
        assertTrue(command.isAudioRequested());
        assertTrue(command.isMaximiseRequested());
        assertEquals(2, command.getDisplayId());
        assertEquals(13, command.getFrameProcessors().size());
        assertNotSame(command.getFrameProcessors().get(1), command.getFrameProcessors().get(2));
        assertThrows(UnsupportedOperationException.class, () -> command.getFrameProcessors().clear());
    }

//----------TEST 3: INVALID ARGUMENTS MUST NOT START THE PLAYER----------
    //try unknown flags, conflicting monitors, two files, a missing file and no arguments
    // EXPECTED: exit code 1 and shouldLaunchApplication() false for every case.
    // Constructing the controller tests its decision logic without launching the GUI. The starter's help/error behaviour is retained.
    @Test void invalidOptionsFilesAndConflictingDisplaysDoNotLaunch() throws Exception {
        String file = movie();
        for (String[] args : List.of(new String[]{"-q", file}, new String[]{"--unknown", file},
                new String[]{"-12", file}, new String[]{file, file}, new String[]{"missing.mp4"}, new String[]{})) {
            CommandLineController command = new CommandLineController(args);
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }
    }

//----------TEST 4: THE FILENAME CAN MOVE AMONG THE OPTIONS----------
    // place the file before, between or after the effect arguments.
    // EXPECTED: each form remains valid and preserves the requested effect order.
    @Test void fileMayAppearBeforeBetweenOrAfterOptions() throws Exception {
        String file = movie();
        List<Class<?>> expected = List.of(FrameJitterer.class, FrameFlickerer.class,
                FrameDuster.class, FrameDuster.class, FrameNumberer.class);
        for (String[] args : List.of(new String[]{file, "-jfddn"},
                new String[]{"-jf", file, "--dust-frames", "-dn"},
                new String[]{"-jfddn", file})) {
            CommandLineController command = new CommandLineController(args);
            assertTrue(command.shouldLaunchApplication());
            assertEquals(expected, classes(command));
        }
    }

//----------TEST 5: EXACT FLAG SPELLING AND AN EMPTY EFFECT LIST----------
    // EXPECTED: a filename alone selects no effects; incorrect case/singular spellings are rejected.
    // List.of(...) builds the test inputs. isEmpty() checks that the parsed effect list has zero entries.
    @Test void optionNamesAreCaseSensitiveAndNoEffectsMeansAnEmptyList() throws Exception {
        String file = movie();
        assertTrue(new CommandLineController(new String[]{file}).getFrameProcessors().isEmpty());
        for (String flag : List.of("-N", "--Number-Frames", "--dust-frame", "--vignette-frame")) {
            CommandLineController command = new CommandLineController(new String[]{flag, file});
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }
    }

//----------TEST 6: HELP TOGETHER WITH INVALID INPUT----------
    // pupose: retain the starter's help/error behaviour without opening a window.
    // ByteArrayOutputStream stores console bytes in memory; PrintStream writes UTF-8 text into it
    // System.out is global: save/restore it and do not redirect it concurrently in another test.
    // EXPECTED: an error exit/no launch, but the captured text contains the error AND usage/effect help
    @Test void helpStillPrintsWithInvalidInputAsInTheStarter() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            CommandLineController command = new CommandLineController(new String[]{"--help", "--unknown"});
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }

        // finally runs even if an assertion fails, so later output is not trapped in this buffer.
        finally {
            System.setOut(original);
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Unknown option: --unknown"));
        assertTrue(output.contains("Usage: VideoPlayer [options] [video-file]"));
        assertTrue(output.contains("Frame processors:"));
    }

//----------TEST 7: THE COMPLETE HELP OUTPUT MUST MATCH EXACTLY----------
    // ACT: capture --help output; EXPECTED: exit code 0, no GUI launch and the exact required text.
    // Replace Windows CRLF with LF for a cross-platform comparison; do not remove meaningful blank lines.
    // The triple-quoted text block below is the independent expected result, not generated by the parser.
    @Test void helpMatchesRequiredEffectParagraphsWithoutLaunchingUi() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            CommandLineController command = new CommandLineController(new String[]{"--help"});
            assertEquals(0, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }

        finally {
            System.setOut(original);
        }

        String output = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        // !! THERE WAS AN ERROR HERE, I really needed codex's help
        // TITAN compares the complete help text, including blank lines. 
        // Keep expected text independent of helpLine() so formatting mistakes in the implementation cannot also change the expected test result.
        String expected = """
                Usage: VideoPlayer [options] [video-file]

                Options:
                  -h, --help         Show this help message
                  -a, --audio        Play audio
                  -x, --maximise     Open the player maximised
                  -1, --monitor-1    Open the player on display 1
                  -2, --monitor-2    Open the player on display 2

                Frame processors:
                  -n, --number-frames    Render the frame number onto each frame
                  -s, --scratch-frames   Render vertical film scratches
                  -f, --flicker-frames   Randomly dim frames
                  -w, --black-and-white  Convert frames to black and white
                  -y, --yellow-frames    Apply a warmer colour temperature
                  -v, --vignette-frames  Darken the frame edges
                  -d, --dust-frames      Render dust and hair marks
                  -j, --jitter-frames    Randomly displace frames by a few pixels
                  -m, --mottle-frames    Add cloudy emulsion mottling
                  -b, --bleed-frames     Bleed light into frames
                  -p, --pepper-frames    Pepper frames with dark spots/blotches

                Frame processors are applied in command-line order and may be repeated.
                Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,
                converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.
                """;
        assertEquals(expected, output);
    }
}
