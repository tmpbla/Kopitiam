/* CoqModel.scala
 * An abstraction layer between Eclipse resources and Coq concepts
 * Copyright © 2013 Alexander Faithfull
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License. */

package dk.itu.coqoon.core.model

import dk.itu.coqoon.core.{Activator, ManifestIdentifiers}
import dk.itu.coqoon.core.debug.CoqoonDebugPreferences
import dk.itu.coqoon.core.coqtop.CoqProgram
import dk.itu.coqoon.core.utilities.TryCast

import org.eclipse.core.runtime.{IPath, Path, IProgressMonitor}
import org.eclipse.core.resources.{
  IResource, IProjectDescription, ICommand,
  IFile, IFolder, IProject, IWorkspace, IWorkspaceRoot}

trait IParent {
  def getChildren : Seq[ICoqElement]
  def hasChildren : Boolean = (!getChildren.isEmpty)
}

trait ICoqElement {
  def exists : Boolean
  def getAncestor[A]()(implicit a0 : Manifest[A]) : Option[A] =
    this match {
      case q : A => Some(q)
      case _ => getParent.flatMap(_.getAncestor[A])
    }
  def getParent : Option[ICoqElement with IParent]
  def getCorrespondingResource : Option[IResource]
  def getContainingResource : Option[IResource] =
    getCorrespondingResource.orElse(getParent.flatMap(_.getContainingResource))
  def getModel : ICoqModel = getAncestor[ICoqModel].get

  def accept(f : ICoqElement => Boolean)
}

trait ICoqModel extends ICoqElement with IParent {
  override def getParent = None
  override def getCorrespondingResource : Option[IWorkspaceRoot]

  def getProject(name : String) : ICoqProject
  def getProjects : Seq[ICoqProject]
  def hasProjects : Boolean = (!getProjects.isEmpty)

  def toCoqElement(resource : IResource) : Option[ICoqElement]

  def addListener(l : CoqElementChangeListener)
  def removeListener(l : CoqElementChangeListener)
}
object ICoqModel {
  def create(root : IWorkspaceRoot) : ICoqModel =
    new CoqModelImpl(Option(root))

  private val instance =
    create(org.eclipse.core.resources.ResourcesPlugin.getWorkspace.getRoot)
  def getInstance : ICoqModel = instance

  def toCoqProject(project : IProject) : ICoqProject =
    getInstance.toCoqElement(project).flatMap(TryCast[ICoqProject]).orNull
}

trait CoqElementChangeListener {
  def coqElementChanged(ev : CoqElementEvent)
}

abstract class CoqElementEvent(val element : ICoqElement)

case class CoqElementAddedEvent(
    override val element : ICoqElement) extends CoqElementEvent(element)
case class CoqElementRemovedEvent(
    override val element : ICoqElement) extends CoqElementEvent(element)

abstract class CoqElementChangedEvent(
    override val element : ICoqElement) extends CoqElementEvent(element)
case class CoqFileContentChangedEvent(
    override val element : ICoqFile) extends CoqElementChangedEvent(element)
case class CoqProjectLoadPathChangedEvent(
    override val element : ICoqProject) extends CoqElementChangedEvent(element)

final case class LoadPathEntry(path : IPath, coqdir : Seq[String]) {
  import dk.itu.coqoon.core.CoqoonPreferences

  def asCommand : String =
    (if (CoqoonPreferences.RequireQualification.get) {
      s"""Add LoadPath "${path.toOSString}" """
    } else s"""Add Rec LoadPath "${path.toOSString}" """) +
    (if (coqdir != Nil) {
      " as " + coqdir.mkString(".")
    } else "") + "."

  def asArguments : Seq[String] = {
    val cd = coqdir.mkString(".")
    if (CoqoonPreferences.RequireQualification.get) {
      Seq("-Q", path.toOSString, cd)
    } else Seq("-R", path.toOSString, cd)
  }

  import java.io.File

  def expand() : Seq[(Seq[String], File)] =
    if (CoqoonPreferences.RequireQualification.get) {
      Seq((coqdir, path.toFile))
    } else {
      def _recurse(
          coqdir : Seq[String], f : File) : Seq[(Seq[String], File)] = {
        val l = f.listFiles
        (if (l != null) {
          l.toSeq.filter(_.isDirectory).flatMap(
            f => _recurse(coqdir :+ f.getName, f))
        } else Seq.empty) :+ (coqdir, f)
      }
      val r = _recurse(coqdir, path.toFile)
      CoqoonDebugPreferences.LoadPathExpansion.log(
          s"${this} -> ${r}")
      r
    }
}

