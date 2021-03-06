package dk.itu.coqoon.ui.editors.ideslave

import dk.itu.coqoon.ui.ManifestIdentifiers
import dk.itu.coqoon.ui.editors.{CoqContainer, CoqGoalsContainer}
import dk.itu.coqoon.core.coqtop.ideslave.{CoqTypes, CoqTopIdeSlave_v20120710}
import dk.itu.coqoon.core.utilities.TryCast

trait CoqTopContainer extends CoqContainer with CoqGoalsContainer {
  private val lock = new Object

  def coqTop : CoqTopIdeSlave_v20120710

  private var busy_ : Boolean = false
  def busy : Boolean = lock synchronized { busy_ }
  def setBusy(b : Boolean) : Unit = {
    lock synchronized { busy_ = b }
    fireChange(CoqTopContainer.PROPERTY_BUSY)
  }

  private var flags = Set[String]()
  def setFlag(name : String) = lock synchronized { flags += name }
  def clearFlag(name : String) = lock synchronized { flags -= name }
  def testFlag(name : String) = lock synchronized { flags.contains(name) }
  def clearFlags = lock synchronized { flags = Set() }

  def run(command : CoqCommand) =
    if (!command.synthetic) {
      coqTop.interp(false, true, command.text)
    } else CoqTypes.Good("")
}
object CoqTopContainer {
  final val PROPERTY_BUSY = 979
}

trait CoqTopEditorContainer extends CoqTopContainer {
  import org.eclipse.jface.text.{Position, IDocument, ITextSelection}
  import org.eclipse.jface.text.source.{
    Annotation, IAnnotationModel, IAnnotationModelExtension}
  import org.eclipse.ui.IFileEditorInput
  import org.eclipse.ui.texteditor.ITextEditor

  import scala.collection.mutable.Stack
  def steps() : Stack[_ <: CoqCommand]

  def file = TryCast[IFileEditorInput](editor.getEditorInput).map(_.getFile)
  def editor : ITextEditor
  def document : IDocument =
    editor.getDocumentProvider.getDocument(editor.getEditorInput)
  def cursorPosition : Int = editor.getSelectionProvider.
      getSelection.asInstanceOf[ITextSelection].getOffset

  import org.eclipse.jface.text.source.IAnnotationModel
  protected def doConnectedToAnnotationModel(f : IAnnotationModel => Unit) = {
    val doc = document
    Option(editor.getDocumentProvider.getAnnotationModel(
        editor.getEditorInput)).foreach(model => {
      model.connect(doc)
      try {
        f(model)
      } finally model.disconnect(doc)
      invalidate()
    })
  }

  protected def invalidate()

  var underwayA : Option[Annotation] = None
  var completeA : Option[Annotation] = None

  protected def doSplitAnnotations(r : (Option[Position], Option[Position]),
      model : IAnnotationModel) = {
    val modelEx = TryCast[IAnnotationModelExtension](model)
    def _do(p : Option[Position], a : Option[Annotation],
        aType : String, aText : String) : Option[Annotation] = (p, a) match {
      case (Some(r), None) =>
        val an = new Annotation(aType, false, aText)
        model.addAnnotation(an, r)
        Some(an)
      case (Some(r), Some(an)) =>
        modelEx.foreach(_.modifyAnnotationPosition(an, r))
        Some(an)
      case (None, _) =>
        a.map(model.removeAnnotation)
        None
    }
    completeA = _do(r._1, completeA,
        ManifestIdentifiers.Annotations.PROCESSED, "Processed Proof")
    underwayA = _do(r._2, underwayA,
        ManifestIdentifiers.Annotations.PROCESSING, "Processing Proof")
  }
}
object CoqTopEditorContainer {
  import org.eclipse.jface.text.Position
  def getSplitAnnotationRanges(
      start_ : Option[Int], complete_ : Option[Int], underway_ : Option[Int]) =
    (start_, complete_, underway_) match {
      case (None, _, _) | (_, None, None) => (None, None)

      case (Some(start), None, Some(underway)) =>
        (None, Some(new Position(start, underway - start)))
      case (Some(start), Some(complete), Some(underway))
          if complete < underway =>
        (Some(new Position(start, complete - start)),
            Some(new Position(complete, underway - complete)))
      case (Some(start), Some(complete), _) =>
        (Some(new Position(start, complete - start)), None)
    }
}