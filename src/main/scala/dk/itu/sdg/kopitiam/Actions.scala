/* (c) 2010-2011 Hannes Mehnert */

package dk.itu.sdg.kopitiam

import org.eclipse.ui.IWorkbenchWindowActionDelegate
import org.eclipse.core.commands.IHandler

abstract class KAction extends IWorkbenchWindowActionDelegate with IHandler {
  import org.eclipse.ui.IWorkbenchWindow
  import org.eclipse.jface.action.IAction
  import org.eclipse.jface.viewers.ISelection
  import org.eclipse.core.commands.{IHandlerListener,ExecutionEvent}

  override def init (w : IWorkbenchWindow) : Unit = ()
  override def dispose () : Unit = ()

  override def selectionChanged (a : IAction, s : ISelection) : Unit = ()

  private var handlers : List[IHandlerListener] = List[IHandlerListener]()
  override def addHandlerListener (h : IHandlerListener) : Unit = { handlers ::= h }
  override def removeHandlerListener (h : IHandlerListener) : Unit =
    { handlers = handlers.filterNot(_ == h) }

  override def run (a : IAction) : Unit = { doit }
  override def execute (ev : ExecutionEvent) : Object = { doit; null }

  override def isEnabled () : Boolean = true
  override def isHandled () : Boolean = true
  def doit () : Unit
}

import org.eclipse.ui.IEditorActionDelegate
abstract class KEditorAction extends KAction with IEditorActionDelegate {
  import org.eclipse.ui.IEditorPart
  import org.eclipse.jface.action.IAction
  var editor : IEditorPart = null

  override def setActiveEditor (a : IAction, t : IEditorPart) : Unit = {
    editor = t
  }
}

abstract class KCoqAction extends KAction {
  import org.eclipse.jface.action.IAction
  import org.eclipse.jface.viewers.ISelection
  import org.eclipse.core.commands.{IHandlerListener,ExecutionEvent}

  private var first : Boolean = true
  override def selectionChanged (a : IAction, s : ISelection) : Unit =
    { if (first) { first = false; ActionDisabler.registeraction(a, start(), end()) } }

  override def isEnabled () : Boolean = {
    if (DocumentState.position == 0 && start)
      false
    else if (DocumentState.position + 1 >= DocumentState.content.length && end)
      false
    else
      true
  }
  override def run (a : IAction) : Unit = { doitH }
  override def execute (ev : ExecutionEvent) : Object = { doitH; null }

  import org.eclipse.ui.{IFileEditorInput, PlatformUI}
  import org.eclipse.core.resources.{IResource, IMarker}
  def doitH () : Unit = {
    ActionDisabler.disableAll
    val coqstarted = CoqTop.isStarted
    var acted = PlatformUI.getWorkbench.getActiveWorkbenchWindow.getActivePage.getActiveEditor
    if (DocumentState.activeEditor != acted && acted.isInstanceOf[CoqEditor]) {
      if (DocumentState.resource != null)
        EclipseBoilerPlate.unmarkReally
      DocumentState.resetState
      JavaPosition.unmark
      JavaPosition.retract
      JavaPosition.retractModel
      if (DocumentState.activeEditor != null)
        DocumentState.processUndo
      DocumentState.activeEditor = acted.asInstanceOf[CoqEditor]

      if (CoqOutputDispatcher.goalviewer != null)
        CoqOutputDispatcher.goalviewer.clear

      if (! coqstarted)
        CoqStartUp.start
      if (coqstarted) {
        DocumentState.setBusy
        PrintActor.deregister(CoqOutputDispatcher)
        PrintActor.deregister(CoqStepNotifier)
        PrintActor.deregister(CoqCommands)
        val shell = CoqState.getShell
        PrintActor.register(CoqStartUp)
        val initial = DocumentState.positionToShell(0).globalStep
        CoqTop.writeToCoq("Backtrack " + initial + " 0 " + shell.context.length + ".")
        while (! CoqStartUp.fini) { }
        CoqStartUp.fini = false
      }
    } else
      if (! coqstarted)
        CoqStartUp.start
    doit
  }

  def start () : Boolean
  def end () : Boolean
}

object ActionDisabler {
  import org.eclipse.jface.action.IAction
  var actions : List[IAction] = List()
  var starts : List[Boolean] = List()
  var ends : List[Boolean] = List()
  def registeraction (act : IAction, start : Boolean, end : Boolean) : Unit = {
    actions ::= act
    starts ::= start
    ends ::= end
  }

  def disableAll () = {
    actions.foreach(_.setEnabled(false))
  }