final case class IncompleteLoadPathEntry(
    path : Seq[Either[IncompleteLoadPathEntry.Variable, String]],
    coqdir : Seq[String]) {
  import IncompleteLoadPathEntry._
  def complete(p : VariableProvider) :
      Either[IncompleteLoadPathEntry, LoadPathEntry] = {
    val t =
      for (i <- path)
        yield (i match {
          case e @ Left(l) =>
            val v = p.getValue(l)
            if (v != None) {
              Right(v.get)
            } else e
          case e => e
        })
    val v =
      if (t.forall(_.isRight)) {
        Right(LoadPathEntry(
            new Path(t.map(_.right.get).mkString("/")), coqdir))
      } else Left(IncompleteLoadPathEntry(t, coqdir))
    CoqoonDebugPreferences.LoadPathResolution.log(s"${this} -> ${v}")
    v
  }
}
object IncompleteLoadPathEntry {
  case class Variable(
      name : String, description : String)
  trait VariableProvider {
    def getValue(v : Variable) : Option[String]
  }

  private final val _beginExpr = """^\$\(""".r
}

case class LoadPathProvider(identifier : String) {
  def getLoadPath =
    getImplementation.flatMap(_.getLoadPath.right.toOption).getOrElse(Nil)

  def getProvider() =
    LoadPathManager.getInstance.getProviderFor(identifier)
  def getImplementation() =
    getProvider.flatMap(_.getImplementation(identifier))
}

object ProjectLoadPath {
  import ProjectLoadPathProvider._
  def apply(project : IProject) =
    LoadPathProvider(makeIdentifier(project))
  def unapply(p : LoadPathProvider) =
    p.getImplementation.flatMap(TryCast[Implementation]).map(
        a => a.project)
}

object SourceLoadPath {
  import SourceLoadPathProvider._
  def apply(folder : IFolder, output : Option[IFolder] = None) =
    LoadPathProvider(makeIdentifier(folder, output))
  def unapply(p : LoadPathProvider) =
    p.getImplementation.flatMap(TryCast[Implementation]).map(
        a => (a.folder, a.output))
}

object DefaultOutputLoadPath {
  import DefaultOutputLoadPathProvider._
  def apply(folder : IFolder) =
    LoadPathProvider(makeIdentifier(folder))
  def unapply(p : LoadPathProvider) =
    p.getImplementation.flatMap(TryCast[Implementation]).map(
        a => a.folder)
}

object ExternalLoadPath {
  import ExternalLoadPathProvider._
  def apply(fsPath : IPath, dir : Seq[String]) =
    LoadPathProvider(makeIdentifier(fsPath, dir))
  def unapply(p : LoadPathProvider) =
    p.getImplementation.flatMap(TryCast[Implementation]).map(
        a => (a.fsPath, a.dir))
}

object AbstractLoadPath {
  def apply(id : String) = LoadPathProvider(s"abstract:${id}")
  def unapply(p : LoadPathProvider) =
    p.getProvider match {
      case Some(_ : AbstractLoadPathProvider) =>
        Some(p.identifier.drop("abstract:".length))
      case _ => None
    }
}

trait LoadPathImplementationProvider {
  def getName() : String

  /* Returns a best-match load path implementation for the given identifier,
   * if there is one.
   *
   * Note that the resulting implementation is not required to work! There are
   * lots of reasons why a provider might not be able to provide a working
   * implementation for a given identifier; see the Status class below. */
  def getImplementation(id : String) : Option[LoadPathImplementation]

  def getImplementations() : Seq[LoadPathImplementation]
}

