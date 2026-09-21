/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Touch Chanchoronaiy (a1978787), I added notes  for further understanding.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;

/**
 * The view part of the video player model-view-controller architecture.
 *
 * <p>
 * This is the JavaFX presentation layer for the video player. It builds the
 * scene graph containing the video surface, playback controls, status display,
 * and tooltips. It exposes controls and display-update methods for
 * {@link VideoPlayerController}. The controller routes user actions; the model,
 * session and pipeline coordinate playback. The view never decodes/processes media.
 * </p>
 */
public class VideoPlayerView {

    // VideoPlayerApp creates the window, the controller wires these buttons to model actions. 
    // The model supplies displayed images/status through callbacks.
    // Only the JavaFX application thread should change these live UI nodes.
    //----------UI NODES / CONSTRUCTOR----------
    // Node is a visible/layout object. Parent can contain child nodes.
    // final fixes each stored reference, while text, icons and sizes can still be updated
    private static final int ICON_SIZE = 28;

    private final ImageView imageView = new ImageView();
    private final Label statusLabel = new Label("No video loaded");
    private final Label tooltipLabel = new Label();
    private final Pane tooltipLayer = new Pane();
    private final StackPane videoPane;
    private final StackPane root;
    private final Button startOverButton;
    private final Button backFiveButton;
    private final Button pausePlayButton;
    private final Button forwardFiveButton;
    private final Button stopButton;
    private final Button audioButton;

    public VideoPlayerView() {
        // Bind fit size to the surrounding pane so resizing updates the image automatically. 
        // Preserve ratio to avoid stretching the source picture.
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);

        videoPane = new StackPane(imageView);
        videoPane.setStyle("-fx-background-color: black;");
        videoPane.setMinSize(0, 0);
        videoPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        imageView.fitWidthProperty().bind(videoPane.widthProperty());
        imageView.fitHeightProperty().bind(videoPane.heightProperty());

        // Build icons/buttons. controller attaches their playback actions.
        startOverButton = iconButton(createLoopIcon(false, null));
        backFiveButton = iconButton(createLoopIcon(false, "5"));
        pausePlayButton = iconButton(createPauseIcon());
        forwardFiveButton = iconButton(createLoopIcon(true, "5"));
        stopButton = iconButton(createStopIcon());
        audioButton = iconButton(createAudioIcon(false));
        setPlaybackControlsDisabled(true);

        // HBox arranges controls horizontally; BorderPane puts them below video
        HBox controls = new HBox(
                10,
                startOverButton,
                backFiveButton,
                pausePlayButton,
                forwardFiveButton,
                stopButton,
                audioButton,
                statusLabel);

        controls.setPadding(new Insets(10));

        BorderPane content = new BorderPane();
        content.setCenter(videoPane);
        content.setBottom(controls);

        configureTooltipLabel();

