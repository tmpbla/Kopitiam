package dk.itu.coqoon.ui.editors.ideslave

import dk.itu.coqoon.ui.ManifestIdentifiers
import dk.itu.coqoon.ui.utilities.UIUtils
import dk.itu.coqoon.core
import dk.itu.coqoon.core.model.ICoqModel
import dk.itu.coqoon.core.coqtop.ideslave.CoqTypes
import dk.itu.coqoon.core.utilities.JobRunner

import org.eclipse.core.runtime.{IProgressMonitor, IStatus, Status, SubMonitor}

class InitialiseCoqRunner(editor : CoqEditor) extends JobRunner[Unit] {
  override def doOperation(monitor : SubMonitor) = {
    monitor.beginTask("Initialising Coq", 1)

    editor.coqTop.transaction[Unit](ct => {
      monitor.subTask("Adding project loadpath entries")

      editor.file.foreach(file => {
        val cp = ICoqModel.toCoqProject(file.getProject)
        /* XXX: I don't like using raw="true" here, but it's the quickest way
         * of making re-initialisation work properly (and, because load path
         * changes can't be undone, it should be approximately safe) */
        cp.getLoadPath.foreach(
            lpe => lpe.asCommands.foreach(ct.interp(true, false, _)))
      })

      monitor.worked(1)
    }) match {
      case CoqTypes.Fail((_, message)) =>
        editor.clearFlag(CoqEditor.FLAG_INITIALISED)
        fail(new Status(IStatus.ERROR, ManifestIdentifiers.PLUGIN, message))
      case _ =>
        editor.setFlag(CoqEditor.FLAG_INITIALISED)
    }
  }
}

abstract class CoqEditorJob(name : String, runner : JobRunner[_],
    editor : CoqEditor) extends ContainerJobBase(name, runner, editor)

class StopCoqRunner(editor : CoqEditor) extends JobRunner[Unit] {
  override protected def doOperation(monitor : SubMonitor) = {
    monitor.beginTask("Stopping Coq", 2)

    monitor.subTask("Clearing state")
    editor.steps.synchronized { editor.steps.clear }
    editor.setUnderway(0)
    UIUtils.asyncExec { editor.setGoals(None) }
    monitor.worked(1)

    monitor.subTask("Stopping Coq")
    editor.coqTop.kill
    editor.clearFlag(CoqEditor.FLAG_INITIALISED)
    monitor.worked(1)
  }
}

class CoqStepBackJob(editor : CoqEditor, stepCount : Int,
    reveal : Boolean) extends CoqEditorJob("Stepping back",
        new CoqStepBackRunner(editor, stepCount, reveal), editor)
class CoqStepBackRunner(editor : CoqEditor, stepCount : Int, reveal : Boolean)
    extends StepRunner[String](editor) {
  override protected def finish = {
    super.finish
    if (reveal) {
      UIUtils.asyncExec {
        editor.getViewer.revealRange(editor.completed, 0)
      }
    }
  }

  override protected def doOperation(
      monitor : SubMonitor) : CoqTypes.value[String] = {
    monitor.beginTask("Stepping back", 2)

    val steps = editor.steps.synchronized { editor.steps.take(stepCount) }
    val rewindCount = steps.count(_.synthetic == false)
    editor.coqTop.rewind(rewindCount) match {
      case CoqTypes.Good(extra) =>
        monitor.worked(1)
        editor.steps.synchronized {
          for (step <- steps)
            editor.steps.pop

          if (extra > 0) {
            var i = 0
            var redoSteps = editor.steps.takeWhile(a => {
              if (i < extra) {
                if (!a.synthetic)
                  i += 1
                true
              } else false
            }).toList.reverse
            for (step <- redoSteps)
              editor.steps.pop
            new CoqStepForwardRunner(
                editor, redoSteps).doOperation(monitor.newChild(1))
          }
        }
        CoqTypes.Good("")
      case CoqTypes.Fail(ep) =>
        val completed = editor.steps.synchronized {
          editor.steps.headOption match {
            case None => 0
            case Some(step) => step.offset + step.text.length
          }
        }
        editor.setUnderway(completed)
        CoqTypes.Fail(ep)
    }
  }
}

class CoqStepForwardJob(editor : CoqEditor, steps : Seq[CoqStep])
    extends CoqEditorJob("Step forward",
        new CoqStepForwardRunner(editor, steps), editor)
class CoqStepForwardRunner(
    editor : CoqEditor,
    steps : Seq[CoqStep]) extends StepForwardRunner(editor, steps) {
  override protected def finish = {
    import org.eclipse.core.resources.{IMarker, IResource}

    super.finish
    UIUtils.asyncExec {
      editor.getViewer.revealRange(editor.completed, 0)
      for (
          f <- editor.file;
          i <- f.findMarkers(core.ManifestIdentifiers.MARKER_PROBLEM,
              false, IResource.DEPTH_ZERO)
            if i.getAttribute(
                IMarker.CHAR_END, Int.MaxValue) <= editor.completed)
        i.delete
    }
  }

  override protected def preCheck =
    if (editor.completed != steps.head.offset) {
      /* We seem to have missed a step somewhere, which means that something's
       * gone wrong (an earlier step has probably failed). Bail out
       * immediately */
      Some(CoqTypes.Fail((None, "(missing step)")))
    } else None

  override protected def onGood(
      step : CoqStep, result : CoqTypes.Good[String]) = {
    editor.steps.synchronized { editor.steps.push(step) }
    editor.setCompleted(step.offset + step.text.length)
  }

  override protected def onFail(
      step : CoqStep, result : CoqTypes.Fail[String]) = {
    /* We might have failed because the user stopped Coq. If that's the case,
     * don't do anything */
    if (editor.testFlag(CoqEditor.FLAG_INITIALISED)) {
      editor.file.foreach(
          f => CreateErrorMarkerJob(f, step, result.value).schedule)
      editor.setUnderway(editor.completed)
    }
  }

  override protected def initialise = {
    super.initialise
    if (!editor.testFlag(CoqEditor.FLAG_INITIALISED))
      try {
        new InitialiseCoqRunner(editor).run(null)
      } catch {
        case t : Throwable =>
          t.printStackTrace
          editor.setUnderway(editor.completed)
          UIUtils.asyncExec {
            UIUtils.Dialog.question("Initialisation failed",
                "Coq initialisation failed (\"" +
                Option(t.getMessage).map(_.trim).orNull +
                "\").\n\nOpen the Coqoon preferences dialog now?") match {
              case true =>
                import org.eclipse.ui.dialogs.PreferencesUtil
                PreferencesUtil.createPreferenceDialogOn(
                    UIUtils.getActiveShell,
                    core.ManifestIdentifiers.PREFERENCE_PAGE_COQOON,
                    null, null).open
              case false =>
            }
            ()
          }
          fail(Status.OK_STATUS)
      }
  }
}
