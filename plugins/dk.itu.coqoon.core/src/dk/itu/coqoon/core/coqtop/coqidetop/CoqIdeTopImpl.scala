package dk.itu.coqoon.core.coqtop.coqidetop

import java.io.Reader
import scala.io.Source
import scala.xml.{XML, Elem}
import scala.xml.parsing.ConstructingParser
import dk.itu.coqoon.core.CoqoonPreferences
import dk.itu.coqoon.core.coqtop.CoqSentence
import dk.itu.coqoon.core.coqtop.{CoqProgram, CoqProgramInstance}

private class StringBuilderSource(
    sb : StringBuilder) extends scala.io.Source {
  override val iter = sb.iterator
  /* Suppress all error reporting -- sb will *usually* be an incomplete XML
   * document */
  override def report(pos : Int, msg : String, out : java.io.PrintStream) = ()
}
object StringBuilderSource {
  def apply(sb : StringBuilder) : scala.io.Source = new StringBuilderSource(sb)
}

private class CoqOutputParser(input : StringBuilder)
    extends ConstructingParser(StringBuilderSource(input), false) {
  override val preserveWS = true

  /* ide/xml_printer.ml:buffer_pcdata is responsible for generating these
   * entities */
  override def replacementText(entity : String) = entity match {
    case "nbsp" => Source.fromChar(' ')
    case "gt" => Source.fromChar('<')
    case "lt" => Source.fromChar('>')
    case "amp" => Source.fromChar('&')
    case "apos" => Source.fromChar('\'')
    case "quot" => Source.fromChar('"')
    case _ => super.replacementText(entity)
  }
  nextch()
}
object CoqOutputParser {
  def parse(sb : StringBuilder) = new CoqOutputParser(sb).document
}

class CoqIdeTopImpl(args : Seq[String]) extends CoqIdeTop_v20170413 {
  import CoqIdeTopImpl._

  private var pr : Option[CoqProgramInstance] = None
  private def notifyListeners(e : Elem) = {
    val f =  Feedback.XML.unwrapFeedback(e)
    listeners.foreach(_.onFeedback(f))
  }
  
  private def send(e : Elem) : Elem = {
    if (pr == None) {
      val ct = CoqProgram
      if (!ct.check) {
        throw new java.io.IOException("Couldn't find the coqtop program")
      } else if (ct.version == None) {
        throw new java.io.IOException("Couldn't detect Coq version")
      }
      pr = Option(ct.run(
          (args ++ Seq("-toploop", "coqidetop", "-main-channel", "stdfds")) ++
          CoqoonPreferences.ExtraArguments.get))
      ReaderThread.running = true
      ReaderThread.start
    }
    ReaderThread.ValueLock synchronized {
      import ReaderThread.ValueLock._
      replyAwaited = true
      if (_DEBUG_PRINT)
        println("-> " + e.toString)
      pr.get.stdin.write(e.toString)
      pr.get.stdin.flush
      while (value.isEmpty)
        ReaderThread.ValueLock.wait
      try {
        value.get
      } finally {
        value = None
        replyAwaited = false
      }
    }
  }

  private object ReaderThread extends Thread {
    object ValueLock {
      var replyAwaited = false
      var value : Option[Elem] = None
    }

    private val buf : Array[Char] = Array(32768)
    
    var running = false
    override def run() =
      while (running) {
        @scala.annotation.tailrec def _util(
            sofar_ : StringBuilder = StringBuilder.newBuilder) :
                Option[Elem] = {
          /* (XXX: this seems to work okay, but would freeze if stderr filled
           * up in the middle of a blocking read on stdout; to address that,
           * this code should move to another thread) */
          /* We don't care about standard error, but we do need to make sure it
           * doesn't fill up and cause coqtop to block */
          if (pr.get.stderr.ready)
            pr.get.stderr.read(buf)

          var sofar = sofar_
          val count = pr.get.stdout.read(buf)
          if (count == -1)
            return None
          sofar ++= buf.toSeq.take(count)
          if (sofar.endsWith(">")) {
            try {
              val doc = CoqOutputParser.parse(sofar)
              Some(doc.children(0).asInstanceOf[Elem])
            } catch {
              case e : scala.xml.parsing.FatalError =>
                _util(sofar)
            }
          } else _util(sofar)
        }
        val elem = _util().get
        if (_DEBUG_PRINT)
          println("<- " + elem)
        ValueLock synchronized {
          import ValueLock._
          if (elem.label == "value") {
            value = Some(elem)
            ValueLock.notify
          } else notifyListeners(elem)
        }
      }
  }

