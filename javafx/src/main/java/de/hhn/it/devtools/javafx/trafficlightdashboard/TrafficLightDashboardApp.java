package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javax.swing.SwingUtilities;
import org.openstreetmap.gui.jmapviewer.Coordinate;
import org.openstreetmap.gui.jmapviewer.DefaultMapController;
import org.openstreetmap.gui.jmapviewer.JMapViewer;
import org.openstreetmap.gui.jmapviewer.OsmTileLoader;
import org.openstreetmap.gui.jmapviewer.events.JMVCommandEvent;
import org.openstreetmap.gui.jmapviewer.interfaces.ICoordinate;

import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator.Rating;
import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator.WaitingTime;

/**
 * GUI for the traffic light dashboard. Shows an OpenStreetMap map (JMapViewer embedded in a
 * SwingNode) with all traffic lights, colored by their average waiting time measured by
 * senseBox:bikes on openSenseMap.
 */
public class TrafficLightDashboardApp extends Application {

  private static final org.slf4j.Logger logger =
          org.slf4j.LoggerFactory.getLogger(TrafficLightDashboardApp.class);
  /**
   * Bike data of this period is loaded on the first start, so the averages do not start empty.
   * 90 days are about 28 MB and cover about a third of the traffic lights. Later starts read
   * the cache and only load what is new.
   */
  private static final Duration HISTORY = Duration.ofDays(90);
  private static final long REFRESH_MINUTES = 2;
  /** Each refresh reloads this much already loaded data, for measurements uploaded late. */
  private static final Duration REFRESH_OVERLAP = Duration.ofMinutes(30);
  private static final long TRAFFIC_LIGHT_RETRY_MINUTES = 1;
  /** Traffic lights rarely change, cached ones are only reloaded after this time. */
  private static final Duration TRAFFIC_LIGHT_MAX_AGE = Duration.ofDays(30);
  private static final Path CACHE_DIRECTORY = Path.of(System.getProperty("user.home"),
          ".smarting-devtools", "traffic-light-dashboard");
  /** The visible map plus this fraction of its size on each side is loaded when not cached. */
  private static final double PRELOAD_MARGIN = 0.25;
  /** Moving the map loads new areas only after it stopped for this long. */
  private static final long AREA_LOAD_DELAY_MILLIS = 500;
  private static final MapArea TRAFFIC_LIGHT_REGION = TrafficDataLoader.TRAFFIC_LIGHT_REGION;