        // StackPane overlays the tooltip layer above content without decoding work
        root = new StackPane(content, tooltipLayer);
        setButtonTooltips();
    }

    //----------ACCESSORS / DISPLAY UPDATES----------
    // Controller callbacks call these methods on the JavaFX application thread.
    // Updating the view is different from applying an effect to native frame pixels
    public Parent getRoot() {
        return root;
    }

    // Expose the existing button objects so the controller can attach actions.
    // A getter does not construct another button or start playback by itself.
    public Button getStartOverButton() {
        return startOverButton;
    }

    public Button getBackFiveButton() {
        return backFiveButton;
    }

    public Button getPausePlayButton() {
        return pausePlayButton;
    }

    public Button getForwardFiveButton() {
        return forwardFiveButton;
    }

    public Button getStopButton() {
        return stopButton;
    }

    public Button getAudioButton() {
        return audioButton;
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    // Display the already-converted JavaFX Image; effects/decoding happen elsewhere.
    public void setImage(Image image) {
        imageView.setImage(image);
    }

    // Set the preferred layout size. 
    // The constructor's bindings still adapt the ImageView when the user resizes the window, keeping the aspect ratio.
    public void setVideoDisplaySize(double width, double height) {
        videoPane.setPrefSize(width, height);
    }

    // These icons show the AVAILABLE ACTION: while playing, offer pause.
    //  while paused, offer play. Replacing the graphic does not pause/resume the model.
    public void showPauseAction() {
        pausePlayButton.setGraphic(createPauseIcon());
    }

    public void showPlayAction() {
        pausePlayButton.setGraphic(createPlayIcon());
    }

    // Update the speaker graphic only; opening/muting the device is model work.
    public void showAudioEnabled(boolean audioEnabled) {
        audioButton.setGraphic(createAudioIcon(audioEnabled));
    }

    // Disable/enable the controls together, for example while no playable video is ready.
    public void setPlaybackControlsDisabled(boolean disabled) {
        startOverButton.setDisable(disabled);
        backFiveButton.setDisable(disabled);
        pausePlayButton.setDisable(disabled);
        forwardFiveButton.setDisable(disabled);
        stopButton.setDisable(disabled);
        audioButton.setDisable(disabled);
    }

    // There are a bunch of tooltip handling methods starting from here. We want to use tooltips so that people with
    // mouse/trackpads can learn what the hotkeys are and hopefully just use them instead, because they are easy/fast.
//----------TOOLTIPS / COORDINATE CONVERSION----------
// handlers below display hotkey hints only,, then lambdas receive mouse events later. Nothing here starts an effect/decoder worker.
    private void setButtonTooltips() {
        setButtonTooltip(startOverButton, "[HOME]");
        setButtonTooltip(backFiveButton, "[LEFT]");
        setButtonTooltip(pausePlayButton, "[SPACE]");
        setButtonTooltip(forwardFiveButton, "[RIGHT]");
        setButtonTooltip(stopButton, "[ESC]");
        setButtonTooltip(audioButton, "[A]");
    }

    // event -> { ... } is a lambda callback: JavaFX runs it when that mouse
    // event occurs, not immediately during setup. _ means the parameter is unused.
    private void setButtonTooltip(Button button, String text) {
        button.setOnMouseEntered(event -> {
            showTooltip(text, event.getSceneX(), event.getSceneY());
        });
        button.setOnMouseMoved(event -> {
            if (tooltipLabel.isVisible()) {
                moveTooltip(event.getSceneX(), event.getSceneY());
            }
        });
        button.setOnMouseExited(_ -> hideTooltip());
        button.setOnMousePressed(_ -> hideTooltip());
    }

    // Mouse-transparent overlays do not intercept clicks meant for video/buttons.
    // setStyle accepts JavaFX CSS property text, not Java executable statements
    private void configureTooltipLabel() {
        tooltipLayer.setMouseTransparent(true);
        tooltipLayer.getChildren().add(tooltipLabel);

        tooltipLabel.setVisible(false);
        tooltipLabel.setMouseTransparent(true);
        tooltipLabel.setStyle(
                "-fx-background-color: #ffffdc;"
                        + "-fx-border-color: #8a8a8a;"
                        + "-fx-border-width: 1;"
                        + "-fx-padding: 4 7 4 7;"
                        + "-fx-font-size: 12px;"
                        + "-fx-text-fill: black;");
    }

    // Apply CSS/layout before positioning so the label's measured size is current.
    // toFront raises the overlay without changing playback or creating a window.
    private void showTooltip(String text, double sceneX, double sceneY) {
        tooltipLabel.setText(text);
        tooltipLabel.setVisible(true);
        tooltipLabel.applyCss();
        tooltipLabel.autosize();
        tooltipLayer.toFront();
        tooltipLabel.toFront();
        moveTooltip(sceneX, sceneY);
    }

    // Convert scene coordinates to overlay-local coordinates, then clamp the hint inside the visible layer so it doesnt disappear beyond the window.
    private void moveTooltip(double sceneX, double sceneY) {
        Point2D point = tooltipLayer.sceneToLocal(sceneX + 12, sceneY + 16);
        double maxX = Math.max(0, tooltipLayer.getWidth() - tooltipLabel.getWidth() - 2);
        double maxY = Math.max(0, tooltipLayer.getHeight() - tooltipLabel.getHeight() - 2);
        tooltipLabel.relocate(
                Math.max(0, Math.min(point.getX(), maxX)),
                Math.max(0, Math.min(point.getY(), maxY)));
    }

    private void hideTooltip() {
        tooltipLabel.setVisible(false);
    }

//----------REUSABLE BUTTON / ICON DRAWING----------
// Helpers below build vector/canvas graphics instead of loading image files.
    // The button receives clicks, not its decoration. Pick-on-bounds allows the
    // whole rectangular area focus settings avoid tab-focus on these controls.
    private Button iconButton(Node icon) {
        Button button = new Button();
        icon.setMouseTransparent(true);
        button.setGraphic(icon);
        button.setPickOnBounds(true);
        button.setFocusTraversable(false);
        button.setMinSize(42, 36);
        button.setPrefSize(42, 36);
        return button;
    }

    // Decided to hand code icons here instead of using pre-drawn assets/resources, 
    // since the icons are simple and universally recognised and this one they will look better on different DPI displays.
    //----------DRAWING THE REPLAY / SKIP SYMBOLS----------
    private Node createLoopIcon(boolean clockwise, String text) {
        Pane pane = new Pane();
        pane.setMinSize(ICON_SIZE, ICON_SIZE);
        pane.setPrefSize(ICON_SIZE, ICON_SIZE);
        pane.setMaxSize(ICON_SIZE, ICON_SIZE);

        SVGPath loop = new SVGPath();
        loop.setContent(clockwise
                ? "M 10 6 "
                        + "C 7 7.5 5 10.5 5 14 "
                        + "C 5 19 9 23 14 23 "
                        + "C 19 23 23 19 23 14 " // ~3/4 circle bezier curve empty top right
                : "M 5 14 "
                        + "C 5 19 9 23 14 23 "
                        + "C 19 23 23 19 23 14 "
                        + "C 23 10.5 21 7.5 18 6"); // ~3/4 circle bezier curve empty top right
        loop.setFill(null);
        loop.setStroke(Color.BLACK);
        loop.setStrokeWidth(2.25);
        loop.setStrokeLineCap(StrokeLineCap.ROUND);

        Polygon arrowHead = clockwise
                ? new Polygon(18, 5, 11.5, 1.5, 11.5, 8.5)
                : new Polygon(10, 5, 16.5, 1.5, 16.5, 8.5);
        arrowHead.setFill(Color.BLACK);

        pane.getChildren().addAll(loop, arrowHead);

        if (text != null) {
            Label label = new Label(text);
            label.setAlignment(Pos.CENTER);
            label.setMinSize(ICON_SIZE, ICON_SIZE);
            label.setPrefSize(ICON_SIZE, ICON_SIZE);
            label.setMaxSize(ICON_SIZE, ICON_SIZE);
            label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: black;");
            label.setMouseTransparent(true);
            pane.getChildren().add(label);
        }

        return pane;
    }

    // two vertical bars 'pause' icon
    private Canvas createPauseIcon() {
        Canvas canvas = new Canvas(ICON_SIZE, ICON_SIZE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(9, 7, 4, 14);
        gc.fillRect(16, 7, 4, 14);
        return canvas;
    }

    // right facing triangle 'play' icon
    private Canvas createPlayIcon() {
        Canvas canvas = new Canvas(ICON_SIZE, ICON_SIZE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillPolygon(
                new double[] { 10, 10, 21 },
                new double[] { 7, 21, 14 },
                3);
        return canvas;
    }

    //  'stop' icon
    private Canvas createStopIcon() {
        Canvas canvas = new Canvas(ICON_SIZE, ICON_SIZE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(9, 9, 11, 11);
        return canvas;
    }

    // Kind of speaker looking thing ...
    private Node createAudioIcon(boolean audioEnabled) {
        Pane pane = new Pane();
        pane.setMinSize(ICON_SIZE, ICON_SIZE);
        pane.setPrefSize(ICON_SIZE, ICON_SIZE);
        pane.setMaxSize(ICON_SIZE, ICON_SIZE);

        Polygon speaker = new Polygon(
                5, 11,
                10, 11,
                16, 6,
                16, 22,
                10, 17,
                5, 17);
        speaker.setFill(Color.BLACK);
        pane.getChildren().add(speaker);

        if (audioEnabled) {
            SVGPath innerWave = audioWave("M 18 11 C 20 12.5 20 15.5 18 17");
            SVGPath outerWave = audioWave("M 20.5 8 C 24 11.25 24 16.75 20.5 20");
            pane.getChildren().addAll(innerWave, outerWave);
        } else {
            Line slashDown = audioLine(19, 9, 25, 19);
            Line slashUp = audioLine(25, 9, 19, 19);
            pane.getChildren().addAll(slashDown, slashUp);
        }

        return pane;
    }

    // Little helper for different radius waves coming out of the speaker.
    private SVGPath audioWave(String content) {
        SVGPath wave = new SVGPath();
        wave.setContent(content);
        wave.setFill(null);
        wave.setStroke(Color.BLACK);
        wave.setStrokeWidth(2.0);
        wave.setStrokeLineCap(StrokeLineCap.ROUND);
        return wave;
    }

    // Helper to draw a cross over the speaker indicating that we're muted.
    private Line audioLine(double startX, double startY, double endX, double endY) {
        Line line = new Line(startX, startY, endX, endY);
        line.setStroke(Color.BLACK);
        line.setStrokeWidth(2.0);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        return line;
    }
}
