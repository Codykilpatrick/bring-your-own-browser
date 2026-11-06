package demo.fc;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Fire Control: a rough JavaFX 8 app. Graphic-only buttons, a context menu, a second Stage per
 * engagement, a Swing dialog opened from FX, java.util.logging, a blocked FX thread, and an uncaught
 * exception on the FX thread.
 */
public class FireControl extends Application {
    static final Logger LOG = Logger.getLogger("fc");
    static final Random RND = new Random(7);
    static final List<byte[]> SOLUTIONS = new ArrayList<byte[]>();

    Stage stage;
    TabPane tabs;
    MenuBar menuBar;
    TableView<Track> trackTable;
    final ObservableList<Track> tracks = FXCollections.observableArrayList();
    final ObservableList<String> engagements = FXCollections.observableArrayList();
    ListView<String> engagementList;
    Button designate, drop, engage, compute, lastFire;
    ToggleButton auto;
    CheckBox ready;
    ComboBox<String> mode;
    Slider range;
    Spinner<Integer> salvo;
    TextField override;
    Label status = new Label("Safe");
    int engagementCount;

    public static void main(String[] args) {
        LOG.info("Fire Control starting on Java " + System.getProperty("java.version"));
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        stage = primary;
        stage.setTitle("Fire Control");
        for (int i = 0; i < 8; i++) addTrack();

        BorderPane root = new BorderPane();
        menuBar = menuBar();
        root.setTop(new VBox(menuBar, toolBar()));
        tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Tracks", tracksPane()), new Tab("Engagements", engagementsPane()), new Tab("Weapons", weaponsPane()));
        root.setCenter(tabs);
        HBox bottom = new HBox(status);
        bottom.setPadding(new Insets(4));
        root.setBottom(bottom);

        stage.setScene(new Scene(root, 1024, 768));
        stage.setX(0);
        stage.setY(0);
        stage.show();
        LOG.info("main window shown");

        javafx.animation.Timeline tick = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(1), e -> {
            SOLUTIONS.add(new byte[100 * 1024]);
            if (RND.nextInt(5) == 0) addTrack();
        }));
        tick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        tick.play();
        javafx.animation.Timeline bounds = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(1), e -> dumpBounds()));
        bounds.setCycleCount(javafx.animation.Animation.INDEFINITE);
        bounds.play();
    }

    MenuBar menuBar() {
        Menu engageMenu = new Menu("_Engage");
        MenuItem newEng = new MenuItem("_New Engagement...");
        newEng.setAccelerator(KeyCombination.keyCombination("Ctrl+N"));
        newEng.setOnAction(e -> newEngagement());
        MenuItem abortAll = new MenuItem("_Abort All");
        abortAll.setOnAction(e -> { engagements.clear(); status.setText("All engagements aborted"); LOG.warning("abort all"); });
        MenuItem exit = new MenuItem("E_xit");
        exit.setOnAction(e -> Platform.exit());
        engageMenu.getItems().addAll(newEng, abortAll, exit);

        Menu weapons = new Menu("_Weapons");
        MenuItem arm = new MenuItem("_Arm");
        arm.setOnAction(e -> { status.setText("ARMED"); LOG.info("armed"); });
        MenuItem safe = new MenuItem("_Safe");
        safe.setOnAction(e -> { status.setText("Safe"); LOG.info("safe"); });
        MenuItem selfTest = new MenuItem("Self _Test");
        selfTest.setOnAction(e -> {
            LOG.warning("self test requested");
            throw new IllegalStateException("Self test failed: launcher 2 interlock open");
        });
        weapons.getItems().addAll(arm, safe, selfTest);

        Menu view = new Menu("_View");
        MenuItem settings = new MenuItem("_Settings...");
        settings.setOnAction(e -> SettingsDialog.show());
        view.getItems().add(settings);

        Menu help = new Menu("_Help");
        MenuItem about = new MenuItem("_About");
        about.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Fire Control 1.4\nJava " + System.getProperty("java.version"));
            a.setTitle("About");
            a.setHeaderText(null);
            a.showAndWait();
        });
        help.getItems().add(about);
        return new MenuBar(engageMenu, weapons, view, help);
    }

    HBox toolBar() {
        designate = new Button();
        designate.setGraphic(new Circle(8, Color.LIMEGREEN));
        designate.setTooltip(new Tooltip("Designate track"));
        designate.setOnAction(e -> designate());
        drop = new Button();
        drop.setGraphic(new Rectangle(16, 16, Color.CRIMSON));
        drop.setOnAction(e -> dropTrack());
        auto = new ToggleButton("Auto");
        auto.setOnAction(e -> LOG.info("auto " + auto.isSelected()));
        HBox bar = new HBox(6, designate, drop, auto);
        bar.setPadding(new Insets(4));
        return bar;
    }

    BorderPane tracksPane() {
        trackTable = new TableView<Track>(tracks);
        trackTable.setId("tracks");
        TableColumn<Track, String> id = new TableColumn<Track, String>("Track");
        id.setCellValueFactory(new PropertyValueFactory<Track, String>("id"));
        TableColumn<Track, Number> bearing = new TableColumn<Track, Number>("Bearing");
        bearing.setCellValueFactory(new PropertyValueFactory<Track, Number>("bearing"));
        TableColumn<Track, Number> rng = new TableColumn<Track, Number>("Range");
        rng.setCellValueFactory(new PropertyValueFactory<Track, Number>("range"));
        TableColumn<Track, String> cls = new TableColumn<Track, String>("Class");
        cls.setCellValueFactory(new PropertyValueFactory<Track, String>("cls"));
        trackTable.getColumns().addAll(id, bearing, rng, cls);
        ContextMenu cm = new ContextMenu();
        MenuItem cmDesignate = new MenuItem("Designate");
        cmDesignate.setOnAction(e -> designate());
        MenuItem cmDrop = new MenuItem("Drop track");
        cmDrop.setOnAction(e -> dropTrack());
        cm.getItems().addAll(cmDesignate, cmDrop);
        trackTable.setContextMenu(cm);
        BorderPane p = new BorderPane(trackTable);
        return p;
    }

    BorderPane engagementsPane() {
        engagementList = new ListView<String>(engagements);
        engage = new Button("Engage");
        engage.setOnAction(e -> newEngagement());
        Button hold = new Button("Hold All");
        hold.setOnAction(e -> status.setText("Holding"));
        HBox buttons = new HBox(6, engage, hold);
        buttons.setPadding(new Insets(6));
        BorderPane p = new BorderPane(engagementList);
        p.setBottom(buttons);
        return p;
    }

    VBox weaponsPane() {
        ready = new CheckBox("Weapon 1 ready");
        ready.setOnAction(e -> LOG.info("weapon 1 ready " + ready.isSelected()));
        mode = new ComboBox<String>(FXCollections.observableArrayList("Manual", "Semi", "Auto"));
        mode.getSelectionModel().selectFirst();
        mode.setOnAction(e -> LOG.info("mode " + mode.getValue()));
        range = new Slider(0, 50, 10);
        range.setShowTickLabels(true);
        range.setShowTickMarks(true);
        range.setPrefWidth(300);
        salvo = new Spinner<Integer>(1, 8, 2);
        override = new TextField();
        override.setPromptText("Range override");
        override.setId("rangeOverride");
        override.setOnAction(e -> {
            try {
                range.setValue(Double.parseDouble(override.getText()));
                status.setText("Range override " + override.getText());
            } catch (NumberFormatException ex) {
                LOG.log(Level.SEVERE, "bad override '" + override.getText() + "'", ex);
            }
        });
        compute = new Button("Compute Solution");
        compute.setOnAction(e -> {
            status.setText("Computing...");
            LOG.info("compute started");
            // Blocks the FX thread on purpose.
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            status.setText("Solution ready");
            System.out.println("stdout: solution ready");
        });
        VBox v = new VBox(10, ready, new HBox(6, new Label("Mode"), mode), new Label("Range (km)"), range,
                new HBox(6, new Label("Salvo"), salvo), new HBox(6, new Label("Override"), override), compute);
        v.setPadding(new Insets(12));
        return v;
    }

    void addTrack() {
        tracks.add(new Track("T" + (200 + tracks.size()), RND.nextInt(360), 2 + RND.nextInt(40), RND.nextBoolean() ? "Hostile" : "Unknown"));
    }

    void designate() {
        Track t = trackTable.getSelectionModel().getSelectedItem();
        if (t == null) { status.setText("Select a track first"); return; }
        status.setText("Designated " + t.getId());
        LOG.info("designated " + t.getId());
    }

    void dropTrack() {
        Track t = trackTable.getSelectionModel().getSelectedItem();
        if (t != null) { tracks.remove(t); LOG.info("dropped " + t.getId()); }
    }

    void newEngagement() {
        engagementCount++;
        final String name = "Engagement " + engagementCount;
        engagements.add(name);
        final Stage s = new Stage();
        s.setTitle(name);
        s.initOwner(stage);
        Button fire = new Button("Fire");
        lastFire = fire;
        fire.setOnAction(e -> { status.setText(name + ": FIRED"); LOG.warning(name + " fired"); });
        Button hold = new Button("Hold");
        hold.setOnAction(e -> status.setText(name + ": hold"));
        Button abort = new Button("Abort");
        abort.setOnAction(e -> { engagements.remove(name); s.close(); });
        VBox v = new VBox(10, new Label(name + " on " + (tracks.isEmpty() ? "no track" : tracks.get(0).getId())), new HBox(6, fire, hold, abort));
        v.setPadding(new Insets(12));
        s.setScene(new Scene(v, 320, 140));
        s.setX(1024 - 320);
        s.setY(0);
        s.show();
    }

    /** Test harness only: screen coordinates for the scripted click list. */
    void dumpBounds() {
        try {
            PrintWriter w = new PrintWriter(new FileWriter("/tmp/fire-control-bounds.sh.tmp"));
            put(w, "designate", designate); put(w, "drop", drop); put(w, "auto", auto); put(w, "tracks", trackTable);
            put(w, "engage", engage); put(w, "engagements", engagementList); put(w, "ready", ready); put(w, "mode", mode);
            put(w, "range", range); put(w, "salvo", salvo); put(w, "override", override); put(w, "compute", compute);
            put(w, "fire", lastFire);
            for (Node n : menuBar.lookupAll(".menu-button")) {
                if (n instanceof javafx.scene.control.Labeled) put(w, "menu_" + ((javafx.scene.control.Labeled) n).getText().replace("_", "").toLowerCase(), n);
            }
            int i = 0;
            for (Node n : tabs.lookupAll(".tab")) {
                Tab t = tabs.getTabs().get(i++);
                put(w, "tab_" + t.getText().toLowerCase(), n);
            }
            w.close();
            new File("/tmp/fire-control-bounds.sh.tmp").renameTo(new File("/tmp/fire-control-bounds.sh"));
        } catch (Exception e) {
            // not showing yet
        }
    }

    static void put(PrintWriter w, String name, Node n) {
        if (n == null || n.getScene() == null || n.getScene().getWindow() == null) return;
        Bounds b = n.localToScreen(n.getBoundsInLocal());
        if (b == null) return;
        w.println(name + "=\"" + (int) (b.getMinX() + b.getWidth() / 2) + " " + (int) (b.getMinY() + b.getHeight() / 2) + "\"");
        w.println(name + "_tl=\"" + (int) b.getMinX() + " " + (int) b.getMinY() + "\"");
        w.println(name + "_wh=\"" + (int) b.getWidth() + " " + (int) b.getHeight() + "\"");
    }

    public static class Track {
        final SimpleStringProperty id = new SimpleStringProperty(), cls = new SimpleStringProperty();
        final SimpleIntegerProperty bearing = new SimpleIntegerProperty(), range = new SimpleIntegerProperty();

        Track(String id, int bearing, int range, String cls) {
            this.id.set(id); this.bearing.set(bearing); this.range.set(range); this.cls.set(cls);
        }

        public String getId() { return id.get(); }
        public int getBearing() { return bearing.get(); }
        public int getRange() { return range.get(); }
        public String getCls() { return cls.get(); }
    }
}
