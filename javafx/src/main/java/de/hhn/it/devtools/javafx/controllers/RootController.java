package de.hhn.it.devtools.javafx.controllers;

import de.hhn.it.devtools.javafx.parts.PartialApp;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.AnchorPane;

public class RootController extends Controller implements Initializable {
  private static final org.slf4j.Logger logger =
          org.slf4j.LoggerFactory.getLogger(RootController.class);

  @FXML
  private MenuBar menuBar;

  @FXML
  private ListView<String> listView;

  @FXML
  private AnchorPane partialAppPane;



  private PartialApp actualPartialApp = null;
  private Map<String, PartialApp> partialAppMap;


  public RootController() {
    logger.debug("RootController created.");
    partialAppMap = new HashMap<>();
  }

  /**
   * Called to initialize a controller after its root element has been
   * completely processed.
   *
   * @param location  The location used to resolve relative paths for the root object, or
   *                  <code>null</code> if the location is not known.
   * @param resources The resources used to localize the root object, or <code>null</code> if
   */
  @Override
  public void initialize(final URL location, final ResourceBundle resources) {
    listView.getSelectionModel().selectedItemProperty().addListener(
            (ObservableValue<? extends String> observable, String oldValue, String newValue) -> {
              logger.info("Selected item: " + newValue);
              partialAppPane.getChildren().clear();
              PartialApp partialApp = partialAppMap.get(newValue);
              // notify actual controller that it will pause (going to be invisible)
              actualPartialApp.controller().pause();
              actualPartialApp = partialApp;
              partialAppPane.getChildren().add(partialApp.sceneGraph());
              // notify new actual controller that its content is now visible
              partialApp.controller().resume();
            }
    );

  }

  public void addModule(PartialApp partialApp) {
    listView.getItems().add(partialApp.name());
    partialAppMap.put(partialApp.name(), partialApp);
    if (actualPartialApp == null) {
      actualPartialApp = partialApp;
      listView.getSelectionModel().selectFirst();
    }
  }

  @Override
  public void shutdown() {
    logger.debug("shutdown: - ");
    // Iterate over all module controllers and ask them to shut down their parts, e.g. store
    // files, stop threads, etc.

    for (String moduleName : partialAppMap.keySet()) {
      logger.debug("Ask {} to shut down.", moduleName);
      PartialApp partialApp = partialAppMap.get(moduleName);
      Controller controller = partialApp.controller();
      controller.shutdown();
    }
    logger.debug("All controllers shutdowns executed. That's it.");
  }
}
