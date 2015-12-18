/* CoqPartitions.scala
 * Utility methods for installing Coq partitioning support
 * Copyright © 2013, 2014, 2015 Alexander Faithfull
 *
 * You may use, copy, modify and/or redistribute this code subject to the terms
 * of either the license of Kopitiam or the Apache License, version 2.0 */

package dk.itu.coqoon.ui

object CoqPartitions {
  final val ID = "__coqoon_coq"
  object Types {
    final val COQ = s"${ID}_coq"
    final val STRING = "${COQ}_string"
    final val COMMENT = "${COQ}_comment"
  }
  final lazy val TYPES = mapping.values.toSet.toArray

  import dk.itu.coqoon.ui.text.coq.{CoqTokeniser, CoqRecogniser}
  private[CoqPartitions] lazy val mapping = {
    import CoqRecogniser.States._
    Map(coq -> Types.COQ,
        coqString -> Types.STRING,
        coqComment -> Types.COMMENT)
  }

  import org.eclipse.jface.text.{IDocument, IDocumentExtension3}
  def installPartitioner(input : IDocument, partitioning : String) = {
    import dk.itu.coqoon.core.utilities.TryCast
    val partitioner = createPartitioner
    TryCast[IDocumentExtension3](input) match {
      case Some(ext) =>
        ext.setDocumentPartitioner(partitioning, partitioner)
      case None =>
        input.setDocumentPartitioner(partitioner)
    }
    partitioner.connect(input)
  }

  import dk.itu.coqoon.ui.text.{Tokeniser, TokeniserPartitioner}
  import dk.itu.coqoon.ui.text.coq.{CoqTokeniser, CoqRecogniser}
  def createPartitioner() =
    new TokeniserPartitioner(CoqTokeniser, CoqRecogniser.States.coq, mapping)
}