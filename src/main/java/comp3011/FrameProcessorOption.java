/*
 * New file author: Touch Chanchoronaiy (a1978787).
 * PPURPOSE: central effect registry
 * WHY: parsing/help share one mapping, and each occurrence creates its own processor rather than sharing mutable state.
 */
package comp3011;

import java.util.function.Supplier;

/** One place to register an effect's flags, description and constructor. */
public enum FrameProcessorOption {
//----------AVAILABLE FILM EFFECTS----------
    // recap: enum = a fixed collection of named choices
    // Each entry below stores its short flag, long flag, help description and the constructor for its effect
    NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

//----------ADDING ANOTHER EFFECT----------
    // add an enum entry above and a FrameProcessor subclass
    // parsing and help use this registry, so they do not need another chain of if statements
    // Supplier is a factory: ::new stores HOW to construct an effect, not one shared effect
    // each occurrence, example both 's' characters in -ss, gets new().
    private final char shortFlag; // char stores one character, e.g. 'n'.
    private final String longFlag; // String stores text, e.g. "number-frames".
    private final String description;
    // recap: <FrameProcessor> supplier returns a FrameProcessor. Final fixes the stored factory reference, not its results.
    private final Supplier<FrameProcessor> factory;

    // enum constructor stores the four values supplied by each entry.
    FrameProcessorOption(char shortFlag, String longFlag, String description, Supplier<FrameProcessor> factory) {
        this.shortFlag = shortFlag;
        this.longFlag = longFlag;
        this.description = description;
        this.factory = factory;
    }

    // get() invokes the saved constructor. 
    // recap: Polymorphism lets the parser use the common FrameProcessor type without knowing each subclass's pixel algorithm.
    public FrameProcessor createProcessor() { return factory.get(); }
    // %c prints a character      %-16s left-aligns text in a minimum-width column
    // Keep this formatting stable: the CLI regression checks exact help output.
    public String helpLine() {
        return String.format("  -%c, --%-16s %s", shortFlag, longFlag, description);
    }
    // static lets parser call FrameProcessorOption.fromShort('n') without already having an option object. 
    // A lookup returns null when no flag matches.
    public static FrameProcessorOption fromShort(char flag) {
        for (FrameProcessorOption option : values()) {
            if (option.shortFlag == flag) return option;
        }
        return null;
    }
    public static FrameProcessorOption fromLong(String flag) {
        for (FrameProcessorOption option : values()) {
            if (("--" + option.longFlag).equals(flag)) return option;
        }
        return null;
    }
}
