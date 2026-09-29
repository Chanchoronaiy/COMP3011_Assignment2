
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
    // @TempDir supplies an isolated temporary path
    @TempDir Path directory;
    private String movie() throws Exception { return Files.createFile(directory.resolve("movie.mp4")).toString(); }
    private List<Class<?>> classes(CommandLineController command) {
        return command.getFrameProcessors().stream().<Class<?>>map(Object::getClass).toList();
    }

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
            assertNotSame(command.getFrameProcessors().get(2), command.getFrameProcessors().get(3));
            assertTrue(command.shouldLaunchApplication());
        }
    }
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
    @Test void invalidOptionsFilesAndConflictingDisplaysDoNotLaunch() throws Exception {
        String file = movie();
        for (String[] args : List.of(new String[]{"-q", file}, new String[]{"--unknown", file},
                new String[]{"-12", file}, new String[]{file, file}, new String[]{"missing.mp4"}, new String[]{})) {
            CommandLineController command = new CommandLineController(args);
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }
    }

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

    @Test void optionNamesAreCaseSensitiveAndNoEffectsMeansAnEmptyList() throws Exception {
        String file = movie();
        assertTrue(new CommandLineController(new String[]{file}).getFrameProcessors().isEmpty());
        for (String flag : List.of("-N", "--Number-Frames", "--dust-frame", "--vignette-frame")) {
            CommandLineController command = new CommandLineController(new String[]{flag, file});
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        }
    }

    @Test void helpStillPrintsWithInvalidInputAsInTheStarter() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            CommandLineController command = new CommandLineController(new String[]{"--help", "--unknown"});
            assertEquals(1, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        } finally { System.setOut(original); }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Unknown option: --unknown"));
        assertTrue(output.contains("Usage: VideoPlayer [options] [video-file]"));
        assertTrue(output.contains("Frame processors:"));
    }
    @Test void helpMatchesRequiredEffectParagraphsWithoutLaunchingUi() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            CommandLineController command = new CommandLineController(new String[]{"--help"});
            assertEquals(0, command.getExitCode());
            assertFalse(command.shouldLaunchApplication());
        } finally { System.setOut(original); }
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
