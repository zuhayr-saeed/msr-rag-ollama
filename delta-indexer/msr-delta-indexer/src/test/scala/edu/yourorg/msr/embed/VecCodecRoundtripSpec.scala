package edu.yourorg.msr.embed

import org.scalatest.funsuite.AnyFunSuite
import java.nio.{ByteBuffer, ByteOrder}

class VecCodecRoundtripSpec extends AnyFunSuite {
  test("floatsToBytes roundtrip (little-endian)") {
    val v = Vector(0.0f, 1.5f, -2.25f, 3.75f, Float.MinValue/2, Float.MaxValue/2)
    val bytes = VecCodec.floatsToBytes(v)

    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val out = Array.ofDim[Float](v.length)
    fb.get(out)

    assert(out.toSeq === v)
    assert(bytes.length == v.length * 4)
  }
}
