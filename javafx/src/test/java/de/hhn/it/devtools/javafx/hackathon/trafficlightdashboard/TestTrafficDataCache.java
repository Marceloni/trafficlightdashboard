package de.hhn.it.devtools.javafx.trafficlightdashboard;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hhn.it.devtools.javafx.trafficlightdashboard.MapArea;
import de.hhn.it.devtools.javafx.trafficlightdashboard.SpeedMeasurement;
import de.hhn.it.devtools.javafx.trafficlightdashboard.TrafficDataCache;
import de.hhn.it.devtools.javafx.trafficlightdashboard.TrafficLight;
import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator;

public class TestTrafficDataCache {

  private static final Instant START = Instant.parse("2026-10-28T08:00:00Z");

  @TempDir
  Path directory;

  private TrafficDataCache cache;

  @BeforeEach
  void setup() {
    cache = new TrafficDataCache(directory.resolve("cache"));
  }

  @Test
  @DisplayName("an empty cache has no data")
  void emptyCache() {
    assertAll(
            () -> assertEquals(Optional.empty(), cache.readTrafficLights()),
            () -> assertFalse(cache.areTrafficLightsYoungerThan(Duration.ofDays(30))),
            () -> assertEquals(List.of(), cache.readBikeSpeeds()),
            () -> assertEquals(Optional.empty(), cache.readBikeSpeedsLoadedUntil()),
            () -> assertEquals(Optional.empty(), cache.readLoadedAreas()),
            () -> assertEquals(Optional.empty(), cache.readTrafficLightsArea()));
  }

  @Test
  @DisplayName("traffic lights are read back as written, also with tabs in names")
  void trafficLights() {
    List<TrafficLight> trafficLights = List.of(
            new TrafficLight(21511019, 49.1419453, 9.1274315, ""),
            new TrafficLight(21722894, 49.1381045, 9.2019882, "Am\tMarkt"));
    MapArea area = new MapArea(47.5, 7.5, 49.8, 10.5);
    cache.writeTrafficLights(trafficLights, area);

    assertAll(
            () -> assertEquals(Optional.of(List.of(trafficLights.get(0),
                    new TrafficLight(21722894, 49.1381045, 9.2019882, "Am Markt"))),
                    cache.readTrafficLights()),
            () -> assertEquals(Optional.of(area), cache.readTrafficLightsArea()),
            () -> assertTrue(cache.areTrafficLightsYoungerThan(Duration.ofDays(30))));
  }

  @Test
  @DisplayName("appended bike speeds are read back together with the loaded time range")
  void bikeSpeeds() {
    SpeedMeasurement first = new SpeedMeasurement("a", "fz", START, 49.1, 9.2, 1.2);
    SpeedMeasurement second = new SpeedMeasurement("b", "Talheim, \"In der Wart\"",
            START.plusSeconds(1), 49.2, 9.3, 0.0);
    cache.appendBikeSpeeds(List.of(first), START.plusSeconds(60));
    cache.appendBikeSpeeds(List.of(second), START.plusSeconds(120));

    assertAll(
            () -> assertEquals(List.of(first, second), cache.readBikeSpeeds()),
            () -> assertEquals(Optional.of(START.plusSeconds(120)),
                    cache.readBikeSpeedsLoadedUntil()));
  }

  @Test
  @DisplayName("loaded areas are read back as written and kept when bike speeds are appended")
  void loadedAreas() {
    List<MapArea> areas = List.of(new MapArea(49.07, 9.1, 49.22, 9.32),
            new MapArea(49.1, 9.32, 49.2, 9.35));
    cache.writeLoadedAreas(areas);
    cache.appendBikeSpeeds(List.of(), START);

    assertAll(
            () -> assertEquals(Optional.of(areas), cache.readLoadedAreas()),
            () -> assertEquals(Optional.of(START), cache.readBikeSpeedsLoadedUntil()));
  }

  @Test
  @DisplayName("only measurements that are new to the calculator are returned for caching")
  void onlyNewMeasurementsAreCached() {
    WaitingTimeCalculator calculator = new WaitingTimeCalculator(List.of());
    SpeedMeasurement first = new SpeedMeasurement("a", "fz", START, 49.1, 9.2, 1.2);
    SpeedMeasurement second = new SpeedMeasurement("a", "fz", START.plusSeconds(1), 49.1, 9.2, 1.0);

    assertAll(
            () -> assertEquals(List.of(first), calculator.addMeasurements(List.of(first))),
            () -> assertEquals(List.of(second),
                    calculator.addMeasurements(List.of(first, second, second))));
  }
}