  def enableMaybe () = {
    //Console.println("maybe enable " + DocumentState.position + " len " + DocumentState.content.length)
    if (! CoqCommands.active) {
      Console.println("enableMaybe - may call content...")
      if (DocumentState.position == 0)
        enableStart
      else if (DocumentState.position + 1 >= DocumentState.content.length)
        actions.zip(ends).filterNot(_._2).map(_._1).foreach(_.setEnabled(true))
      else
        actions.foreach(_.setEnabled(true))
    }
  }

  def enableStart () = {
    actions.zip(starts).filterNot(_._2).map(_._1).foreach(_.setEnabled(true))
  }
}

class CoqUndoAction extends KCoqAction {
  override def doit () : Unit = {
    doitReally(DocumentState.position)
  }

  def doitReally (pos : Int) : Unit = {
    if (DocumentState.readyForInput) {
      DocumentState.setBusy
      //invariant: we're at position p, all previous commands are already sent to coq
      // this implies that the table (positionToShell) is fully populated until p
      // this also implies that the previous content is not messed up!
      val curshell = CoqState.getShell
      //curshell is head of prevs, so we better have one more thing
      val prevs = DocumentState.positionToShell.keys.toList.sortWith(_ > _)
      assert(prevs.length > 0)
      Console.println("working (pos: " + pos + ") on the following keys " + prevs)
      //now we have the current state (curshell): g cs l
      //we look into lastmost shell, either:
      //g-- cs l or g-- cs l-- <- we're fine
      //g-- cs+c l++ <- we need to find something with cs (just jumped into a proof)
      var i : Int = prevs.filter(_ >= pos).length
      if (i == prevs.length) //special case, go to lastmost
        i = i - 1
      var prevshell : CoqShellTokens = DocumentState.positionToShell(prevs(i))
      //Console.println("prevshell: " + prevshell + "\ncurshell: " + curshell)
      assert(prevshell.globalStep < curshell.globalStep) //we're decreasing!
      while (! prevshell.context.toSet.subsetOf(curshell.context.toSet) && prevs.length > (i + 1)) {
        i += 1
        prevshell = DocumentState.positionToShell(prevs(i))
      }
      val ctxdrop = curshell.context.length - prevshell.context.length
      DocumentState.realundo = true
      EclipseBoilerPlate.unmarkReally
      JavaPosition.unmark
      DocumentState.sendlen = DocumentState.position - prevs(i)
      prevs.take(i).foreach(DocumentState.positionToShell.remove(_))
      assert(DocumentState.positionToShell.contains(0) == true)
      CoqTop.writeToCoq("Backtrack " + prevshell.globalStep + " " + prevshell.localStep + " " + ctxdrop + ".")
    }
  }
  override def start () : Boolean = true
  override def end () : Boolean = false
}
object CoqUndoAction extends CoqUndoAction { }

class CoqRetractAction extends KCoqAction {
  override def doit () : Unit = {
    DocumentState.setBusy
    PrintActor.deregister(CoqOutputDispatcher)
    DocumentState.resetState
    PrintActor.register(CoqStartUp)
    val shell = CoqState.getShell
    DocumentState.processUndo
    EclipseBoilerPlate.unmarkReally
    JavaPosition.unmark
    JavaPosition.retract
    val initial =
      if (DocumentState.positionToShell.contains(0))
        DocumentState.positionToShell(0).globalStep
      else {
        Console.println("CoqRetractAction: retracting without position information, using 2")
        2
      }
    CoqTop.writeToCoq("Backtrack " + initial + " 0 " + shell.context.length + ".")
  }
  override def start () : Boolean = true
  override def end () : Boolean = false
}
object CoqRetractAction extends CoqRetractAction { }

class CoqStepAction extends KCoqAction {
  override def doit () : Unit = {
    Console.println("CoqStepAction.doit called, ready? " + DocumentState.readyForInput)
    if (DocumentState.readyForInput) {
      Console.println("CoqStepAction: calling content")
      val con = DocumentState.content
      CoqCommands.step
      val content = con.drop(DocumentState.position)
      if (content.length > 0) {
        val eoc = CoqTop.findNextCommand(content)
        //Console.println("eoc is " + eoc)
        if (eoc > 0) {
          DocumentState.setBusy
          DocumentState.sendlen = eoc
          DocumentState.process
          val cmd = content.take(eoc).trim
          Console.println("command is (" + eoc + "): " + cmd)
          //CoqProgressMonitor.actor.tell(("START", cmd))
          CoqTop.writeToCoq(cmd) //sends comments over the line
        }
      }
    }
  }
  override def start () : Boolean = false
  override def end () : Boolean = true
}
object CoqStepAction extends CoqStepAction { }