  override def about() = unwrapAboutResponse(send(wrapAboutCall))
  override def add(
      stateId : Integer, command : CharSequence, v : Interface.verbose) =
    unwrapAddResponse(send(wrapAddCall(stateId, command, v)))
  def annotate(annotation : String) =
    unwrapAnnotateResponse(send(wrapAnnotateCall(annotation)))
  override def editAt(stateId : Integer) =
    unwrapEditAtResponse(send(wrapEditAtCall(stateId)))
  override def evars() = unwrapEvarsResponse(send(wrapEvarsCall))
  override def getOptions() =
    unwrapGetOptionsResponse(send(wrapGetOptionsCall))
  override def goal() = unwrapGoalResponse(send(wrapGoalCall))
  override def hints() = unwrapHintsResponse(send(wrapHintsCall))
  override def init(scriptPath : Option[String]) =
    unwrapInitResponse(send(wrapInitCall(scriptPath)))
  override def mkCases(s : String) =
    unwrapMkCasesResponse(send(wrapMkCasesCall(s)))
  def printAst(stateId : Integer) =
    unwrapPrintAstResponse(send(wrapPrintAstCall(stateId)))
  override def query(routeId : Integer, query : String, stateId : Integer) =
    unwrapQueryResponse(send(wrapQueryCall(routeId, query, stateId)))
  /* override def quit() = unwrapQuitResponse(send(wrapQuitCall)) */
  override def search(
      constraints : Seq[(Interface.search_constraint, Boolean)]) =
    unwrapSearchResponse(send(wrapSearchCall(constraints)))
  override def setOptions(
      options : Seq[(Seq[String], Interface.option_value)]) =
    unwrapSetOptionsResponse(send(wrapSetOptionsCall(options)))
  override def status(force : Boolean) =
    unwrapStatusResponse(send(wrapStatusCall(force)))
  override def stopWorker(worker : String) =
    unwrapStopWorkerResponse(send(wrapStopWorkerCall(worker)))
}
private object CoqIdeTopImpl {
  private final val _DEBUG_PRINT = false

  import Interface._
  import Interface.XML._

  def wrapAboutCall() =
    <call val="About">{
    wrapUnit()}</call>
  def unwrapAboutResponse(e : Elem) =
    unwrapValue(unwrapCoqInfo)(e)

  def wrapAddCall(stateId : Integer, command : CharSequence, v : verbose) =
    <call val="Add">{
    wrapPair(
        wrapPair(wrapString, wrapInt),
        wrapPair(wrapStateId, wrapBoolean))((command, 1), (stateId, v))}</call>
  def unwrapAddResponse(e : Elem) =
    unwrapValue(unwrapPair(
        unwrapStateId, unwrapPair(
            unwrapUnion(unwrapUnit, unwrapStateId), unwrapString)))(e)

  def wrapAnnotateCall(annotation : String) =
    <call val="Annotate">{
    wrapString(annotation)
    }</call>
  def unwrapAnnotateResponse(e : Elem) =
    unwrapValue(_unwrapRaw)(e)

  def wrapEditAtCall(stateId : Integer) =
    <call val="Edit_at">{
    wrapStateId(stateId)}</call>
  def unwrapEditAtResponse(e : Elem) =
    unwrapValue(unwrapUnion(
        unwrapUnit, unwrapPair(unwrapStateId, unwrapPair(
            unwrapStateId, unwrapStateId))))(e)

  def wrapEvarsCall() =
    <call val="Evars">{
    wrapUnit()}</call>
  def unwrapEvarsResponse(e : Elem) =
    unwrapValue(unwrapOption(unwrapList(unwrapString)))(e)

  def wrapGetOptionsCall() =
    <call val="GetOptions">{
    wrapUnit()}</call>
  def unwrapGetOptionsResponse(e : Elem) =
    unwrapValue(unwrapList(unwrapPair(
        unwrapList(unwrapString), unwrapOptionState)))(e)

  def wrapGoalCall() =
    <call val="Goal">{
    wrapUnit()}</call>
  def unwrapGoalResponse(e : Elem) =
    unwrapValue(unwrapOption(unwrapGoals))(e)

