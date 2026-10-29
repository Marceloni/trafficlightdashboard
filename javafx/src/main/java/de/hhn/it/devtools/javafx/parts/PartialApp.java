package de.hhn.it.devtools.javafx.parts;


import de.hhn.it.devtools.javafx.controllers.Controller;
import javafx.scene.Node;

/**
 * Represents a partial application.
 *
 * @param name name to be used in the selection to switch to the partial application.
 * @param controller central controller which manages different states like pause, resume, shutdown.
 * @param sceneGraph Scene graph to be pasted into the main area for display
 */
public record PartialApp (String name, Controller controller, Node sceneGraph){


}
