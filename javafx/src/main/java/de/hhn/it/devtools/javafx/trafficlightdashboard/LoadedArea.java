package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The part of the map for which traffic lights and bike data have been loaded. It is stored as
 * a set of tiles of 0.01 x 0.01 degrees (about 1.1 x 0.7 km in Heilbronn), so it can grow in any
 * direction while the map is moved.
 */
public class LoadedArea {

  static final int TILES_PER_DEGREE = 100;
  // tile borders read back from the cache are not exact, e.g. 49.07 * 100 = 4907.000000000001
  private static final double EPSILON = 1e-6;

  private record Tile(int row, int column) {
  }

  private record ColumnRange(int first, int last) {
  }

  private final Set<Tile> tiles = new HashSet<>();

  public LoadedArea(Collection<MapArea> areas) {
    areas.forEach(this::add);
  }

  /**
   * Adds all tiles that overlap the given area.
   *
   * @param area the newly loaded area
   */
  public void add(MapArea area) {
    tiles.addAll(tilesOf(area));
  }

  /**
   * Tells whether a position lies in the loaded area.
   *
   * @param lat latitude in degrees
   * @param lon longitude in degrees
   * @return true if the tile of the position has been loaded
   */
  public boolean contains(double lat, double lon) {
    return tiles.contains(new Tile((int) Math.floor(lat * TILES_PER_DEGREE),
            (int) Math.floor(lon * TILES_PER_DEGREE)));
  }

  /**
   * Returns the part of the given area that has not been loaded yet.
   *
   * @param area the area that should be loaded
   * @return the smallest tile aligned rectangle containing all missing tiles of the area,
   *         or empty if the whole area has been loaded
   */
  public Optional<MapArea> missingPart(MapArea area) {
    List<Tile> missing = new ArrayList<>(tilesOf(area));
    missing.removeAll(tiles);
    if (missing.isEmpty()) {
      return Optional.empty();
    }
    int firstRow = Integer.MAX_VALUE;
    int lastRow = Integer.MIN_VALUE;
    int firstColumn = Integer.MAX_VALUE;
    int lastColumn = Integer.MIN_VALUE;
    for (Tile tile : missing) {
      firstRow = Math.min(firstRow, tile.row());
      lastRow = Math.max(lastRow, tile.row());
      firstColumn = Math.min(firstColumn, tile.column());
      lastColumn = Math.max(lastColumn, tile.column());
    }
    return Optional.of(area(firstRow, lastRow, firstColumn, lastColumn));
  }

  /**
   * Returns the loaded area as non overlapping rectangles, for requests that take a bounding box.
   *
   * @return as few rectangles as easily possible, covering exactly the loaded tiles
   */
  public List<MapArea> rectangles() {
    List<Tile> sorted = new ArrayList<>(tiles);
    sorted.sort(Comparator.comparingInt(Tile::row).thenComparingInt(Tile::column));
    List<MapArea> rectangles = new ArrayList<>();
    // first and last row of the rectangles that may still grow upwards, by their columns
    Map<ColumnRange, int[]> open = new HashMap<>();
    int index = 0;
    while (index < sorted.size()) {
      // a run of neighboring tiles in one row
      Tile first = sorted.get(index);
      int lastColumn = first.column();
      index++;
      while (index < sorted.size() && sorted.get(index).row() == first.row()
              && sorted.get(index).column() == lastColumn + 1) {
        lastColumn++;
        index++;
      }
      ColumnRange columns = new ColumnRange(first.column(), lastColumn);
      int[] rows = open.get(columns);
      if (rows != null && rows[1] == first.row() - 1) {
        rows[1] = first.row();
      } else {
        if (rows != null) {
          rectangles.add(area(rows[0], rows[1], columns.first(), columns.last()));
        }
        open.put(columns, new int[] {first.row(), first.row()});
      }
    }
    open.forEach((columns, rows) ->
            rectangles.add(area(rows[0], rows[1], columns.first(), columns.last())));
    return rectangles;
  }

  private static Set<Tile> tilesOf(MapArea area) {
    int firstRow = (int) Math.floor(area.south() * TILES_PER_DEGREE + EPSILON);
    int lastRow = (int) Math.ceil(area.north() * TILES_PER_DEGREE - EPSILON) - 1;
    int firstColumn = (int) Math.floor(area.west() * TILES_PER_DEGREE + EPSILON);
    int lastColumn = (int) Math.ceil(area.east() * TILES_PER_DEGREE - EPSILON) - 1;
    Set<Tile> result = new HashSet<>();
    for (int row = firstRow; row <= lastRow; row++) {
      for (int column = firstColumn; column <= lastColumn; column++) {
        result.add(new Tile(row, column));
      }
    }
    return result;
  }

  private static MapArea area(int firstRow, int lastRow, int firstColumn, int lastColumn) {
    return new MapArea((double) firstRow / TILES_PER_DEGREE,
            (double) firstColumn / TILES_PER_DEGREE,
            (double) (lastRow + 1) / TILES_PER_DEGREE,
            (double) (lastColumn + 1) / TILES_PER_DEGREE);
  }
}