  def wrapHintsCall() =
    <call val="Hints">{
    wrapUnit()}</call>
  def unwrapHintsResponse(e : Elem) =
    unwrapValue(unwrapOption(unwrapPair(
        unwrapList(unwrapHint), unwrapHint)))(e)

  def wrapInitCall(scriptPath : Option[String]) =
    <call val="Init">{
    wrapOption(wrapString)(scriptPath)
    }</call>
  def unwrapInitResponse(e : Elem) =
    unwrapValue(unwrapStateId)(e)

  def wrapMkCasesCall(s : String) =
    <call val="MkCases">{
    wrapString(s)
    }</call>
  def unwrapMkCasesResponse(e : Elem) =
    unwrapValue(unwrapList(unwrapList(unwrapString)))(e)

  def wrapPrintAstCall(sid : state_id) =
    <call val="PrintAst">{
    wrapStateId(sid)
    }</call>
  def unwrapPrintAstResponse(e : Elem) =
    unwrapValue(_unwrapRaw)(e)

  def wrapQueryCall(rid : Int, query : String, sid : Int) =
    <call val="Query">{
    wrapPair(wrapRouteId, wrapPair(wrapString, wrapStateId))(
        rid, (query, sid))}</call>
  def unwrapQueryResponse(e : Elem) =
    unwrapValue(unwrapString)(e)

  def wrapQuitCall() =
    <call val="Quit">{
    wrapUnit()}</call>
  def unwrapQuitResponse(e : Elem) =
    unwrapValue(unwrapUnit)(e)

  def wrapSearchCall(constraints : Seq[(search_constraint, Boolean)]) =
    <call val="Search">{
    wrapList(wrapPair(wrapSearchConstraint, wrapBoolean))(constraints)}</call>
  def unwrapSearchResponse(e : Elem) =
    unwrapValue(unwrapList(unwrapCoqObject(unwrapString)))(e)

  def wrapSetOptionsCall(options : Seq[(Seq[String], option_value)]) =
    <call val="SetOptions">{
    wrapList(wrapPair(wrapList(wrapString), wrapOptionValue))(options)}</call>
  def unwrapSetOptionsResponse(e : Elem) =
    unwrapValue(unwrapUnit)(e)

  def wrapStatusCall(force : Boolean) =
    <call val="Status">{
    wrapBoolean(force)
    }</call>
  def unwrapStatusResponse(e : Elem) =
    unwrapValue(unwrapStatus)(e)

  def wrapStopWorkerCall(worker : String) =
    <call val="StopWorker">{
    wrapString(worker)
    }</call>
  def unwrapStopWorkerResponse(e : Elem) =
    unwrapValue(unwrapUnit)(e)
}

object CoqIdeTopImplTest {
  object FeedbackListener extends CoqIdeTopFeedbackListener {
    override def onFeedback(f : Feedback) = println(f)
  }
  import Interface._

  val doc = """
Theorem t : True /\ True /\ True /\ True.
Proof.
  (* This is a Synthetic Sentence *)
  (* Synthetic Sentence description 3 *)
  split.
  + trivial.
  + split.
    - trivial.
    - split.
      * trivial.
      * trivial.
Qed."""
  val sentences = CoqSentence.getNextSentences(doc, 0, doc.length)

  def main(args : Array[String]) : Unit = {
    val a = new CoqIdeTopImpl(Seq())
    a.addListener(FeedbackListener)
    var head = a.init(None) match {
      case Interface.Good(head) =>
        head
      case _ =>
        1
    }
    println(a.about())
    println(a.setOptions(Seq((Seq("Printing", "All"), BoolValue(true)))))
    var off = 0
    var stateIdMappings : Map[Int, state_id] = Map()
    var goalMappings : Map[(state_id, Int), Interface.goals] = Map()
    var statusMappings :
        Map[state_id, Interface.value[Interface.status]] = Map()
    sentences.zipWithIndex foreach {
      case ((s, true), _) =>
        /* Coq doesn't need to know about comments and whatnot */
        off += s.length
      case ((s, false), i) =>
        a.add(head, s, true) match {
          case Interface.Good((newHead, (Left(()), c))) =>
            stateIdMappings += (i -> newHead)
            head = newHead
            a.goal match {
              case Interface.Good(Some(goals)) =>
                goalMappings += (off, off + s.length) -> goals
              case _ =>
            }
            off += s.length
            statusMappings += (newHead -> a.status(false))
          case Interface.Fail((s, l, e)) =>
            println(e)
        }
    }
  }
}