trait LoadPathImplementation
    extends IncompleteLoadPathEntry.VariableProvider {
  def getProvider() : LoadPathImplementationProvider
  def getIdentifier() : String

  def getName() : String
  def getAuthor() : String
  def getDescription() : String

  import LoadPathImplementation._
  final def getLoadPath() : Either[Excuse, Seq[LoadPathEntry]] =
    getIncompleteLoadPath match {
      case Right(r) =>
        val c = r.map(_.complete(this))
        if (c.forall(_.isRight)) {
          Right(c.map(_.right.get))
        } else Left(Broken)
      case Left(e) => Left(e)
    }

  def getIncompleteLoadPath() : Either[Excuse, Seq[IncompleteLoadPathEntry]]
}
object LoadPathImplementation {
  sealed abstract class Excuse

  sealed abstract class Available extends Excuse
  /* Installed but not working */
  final case object Broken extends Available
  /* Installed and (potentially) working, but not compatible with the requested
   * version constraint */
  final case object VersionMismatch extends Available

  sealed abstract class NotAvailable extends Excuse
  /* Not installed, but (potentially) installable */
  final case object Retrievable extends NotAvailable
  /* Not installed and not installable */
  final case object NotRetrievable extends NotAvailable
}

class LoadPathManager {
  private var providers : Seq[LoadPathImplementationProvider] = Seq()
  def getProviders() = providers
  def addProvider(provider : LoadPathImplementationProvider) =
    providers :+= provider

  def getProviderFor(
      identifier : String) : Option[LoadPathImplementationProvider] = {
    for (i <- getProviders;
         j <- i.getImplementation(identifier))
      return Some(i)
    None
  }
}
object LoadPathManager {
  private final val instance = new LoadPathManager
  def getInstance() = instance

  getInstance.addProvider(new ProjectLoadPathProvider)
  getInstance.addProvider(new SourceLoadPathProvider)
  getInstance.addProvider(new DefaultOutputLoadPathProvider)
  getInstance.addProvider(new ExternalLoadPathProvider)
  getInstance.addProvider(new AbstractLoadPathProvider)
}

object AbstractLoadPathManager {
  private final val instance = new LoadPathManager
  def getInstance() = instance

  import org.eclipse.core.runtime.{CoreException, RegistryFactory}

  for (ice <- RegistryFactory.getRegistry.getConfigurationElementsFor(
           ManifestIdentifiers.EXTENSION_POINT_LOADPATH)
         if ice.getName == "provider") {
    val ex = try {
      TryCast[LoadPathImplementationProvider](
          ice.createExecutableExtension("provider"))
    } catch {
      case e : CoreException => None
    }
    ex.foreach(getInstance.addProvider)
  }
}

class CoqStandardLibrary extends LoadPathImplementationProvider {
  override def getName = "Coq standard library"

  override def getImplementation(id : String) =
    if (CoqStandardLibrary.ID == id) {
      Some(new CoqStandardLibrary.Implementation(this, id))
    } else None

  override def getImplementations : Seq[LoadPathImplementation] =
    Seq(new CoqStandardLibrary.Implementation(this))
}
object CoqStandardLibrary {
  final val ID = "dk.itu.sdg.kopitiam/lp/coq/8.4"

  private class Implementation(provider : LoadPathImplementationProvider,
      id : String = ID) extends LoadPathImplementation {
    override def getProvider = provider

    override def getIdentifier = id
    override def getName = "Coq standard library"
    override def getAuthor = "Coq development team <coqdev@inria.fr>"
    override def getDescription = "The standard library of Coq."

    import LoadPathImplementation._
    import IncompleteLoadPathEntry.Variable
    override def getIncompleteLoadPath =
      if (id == ID) {
        CoqProgram.run(Seq("-where")).readAll match {
          case (0, _) =>
            Right(Seq(
                IncompleteLoadPathEntry(
                    Seq(Left(CoqLocation), Right("/theories")),
                    Seq("Coq")),
                IncompleteLoadPathEntry(
                    Seq(Left(CoqLocation), Right("/plugins")),
                    Seq("Coq")),
                IncompleteLoadPathEntry(
                    Seq(Left(CoqLocation), Right("/user-contrib")),
                    Nil)))
          case _ => Left(Broken)
        }
      } else Left(VersionMismatch)

    override def getValue(v : Variable) =
      if (v == CoqLocation) {
        CoqProgram.run(Seq("-where")).readAll match {
          case (0, path) => Some(path.trim)
          case _ => None
        }
      } else None
  }

