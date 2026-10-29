package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;

/**
 * Local file cache for the dashboard data, so the app starts without downloading everything.
 *
 * <p>Files in the cache directory:
 * <ul>
 *   <li>traffic-lights.tsv: traffic lights in the Overpass CSV format (id, lat, lon, name)</li>
 *   <li>bike-speeds.csv: all bike speed measurements in the openSenseMap CSV format, new
 *       measurements are appended</li>
 *   <li>cache.properties: up to which time the bike speeds have been loaded, for which areas
 *       of the map bike speeds are cached and which area the traffic lights cover</li>
 * </ul>
 * Write errors are logged and otherwise ignored, the app then just loads more from the web.
 */
public class TrafficDataCache {

  private static final org.slf4j.Logger logger =
          org.slf4j.LoggerFactory.getLogger(TrafficDataCache.class);
  private static final String TRAFFIC_LIGHTS_FILE = "traffic-lights.tsv";
  private static final String BIKE_SPEEDS_FILE = "bike-speeds.csv";
  private static final String PROPERTIES_FILE = "cache.properties";
  private static final String BIKE_SPEEDS_HEADER = "boxId,createdAt,value,lat,lon,boxName\n";
  private static final String LOADED_UNTIL_KEY = "bikeSpeedsLoadedUntil";
  private static final String LOADED_AREAS_KEY = "loadedAreas";
  private static final String TRAFFIC_LIGHTS_AREA_KEY = "trafficLightsArea";

  private final Path directory;

  public TrafficDataCache(Path directory) {
    this.directory = directory;
  }

