package de.hhn.it.devtools.javafx.trafficlightdashboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads live data for an area of the map: traffic light positions from OpenStreetMap (Overpass)
 * and GPS speed measurements of senseBox:bikes from openSenseMap. Both are requested as CSV.
 */
public class TrafficDataLoader {

  private static final org.slf4j.Logger logger =
          org.slf4j.LoggerFactory.getLogger(TrafficDataLoader.class);

  /** Heilbronn, its bike data is loaded on the first start. */
  public static final MapArea DEFAULT_AREA = new MapArea(49.07, 9.10, 49.22, 9.32);
  /**
   * Baden-Württemberg, its traffic lights are loaded at once, so moving the map needs no slow
   * Overpass request. About 23,000 traffic lights, 750 kB, 35 s on a busy Overpass server.
   */
  public static final MapArea TRAFFIC_LIGHT_REGION = new MapArea(47.5, 7.5, 49.8, 10.5);

  // tab separated: id, lat, lon, name; bounding box as south, west, north, east
  private static final String TRAFFIC_LIGHT_QUERY =
          "[out:csv(::id,::lat,::lon,name;false)][timeout:180];"
                  + "node[\"highway\"=\"traffic_signals\"](%s,%s,%s,%s);out;";
  private static final List<String> OVERPASS_URLS = List.of(
          "https://overpass-api.de/api/interpreter",
          "https://overpass.kumi.systems/api/interpreter",
          "https://maps.mail.ru/osm/tools/overpass/api/interpreter");
  // speed of all mobile boxes (senseBox:bike) in the area, comma separated with header line;
  // bounding box as west, south, east, north
  private static final String BIKE_SPEED_URL =
          "https://api.opensensemap.org/boxes/data?bbox=%s,%s,%s,%s&exposure=mobile"
                  + "&phenomenon=Speed&format=csv&columns=boxId,createdAt,value,lat,lon,boxName";
  // best match for an address, places in the traffic light region are preferred;
  // viewbox as west, south, east, north
  private static final String GEOCODE_URL =
          "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=%s"
                  + "&viewbox=%s,%s,%s,%s";
  // only lat and lon of the first result are needed, so no JSON library is used
  private static final Pattern LAT = Pattern.compile("\"lat\"\\s*:\\s*\"(-?[0-9.]+)\"");
  private static final Pattern LON = Pattern.compile("\"lon\"\\s*:\\s*\"(-?[0-9.]+)\"");
  // Overpass and Nominatim reject requests without an identifying user agent
  private static final String USER_AGENT = "smarting-devtools-traffic-light-dashboard/1.0";

  /** A position found for an address. */
  public record Place(double lat, double lon) {
  }