  final val CoqLocation = IncompleteLoadPathEntry.Variable(
      "COQ_LOCATION", "Path to the Coq standard library")
}

trait ICoqProject extends ICoqElement with IParent {
  override def getParent : Option[ICoqModel]
  override def getCorrespondingResource : Option[IProject]

  def getLoadPath() : Seq[LoadPathEntry]

  def getLoadPathProviders() : Seq[LoadPathProvider]
  def setLoadPathProviders(
      lp : Seq[LoadPathProvider], monitor : IProgressMonitor)

  def getLocalOverrides() : Map[IPath, IPath]
  def setLocalOverrides(overrides : Map[IPath, IPath])

  def getDefaultOutputLocation : Option[IFolder]

  def getPackageFragmentRoot(folder : IPath) : ICoqPackageFragmentRoot
  def getPackageFragmentRoots : Seq[ICoqPackageFragmentRoot]
  def hasPackageFragmentRoots : Boolean = (!getPackageFragmentRoots.isEmpty)

  override def getChildren : Seq[ICoqPackageFragmentRoot]
}
object ICoqProject {
  def isCoqNature(a : String) = (ManifestIdentifiers.NATURE_COQ == a)
  def isCoqBuilder(a : ICommand) =
    (ManifestIdentifiers.BUILDER_COQ == a.getBuilderName)

  def newDescription(ws : IWorkspace, name : String) : IProjectDescription =
      configureDescription(ws.newProjectDescription(name))

  def newDescription(proj : IProject) : IProjectDescription =
      newDescription(proj.getWorkspace, proj.getName)

  def configureDescription(d : IProjectDescription) :
      IProjectDescription = {
    val bs = d.getBuildSpec
    if (!bs.exists(isCoqBuilder))
      d.setBuildSpec(bs :+ makeBuilderCommand(d))
    val ns = d.getNatureIds
    if (!ns.exists(isCoqNature))
      d.setNatureIds(ns :+ ManifestIdentifiers.NATURE_COQ)
    d
  }

  def deconfigureDescription(d : IProjectDescription) :
      IProjectDescription = {
    d.setBuildSpec(d.getBuildSpec.filterNot(isCoqBuilder))
    d.setNatureIds(d.getNatureIds.filterNot(isCoqNature))
    d
  }

  def makeBuilderCommand(d : IProjectDescription) = {
    val c = d.newCommand()
    c.setBuilderName(ManifestIdentifiers.BUILDER_COQ)
    c
  }
}

trait ICoqPackageFragmentRoot extends ICoqElement with IParent {
  override def getCorrespondingResource : Option[IFolder]
  override def getParent : Option[ICoqProject]

  def getPackageFragment(folder : IPath) : ICoqPackageFragment
  def getPackageFragments : Seq[ICoqPackageFragment]
  def hasPackageFragments : Boolean = (!getPackageFragments.isEmpty)

  override def getChildren : Seq[ICoqPackageFragment]
}

trait ICoqPackageFragment extends ICoqElement with IParent {
  override def getCorrespondingResource : Option[IFolder]
  override def getParent : Option[ICoqPackageFragmentRoot]

  def getCoqdir() : Option[Seq[String]]

  def getVernacFile(file : IPath) : ICoqVernacFile
  def getVernacFiles : Seq[ICoqVernacFile]
  def hasVernacFiles : Boolean = (!getVernacFiles.isEmpty)

  def getObjectFile(file : IPath) : ICoqObjectFile
  def getObjectFiles : Seq[ICoqObjectFile]
  def hasObjectFiles : Boolean = (!getObjectFiles.isEmpty)

  def hasCoqFiles : Boolean = (hasVernacFiles || hasObjectFiles)

  def getNonCoqFiles : Seq[IFile]
  def hasNonCoqFiles : Boolean = (!getNonCoqFiles.isEmpty)

  override def getChildren : Seq[ICoqFile]
}

trait ICoqFile extends ICoqElement {
  override def getCorrespondingResource : Option[IFile]
  override def getParent : Option[ICoqPackageFragment]
}

import java.io.InputStream

trait ICoqVernacFile extends ICoqFile with IParent {
  override def getChildren() : Seq[ICoqScriptElement]

