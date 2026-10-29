package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates the average waiting time of each traffic light from bike speed measurements.
 *
 * <p>A wait is a sequence of measurements of one bike that are slower than
 * {@link #STOP_SPEED} and closer than {@link #TRAFFIC_LIGHT_RADIUS_METERS} to the same traffic
 * light. It lasts from the first stopped measurement until the bike moves again. Measurements can
 * be added in overlapping batches, already processed measurements are ignored.
 */
public class WaitingTimeCalculator {

  /** Bikes slower than this (m/s) count as standing. */
  static final double STOP_SPEED = 1.0;
  /** Stops closer than this to a traffic light count as waiting at that light. */
  static final double TRAFFIC_LIGHT_RADIUS_METERS = 30;
  /** A gap between two measurements longer than this ends a wait. */
  static final Duration MAX_MEASUREMENT_GAP = Duration.ofSeconds(30);
  /** Shorter stops are ignored. */
  static final Duration MIN_WAIT = Duration.ofSeconds(3);
  /** Longer stops are a parked bike, not a wait. */
  static final Duration MAX_WAIT = Duration.ofMinutes(3);

  /** Average waiting times up to this many seconds are good. */
  public static final double GOOD_MAX_SECONDS = 20;
  /** Average waiting times up to this many seconds are average, longer ones are bad. */
  public static final double AVERAGE_MAX_SECONDS = 40;

  private static final double METERS_PER_DEGREE = 111_320;

  /** Rating of a traffic light by its average waiting time. */
  public enum Rating { GOOD, AVERAGE, BAD, UNKNOWN }

  /**
   * Waiting time statistics of one traffic light.
   *
   * @param trafficLight the traffic light
   * @param waits number of measured waits
   * @param averageSeconds average waiting time in seconds, 0 without waits
   * @param lastSeconds duration of the most recent wait in seconds, 0 without waits
   * @param lastWaitAt start of the most recent wait, null without waits
   * @param lastBoxName name of the senseBox that measured the most recent wait, null without waits
   * @param rating rating by the average waiting time
   */
  public record WaitingTime(TrafficLight trafficLight, int waits, double averageSeconds,
                            double lastSeconds, Instant lastWaitAt, String lastBoxName,
                            Rating rating) {
  }

  private static final class Statistics {
    private int waits;
    private double totalSeconds;
    private double lastSeconds;
    private Instant lastWaitAt;
    private String lastBoxName;
  }

  private static final class Stop {
    private final TrafficLight trafficLight;
    private final String boxName;
    private final Instant start;
    private Instant last;

    private Stop(TrafficLight trafficLight, String boxName, Instant start) {
      this.trafficLight = trafficLight;
      this.boxName = boxName;
      this.start = start;
      this.last = start;
    }
  }

  private final List<TrafficLight> trafficLights;
  private final Map<Long, Statistics> statistics = new HashMap<>();
  private final Map<String, Instant> lastMeasurementTimes = new HashMap<>();
  private final Map<String, Stop> openStops = new HashMap<>();

  public WaitingTimeCalculator(List<TrafficLight> trafficLights) {
    this.trafficLights = List.copyOf(trafficLights);
  }

  /**
   * Adds speed measurements of any number of bikes, in any order.
   *
   * @param measurements new measurements, may overlap with already added ones
   * @return the measurements that were not added before, sorted by box and time
   */
  public List<SpeedMeasurement> addMeasurements(List<SpeedMeasurement> measurements) {
    List<SpeedMeasurement> sorted = new ArrayList<>(measurements);
    sorted.sort(Comparator.comparing(SpeedMeasurement::boxId)
            .thenComparing(SpeedMeasurement::time));
    List<SpeedMeasurement> added = new ArrayList<>();
    for (SpeedMeasurement measurement : sorted) {
      String boxId = measurement.boxId();
      Instant lastTime = lastMeasurementTimes.get(boxId);
      if (lastTime != null && !measurement.time().isAfter(lastTime)) {
        // duplicate or already processed in an earlier batch
        continue;
      }
      lastMeasurementTimes.put(boxId, measurement.time());
      added.add(measurement);

      Stop stop = openStops.get(boxId);
      if (stop != null
              && Duration.between(stop.last, measurement.time()).compareTo(MAX_MEASUREMENT_GAP) > 0) {
        // no data in between, the wait ended at some unknown point after the last measurement
        closeStop(boxId, stop.last);
        stop = null;
      }

      if (measurement.speed() >= STOP_SPEED) {
        if (stop != null) {
          closeStop(boxId, measurement.time());
        }
        continue;
      }

      TrafficLight trafficLight = nearestTrafficLight(measurement.lat(), measurement.lon());
      if (stop != null && stop.trafficLight == trafficLight) {
        stop.last = measurement.time();
        continue;
      }
      if (stop != null) {
        closeStop(boxId, measurement.time());
      }
      if (trafficLight != null) {
        openStops.put(boxId, new Stop(trafficLight, measurement.boxName(), measurement.time()));
      }
    }
    return added;
  }

  /**
   * Returns the waiting time statistics of all traffic lights.
   *
   * @return one entry per traffic light, including lights without waits
   */
  public List<WaitingTime> getWaitingTimes() {
    List<WaitingTime> waitingTimes = new ArrayList<>(trafficLights.size());
    for (TrafficLight trafficLight : trafficLights) {
      Statistics lightStatistics = statistics.get(trafficLight.id());
      if (lightStatistics == null) {
        waitingTimes.add(new WaitingTime(trafficLight, 0, 0, 0, null, null, Rating.UNKNOWN));
      } else {
        double average = lightStatistics.totalSeconds / lightStatistics.waits;
        waitingTimes.add(new WaitingTime(trafficLight, lightStatistics.waits, average,
                lightStatistics.lastSeconds, lightStatistics.lastWaitAt,
                lightStatistics.lastBoxName, rate(average)));
      }
    }
    return waitingTimes;
  }

  static Rating rate(double averageSeconds) {
    if (averageSeconds <= GOOD_MAX_SECONDS) {
      return Rating.GOOD;
    }
    return averageSeconds <= AVERAGE_MAX_SECONDS ? Rating.AVERAGE : Rating.BAD;
  }

  private void closeStop(String boxId, Instant end) {
    Stop stop = openStops.remove(boxId);
    Duration wait = Duration.between(stop.start, end);
    if (wait.compareTo(MIN_WAIT) < 0 || wait.compareTo(MAX_WAIT) > 0) {
      return;
    }
    Statistics lightStatistics =
            statistics.computeIfAbsent(stop.trafficLight.id(), id -> new Statistics());
    double seconds = wait.toMillis() / 1000.0;
    lightStatistics.waits++;
    lightStatistics.totalSeconds += seconds;
    if (lightStatistics.lastWaitAt == null || stop.start.isAfter(lightStatistics.lastWaitAt)) {
      lightStatistics.lastSeconds = seconds;
      lightStatistics.lastWaitAt = stop.start;
      lightStatistics.lastBoxName = stop.boxName;
    }
  }

  private TrafficLight nearestTrafficLight(double lat, double lon) {
    double cosLat = Math.cos(Math.toRadians(lat));
    TrafficLight nearest = null;
    double nearestDistance = TRAFFIC_LIGHT_RADIUS_METERS;
    for (TrafficLight trafficLight : trafficLights) {
      // flat projection is exact enough for distances of a few meters
      double dx = (trafficLight.lon() - lon) * cosLat * METERS_PER_DEGREE;
      double dy = (trafficLight.lat() - lat) * METERS_PER_DEGREE;
      double distance = Math.sqrt(dx * dx + dy * dy);
      if (distance <= nearestDistance) {
        nearest = trafficLight;
        nearestDistance = distance;
      }
    }
    return nearest;
  }
}
