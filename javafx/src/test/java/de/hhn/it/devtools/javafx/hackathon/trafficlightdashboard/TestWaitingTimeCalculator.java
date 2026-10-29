package de.hhn.it.devtools.javafx.trafficlightdashboard;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.hhn.it.devtools.javafx.trafficlightdashboard.SpeedMeasurement;
import de.hhn.it.devtools.javafx.trafficlightdashboard.TrafficDataLoader;
import de.hhn.it.devtools.javafx.trafficlightdashboard.TrafficLight;
import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator;
import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator.Rating;
import de.hhn.it.devtools.javafx.trafficlightdashboard.WaitingTimeCalculator.WaitingTime;

public class TestWaitingTimeCalculator {

  private static final TrafficLight LIGHT = new TrafficLight(1, 49.1427, 9.2109, "Test");
  private static final TrafficLight OTHER_LIGHT = new TrafficLight(2, 49.1500, 9.2200, "");
  private static final Instant START = Instant.parse("2026-10-28T08:00:00Z");
  // about 10 m north of LIGHT
  private static final double NEAR_LAT = LIGHT.lat() + 0.00009;
  // about 200 m north of LIGHT
  private static final double FAR_LAT = LIGHT.lat() + 0.0018;

  private WaitingTimeCalculator calculator;

  @BeforeEach
  void setup() {
    calculator = new WaitingTimeCalculator(List.of(LIGHT, OTHER_LIGHT));
  }

  /** Measurements once per second: riding, standing near LIGHT for the given time, riding. */
  private static List<SpeedMeasurement> ride(String boxId, Instant start, int standingSeconds,
                                             double standingLat) {
    List<SpeedMeasurement> measurements = new ArrayList<>();
    int second = 0;
    for (int i = 0; i < 5; i++) {
      measurements.add(at(boxId, start, second++, standingLat, 5.0));
    }
    for (int i = 0; i < standingSeconds; i++) {
      measurements.add(at(boxId, start, second++, standingLat, 0.1));
    }
    for (int i = 0; i < 5; i++) {
      measurements.add(at(boxId, start, second++, standingLat, 5.0));
    }
    return measurements;
  }

  private static SpeedMeasurement at(String boxId, Instant start, int second, double lat,
                                     double speed) {
    return new SpeedMeasurement(boxId, "Box " + boxId, start.plusSeconds(second), lat, LIGHT.lon(),
            speed);
  }

  private WaitingTime waitingTimeOf(TrafficLight trafficLight) {
    return calculator.getWaitingTimes().stream()
            .filter(waitingTime -> waitingTime.trafficLight().equals(trafficLight))
            .findFirst().orElseThrow();
  }

  @Test
  @DisplayName("a stop near a traffic light is one wait from stopping until moving again")
  void stopNearTrafficLight() {
    calculator.addMeasurements(ride("bike", START, 30, NEAR_LAT));

    WaitingTime waitingTime = waitingTimeOf(LIGHT);
    assertAll(
            () -> assertEquals(1, waitingTime.waits()),
            () -> assertEquals(30, waitingTime.averageSeconds(), 0.001),
            () -> assertEquals(Rating.AVERAGE, waitingTime.rating()),
            () -> assertEquals(START.plusSeconds(5), waitingTime.lastWaitAt()),
            () -> assertEquals("Box bike", waitingTime.lastBoxName()),
            () -> assertEquals(Rating.UNKNOWN, waitingTimeOf(OTHER_LIGHT).rating()));
  }

  @Test
  @DisplayName("stops away from traffic lights are ignored")
  void stopFarFromTrafficLight() {
    calculator.addMeasurements(ride("bike", START, 30, FAR_LAT));

    assertEquals(0, waitingTimeOf(LIGHT).waits());
  }

  @Test
  @DisplayName("very short stops and parked bikes are not counted as waits")
  void tooShortAndTooLongStops() {
    calculator.addMeasurements(ride("bike", START, 2, NEAR_LAT));
    calculator.addMeasurements(ride("bike", START.plusSeconds(600), 400, NEAR_LAT));

    assertEquals(0, waitingTimeOf(LIGHT).waits());
  }