  /**
   * Reads the cached traffic lights.
   *
   * @return the traffic lights, or empty if there are none in the cache
   */
  public Optional<List<TrafficLight>> readTrafficLights() {
    Path file = directory.resolve(TRAFFIC_LIGHTS_FILE);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      List<TrafficLight> trafficLights =
              TrafficDataLoader.parseTrafficLights(Files.readString(file, StandardCharsets.UTF_8));
      return trafficLights.isEmpty() ? Optional.empty() : Optional.of(trafficLights);
    } catch (IOException e) {
      logger.warn("Could not read cached traffic lights {}: {}", file, e.toString());
      return Optional.empty();
    }
  }

  /**
   * Tells whether the cached traffic lights are younger than the given age.
   *
   * @param maxAge maximum age
   * @return true if there are cached traffic lights younger than maxAge
   */
  public boolean areTrafficLightsYoungerThan(Duration maxAge) {
    try {
      Instant written = Files.getLastModifiedTime(directory.resolve(TRAFFIC_LIGHTS_FILE)).toInstant();
      return written.plus(maxAge).isAfter(Instant.now());
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Returns the area the cached traffic lights have been loaded for.
   *
   * @return the area, or empty if it is not known
   */
  public Optional<MapArea> readTrafficLightsArea() {
    return readAreas(TRAFFIC_LIGHTS_AREA_KEY).map(areas -> areas.get(0));
  }

  /**
   * Replaces the cached traffic lights.
   *
   * @param trafficLights the current traffic lights
   * @param area the area the traffic lights have been loaded for
   */
  public void writeTrafficLights(List<TrafficLight> trafficLights, MapArea area) {
    StringBuilder tsv = new StringBuilder();
    for (TrafficLight trafficLight : trafficLights) {
      tsv.append(String.format(Locale.ROOT, "%d\t%s\t%s\t%s%n", trafficLight.id(),
              trafficLight.lat(), trafficLight.lon(), trafficLight.name().replaceAll("[\t\r\n]", " ")));
    }
    try {
      Files.createDirectories(directory);
      // write to a temporary file first, so a crash never leaves a half written cache
      Path temporary = directory.resolve(TRAFFIC_LIGHTS_FILE + ".tmp");
      Files.writeString(temporary, tsv, StandardCharsets.UTF_8);
      Files.move(temporary, directory.resolve(TRAFFIC_LIGHTS_FILE),
              StandardCopyOption.REPLACE_EXISTING);
      writeProperty(TRAFFIC_LIGHTS_AREA_KEY, formatAreas(List.of(area)));
    } catch (IOException e) {
      logger.warn("Could not cache traffic lights in {}: {}", directory, e.toString());
    }
  }

  /**
   * Reads all cached bike speed measurements.
   *
   * @return the measurements, empty if there are none in the cache
   */
  public List<SpeedMeasurement> readBikeSpeeds() {
    Path file = directory.resolve(BIKE_SPEEDS_FILE);
    if (!Files.exists(file)) {
      return List.of();
    }
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return TrafficDataLoader.parseSpeedMeasurements(reader);
    } catch (IOException e) {
      logger.warn("Could not read cached bike speeds {}: {}", file, e.toString());
      return List.of();
    }
  }

  /**
   * Returns up to which time the cached bike speeds have been loaded from openSenseMap.
   *
   * @return the time, or empty if nothing has been cached yet
   */
  public Optional<Instant> readBikeSpeedsLoadedUntil() {
    String loadedUntil = readProperties().getProperty(LOADED_UNTIL_KEY);
    if (loadedUntil == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(loadedUntil));
    } catch (DateTimeParseException e) {
      logger.warn("Invalid {} in the cache properties: {}", LOADED_UNTIL_KEY, loadedUntil);
      return Optional.empty();
    }
  }

  /**
   * Returns the areas for which the cached bike speeds have been loaded.
   *
   * @return the areas, or empty if none have been stored yet
   */
  public Optional<List<MapArea>> readLoadedAreas() {
    return readAreas(LOADED_AREAS_KEY);
  }

  /**
   * Stores the areas for which bike speeds are in the cache.
   *
   * @param areas the loaded areas
   */
  public void writeLoadedAreas(List<MapArea> areas) {
    try {
      writeProperty(LOADED_AREAS_KEY, formatAreas(areas));
    } catch (IOException e) {
      logger.warn("Could not cache loaded areas in {}: {}", directory, e.toString());
    }
  }

  private Optional<List<MapArea>> readAreas(String key) {
    String areas = readProperties().getProperty(key);
    if (areas == null || areas.isBlank()) {
      return Optional.empty();
    }
    List<MapArea> result = new ArrayList<>();
    try {
      for (String area : areas.split(";")) {
        String[] borders = area.split(",");
        result.add(new MapArea(Double.parseDouble(borders[0]), Double.parseDouble(borders[1]),
                Double.parseDouble(borders[2]), Double.parseDouble(borders[3])));
      }
    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
      logger.warn("Invalid {} in the cache properties: {}", key, areas);
      return Optional.empty();
    }
    return Optional.of(result);
  }

  private static String formatAreas(List<MapArea> areas) {
    StringJoiner joined = new StringJoiner(";");
    for (MapArea area : areas) {
      joined.add(String.format(Locale.ROOT, "%s,%s,%s,%s",
              area.south(), area.west(), area.north(), area.east()));
    }
    return joined.toString();
  }

  /**
   * Appends new bike speed measurements to the cache.
   *
   * @param measurements measurements that are not in the cache yet
   * @param loadedUntil end of the time range the measurements were loaded for
   */
  public void appendBikeSpeeds(List<SpeedMeasurement> measurements, Instant loadedUntil) {
    StringBuilder csv = new StringBuilder();
    for (SpeedMeasurement measurement : measurements) {
      csv.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%s%n", measurement.boxId(),
              measurement.time(), measurement.speed(), measurement.lat(), measurement.lon(),
              csvValue(measurement.boxName())));
    }
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(BIKE_SPEEDS_FILE);
      if (!Files.exists(file)) {
        Files.writeString(file, BIKE_SPEEDS_HEADER, StandardCharsets.UTF_8);
      }
      Files.writeString(file, csv, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      // written after the measurements: if the app stops in between, the next start loads the
      // same time range again and the duplicates are ignored when reading the cache
      writeProperty(LOADED_UNTIL_KEY, loadedUntil.toString());
    } catch (IOException e) {
      logger.warn("Could not cache bike speeds in {}: {}", directory, e.toString());
    }
  }

  private Properties readProperties() {
    Properties properties = new Properties();
    Path file = directory.resolve(PROPERTIES_FILE);
    if (Files.exists(file)) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        properties.load(reader);
      } catch (IOException e) {
        logger.warn("Could not read cache properties {}: {}", file, e.toString());
      }
    }
    return properties;
  }

  private void writeProperty(String key, String value) throws IOException {
    Properties properties = readProperties();
    properties.setProperty(key, value);
    Files.createDirectories(directory);
    try (Writer writer = Files.newBufferedWriter(directory.resolve(PROPERTIES_FILE),
            StandardCharsets.UTF_8)) {
      properties.store(writer, "Traffic light dashboard cache");
    }
  }

  // quotes values containing commas or quotes, newlines would break the line based format
  private static String csvValue(String value) {
    String singleLine = value.replaceAll("[\r\n]", " ");
    if (singleLine.contains(",") || singleLine.contains("\"")) {
      return "\"" + singleLine.replace("\"", "\"\"") + "\"";
    }
    return singleLine;
  }
}
