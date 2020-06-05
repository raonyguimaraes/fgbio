/*
 * The MIT License
 *
 * Copyright (c) 2019 Fulcrum Genomics LLC
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

package com.fulcrumgenomics.fasta

import java.io.BufferedWriter

import com.fulcrumgenomics.FgBioDef.PathToSequenceDictionary
import com.fulcrumgenomics.cmdline.{ClpGroups, FgBioTool}
import com.fulcrumgenomics.commons.CommonsDef._
import com.fulcrumgenomics.commons.util.LazyLogging
import com.fulcrumgenomics.sopt.{arg, clp}
import com.fulcrumgenomics.util.{Io, ProgressLogger}
import htsjdk.samtools.reference.{FastaSequenceIndex, ReferenceSequence, ReferenceSequenceFile, ReferenceSequenceFileFactory}

@clp(description =
  """
    |Updates the sequence names in a FASTA.
    |
    |The name of each sequence must match one of the names (including aliases) in the given sequence dictionary.  The
    |new name will be the primary (non-alias) name in the sequence dictionary.
    |
    |By default, the sort order of the contigs will be the same as the input FASTA.  Use the `--sort` option to sort by
    |the input sequence dictionary.  Furthermore, the sequence dictionary may contain **more** contigs than the input
    |FASTA, and they wont be used.  Use the `--skip-missing` option to skip contigs in the input FASTA that cannot be
    |renamed (i.e. who are not present in the input sequence dictionary).  Finally, use the `--default-contigs` to
    |append contigs not present in the input FASTA but present in the sequence dictionary.
  """,
  group = ClpGroups.Fasta)
class UpdateFastaContigNames
(@arg(flag='i', doc="Input FASTA.") val input: PathToFasta,
 @arg(flag='d', doc="The path to the sequence dictionary with contig aliases.") val dict: PathToSequenceDictionary,
 @arg(flag='o', doc="Output FASTA.") val output: PathToFasta,
 @arg(flag='l', doc="Line length or sequence lines.") val lineLength: Int = 100,
 @arg(doc="Skip missing source contigs.") val skipMissing: Boolean = false,
 @arg(doc="Sort the contigs based on the input sequence dictionary") sort: Boolean = false,
 @arg(doc="Add sequences from this FASTA when contigs in the sequence dictionary are missing from the input FASTA")
 defaultContigs: Option[PathToFasta] = None
) extends FgBioTool with LazyLogging {
  import com.fulcrumgenomics.fasta.Converters.FromSAMSequenceDictionary

  Io.assertReadable(Seq(input, dict))
  defaultContigs.foreach(Io.assertReadable)
  Io.assertCanWriteFile(output)

  /** Little class to store the sequence metadata and reference sequence to output.
    *
    * The reference sequence to output is stored as a method, so we can lazily retrieve it and not incur large memory
    * overhead.
    *
    * @param info the sequence metadata to output
    * @param toSequence method that returns the reference sequence to output
    */
  private case class OutputSequence(info: SequenceMetadata, toSequence: () => ReferenceSequence) {
    /** Writes the output sequence */
    def write(writer: BufferedWriter, progress: ProgressLogger): Unit = {
      val ref = toSequence()
      writer.append('>').append(info.name).append('\n')
      val bases = ref.getBases
      var baseCounter = 0
      forloop(from = 0, until = bases.length) { baseIdx =>
        writer.write(bases(baseIdx))
        progress.record(info.name, baseIdx + 1)
        baseCounter += 1
        if (baseCounter >= lineLength) {
          writer.newLine()
          baseCounter = 0
        }
      }
      if (baseCounter > 0) writer.newLine()
    }
  }

  override def execute(): Unit = {
    val progress = ProgressLogger(logger, noun="bases", verb="written", unit=10e7.toInt)
    val dict     = SequenceDictionary(this.dict)
    val out      = Io.toWriter(output)

    val (srcDict, srcRefFile) = getDictAndFile(fasta=this.input)
    val defaultDictAndRefFile = this.defaultContigs.map(fasta => getDictAndFile(fasta=fasta))

    // Make sure the default FASTA and the input dict are the same
    defaultDictAndRefFile.foreach { case (defaultDict, _) =>
      require(dict.sameAs(defaultDict), "Input sequence dictionary mismatch and default FASTA mismatch")
    }

    // Get the contigs from the input FASTA to output.
    // Note: we do not pre-load the sequences, as to preserve memory
    val srcSequences: Seq[OutputSequence] = srcDict.iterator.flatMap { srcInfo =>
      // Check if the sequence dictionary can rename this contig, and if not, either log or fail depending on --skip-missing
      val dictInfo = dict.get(srcInfo.name) match {
        case None if skipMissing => logger.warning(s"Did not find contig ${srcInfo.name} in the sequence dictionary."); None
        case None                => throw new IllegalStateException(s"Did not find contig ${srcInfo.name} in the sequence dictionary.")
        case Some(info)          => Some(info)
      }
      dictInfo.map(info => OutputSequence(info=info, toSequence=() => srcRefFile.getSequence(srcInfo.name)))
    }.toSeq

    // Get any default contigs that we need to add/append to the output
    // Note: we do not pre-load the sequences, as to preserve memory
    val defaultSequences: Seq[OutputSequence] = {
      defaultDictAndRefFile match {
        case None                      => Seq.empty
        case Some((_, defaultRefFile)) =>
          // Build the list of contig names that will be renamed (i.e. outputted)
          val namesThatCanBeRenamed = srcSequences.map(_.info.name).toSet
          // Keep the contigs in the input sequence dictionary that are not going be used to rename
          val contigs = dict
            .filterNot(info => namesThatCanBeRenamed.contains(info.name))
            .map(info => OutputSequence(info=info, toSequence=() => defaultRefFile.getSequence(info.name)))
            .toSeq
          logger.info(s"Will add/append ${contigs.length} contigs to the output.")
          contigs
      }
    }

    // Order the contigs based on if we want to sort by the input sequence dictionary or not
    val sequences: Seq[OutputSequence] = {
      if (sort) {
        logger.info("Sorting the contigs using the input sequence dictionary")
        (srcSequences ++ defaultSequences).sortBy(_.info.index)
      }
      else {
        logger.info("Sorting the contigs using the input FASTA")
        srcSequences ++ defaultSequences
      }
    }

    // Write them out
    sequences.foreach(_.write(writer=out, progress=progress))
    logger.info(s"Wrote ${sequences.length} contigs.")

    srcRefFile.safelyClose()
    defaultDictAndRefFile.foreach(_._2.safelyClose())
    out.close()
  }


  /** Gets the sequence dictionary and referene sequence file for a given FASTA */
  private def getDictAndFile(fasta: PathToFasta): (SequenceDictionary, ReferenceSequenceFile) = {
    val refFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(fasta, true, true)
    val dict    = Option(refFile.getSequenceDictionary) match {
      case Some(dict) => dict.fromSam
      case None       =>
        require(refFile.isIndexed,
          s"Reference sequence file must have a sequence dictionary or be indexed." +
          s"  Try 'picard CreateSequenceDictionary' or 'samtools faidx <ref.fasta>'. $fasta"
        )
        // Build a very basic sequence dictionary from the fasta index
        val infos = new FastaSequenceIndex(ReferenceSequenceFileFactory
          .getFastaIndexFileName(this.input))
          .map { entry =>
            SequenceMetadata(name=entry.getContig, length=entry.getSize.toInt, index=entry.getSequenceIndex)
          }.toSeq
        SequenceDictionary(infos:_*)
    }
    (dict, refFile)
  }
}