  @Test
  @DisplayName("the average is taken over all waits of all bikes")
  void averageOverBikes() {
    calculator.addMeasurements(ride("bike1", START, 10, NEAR_LAT));
    calculator.addMeasurements(ride("bike2", START, 50, NEAR_LAT));

    WaitingTime waitingTime = waitingTimeOf(LIGHT);
    assertAll(
            () -> assertEquals(2, waitingTime.waits()),
            () -> assertEquals(30, waitingTime.averageSeconds(), 0.001));
  }

  @Test
  @DisplayName("the last wait is the most recent one, with the senseBox that measured it")
  void lastWaitAndBoxName() {
    // bike "a" is processed first but waited later than bike "b"
    calculator.addMeasurements(ride("a", START.plusSeconds(600), 25, NEAR_LAT));
    calculator.addMeasurements(ride("b", START, 10, NEAR_LAT));

    WaitingTime waitingTime = waitingTimeOf(LIGHT);
    assertAll(
            () -> assertEquals(25, waitingTime.lastSeconds(), 0.001),
            () -> assertEquals(START.plusSeconds(605), waitingTime.lastWaitAt()),
            () -> assertEquals("Box a", waitingTime.lastBoxName()));
  }

  @Test
  @DisplayName("overlapping batches count each wait once, also when split between batches")
  void overlappingBatches() {
    List<SpeedMeasurement> measurements = ride("bike", START, 30, NEAR_LAT);
    calculator.addMeasurements(measurements.subList(0, 20));
    calculator.addMeasurements(measurements);

    WaitingTime waitingTime = waitingTimeOf(LIGHT);
    assertAll(
            () -> assertEquals(1, waitingTime.waits()),
            () -> assertEquals(30, waitingTime.averageSeconds(), 0.001));
  }

  @Test
  @DisplayName("a gap in the data ends the wait at the last stopped measurement")
  void gapEndsWait() {
    List<SpeedMeasurement> measurements = new ArrayList<>();
    for (int second = 0; second <= 10; second++) {
      measurements.add(at("bike", START, second, NEAR_LAT, 0.0));
    }
    measurements.add(at("bike", START, 200, NEAR_LAT, 5.0));
    calculator.addMeasurements(measurements);

    assertEquals(10, waitingTimeOf(LIGHT).averageSeconds(), 0.001);
  }

  @Test
  @DisplayName("ratings follow the thresholds")
  void ratings() {
    assertAll(
            () -> assertEquals(Rating.GOOD, WaitingTimeCalculator.rate(20)),
            () -> assertEquals(Rating.AVERAGE, WaitingTimeCalculator.rate(20.1)),
            () -> assertEquals(Rating.AVERAGE, WaitingTimeCalculator.rate(40)),
            () -> assertEquals(Rating.BAD, WaitingTimeCalculator.rate(40.1)));
  }

  @Test
  @DisplayName("CSV answers of Overpass and openSenseMap are parsed, invalid lines skipped")
  void parseCsv() {
    List<TrafficLight> trafficLights = TrafficDataLoader.parseTrafficLights(
            "21511019\t49.1419453\t9.1274315\t\n21722894\t49.1381045\t9.2019882\tAm Markt\n"
                    + "<html>error</html>\n");
    List<SpeedMeasurement> measurements = TrafficDataLoader.parseSpeedMeasurements(
            "boxId,createdAt,value,lat,lon,boxName\n"
                    + "6ad4f67d040b6100083fff87,2026-10-28T08:08:07.998Z,1.20,49.1483716,9.2154361,fz\n"
                    + "6ad4f67d040b6100083fff87,2026-10-28T08:08:06.998Z,,49.1483605,9.2154355,fz\n"
                    + "5b24e1951fef04001b3847c5,2026-10-28T08:08:05.998Z,0.50,49.10,9.20,"
                    + "\"Talheim, \"\"In der Wart\"\"\"\n");

    assertAll(
            () -> assertEquals(List.of(
                    new TrafficLight(21511019, 49.1419453, 9.1274315, ""),
                    new TrafficLight(21722894, 49.1381045, 9.2019882, "Am Markt")), trafficLights),
            () -> assertEquals(List.of(
                    new SpeedMeasurement("6ad4f67d040b6100083fff87", "fz",
                            Instant.parse("2026-10-28T08:08:07.998Z"), 49.1483716, 9.2154361, 1.2),
                    new SpeedMeasurement("5b24e1951fef04001b3847c5", "Talheim, \"In der Wart\"",
                            Instant.parse("2026-10-28T08:08:05.998Z"), 49.10, 9.20, 0.5)),
                    measurements));
  }
}