class CoqStepAllAction extends KCoqAction {
  override def doit () : Unit = {
    //CoqProgressMonitor.multistep = true
    DocumentState.reveal = false
    CoqStepNotifier.active = true
    //we need to provoke first message to start callback loops
    CoqStepAction.doit()
  }
  override def start () : Boolean = false
  override def end () : Boolean = true
}
object CoqStepAllAction extends CoqStepAllAction { }

class CoqStepUntilAction extends KCoqAction {
  import org.eclipse.swt.widgets.Display

  override def doit () : Unit = {
    val cursor = EclipseBoilerPlate.getCaretPosition
    reallydoit(cursor)
  }

  def reallydoit (cursor : Int) : Unit = {
    Console.println("CoqStepUntilAction:reallydoit - content")
    val togo = CoqTop.findNextCommand(DocumentState.content.drop(cursor)) + cursor
    Console.println("reallydoit: togo is " + togo + ", cursor is " + cursor + ", docpos is " + DocumentState.position)
    //doesn't work reliable when inside a comment
    if (DocumentState.position == togo) { } else
    if (DocumentState.position < togo) {
      DocumentState.until = CoqTop.findPreviousCommand(DocumentState.content, togo - 2)
      DocumentState.process
      //CoqProgressMonitor.multistep = true
      DocumentState.reveal = false
      CoqStepNotifier.test = Some((x : Int, y : Int) => y >= togo)
      CoqStepNotifier.active = true
      CoqStepAction.doit()
    } else { //Backtrack
      //go forward till cursor afterwards
      DocumentState.reveal = false
      CoqCommands.doLater(() => CoqStepUntilAction.reallydoit(cursor))
      CoqUndoAction.doitReally(cursor)
    }
  }
  override def start () : Boolean = false
  override def end () : Boolean = false
}
object CoqStepUntilAction extends CoqStepUntilAction { }

class RestartCoqAction extends KAction {
  override def doit () : Unit = {
    CoqTop.killCoq
    DocumentState.resetState
    DocumentState.processUndo
    EclipseBoilerPlate.unmarkReally
    JavaPosition.unmark
    JavaPosition.retract
    PrintActor.deregister(CoqOutputDispatcher)
    CoqStartUp.start
  }
}
object RestartCoqAction extends RestartCoqAction { }

class InterruptCoqAction extends KAction {
  override def doit () : Unit = {
    Console.println("interrupt called")
    DocumentState.processUndo
    CoqTop.interruptCoq
  }
}
object InterruptCoqAction extends InterruptCoqAction { }

//only enable in proof edit mode!
class CoqRefreshAction extends KCoqAction {
  override def doit () : Unit = {
    CoqTop.writeToCoq("Show. ")
  }
  override def start () : Boolean = true
  override def end () : Boolean = false
}
object CoqRefreshAction extends CoqRefreshAction { }

class ProveMethodAction extends KEditorAction {
  import org.eclipse.jface.action.IAction
  import org.eclipse.jface.text.ITextSelection
  import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor
  import org.eclipse.ui.part.FileEditorInput
  override def run (a : IAction) : Unit = {
    //plan:
    // a: get project
    val edi : JavaEditor = editor.asInstanceOf[JavaEditor]
    val prov = edi.getDocumentProvider
    val doc = prov.getDocument(edi.getEditorInput)
    val proj = EclipseTables.DocToProject(doc)
    // c: find method name in java buffer
    val selection = edi.getSelectionProvider.getSelection.asInstanceOf[ITextSelection]
    val sl = selection.getStartLine
    val soff = doc.getLineOffset(sl)
    val slen = doc.getLineLength(sl)
    val line = doc.get(soff, slen)
    val marr = line.split("\\(")
    assert(marr.length == 2)
    val arr = marr(0).split(" ")
    val nam = arr(arr.length - 1)
    if (JavaPosition.active == false || JavaPosition.name != nam) {
      JavaPosition.retract
      // e: set name in JavaPosition
      JavaPosition.name = nam
    }

    // b: if outdated coqString: translate
    proj.proveMethod(nam)
    CoqCommands.step
  }
  override def doit () : Unit = { }
}
object ProveMethodAction extends ProveMethodAction { }

class TranslateAction extends KAction {
  import org.eclipse.ui.handlers.HandlerUtil
  import org.eclipse.jface.viewers.IStructuredSelection
  import org.eclipse.jdt.core.ICompilationUnit
  import org.eclipse.core.resources.{IResource,IFile}
  import org.eclipse.core.commands.ExecutionEvent
  import java.io.{InputStreamReader,ByteArrayInputStream}
  import scala.util.parsing.input.StreamReader

