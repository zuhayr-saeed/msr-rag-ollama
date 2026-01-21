package edu.yourorg.msr.db

import org.scalatest.funsuite.AnyFunSuite

class DeltaStoreIntegrationSpec extends AnyFunSuite {
  private def enabled: Boolean = sys.env.get("RAG_TEST_DB").contains("1")

  test("can connect to Postgres and run a simple query") {
    assume(enabled, "set RAG_TEST_DB=1 to enable DB integration tests")
    DeltaStore.withConn { c =>
      val rs = c.createStatement().executeQuery("select 1")
      assert(rs.next())
      assert(rs.getInt(1) == 1)
      rs.close()
    }
  }
}
