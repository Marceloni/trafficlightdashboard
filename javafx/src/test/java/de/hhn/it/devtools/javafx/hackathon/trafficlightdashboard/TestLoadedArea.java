package de.hhn.it.devtools.javafx.trafficlightdashboard;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class TestLoadedArea {

  private static final MapArea HEILBRONN = TrafficDataLoader.DEFAULT_AREA;

  @Test
  @DisplayName("an area inside the loaded area is not missing, also with inexact borders")
  void insideIsLoaded() {
    LoadedArea loaded = new LoadedArea(List.of(HEILBRONN));

    assertAll(
            () -> assertEquals(Optional.empty(), loaded.missingPart(HEILBRONN)),
            () -> assertEquals(Optional.empty(),
                    loaded.missingPart(new MapArea(49.1, 9.15, 49.2, 9.3))),
            () -> assertEquals(List.of(HEILBRONN), loaded.rectangles()));
  }

  @Test
  @DisplayName("positions are inside the loaded area up to its borders")
  void contains() {
    LoadedArea loaded = new LoadedArea(List.of(HEILBRONN));

    assertAll(
            () -> assertTrue(loaded.contains(49.1427, 9.2109)),
            () -> assertTrue(loaded.contains(49.07, 9.10)),
            () -> assertTrue(loaded.contains(49.2199, 9.3199)),
            () -> assertFalse(loaded.contains(49.2201, 9.2)),
            () -> assertFalse(loaded.contains(49.1, 9.0999)));
  }

  @Test
  @DisplayName("only the tiles outside the loaded area are missing")
  void missingPart() {
    LoadedArea loaded = new LoadedArea(List.of(HEILBRONN));

    // reaches 0.025 degrees beyond the eastern border
    assertEquals(Optional.of(new MapArea(49.1, 9.32, 49.2, 9.35)),
            loaded.missingPart(new MapArea(49.1, 9.3, 49.2, 9.345)));
  }

  @Test
  @DisplayName("an added area is loaded and listed as an additional rectangle")
  void add() {
    LoadedArea loaded = new LoadedArea(List.of(HEILBRONN));
    MapArea east = new MapArea(49.1, 9.32, 49.2, 9.35);
    loaded.add(east);

    List<MapArea> rectangles = loaded.rectangles();
    assertAll(
            () -> assertEquals(Optional.empty(), loaded.missingPart(new MapArea(49.1, 9.2, 49.2, 9.35))),
            () -> assertEquals(3, rectangles.size()),
            // the rectangles cover exactly the loaded tiles, nothing twice
            () -> assertEquals(15 * 22 + 10 * 3, rectangles.stream()
                    .mapToLong(area -> Math.round((area.north() - area.south()) * 100)
                            * Math.round((area.east() - area.west()) * 100))
                    .sum()));
  }

  @Test
  @DisplayName("the rectangles are found again after storing them")
  void rectanglesRoundTrip() {
    LoadedArea loaded = new LoadedArea(List.of(HEILBRONN, new MapArea(49.1, 9.32, 49.2, 9.35)));

    assertEquals(loaded.rectangles().size(),
            new LoadedArea(loaded.rectangles()).rectangles().size());
  }
}