  import dk.itu.sdg.javaparser.JavaAST
  object JavaTC extends JavaAST { }

  import org.eclipse.jdt.core.ICompilationUnit
  override def execute (ev : ExecutionEvent) : Object = {
    Console.println("execute translation!")
    val sel = HandlerUtil.getActiveMenuSelection(ev).asInstanceOf[IStructuredSelection]
    val fe = sel.getFirstElement
    if (fe.isInstanceOf[IFile])
      translate(fe.asInstanceOf[IFile])
    else if (fe.isInstanceOf[ICompilationUnit])
      translate(fe.asInstanceOf[ICompilationUnit].getResource.asInstanceOf[IFile])
    null
  }

  def translate (file : IFile) : Option[String] = {
    val nam = file.getName
    if (nam.endsWith(".java")) {
      val trfi = file.getProject.getFile(nam + ".v") //TODO: find a suitable location!
      val is = StreamReader(new InputStreamReader(file.getContents, "UTF-8"))
      if (trfi.exists)
        trfi.delete(true, false, null)
      trfi.create(null, IResource.NONE, null)
      trfi.setCharset("UTF-8", null)
      val (con, off) = JavaTC.parse(is, nam.substring(0, nam.indexOf(".java")))
      trfi.setContents(new ByteArrayInputStream(con.getBytes("UTF-8")), IResource.NONE, null)
      val proj = EclipseTables.StringToProject(nam.split("\\.")(0))
      proj.proofOffset = off._1
      proj.coqString = Some(con)
      proj.modelNewerThanSource = false
      proj.javaNewerThanSource = false
      off._2.map(x => {
        proj.javaOffsets = proj.javaOffsets + (x._1._1 -> x._1._2)
        proj.coqOffsets = proj.coqOffsets + (x._1._1 -> x._2)
      })
      Some(con)
    } else {
      Console.println("wasn't a java file")
      None
    }
  }

  override def doit () : Unit = ()
}
object TranslateAction extends TranslateAction { }

object CoqStartUp extends CoqCallback {
  private var initialize : Int = 0
  var fini : Boolean = false

  def start () : Unit = {
    if (! CoqTop.isStarted) {
      PrintActor.register(CoqStartUp)
      if (EclipseConsole.out == null)
        EclipseConsole.initConsole
      PrintActor.stream = EclipseConsole.out
      if (! CoqTop.startCoq)
        EclipseBoilerPlate.warnUser("No Coq", "No Coq binary found, please specify one in the Kopitiam preferences page")
      else {
        while (!fini) { }
        fini = false
      }
      PrintActor.deregister(CoqStartUp)
    }
  }

  import java.io.File
  override def dispatch (x : CoqResponse) : Unit = {
    x match {
      case CoqShellReady(m, t) =>
      	//Console.println("CoqStartUp dispatch, initialize is " + initialize + " fini is " + fini)
        val loadp = Activator.getDefault.getPreferenceStore.getString("loadpath")
        val lp = new File(loadp).exists
        if (initialize == 0) {
          DocumentState.setBusy
          if (lp) {
            CoqTop.writeToCoq("Add LoadPath \"" + loadp + "\".")
            initialize = 1
          } else {
            CoqTop.writeToCoq("Add LoadPath \"" + EclipseBoilerPlate.getProjectDir + "\".")
            initialize = 2
          }
        } else if (initialize == 1) {
            DocumentState.setBusy
            CoqTop.writeToCoq("Add LoadPath \"" + EclipseBoilerPlate.getProjectDir + "\".")
            initialize = 2
        } else {
          PrintActor.deregister(CoqStartUp)
          PrintActor.register(CoqOutputDispatcher)
          PrintActor.register(CoqStepNotifier)
          PrintActor.register(CoqCommands)
          initialize = 0
          fini = true
          ActionDisabler.enableMaybe
        }
      case y =>
    }
  }
}

object CoqStepNotifier extends CoqCallback {
  var err : Boolean = false
  var test : Option[(Int, Int) => Boolean] = None
  var active : Boolean = false

  override def dispatch (x : CoqResponse) : Unit = {
    x match {
      case CoqError(m, s, l) => if (active) err = true
      case CoqUserInterrupt() => if (active) err = true
      case CoqShellReady(monoton, tokens) =>
        if (active)
          if (err)
            fini
          else {
            Console.println("CoqStepNotifier - content")
            val nc = CoqTop.findNextCommand(DocumentState.content.drop(DocumentState.position))
            if ((test.isDefined && test.get(DocumentState.position, DocumentState.position + nc)) || nc == -1)
              fini
            else if (monoton) {
              CoqStepAction.doit
            } else
              fini
          }
      case x => //Console.println("got something, try again player 1 " + x)
    }
  }

