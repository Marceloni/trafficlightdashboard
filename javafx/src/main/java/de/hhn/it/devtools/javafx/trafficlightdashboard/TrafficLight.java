package de.hhn.it.devtools.javafx.trafficlightdashboard;

/**
 * A traffic light, i.e. an OpenStreetMap node tagged highway=traffic_signals.
 *
 * @param id OSM node id
 * @param lat latitude in degrees
 * @param lon longitude in degrees
 * @param name name of the traffic light, may be empty
 */
public record TrafficLight(long id, double lat, double lon, String name) {
}
