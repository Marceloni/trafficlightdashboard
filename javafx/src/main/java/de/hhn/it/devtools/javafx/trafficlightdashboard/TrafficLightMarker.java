package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import org.openstreetmap.gui.jmapviewer.Coordinate;
import org.openstreetmap.gui.jmapviewer.MapObjectImpl;
import org.openstreetmap.gui.jmapviewer.interfaces.MapMarker;

import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator.Rating;
import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator.WaitingTime;

/**
 * Map marker drawn as a small traffic light whose lit lamp shows the waiting time rating.
 * Only used on the Swing event dispatch thread.
 */
public class TrafficLightMarker extends MapObjectImpl implements MapMarker {

  static final int WIDTH = 14;
  static final int HEIGHT = 30;

  private static final Color GOOD = new Color(0x43a047);
  private static final Color AVERAGE = new Color(0xfdd835);
  private static final Color BAD = new Color(0xe53935);
  private static final Color UNKNOWN = new Color(0x9e9e9e);
  private static final Color FRAME = new Color(0x222222);
  private static final Color SELECTION = new Color(0x1e88e5);

  private final TrafficLight trafficLight;
  private final Coordinate coordinate;
  private WaitingTime waitingTime;
  private boolean selected;

  public TrafficLightMarker(TrafficLight trafficLight) {
    super(trafficLight.name().isEmpty() ? "Traffic light" : trafficLight.name());
    this.trafficLight = trafficLight;
    this.coordinate = new Coordinate(trafficLight.lat(), trafficLight.lon());
  }

  public TrafficLight getTrafficLight() {
    return trafficLight;
  }

  /**
   * Returns the latest waiting time statistics.
   *
   * @return the statistics, or null before the first calculation
   */
  public WaitingTime getWaitingTime() {
    return waitingTime;
  }

  public void setWaitingTime(WaitingTime waitingTime) {
    this.waitingTime = waitingTime;
  }

  public void setSelected(boolean selected) {
    this.selected = selected;
  }

  /**
   * Returns the color of a rating, also used by the legend.
   *
   * @param rating the rating
   * @return its color
   */
  public static Color color(Rating rating) {
    return switch (rating) {
      case GOOD -> GOOD;
      case AVERAGE -> AVERAGE;
      case BAD -> BAD;
      case UNKNOWN -> UNKNOWN;
    };
  }

  @Override
  public Coordinate getCoordinate() {
    return coordinate;
  }

  @Override
  public double getLat() {
    return coordinate.getLat();
  }

  @Override
  public double getLon() {
    return coordinate.getLon();
  }

  @Override
  public void setLat(double lat) {
    coordinate.setLat(lat);
  }

  @Override
  public void setLon(double lon) {
    coordinate.setLon(lon);
  }

  @Override
  public double getRadius() {
    return HEIGHT / 2.0;
  }

  @Override
  public STYLE getMarkerStyle() {
    return STYLE.FIXED;
  }

  @Override
  public void paint(Graphics g, Point position, int radius) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      // anchored at its center, like the icon of the former Leaflet map
      g2.translate(position.x - WIDTH / 2.0, position.y - HEIGHT / 2.0);
      Rating rating = waitingTime == null ? Rating.UNKNOWN : waitingTime.rating();
      RoundRectangle2D frame = new RoundRectangle2D.Double(0.5, 0.5, WIDTH - 1, HEIGHT - 1, 6, 6);
      if (selected) {
        g2.setColor(SELECTION);
        g2.setStroke(new BasicStroke(5));
        g2.draw(frame);
      }
      g2.setColor(rating == Rating.UNKNOWN ? UNKNOWN : FRAME);
      g2.fill(frame);
      g2.setColor(Color.WHITE);
      g2.setStroke(new BasicStroke(1));
      g2.draw(frame);
      paintLamp(g2, 6.5, BAD, rating);
      paintLamp(g2, 15, AVERAGE, rating);
      paintLamp(g2, 23.5, GOOD, rating);
    } finally {
      g2.dispose();
    }
  }

  private static void paintLamp(Graphics2D g2, double centerY, Color color, Rating rating) {
    boolean lit = rating == Rating.UNKNOWN || color(rating).equals(color);
    g2.setColor(lit ? color : new Color(color.getRed(), color.getGreen(), color.getBlue(), 38));
    g2.fill(new Ellipse2D.Double(7 - 3.2, centerY - 3.2, 6.4, 6.4));
  }
}
