/* (c) 2012 Hannes Mehnert */

package dk.itu.sdg.javaparser

import scala.util.parsing.input.Positional

sealed abstract class Specification () extends SJExpression with SJBodyDefinition with Positional {
  val data : String
}

case class Precondition (override val data : String) extends Specification { }
case class Postcondition (override val data : String) extends Specification { }
case class Loopinvariant (override val data : String) extends Specification { }
case class Frame (override val data : String) extends Specification { }

//either proof script or predicate - depending on the context
case class RawSpecification (override val data : String) extends Specification { }


object ParseSpecification {
  private val Precon = """precondition: (.*)""".r
  private val Postcon = """postcondition: (.*)""".r
  private val Invariant = """invariant: (.*)""".r
  private val Fram = """frame: (.*)""".r

  def parse (s : String) : Specification = {
    s.trim match {
      case Precon(x) => Precondition(x)
      case Postcon(x) => Postcondition(x)
      case Invariant(x) => Loopinvariant(x)
      case Fram(x) => Frame(x)
      case x => RawSpecification(x)
    }
  }
}



