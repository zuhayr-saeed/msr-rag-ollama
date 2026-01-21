package edu.yourorg.msr.search

import org.scalatest.funsuite.AnyFunSuite

class SimilaritySpec extends AnyFunSuite {
  test("dot and norm are consistent") {
    val a = Array(1f, 2f, 3f)
    val b = Array(4f, 5f, 6f)
    assert(Similarity.dot(a,b) == 1*4 + 2*5 + 3*6)
    assert(math.abs(Similarity.norm(a) - math.sqrt(14.0)) < 1e-9)
  }

  test("cosine is within [-1,1] and behaves as expected") {
    val x = Array(1f, 0f)
    val y = Array(0f, 1f)
    val z = Array(1f, 1f)

    val cxy = Similarity.cosine(x, y)
    val cxz = Similarity.cosine(x, z)

    assert(cxy >= -1.0 && cxy <= 1.0)
    assert(cxz >= -1.0 && cxz <= 1.0)

    assert(math.abs(cxy) < 1e-9)  // orthogonal
    assert(cxz > 0.0)             // acute
  }
}
