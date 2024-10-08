/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package hashing

import cats.effect.IO
import cats.syntax.all._
import org.scalacheck.Gen
import org.scalacheck.effect.PropF.forAllF

class HashingSuite extends Fs2Suite with HashingSuitePlatform with TestPlatform {

  def checkHash[A](algo: HashAlgorithm, str: String) =
    streamFromString(str)
      .through(Hashing[IO].hash(algo))
      .compile
      .lastOrError
      .assertEquals(digest(algo, str))

  def streamFromString(str: String): Stream[Pure, Byte] = {
    val n =
      if (str.length > 0) Gen.choose(1, str.length).sample.getOrElse(1) else 1
    if (str.isEmpty) Stream.empty
    else
      str.getBytes
        .grouped(n)
        .foldLeft(Stream.empty.covaryOutput[Byte])((acc, c) => acc ++ Stream.chunk(Chunk.array(c)))
  }

  group("hashes") {
    HashAlgorithm.BuiltIn.foreach { algo =>
      test(algo.toString)(forAllF((s: String) => checkHash(algo, s)))
    }
  }

  def checkHmac[A](algo: HashAlgorithm, key: Chunk[Byte], str: String) =
    streamFromString(str)
      .through(Hashing[IO].hashWith(Hashing[IO].hmac(algo, key)))
      .compile
      .lastOrError
      .assertEquals(hmac(algo, key, str))

  group("hmacs") {
    val key = Chunk.array(Array.range(0, 64).map(_.toByte))
    HashAlgorithm.BuiltIn.foreach { algo =>
      test(algo.toString)(forAllF((s: String) => checkHmac(algo, key, s)))
    }
  }

  test("empty input") {
    Stream.empty
      .covary[IO]
      .through(Hashing[IO].hash(HashAlgorithm.SHA1))
      .flatMap(d => Stream.chunk(d.bytes))
      .compile
      .count
      .assertEquals(20L)
  }

  test("zero or one output") {
    forAllF { (lb: List[Array[Byte]]) =>
      val size = lb
        .foldLeft(Stream.empty.covaryOutput[Byte])((acc, b) => acc ++ Stream.chunk(Chunk.array(b)))
        .through(Hashing[IO].hash(HashAlgorithm.SHA1))
        .flatMap(d => Stream.chunk(d.bytes))
        .compile
        .count
      size.assertEquals(20L)
    }
  }

  test("thread-safety") {
    val s = Stream
      .range(1, 100)
      .covary[IO]
      .flatMap(i => Stream.chunk(Chunk.array(i.toString.getBytes)))
      .through(Hashing[IO].hash(HashAlgorithm.SHA256))
    for {
      once <- s.compile.toVector
      oneHundred <- Vector.fill(100)(s.compile.toVector).parSequence
    } yield assertEquals(oneHundred, Vector.fill(100)(once))
  }

  group("verify") {
    test("success") {
      forAllF { (strings: List[String]) =>
        val source = strings.foldMap(s => Stream.chunk(Chunk.array(s.getBytes))).covary[IO]
        Hashing[IO].hasher(HashAlgorithm.SHA256).use { h =>
          val expected = digest(HashAlgorithm.SHA256, strings.combineAll)
          source.through(h.verify(expected)).compile.drain
        }
      }
    }

    test("failure") {
      forAllF { (strings: List[String]) =>
        val source = strings.foldMap(s => Stream.chunk(Chunk.array(s.getBytes))).covary[IO]
        Hashing[IO]
          .hasher(HashAlgorithm.SHA256)
          .use { h =>
            val expected = digest(HashAlgorithm.SHA256, strings.combineAll)
            (source ++ Stream(0.toByte)).through(h.verify(expected)).compile.drain
          }
          .intercept[HashVerificationException]
          .void
      }
    }
  }

  test("reuse") {
    forAllF { (strings: List[String]) =>
      Hashing[IO].hasher(HashAlgorithm.SHA256).use { h =>
        val actual = strings.traverse(s => h.update(Chunk.array(s.getBytes)) >> h.hash)
        val expected = strings.map(s => digest(HashAlgorithm.SHA256, s))
        actual.assertEquals(expected)
      }
    }
  }

  test("hashPureStream") {
    forAllF { (strings: List[String]) =>
      val source = strings.foldMap(s => Stream.chunk(Chunk.array(s.getBytes)))
      val actual = Hashing.hashPureStream(HashAlgorithm.SHA256, source)
      val expected = digest(HashAlgorithm.SHA256, strings.combineAll)
      actual.pure[IO].assertEquals(expected)
    }
  }

  test("hashChunk") {
    forAllF { (string: String) =>
      val actual = Hashing.hashChunk(HashAlgorithm.SHA256, Chunk.array(string.getBytes))
      val expected = digest(HashAlgorithm.SHA256, string)
      actual.pure[IO].assertEquals(expected)
    }
  }

  test("example of writing a file and a hash") {
    def writeAll(path: String): Pipe[IO, Byte, Nothing] = {
      identity(path) // Ignore unused warning
      ???
    }

    def writeFileAndHash(path: String): Pipe[IO, Byte, Nothing] =
      source =>
        // Create a hash
        Stream.resource(Hashing[IO].hasher(HashAlgorithm.SHA256)).flatMap { h =>
          source
            // Write source to file, updating the hash with observed bytes
            .through(h.observe(writeAll(path)))
            // Write digest to separate file
            .map(_.bytes)
            .unchunks
            .through(writeAll(path + ".sha256"))
        }

    writeFileAndHash("output.txt")
  }
}
