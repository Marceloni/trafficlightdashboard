# Traffic Light Dashboard

A JavaFX desktop app that shows traffic lights on an OpenStreetMap map, colored by how long
cyclists wait at them. The waiting times come from the GPS speed measurements of
[senseBox:bike](https://sensebox.de/en/products-bike) devices published on
[openSenseMap](https://opensensemap.org/).

The map starts at Heilbronn. Traffic lights for all of Baden-Württemberg are loaded, and bike
data is fetched for whatever part of the map you look at.

## Features

- **Map** of OpenStreetMap tiles (JMapViewer embedded in JavaFX), panned with the left mouse
  button. Traffic lights are shown from zoom level 14 on.
- **Traffic light markers** whose lit lamp shows the average waiting time:

  | Color  | Average wait     |
  |--------|------------------|
  | Green  | up to 20 s       |
  | Yellow | 20 s to 40 s     |
  | Red    | over 40 s        |
  | Grey   | no measured wait |

- **Details panel**: click a traffic light to see its number of waits, its average wait and
  its most recent wait (when it happened, how long it was, and which senseBox measured it).
- **Sidebar** listing the measured traffic lights in the visible area, sortable by longest or
  shortest wait.
- **Address search** using Nominatim. Places in Baden-Württemberg are preferred.
- **Live updates**: new bike data is polled every 2 minutes.
- **Local cache**, so later starts don't download everything again.

## How waiting times are calculated

`WaitingTimeCalculator` looks at each bike's measurements in time order. A *wait* is a run of
measurements that are:

- slower than 1 m/s,
- within 30 m of the same traffic light, and
- no more than 30 s apart.

Stops shorter than 3 s are ignored. Stops longer than 3 minutes are treated as a parked bike,
not a wait. A traffic light's rating is the average of all its waits.

## Data sources

| Data                     | Source                                                                   |
|--------------------------|--------------------------------------------------------------------------|
| Traffic light positions  | OpenStreetMap nodes tagged `highway=traffic_signals`, via the Overpass API (several public servers are queried at once and the first answer is used) |
| Bike speed measurements  | openSenseMap API, mobile boxes, phenomenon `Speed`                       |
| Address search           | Nominatim                                                                |
| Map tiles                | OpenStreetMap tile server                                                |

All of them are public services. You need an internet connection on the first start.

## Requirements

- JDK 25. If it isn't installed, Gradle downloads it through the Foojay toolchain resolver.
- Nothing else. The Gradle wrapper (9.7.1) and every dependency are fetched automatically.

## Getting started

```sh
./gradlew
```

The default task is `:javafx:runTrafficLightDashboard`, so this builds and starts the
dashboard. The explicit form is:

```sh
./gradlew :javafx:runTrafficLightDashboard
```

**On the first start**, the app downloads:

- every traffic light in Baden-Württemberg, about 23,000 (this can take a few minutes when the
  Overpass servers are busy), and
- the last 90 days of bike data for Heilbronn.

After that, it only loads what is new or what is in newly visited areas. When a service is
unreachable, the app shows the cached data and retries.

### Cache

Cached data is stored in `~/.smarting-devtools/traffic-light-dashboard/`:

| File                 | Contents                                                       |
|----------------------|----------------------------------------------------------------|
| `traffic-lights.tsv` | Traffic lights (id, lat, lon, name). Refreshed after 30 days.   |
| `bike-speeds.csv`    | All loaded bike speed measurements. New ones are appended.     |
| `cache.properties`   | Which areas have been loaded, and up to which time             |

Delete the directory to start fresh.

## Development

```sh
./gradlew build    # compile and run the tests
./gradlew test     # run the tests only
./gradlew javadoc  # generate the API docs
```

### Project layout

```
apis/     shared API module (devtools.apis): common exceptions
javafx/   the JavaFX application (devtools.javafx)
  src/main/java/de/hhn/it/devtools/javafx/trafficlightdashboard/
    TrafficLightDashboardApp   GUI: map, sidebar, details panel, search, scheduling
    TrafficDataLoader          HTTP clients for Overpass, openSenseMap and Nominatim
    TrafficDataCache           reads and writes the local file cache
    WaitingTimeCalculator      finds waits and computes the statistics for each traffic light
    LoadedArea, MapArea        track which parts of the map have bike data
    TrafficLightMarker         custom map marker drawing a small traffic light
  src/test/java/...            JUnit tests for the calculator, cache and loaded area
config/, etc/  Checkstyle and PMD rule sets
docs/          code review notes
```

Both modules use the Java Platform Module System (`module-info.java`). The build uses Java 25,
JavaFX 25, JMapViewer 2.25 (from the JOSM repository), SLF4J and JUnit 6.
