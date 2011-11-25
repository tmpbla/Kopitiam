package dk.itu.sdg.analysis

import scala.collection.immutable.{ HashSet, HashMap }
import org.scalatest.{ FlatSpec }
import org.scalatest.matchers.{ ShouldMatchers }
import dk.itu.sdg.javaparser._
import Purity._
import AnalysisTestHelpers._


class PurityTestsFromPaper extends FlatSpec with ShouldMatchers with ASTSpec {
  
  /*
    Test results expected from the paper 
  */
    
  // ListItr
  
  "Purity analysis on ListItr constructor" should "record a mutation on this.cell" in {
    modifiedAbstractFields("ListItr", listConstructor) should equal (HashSet(AbstractField(ParameterNode("this"),"cell")))
  }
  
  "Purity analysis on ListItr.next" should "record a mutation on this.cell" in {
    modifiedAbstractFields("ListItr", listItrNextMethod) should equal (HashSet(AbstractField(ParameterNode("this"),"cell")))
  }
  
  "Purity analysis on ListItr.hasNext" should "not record any mutations" in {
    isPure("ListItr", listItrHasNext) should equal (true)
  }
  
  // List
  
  "Purity analysis on List.add" should "record a mutation on this.head" in  {
    modifiedAbstractFields("List",listAddMethod) should equal (HashSet(AbstractField(ParameterNode("this"),"head")))
  }
  
  "Purity analysis on List.iterator" should "not record any mutations" in {
    modifiedAbstractFields("List", listIteratorMethod) should equal (HashSet[String]())
  }
  
  // Point
  
  "Purity analysis on Point constructor" should "record mutations on this.x and this.y" in {
    modifiedAbstractFields("Point", pointConstructor) should equal (HashSet(AbstractField(ParameterNode("this"),"x"),
                                                                            AbstractField(ParameterNode("this"),"y")))
  }
  
  // Cell
  
  "Purity analysis on Cell constructor" should "record mutations on this.data and this.next" in {
    modifiedAbstractFields("Cell", cellConstructor) should equal (HashSet(AbstractField(ParameterNode("this"),"data"),
                                                                          AbstractField(ParameterNode("this"),"next")))
  }
  
  // PurityAnalysisExample
  
  // "Purity analysis on PurityAnalysisExample.sumX" should "record mutations on ..." in {
  //     println(modifiedAbstractFields("PurityAnalysisExample", sumx))
  //     // modifiedAbstractFields("PurityAnalysisExample", sumx) should equal (HashSet[AbstractField]())
  //     true should equal (true)
  //   }
  
  val ast = getASTbyParsingFileNamed("PurityAnalysisExample.java", List("src", "test", "resources", "static_analysis", "source"))
  val listAddMethod = methodsOf("List",ast).filter(_.id == "add").head
  val listItrNextMethod = methodsOf("ListItr",ast).filter(_.id == "next").head
  val listItrHasNext = methodsOf("ListItr", ast).filter(_.id == "hasNext").head
  val listIteratorMethod = methodsOf("List",ast).filter(_.id == "iterator").head
  
  val listConstructor = constructorOf("ListItr",ast)  
  val pointConstructor = constructorOf("Point", ast)
  val cellConstructor = constructorOf("Cell", ast)
  
  val sumx = methodsOf("PurityAnalysisExample",ast).filter(_.id == "sumX").head
  val flipAll = methodsOf("PurityAnalysisExample",ast).filter(_.id == "flipAll").head
}

class PurityTestsByMads extends FlatSpec with ShouldMatchers with ASTSpec {
  /*
    Tests and results by Mads
  */
  
  "ParameterToArgument.pta" should "be pure" in {
    println(getState("PersonModifier", swapNames))
    isPure("ParameterToArgument", pta) should equal (true)
  }
  
  val ast2 = getASTbyParsingFileNamed("PurityAnalysisExample2.java", List("src", "test", "resources", "static_analysis", "source"))
  val pta = methodsOf("ParameterToArgument",ast2).filter(_.id == "pta").head
  val swapNames = methodsOf("PersonModifier",ast2).filter(_.id == "swapNames").head
}