  // Heilbronn
  private static final Coordinate MAP_CENTER = new Coordinate(49.1427, 9.2109);
  private static final int INITIAL_ZOOM = 14;
  /** The traffic lights are only shown from this zoom level on. */
  private static final int MIN_TRAFFIC_LIGHT_ZOOM = 14;
  /** A found address is shown at least at this zoom level. */
  private static final int SEARCH_ZOOM = 16;
  /** Clicks closer than this many pixels to a traffic light select it. */
  private static final int CLICK_RADIUS_PIXELS = 15;
  // the OSM tile usage policy requires an identifying user agent
  private static final String USER_AGENT = "smarting-devtools-traffic-light-dashboard/1.0";
  private static final DateTimeFormatter DATE_TIME_FORMAT =
          DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter TIME_FORMAT =
          DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM);

  private final TrafficDataLoader loader = new TrafficDataLoader();
  private final TrafficDataCache cache = new TrafficDataCache(CACHE_DIRECTORY);
  private final ScheduledExecutorService scheduler =
          Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "traffic-data-loader");
            thread.setDaemon(true);
            return thread;
          });
  // only used on the scheduler thread
  private WaitingTimeCalculator calculator;
  private Instant loadedUntil;
  private LoadedArea loadedArea;
  private List<TrafficLight> trafficLights;
  // set on the Swing event dispatch thread, read on the scheduler thread
  private final AtomicReference<MapArea> visibleArea = new AtomicReference<>();
  private final AtomicBoolean areaLoadScheduled = new AtomicBoolean();
  // only used on the Swing event dispatch thread
  private JMapViewer map;
  private final Map<Long, TrafficLightMarker> markers = new HashMap<>();
  private TrafficLightMarker selectedMarker;
  // only used on the JavaFX application thread
  private Label statusLabel;
  private Label detailsTitle;
  private Label detailsText;
  private VBox detailsBox;
  private Label sidebarCount;
  private ListView<SidebarEntry> sidebarList;
  private List<SidebarEntry> sidebarEntries = List.of();
  private boolean longestWaitFirst = true;

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void start(Stage stage) {
    SwingNode mapNode = new SwingNode();
    SwingUtilities.invokeLater(() -> mapNode.setContent(createMap()));

    VBox legend = createLegend();
    StackPane.setAlignment(legend, Pos.BOTTOM_LEFT);
    StackPane.setMargin(legend, new Insets(10));
    detailsBox = createDetailsBox();
    StackPane.setAlignment(detailsBox, Pos.TOP_RIGHT);
    StackPane.setMargin(detailsBox, new Insets(10));
    VBox searchBox = createSearchBox();
    StackPane.setAlignment(searchBox, Pos.TOP_LEFT);
    StackPane.setMargin(searchBox, new Insets(10));

    BorderPane root = new BorderPane(new StackPane(mapNode, legend, detailsBox, searchBox));
    root.setLeft(createSidebar());

    stage.setTitle("Traffic Light Dashboard");
    stage.setScene(new Scene(root, 1280, 720));
    stage.show();

    setStatus("Loading traffic lights ...");
    scheduler.execute(this::loadTrafficLights);
  }

  @Override
  public void stop() {
    scheduler.shutdownNow();
    // JMapViewer's tile loader threads are not daemon threads and cannot be shut down,
    // they would keep the JVM running after the window is closed
    System.exit(0);
  }

  // Swing event dispatch thread
  private JMapViewer createMap() {
    map = new JMapViewer();
    map.setTileLoader(new OsmTileLoader(map, new HashMap<>(Map.of("User-Agent", USER_AGENT))));
    map.setDisplayPosition(MAP_CENTER, INITIAL_ZOOM);
    // JMapViewer pans with the right mouse button by default, its controller has no getter
    for (MouseListener listener : map.getMouseListeners()) {
      if (listener instanceof DefaultMapController controller) {
        controller.setMovementMouseButton(MouseEvent.BUTTON1);
      }
    }
    map.addJMVListener(event -> {
      if (event.getCommand() == JMVCommandEvent.COMMAND.ZOOM) {
        map.setMapMarkerVisible(map.getZoom() >= MIN_TRAFFIC_LIGHT_ZOOM);
      }
      requestVisibleArea();
      updateSidebar();
    });
    map.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent event) {
        requestVisibleArea();
        updateSidebar();
      }
    });
    map.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1) {
          select(markerAt(event.getPoint()));
        }
      }
    });
    return map;
  }

  /**
   * Remembers the visible part of the map and schedules loading it, if traffic lights are shown.
   * Runs on the Swing event dispatch thread.
   */
  private void requestVisibleArea() {
    if (map.getZoom() < MIN_TRAFFIC_LIGHT_ZOOM || map.getWidth() == 0 || map.getHeight() == 0) {
      return;
    }
    visibleArea.set(shownArea());
    // while the map is dragged only one load is scheduled, it uses the last visible area
    if (areaLoadScheduled.compareAndSet(false, true)) {
      scheduler.schedule(this::loadVisibleArea, AREA_LOAD_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }
  }

  // Swing event dispatch thread
  private MapArea shownArea() {
    ICoordinate topLeft = map.getPosition(0, 0);
    ICoordinate bottomRight = map.getPosition(map.getWidth(), map.getHeight());
    return new MapArea(bottomRight.getLat(), topLeft.getLon(),
            topLeft.getLat(), bottomRight.getLon());
  }

  /**
   * Hands the measured traffic lights in the visible part of the map to the sidebar.
   * Runs on the Swing event dispatch thread.
   */
  private void updateSidebar() {
    if (map.getZoom() < MIN_TRAFFIC_LIGHT_ZOOM || map.getWidth() == 0 || map.getHeight() == 0) {
      Platform.runLater(() -> showSidebarEntries(null));
      return;
    }
    MapArea shown = shownArea();
    List<SidebarEntry> entries = new ArrayList<>();
    for (TrafficLightMarker marker : markers.values()) {
      WaitingTime waitingTime = marker.getWaitingTime();
      if (waitingTime != null && waitingTime.waits() > 0
              && shown.contains(marker.getLat(), marker.getLon())) {
        entries.add(new SidebarEntry(marker, marker.getName(), waitingTime.averageSeconds(),
                waitingTime.waits(), waitingTime.rating()));
      }
    }
    Platform.runLater(() -> showSidebarEntries(entries));
  }

  /**
   * Loads the bike data of the visible part of the map, if it is not cached yet. The traffic
   * lights of the whole region are loaded already.
   * Runs on the scheduler thread.
   */
  private void loadVisibleArea() {
    areaLoadScheduled.set(false);
    MapArea visible = visibleArea.get();
    if (calculator == null || visible == null) {
      // loadTrafficLights calls this again when it is done
      return;
    }
    Optional<MapArea> missing = loadedArea.missingPart(visible.expandedBy(PRELOAD_MARGIN));
    if (missing.isEmpty()) {
      return;
    }
    MapArea area = missing.get();
    // without loadedUntil the first bike data load is still pending, it covers the new area
    Optional<List<SpeedMeasurement>> newMeasurements = Optional.of(List.of());
    if (loadedUntil != null) {
      setStatus("Loading bike data of the new area ...");
      try {
        newMeasurements =
                loader.fetchBikeSpeeds(area, Instant.now().minus(HISTORY), loadedUntil);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (newMeasurements.isEmpty()) {
      // the area stays missing and is loaded again when the map is moved
      setStatus("Bike data of the new area unavailable, move the map to retry");
      return;
    }

    loadedArea.add(area);
    List<SpeedMeasurement> cachedMeasurements = cache.readBikeSpeeds();
    Set<SpeedMeasurement> known = new HashSet<>(cachedMeasurements);
    List<SpeedMeasurement> unknown = newMeasurements.get().stream()
            .filter(measurement -> !known.contains(measurement))
            .toList();
    // the areas are written last: after a crash in between, the area is just loaded again
    if (loadedUntil != null) {
      cache.appendBikeSpeeds(unknown, loadedUntil);
    }
    cache.writeLoadedAreas(loadedArea.rectangles());

    List<SpeedMeasurement> allMeasurements = new ArrayList<>(cachedMeasurements);
    allMeasurements.addAll(unknown);
    recalculate(allMeasurements);
  }

  /**
   * Calculates all waiting times again, after traffic lights or areas were added. The calculator
   * needs each bike's measurements in order, so they cannot just be added to the old one.
   * Runs on the scheduler thread.
   */
  private void recalculate(List<SpeedMeasurement> measurements) {
    calculator = new WaitingTimeCalculator(trafficLightsInLoadedArea());
    calculator.addMeasurements(measurements);
    showWaitingTimes();
  }

  /**
   * Returns the traffic lights the waiting times are calculated for. Outside of the loaded area
   * there is no bike data, and checking all lights of the region for each stopped measurement
   * would be slow.
   */
  private List<TrafficLight> trafficLightsInLoadedArea() {
    return trafficLights.stream()
            .filter(light -> loadedArea.contains(light.lat(), light.lon()))
            .toList();
  }

  /**
   * Loads the traffic lights and the cached bike data, then starts polling new bike data.
   * Runs on the scheduler thread.
   */
  private void loadTrafficLights() {
    loadedArea = new LoadedArea(
            cache.readLoadedAreas().orElse(List.of(TrafficDataLoader.DEFAULT_AREA)));
    Optional<List<TrafficLight>> cached = cache.readTrafficLights();
    boolean cachedAreCurrent = cached.isPresent()
            && cache.readTrafficLightsArea().equals(Optional.of(TRAFFIC_LIGHT_REGION))
            && cache.areTrafficLightsYoungerThan(TRAFFIC_LIGHT_MAX_AGE);
    if (cachedAreCurrent) {
      logger.info("Read {} cached traffic lights", cached.get().size());
      trafficLights = cached.get();
    } else {
      Optional<List<TrafficLight>> current;
      try {
        current = fetchRegionTrafficLights();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (current.isPresent()) {
        trafficLights = current.get();
      } else if (cached.isPresent()) {
        // outdated or only covering a smaller area, still better than nothing
        logger.warn("Overpass unavailable, using the cached traffic lights for now");
        trafficLights = cached.get();
        scheduler.schedule(this::reloadTrafficLights, TRAFFIC_LIGHT_RETRY_MINUTES,
                TimeUnit.MINUTES);
      } else {
        logger.warn("No Overpass server available, retrying in {} minute(s)",
                TRAFFIC_LIGHT_RETRY_MINUTES);
        setStatus("Traffic lights unavailable, retrying ...");
        scheduler.schedule(this::loadTrafficLights, TRAFFIC_LIGHT_RETRY_MINUTES,
                TimeUnit.MINUTES);
        return;
      }
    }
    calculator = new WaitingTimeCalculator(trafficLightsInLoadedArea());
    List<TrafficLight> lights = trafficLights;
    SwingUtilities.invokeLater(() -> addMarkers(lights));

    List<SpeedMeasurement> cachedMeasurements = cache.readBikeSpeeds();
    loadedUntil = cache.readBikeSpeedsLoadedUntil().orElse(null);
    if (cachedMeasurements.isEmpty() || loadedUntil == null) {
      loadedUntil = null;
      setStatus("First start: loading bike data of the last " + HISTORY.toDays() + " days ...");
    } else {
      logger.info("Read {} cached bike speed measurements loaded until {}",
              cachedMeasurements.size(), loadedUntil);
      calculator.addMeasurements(cachedMeasurements);
      showWaitingTimes();
    }
    scheduler.scheduleWithFixedDelay(this::loadBikeData, 0, REFRESH_MINUTES, TimeUnit.MINUTES);
    // the map may have been moved while the traffic lights were loading
    scheduler.execute(this::loadVisibleArea);
  }

  /** Fetches and caches the traffic lights of the whole region. */
  private Optional<List<TrafficLight>> fetchRegionTrafficLights() throws InterruptedException {
    setStatus("Loading all traffic lights of the region, this can take a few minutes ...");
    Optional<List<TrafficLight>> current = loader.fetchTrafficLights(TRAFFIC_LIGHT_REGION);
    current.ifPresent(lights -> cache.writeTrafficLights(lights, TRAFFIC_LIGHT_REGION));
    return current;
  }

  /**
   * Retries loading the traffic lights of the region while the outdated cached ones are shown.
   * Runs on the scheduler thread.
   */
  private void reloadTrafficLights() {
    Optional<List<TrafficLight>> current;
    try {
      current = fetchRegionTrafficLights();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    if (current.isEmpty()) {
      setStatus("Traffic lights of the region unavailable, retrying ...");
      scheduler.schedule(this::reloadTrafficLights, TRAFFIC_LIGHT_RETRY_MINUTES,
              TimeUnit.MINUTES);
      return;
    }
    trafficLights = current.get();
    List<TrafficLight> lights = trafficLights;
    SwingUtilities.invokeLater(() -> addMarkers(lights));
    recalculate(cache.readBikeSpeeds());
  }

  /** Fetches the bike speeds of all areas, empty if one of them could not be loaded. */
  private Optional<List<SpeedMeasurement>> fetchBikeSpeeds(List<MapArea> areas, Instant from,
                                                           Instant to) throws InterruptedException {
    List<SpeedMeasurement> measurements = new ArrayList<>();
    for (MapArea area : areas) {
      Optional<List<SpeedMeasurement>> areaMeasurements = loader.fetchBikeSpeeds(area, from, to);
      if (areaMeasurements.isEmpty()) {
        return Optional.empty();
      }
      measurements.addAll(areaMeasurements.get());
    }
    return Optional.of(measurements);
  }

  /**
   * Loads new bike speed measurements and updates the waiting times on the map.
   * Runs on the scheduler thread.
   */
  private void loadBikeData() {
    Instant to = Instant.now();
    Instant from = loadedUntil == null ? to.minus(HISTORY) : loadedUntil.minus(REFRESH_OVERLAP);
    Optional<List<SpeedMeasurement>> measurements;
    try {
      measurements = fetchBikeSpeeds(loadedArea.rectangles(), from, to);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    if (measurements.isEmpty()) {
      // the cached waiting times stay on the map
      setStatus("openSenseMap unavailable, showing cached data, retrying in "
              + REFRESH_MINUTES + " min");
      return;
    }
    List<SpeedMeasurement> newMeasurements = calculator.addMeasurements(measurements.get());
    cache.appendBikeSpeeds(newMeasurements, to);
    loadedUntil = to;
    showWaitingTimes();
  }

  // scheduler thread
  private void showWaitingTimes() {
    List<WaitingTime> waitingTimes = calculator.getWaitingTimes();
    int lightsWithData = 0;
    int waits = 0;
    for (WaitingTime waitingTime : waitingTimes) {
      if (waitingTime.waits() > 0) {
        lightsWithData++;
        waits += waitingTime.waits();
      }
    }
    SwingUtilities.invokeLater(() -> updateMarkers(waitingTimes));
    setStatus(String.format("%d measured waits at %d of %d traffic lights%nLast update: %s",
            waits, lightsWithData, waitingTimes.size(), LocalTime.now().format(TIME_FORMAT)));
  }

  // Swing event dispatch thread
  private void addMarkers(List<TrafficLight> trafficLights) {
    for (TrafficLight trafficLight : trafficLights) {
      if (!markers.containsKey(trafficLight.id())) {
        TrafficLightMarker marker = new TrafficLightMarker(trafficLight);
        markers.put(trafficLight.id(), marker);
        map.addMapMarker(marker);
      }
    }
  }

  // Swing event dispatch thread
  private void updateMarkers(List<WaitingTime> waitingTimes) {
    for (WaitingTime waitingTime : waitingTimes) {
      TrafficLightMarker marker = markers.get(waitingTime.trafficLight().id());
      if (marker != null) {
        marker.setWaitingTime(waitingTime);
      }
    }
    map.repaint();
    if (selectedMarker != null) {
      showDetails(selectedMarker);
    }
    updateSidebar();
  }

  // Swing event dispatch thread
  private TrafficLightMarker markerAt(Point point) {
    if (!map.getMapMarkersVisible()) {
      return null;
    }
    TrafficLightMarker nearest = null;
    double nearestDistance = CLICK_RADIUS_PIXELS;
    for (TrafficLightMarker marker : markers.values()) {
      Point position = map.getMapPosition(marker.getLat(), marker.getLon(), true);
      if (position != null && position.distance(point) <= nearestDistance) {
        nearest = marker;
        nearestDistance = position.distance(point);
      }
    }
    return nearest;
  }

  // Swing event dispatch thread
  private void select(TrafficLightMarker marker) {
    if (selectedMarker != null) {
      selectedMarker.setSelected(false);
    }
    selectedMarker = marker;
    if (marker == null) {
      Platform.runLater(() -> detailsBox.setVisible(false));
    } else {
      marker.setSelected(true);
      showDetails(marker);
    }
    map.repaint();
  }

  // Swing event dispatch thread, the marker state is read here and handed to JavaFX as text
  private void showDetails(TrafficLightMarker marker) {
    String title = marker.getName();
    String text = detailsText(marker.getTrafficLight(), marker.getWaitingTime());
    Platform.runLater(() -> {
      detailsTitle.setText(title);
      detailsText.setText(text);
      detailsBox.setVisible(true);
    });
  }

  private static String detailsText(TrafficLight trafficLight, WaitingTime waitingTime) {
    String text = "OSM node " + trafficLight.id() + "\n";
    if (waitingTime == null || waitingTime.waits() == 0) {
      return text + "No bike has waited here yet";
    }
    return text + String.format(Locale.ROOT,
            "Rating: %s%nAverage waiting time: %.1f s%nMeasured waits: %d%n"
                    + "Last wait: %.1f s on %s%nReported by senseBox: %s",
            ratingLabel(waitingTime.rating()), waitingTime.averageSeconds(), waitingTime.waits(),
            waitingTime.lastSeconds(), DATE_TIME_FORMAT.format(waitingTime.lastWaitAt()),
            waitingTime.lastBoxName() == null ? "unknown" : waitingTime.lastBoxName());
  }

  private static String ratingLabel(Rating rating) {
    return switch (rating) {
      case GOOD -> "Good";
      case AVERAGE -> "Average";
      case BAD -> "Bad";
      case UNKNOWN -> "No bike data yet";
    };
  }

  private VBox createLegend() {
    Label title = new Label("Average waiting time (bikes)");
    title.setStyle("-fx-font-weight: bold;");
    statusLabel = new Label("Loading map ...");
    statusLabel.setWrapText(true);
    statusLabel.setMaxWidth(240);
    statusLabel.setStyle("-fx-text-fill: #555;");
    VBox legend = new VBox(2, title,
            legendRow(Rating.GOOD, String.format(Locale.ROOT, " (≤ %.0f s)",
                    WaitingTimeCalculator.GOOD_MAX_SECONDS)),
            legendRow(Rating.AVERAGE, String.format(Locale.ROOT, " (%.0f – %.0f s)",
                    WaitingTimeCalculator.GOOD_MAX_SECONDS,
                    WaitingTimeCalculator.AVERAGE_MAX_SECONDS)),
            legendRow(Rating.BAD, String.format(Locale.ROOT, " (> %.0f s)",
                    WaitingTimeCalculator.AVERAGE_MAX_SECONDS)),
            legendRow(Rating.UNKNOWN, ""),
            statusLabel);
    VBox.setMargin(statusLabel, new Insets(4, 0, 0, 0));
    styleOverlay(legend);
    return legend;
  }

  private static HBox legendRow(Rating rating, String range) {
    java.awt.Color color = TrafficLightMarker.color(rating);
    Circle dot = new Circle(5, Color.rgb(color.getRed(), color.getGreen(), color.getBlue()));
    HBox row = new HBox(6, dot, new Label(ratingLabel(rating) + range));
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  private VBox createDetailsBox() {
    detailsTitle = new Label();
    detailsTitle.setStyle("-fx-font-weight: bold;");
    detailsText = new Label();
    Button close = new Button("×");
    close.setStyle("-fx-background-color: transparent; -fx-padding: 0 4 0 4;");
    close.setOnAction(event -> SwingUtilities.invokeLater(() -> select(null)));
    Region spacer = new Region();
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
    HBox header = new HBox(detailsTitle, spacer, close);
    header.setAlignment(Pos.CENTER_LEFT);
    VBox details = new VBox(4, header, detailsText);
    details.setPrefWidth(280);
    styleOverlay(details);
    details.setVisible(false);
    return details;
  }

  private VBox createSidebar() {
    Label title = new Label("Traffic lights in view");
    title.setStyle("-fx-font-weight: bold;");
    sidebarCount = new Label();
    sidebarCount.setWrapText(true);
    sidebarCount.setStyle("-fx-text-fill: #555;");
    Button sort = new Button("Longest wait first");
    sort.setMaxWidth(Double.MAX_VALUE);
    sort.setOnAction(event -> {
      longestWaitFirst = !longestWaitFirst;
      sort.setText(longestWaitFirst ? "Longest wait first" : "Shortest wait first");
      showSidebarEntries(sidebarEntries);
    });
    sidebarList = new ListView<>();
    sidebarList.setCellFactory(list -> new SidebarCell());
    sidebarList.setOnMouseClicked(event -> {
      SidebarEntry entry = sidebarList.getSelectionModel().getSelectedItem();
      if (entry != null) {
        SwingUtilities.invokeLater(() -> {
          map.setDisplayPosition(entry.marker().getCoordinate(),
                  Math.max(map.getZoom(), SEARCH_ZOOM));
          select(entry.marker());
        });
      }
    });
    VBox.setVgrow(sidebarList, Priority.ALWAYS);
    VBox sidebar = new VBox(6, title, sidebarCount, sort, sidebarList);
    sidebar.setPadding(new Insets(10));
    sidebar.setPrefWidth(280);
    sidebar.setStyle("-fx-background-color: white; -fx-font-size: 12px;");
    showSidebarEntries(null);
    return sidebar;
  }

  /**
   * Shows the entries sorted by their average waiting time, null if the map is zoomed out too far.
   * Runs on the JavaFX application thread.
   */
  private void showSidebarEntries(List<SidebarEntry> entries) {
    if (entries == null) {
      sidebarEntries = List.of();
      sidebarCount.setText("Zoom in to see the traffic lights");
      sidebarList.getItems().clear();
      return;
    }
    Comparator<SidebarEntry> byWait = Comparator.comparingDouble(SidebarEntry::averageSeconds);
    sidebarEntries = entries.stream()
            .sorted(longestWaitFirst ? byWait.reversed() : byWait)
            .toList();
    sidebarCount.setText(sidebarEntries.size() + " traffic lights with bike data");
    // the list is replaced whenever the map moves, the clicked traffic light stays selected
    SidebarEntry selected = sidebarList.getSelectionModel().getSelectedItem();
    sidebarList.getItems().setAll(sidebarEntries);
    if (selected != null) {
      sidebarEntries.stream()
              .filter(entry -> entry.marker() == selected.marker())
              .findFirst()
              .ifPresent(entry -> sidebarList.getSelectionModel().select(entry));
    }
  }

  /** A traffic light in the sidebar, its marker is only used on the Swing thread. */
  private record SidebarEntry(TrafficLightMarker marker, String name, double averageSeconds,
                              int waits, Rating rating) {
  }

  private static class SidebarCell extends ListCell<SidebarEntry> {
    @Override
    protected void updateItem(SidebarEntry entry, boolean empty) {
      super.updateItem(entry, empty);
      if (empty || entry == null) {
        setGraphic(null);
        return;
      }
      java.awt.Color color = TrafficLightMarker.color(entry.rating());
      Circle dot = new Circle(5, Color.rgb(color.getRed(), color.getGreen(), color.getBlue()));
      Label name = new Label(entry.name());
      Label info = new Label(String.format(Locale.ROOT, "%.1f s average, %d waits",
              entry.averageSeconds(), entry.waits()));
      info.setStyle("-fx-text-fill: #555;");
      HBox row = new HBox(8, dot, new VBox(name, info));
      row.setAlignment(Pos.CENTER_LEFT);
      setGraphic(row);
    }
  }

  private VBox createSearchBox() {
    TextField field = new TextField();
    field.setPromptText("Search address ...");
    field.setPrefColumnCount(22);
    Label error = new Label();
    error.setStyle("-fx-text-fill: #c00;");
    error.setManaged(false);
    field.setOnAction(event -> {
      String address = field.getText().trim();
      if (address.isEmpty()) {
        return;
      }
      field.setDisable(true);
      loader.geocode(address).thenAccept(place -> Platform.runLater(() -> {
        field.setDisable(false);
        error.setText(place.isEmpty() ? "Address not found" : "");
        error.setManaged(place.isEmpty());
        place.ifPresent(found -> SwingUtilities.invokeLater(() -> map.setDisplayPosition(
                new Coordinate(found.lat(), found.lon()),
                Math.max(map.getZoom(), SEARCH_ZOOM))));
      }));
    });
    VBox search = new VBox(4, field, error);
    styleOverlay(search);
    return search;
  }

  // overlays only take their preferred size, so the rest of the map stays clickable
  private static void styleOverlay(VBox overlay) {
    overlay.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    overlay.setPadding(new Insets(8, 10, 8, 10));
    overlay.setStyle("-fx-background-color: white; -fx-background-radius: 4; -fx-font-size: 12px;"
            + "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.4), 4, 0, 0, 1);");
  }

  // any thread
  private void setStatus(String text) {
    Platform.runLater(() -> statusLabel.setText(text));
  }
}
