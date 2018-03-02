/*
 * The MIT License
 *
 * Copyright (c) 2016 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.fulcrumgenomics.util

import java.io.{BufferedReader, FileInputStream, InputStreamReader}
import java.util.zip.GZIPInputStream

import com.fulcrumgenomics.testing.UnitSpec

/**
  * Tests for the Io utility class.
  */
class IoTest extends UnitSpec {

  "Io" should "open a file for gzip writing if it ends with .gz" in {
    val text = "This is a stupid little text fragment for compression. Yay compression!"
    val in   = Seq(text, text, text)
    val f = makeTempFile("test", ".gz")
    Io.writeLines(f, in)

    // Manually read it back as gzipped data
    val reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(f.toFile))))
    val out = Seq(reader.readLine(), reader.readLine(), reader.readLine())
    out shouldBe in
  }

  "Io" should "round trip data to a gzipped file if it ends with .gz" in {
    val text = "This is a stupid little text fragment for compression. Yay compression!"
    val in   = Seq(text, text, text)
    val f = makeTempFile("test", ".gz")
    Io.writeLines(f, in)
    val out = Io.readLines(f).toSeq
    out shouldBe in
  }
}