  def fini () : Unit = {
    err = false
    active = false
    test = None
    DocumentState.reveal = true
    DocumentState.until = -1
    //CoqProgressMonitor.multistep = false
    //CoqProgressMonitor.actor.tell("FINISHED")
  }
}

object CoqCommands extends CoqCallback {
  private var commands : List[() => Unit] = List[() => Unit]()

  def active () : Boolean = commands.length > 0

  def doLater (f : () => Unit) : Unit = {
    commands = (commands :+ f)
  }

  def step () : Unit = {
    if (finished) {
      if (commands.size != 0) {
        val c = commands.head 
        Console.println("coq commands here - will do next command " + c)
        commands = commands.tail
        //should we execute in a certain thread? UI?
        c()
      } else
        Console.println("no command to run")
    }
    else
      Console.println("not finished... can't do much")
  }

  private def finished () : Boolean = {
    //might also be used instead of reveal stuff in DocumentState!
    if (CoqStepNotifier.active)
      return false
    //if (CoqStartUp.fini == false)
      //unfortunately this is reset (to false) in CoqStartUp.start
    //  return false
    return true
  }

  override def dispatch (x : CoqResponse) : Unit = {
    x match {
      case CoqShellReady(monoton, token) => step
      case CoqError(m, s, l) => commands = List[() => Unit]()
      case _ =>
    }
  }
  
}

object CoqOutputDispatcher extends CoqCallback {
  import org.eclipse.swt.widgets.Display
  import org.eclipse.core.resources.IMarker

  var goalviewer : GoalViewer = null

  def stripAndReduce (x : List[String]) : String = {
    if (x.length > 0) x.map(_.filterNot(_ == '\r')).reduceLeft(_ + "\n" + _) else ""
  }

  override def dispatch (x : CoqResponse) : Unit = {
    //Console.println("received in dispatch " + x)
    x match {
      case CoqShellReady(monoton, token) =>
        //CoqProgressMonitor.actor.tell("FINISHED")
        if (monoton) {
          EclipseBoilerPlate.unmark
          JavaPosition.unmark
        }
        ActionDisabler.enableMaybe
        if (!monoton && token.context.length == 0 && goalviewer != null)
          goalviewer.clear
      case CoqGoal(n, goals) =>
        //Console.println("outputdispatcher, n is " + n + ", goals:\n" + goals)
        val (hy, res) = goals.splitAt(goals.indexWhere(_.contains("======")))
        val ht = stripAndReduce(hy)
        var subgoals : List[String] = List[String]()
        var index : Int = 0
        while (index < res.length) {
          val rr = res.drop(index)
          //Console.println("index: " + index + " res.length: " + res.length + " list stuff: " + rr)
          val subd = rr.drop(1).indexWhere(_.contains("subgoal ")) + 1
          //Console.println("subd is " + subd)
          val (g, r) = if (subd > 0) rr.splitAt(subd) else (rr, List[String]())
          //Console.println("g is " + g + " r is " + r)
          val gt = if (g.length > 1) stripAndReduce(g.drop(1)).trim else ""
          //Console.println("gt " + gt)
          subgoals = gt :: subgoals
          if (subd > 0)
            index = index + subd
          else
            index = res.length
        }
        if (goalviewer != null) {
          //Console.println("calling writegoal with " + subgoals.reverse)
          goalviewer.writeGoal(ht, subgoals.reverse)
        }
      case CoqProofCompleted() =>
        if (goalviewer != null)
          goalviewer.writeGoal("Proof completed", List[String]())
      case CoqError(msg, start, len) =>
        if (len != 0)
          EclipseBoilerPlate.mark(msg, IMarker.SEVERITY_ERROR, false, start, len)
        else
          EclipseBoilerPlate.mark(msg)
        EclipseConsole.out.println("Error: " + msg)
      case CoqWarning(msg) =>
        EclipseBoilerPlate.mark(msg, IMarker.SEVERITY_WARNING, true)
        EclipseConsole.out.println("Warning: " + msg)
      case CoqUserInterrupt() =>
        EclipseConsole.out.println("Interrupted. Most likely in a sane state.")
      case CoqVariablesAssumed(v) =>
        EclipseConsole.out.println("Variable " + v + " assumed")
      case CoqTheoremDefined(t) =>
        EclipseConsole.out.println("Theorem " + t + " defined")
      case CoqSearchResult(n) => ()
      case CoqUnknown(x) =>
        EclipseConsole.out.println(x)
    }
  }
}

