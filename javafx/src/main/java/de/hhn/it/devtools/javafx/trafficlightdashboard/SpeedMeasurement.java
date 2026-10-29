package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.time.Instant;

/**
 * A GPS speed measurement of a senseBox:bike on openSenseMap.
 *
 * @param boxId openSenseMap id of the senseBox
 * @param boxName name of the senseBox
 * @param time time of the measurement
 * @param lat latitude in degrees
 * @param lon longitude in degrees
 * @param speed speed in m/s
 */
public record SpeedMeasurement(String boxId, String boxName, Instant time, double lat, double lon,
                               double speed) {
}
