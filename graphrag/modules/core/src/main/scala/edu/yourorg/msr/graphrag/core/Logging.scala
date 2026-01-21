package edu.yourorg.msr.graphrag.core

import org.slf4j.LoggerFactory

/**
 * Simple logging mixin so we can do:
 *
 *   class Foo extends Logging { logger.info("hello") }
 */
trait Logging {
  protected lazy val logger =
    LoggerFactory.getLogger(getClass.getName)
}
