module devtools.javafx {
  requires org.slf4j;
  requires devtools.apis;
  requires javafx.controls;
  requires javafx.fxml;
  requires java.net.http;
  requires java.desktop;
  requires javafx.swing;
  requires org.openstreetmap.gui.jmapviewer;
  opens de.hhn.it.devtools.javafx.controllers to javafx.fxml;
  opens de.hhn.it.devtools.javafx.controllers.template to javafx.fxml;
  exports de.hhn.it.devtools.javafx;
  exports de.hhn.it.devtools.javafx.trafficlightdashboard;
  exports de.hhn.it.devtools.javafx.controllers;
}
