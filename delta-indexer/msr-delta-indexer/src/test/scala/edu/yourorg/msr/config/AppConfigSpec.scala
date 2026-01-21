package edu.yourorg.msr.config

import org.scalatest.funsuite.AnyFunSuite

class AppConfigSpec extends AnyFunSuite {
  test("defaults and/or env-overrides produce sane values") {
    assert(AppConfig.db.url.nonEmpty)
    assert(AppConfig.db.user.nonEmpty)
    assert(AppConfig.db.pass.nonEmpty)

    assert(AppConfig.embed.url.nonEmpty)
    assert(AppConfig.embed.model.nonEmpty)
    assert(AppConfig.embed.version.nonEmpty)
    assert(AppConfig.embed.batchSize > 0)

    assert(AppConfig.search.topK > 0)
  }
}