  def detach() : IDetachedCoqVernacFile
}
object ICoqVernacFile {
  import org.eclipse.core.runtime.Platform
  final def CONTENT_TYPE = Platform.getContentTypeManager.getContentType(
      ManifestIdentifiers.CONTENT_TYPE_COQFILE)
}

trait IDetachedCoqVernacFile extends ICoqVernacFile {
  def commit(monitor : IProgressMonitor)

  def getContents() : String
  def setContents(contents : String)
}
object IDetachedCoqVernacFile {
  def createDummy() : IDetachedCoqVernacFile = new DetachedCoqVernacFileImpl(
      new CoqVernacFileImpl(None, new CoqPackageFragmentImpl(
          None, new CoqPackageFragmentRootImpl(
              None, new CoqProjectImpl(None,
                  new CoqModelImpl(None))))))
}

sealed trait ICoqScriptElement extends ICoqElement {
  def getText() : String
  def getLength() : Int
  def getOffset() : Int
}

trait ICoqScriptSentence extends ICoqScriptElement {
  def isSynthetic() : Boolean
}

trait ICoqLtacSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getIdentifier() = Classifier.LtacSentence.unapply(getText).get._1
  def getBody() = Classifier.LtacSentence.unapply(getText).get._2
}

trait ICoqFixpointSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getKeyword() = Classifier.FixpointSentence.unapply(getText).get._1
  def getIdentifier() = Classifier.FixpointSentence.unapply(getText).get._2
  def getBody() = Classifier.FixpointSentence.unapply(getText).get._3
}

trait ICoqInductiveSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getKeyword() = Classifier.InductiveSentence.unapply(getText).get._1
  def getIdentifier() = Classifier.InductiveSentence.unapply(getText).get._2
  def getBody() = Classifier.InductiveSentence.unapply(getText).get._3
}

trait ICoqDefinitionSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getKeyword() = Classifier.DefinitionSentence.unapply(getText).get._1
  def getIdentifier() = Classifier.DefinitionSentence.unapply(getText).get._2
  def getBinders() = Classifier.DefinitionSentence.unapply(getText).get._3
  def getBody() = Classifier.DefinitionSentence.unapply(getText).get._4
}

trait ICoqLoadSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getIdent() = Classifier.LoadSentence.unapply(getText).get
}

trait ICoqRequireSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getKind() = Classifier.RequireSentence.unapply(getText).get._1
  def getIdent() = Classifier.RequireSentence.unapply(getText).get._2

  def getQualid() : Seq[String] = {
    val ident = getIdent
    if (ident(0) == '"') {
      Seq(ident.substring(1).split("\"", 2)(0))
    } else ident.split("\\s+")
  }
}

trait ICoqAssertionSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getKeyword() = Classifier.AssertionSentence.unapply(getText).get._1
  def getIdentifier() = Classifier.AssertionSentence.unapply(getText).get._2
  def getBody() = Classifier.AssertionSentence.unapply(getText).get._3
}

trait ICoqModuleStartSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getIdentifier() = Classifier.ModuleStartSentence.unapply(getText).get
}

trait ICoqSectionStartSentence extends ICoqScriptSentence {
  import dk.itu.coqoon.core.coqtop.CoqSentence.Classifier
  def getIdentifier() = Classifier.SectionStartSentence.unapply(getText).get
}

trait ICoqScriptGroup extends ICoqScriptElement with IParent {
  override def getText = getChildren.map(_.getText).mkString
  override def getLength = getChildren.foldLeft(0)((a, b) => a + b.getLength)
  override def getOffset = getChildren.head.getOffset

  def getDeterminingSentence() : ICoqScriptSentence =
    getChildren.head.asInstanceOf[ICoqScriptSentence]

  override def getChildren() : Seq[ICoqScriptElement]
}

trait ICoqObjectFile extends ICoqFile {
  def getVernacFile : Option[ICoqVernacFile]
}
object ICoqObjectFile {
  import org.eclipse.core.runtime.Platform
  final def CONTENT_TYPE = Platform.getContentTypeManager.getContentType(
      ManifestIdentifiers.CONTENT_TYPE_COQOBJECTFILE)
}
