package de.hhn.it.devtools.javafx.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract controller to have default implementations for the methods pause, resume and shutdown.
 */
public abstract class Controller {
  private static final Logger logger = LoggerFactory.getLogger(Controller.class);

  void pause() {
    logger.debug("pause: -");
  }

  public void resume() {
    logger.debug("resume: -");
  }

  public void shutdown() {
    logger.debug("shutdown: -");
  }

}
