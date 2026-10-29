package de.hhn.it.devtools.javafx.trafficlightdashboard;

/**
 * A rectangular part of the map.
 *
 * @param south southern border, latitude in degrees
 * @param west western border, longitude in degrees
 * @param north northern border, latitude in degrees
 * @param east eastern border, longitude in degrees
 */
public record MapArea(double south, double west, double north, double east) {

  /**
   * Returns this area grown on each side by the given fraction of its size.
   *
   * @param fraction 0.5 adds half the height above and below and half the width left and right
   * @return the larger area
   */
  public MapArea expandedBy(double fraction) {
    double latitudeMargin = (north - south) * fraction;
    double longitudeMargin = (east - west) * fraction;
    return new MapArea(south - latitudeMargin, west - longitudeMargin,
            north + latitudeMargin, east + longitudeMargin);
  }

  /**
   * Returns whether a position lies in this area.
   *
   * @param lat latitude in degrees
   * @param lon longitude in degrees
   * @return true if it lies inside or on the border
   */
  public boolean contains(double lat, double lon) {
    return lat >= south && lat <= north && lon >= west && lon <= east;
  }
}