  private final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /**
   * Fetches the current traffic lights of an area from Overpass. All servers are asked at once,
   * the first answer is used: the public servers are often overloaded, some answer with an error
   * right away, others not at all until the timeout.
   *
   * @param area the area
   * @return the traffic lights, or empty if no server answered
   * @throws InterruptedException if the calling thread was interrupted
   */
  public Optional<List<TrafficLight>> fetchTrafficLights(MapArea area)
          throws InterruptedException {
    String query = String.format(Locale.ROOT, TRAFFIC_LIGHT_QUERY,
            area.south(), area.west(), area.north(), area.east());
    String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    CompletableFuture<Optional<List<TrafficLight>>> first = new CompletableFuture<>();
    List<CompletableFuture<Void>> requests = new ArrayList<>();
    for (String url : OVERPASS_URLS) {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(200))
              .header("User-Agent", USER_AGENT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
              .handle((response, error) -> {
                if (error != null) {
                  logger.warn("Overpass server {} not reachable: {}", url, error.toString());
                } else if (response.statusCode() != 200) {
                  logger.warn("Overpass server {} answered with status {}", url,
                          response.statusCode());
                } else {
                  List<TrafficLight> trafficLights = parseTrafficLights(response.body());
                  logger.info("Loaded {} traffic lights in {} from {}", trafficLights.size(),
                          area, url);
                  first.complete(Optional.of(trafficLights));
                }
                return null;
              }));
    }
    // no effect if a server has answered already
    CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, error) -> first.complete(Optional.empty()));
    try {
      return first.get();
    } catch (ExecutionException e) {
      throw new IllegalStateException("never completed exceptionally", e);
    } finally {
      // stops waiting for the slower servers
      requests.forEach(request -> request.cancel(true));
    }
  }

  /**
   * Fetches the speed measurements of all senseBox:bikes in an area from openSenseMap.
   *
   * @param area the area
   * @param from start of the time range
   * @param to end of the time range
   * @return the measurements, or empty if the request failed
   * @throws InterruptedException if the calling thread was interrupted
   */
  public Optional<List<SpeedMeasurement>> fetchBikeSpeeds(MapArea area, Instant from, Instant to)
          throws InterruptedException {
    String url = String.format(Locale.ROOT, BIKE_SPEED_URL,
            area.west(), area.south(), area.east(), area.north())
            + "&from-date=" + from.truncatedTo(ChronoUnit.SECONDS)
            + "&to-date=" + to.truncatedTo(ChronoUnit.SECONDS);
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    try {
      // streamed line by line: openSenseMap sends the CSV in many tiny chunks, and buffering
      // them with BodyHandlers.ofString needs gigabytes of heap for a 30 MB response
      HttpResponse<InputStream> response =
              client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (BufferedReader reader = new BufferedReader(
              new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        if (response.statusCode() == 200) {
          List<SpeedMeasurement> measurements = parseSpeedMeasurements(reader);
          logger.info("Loaded {} bike speed measurements in {} from openSenseMap",
                  measurements.size(), area);
          return Optional.of(measurements);
        }
        logger.warn("openSenseMap answered with status {}", response.statusCode());
      }
    } catch (IOException e) {
      logger.warn("openSenseMap not reachable: {}", e.toString());
    }
    return Optional.empty();
  }

  /**
   * Looks up the position of an address with Nominatim. Runs asynchronously, so a search is not
   * delayed by a running bike data load.
   *
   * @param address the address, e.g. "Bildungscampus Heilbronn"
   * @return the position, or empty if nothing was found or the request failed
   */
  public CompletableFuture<Optional<Place>> geocode(String address) {
    String url = String.format(Locale.ROOT, GEOCODE_URL,
            URLEncoder.encode(address, StandardCharsets.UTF_8),
            TRAFFIC_LIGHT_REGION.west(), TRAFFIC_LIGHT_REGION.south(),
            TRAFFIC_LIGHT_REGION.east(), TRAFFIC_LIGHT_REGION.north());
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle((response, error) -> {
              if (error != null) {
                logger.warn("Nominatim not reachable: {}", error.toString());
                return Optional.empty();
              }
              if (response.statusCode() != 200) {
                logger.warn("Nominatim answered with status {}", response.statusCode());
                return Optional.empty();
              }
              return parsePlace(response.body());
            });
  }

  static Optional<Place> parsePlace(String json) {
    Matcher lat = LAT.matcher(json);
    Matcher lon = LON.matcher(json);
    if (!lat.find() || !lon.find()) {
      return Optional.empty();
    }
    return Optional.of(new Place(Double.parseDouble(lat.group(1)),
            Double.parseDouble(lon.group(1))));
  }

  static List<TrafficLight> parseTrafficLights(String csv) {
    List<TrafficLight> trafficLights = new ArrayList<>();
    for (String line : csv.split("\n")) {
      String[] fields = line.split("\t", -1);
      if (fields.length < 3) {
        continue;
      }
      try {
        trafficLights.add(new TrafficLight(Long.parseLong(fields[0].trim()),
                Double.parseDouble(fields[1].trim()), Double.parseDouble(fields[2].trim()),
                fields.length > 3 ? fields[3].trim() : ""));
      } catch (NumberFormatException e) {
        logger.debug("Skipping invalid traffic light line: {}", line);
      }
    }
    return trafficLights;
  }

  static List<SpeedMeasurement> parseSpeedMeasurements(String csv) {
    try {
      return parseSpeedMeasurements(new BufferedReader(new StringReader(csv)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<SpeedMeasurement> parseSpeedMeasurements(BufferedReader csv) throws IOException {
    List<SpeedMeasurement> measurements = new ArrayList<>();
    String line;
    while ((line = csv.readLine()) != null) {
      // boxId,createdAt,value,lat,lon,boxName - the name is last, so commas in it stay intact
      String[] fields = line.trim().split(",", 6);
      if (fields.length < 6 || fields[0].equals("boxId")) {
        continue;
      }
      try {
        measurements.add(new SpeedMeasurement(fields[0], unquote(fields[5]),
                Instant.parse(fields[1]), Double.parseDouble(fields[3]),
                Double.parseDouble(fields[4]), Double.parseDouble(fields[2])));
      } catch (NumberFormatException | DateTimeParseException e) {
        logger.debug("Skipping invalid speed measurement line: {}", line);
      }
    }
    return measurements;
  }

  // CSV quotes values containing commas or quotes as "a ""b"", c"
  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1).replace("\"\"", "\"");
    }
    return value;
  }
}
