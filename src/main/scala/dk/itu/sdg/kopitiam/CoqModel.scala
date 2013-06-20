/* CoqModel.scala
 * An abstraction layer between Eclipse resources and Coq concepts
 * Copyright © 2013 Alexander Faithfull
 * 
 * You may use, modify, copy and/or redistribute this code subject to the terms
 * of either the license of Kopitiam or the Apache License, version 2.0 */

package dk.itu.sdg.kopitiam

import org.eclipse.core.commands.operations.IUndoableOperation
import org.eclipse.core.runtime.{IPath, Path, IProgressMonitor}
import org.eclipse.core.runtime.jobs.{ISchedulingRule, MultiRule}
import org.eclipse.core.resources.{
  IResource, IProjectDescription, ICommand,
  IFile, IFolder, IProject, IWorkspace, IWorkspaceRoot, IWorkspaceRunnable}

trait IParent {
  def getChildren : Seq[ICoqElement]
  def hasChildren : Boolean = (!getChildren.isEmpty)
}

trait ICoqElement {
  def exists : Boolean
  def getAncestor[A](klass : Class[A]) : Option[A] = {
    var a = getParent
    while (a != None) {
      val a_ = a.orNull
      if (klass.isInstance(a))
        return Some(klass.cast(a))
      a = a_.getParent
    }
    None
  }
  def getParent : Option[ICoqElement with IParent]
  def getElementType : Class[_ <: ICoqElement]
  def getCorrespondingResource : Option[_ <: IResource]
  def getContainingResource : Option[_ <: IResource] = {
    getCorrespondingResource match {
      case Some(a) => Some(a)
      case None => getParent match {
        case Some(a) => a.getContainingResource
        case None => None
      }
    }
  }
  def getModel : ICoqModel = getAncestor(classOf[ICoqModel]).get
}

object PathUtilities {
  object Implicits {
    implicit def stringToPath(s : String) : IPath = new Path(s)
    implicit def stringSeqToPath(s : Seq[_ <: String]) : IPath =
      new Path(s.mkString("/"))
  }
}

private abstract class CoqElementImpl[
    A <: IResource, B <: ICoqElement with IParent](
    private val res : A, private val parent : B) extends ICoqElement {
  override def getParent = Option(parent)
  override def exists = getCorrespondingResource.exists { _.exists }
  override def getCorrespondingResource = Option(res)
}

private abstract class ParentImpl[
    A <: IResource, B <: ICoqElement with IParent](
    private val res : A, private val parent : B)
    extends CoqElementImpl(res, parent) with IParent

trait ICoqModel extends ICoqElement with IParent {
  override def getElementType = classOf[ICoqModel]
  
  override def getParent = None
  override def getCorrespondingResource : Option[IWorkspaceRoot]
  
  def getProject(name : String) : ICoqProject
  def getProjects : Seq[_ <: ICoqProject] = List()
  def hasProjects : Boolean = (!getProjects.isEmpty)
}
object ICoqModel {
  def create(root : IWorkspaceRoot) : ICoqModel = new CoqModelImpl(root)
}

private class CoqModelImpl(
    private val res : IWorkspaceRoot)
    extends ParentImpl(res, null) with ICoqModel {
  import CoqModelImpl._
  
  override def getProject(name : String) =
    new CoqProjectImpl(res.getProject(name), this)
  override def getProjects = res.getProjects.filter(hasNature).map(
      a => new CoqProjectImpl(a, this))
  
  override def getChildren = getProjects
}
private object CoqModelImpl {
  def hasNature(a : IProject) =
    (a.getDescription.getNatureIds.exists(ICoqProject.isCoqNature))
}

trait ICoqLoadPath {
  def path : String
  def coqdir : Option[String]
  
  def _tmp_makecmd = "Add Rec LoadPath \"" + path + "\"" + (coqdir match {
      case Some(dir) => " as " + dir
      case None => ""
    }) + "."
  
  /* def install(coqtop : CoqTopIdeSlave_v20120703) = {
    coqtop.interp(true, false, _tmp_makecmd)
  } */
}

case class ProjectSourceLoadPath(
    val folder : IFolder, val output : Option[ProjectBinaryLoadPath] = None)
    extends ICoqLoadPath {
  def path = folder.getLocation.toOSString
  def coqdir = None
}

case class ProjectBinaryLoadPath(val folder : IFolder) extends ICoqLoadPath {
  def path = folder.getLocation.toOSString
  def coqdir = None
}

case class ExternalLoadPath(val fsPath : IPath, val dir : String)
    extends ICoqLoadPath {
  def path = fsPath.toOSString
  def coqdir = Option(dir)
}

trait ICoqProject extends ICoqElement with IParent {
  override def getElementType = classOf[ICoqProject]
  
  override def getParent : Option[ICoqModel]
  override def getCorrespondingResource : Option[IProject]
  
  def getLoadPath : Seq[_ <: ICoqLoadPath]
  def setLoadPath(lp : Seq[_ <: ICoqLoadPath], monitor : IProgressMonitor)
  
  def getDefaultOutputLocation : IPath
  
  def getPackageFragmentRoot(folder : IPath) : ICoqPackageFragmentRoot
  def getPackageFragmentRoots : Seq[_ <: ICoqPackageFragmentRoot]
  def hasPackageFragmentRoots : Boolean = (!getPackageFragmentRoots.isEmpty)
  
  def getCreateOperation : IUndoableOperation
  def getDeleteOperation(deleteContent : Boolean) : IUndoableOperation
}
object ICoqProject {
  def isCoqNature(a : String) = (CoqNature.NATURE_ID == a)
  def isCoqBuilder(a : ICommand) = (CoqBuilder.BUILDER_ID == a.getBuilderName)
    
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
      d.setNatureIds(ns :+ CoqNature.NATURE_ID)
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
    c.setBuilderName(CoqBuilder.BUILDER_ID)
    c
  }
}

import org.eclipse.ui.ide.undo.DeleteResourcesOperation

private class CoqProjectImpl(
    private val res : IProject,
    private val parent : ICoqModel)
    extends ParentImpl(res, parent) with ICoqProject {
  import org.eclipse.ui.ide.undo.CreateProjectOperation
  
  override def getCreateOperation = {
    new CreateProjectOperation(
        ICoqProject.newDescription(res), "New Coq project")
  }
  
  override def getDeleteOperation(deleteContent : Boolean) = {
    new DeleteResourcesOperation(
        Array(res), "Delete Coq project", deleteContent)
  }
  
  override def getLoadPath : Seq[_ <: ICoqLoadPath] = List(
      new ProjectSourceLoadPath(res.getFolder("src")),
      new ProjectBinaryLoadPath(res.getFolder("bin")))
  override def setLoadPath(
      lp : Seq[_ <: ICoqLoadPath], monitor : IProgressMonitor) = ()
  
  override def getDefaultOutputLocation = res.getFolder("bin").getFullPath
  
  override def getPackageFragmentRoot(folder : IPath) =
    new CoqPackageFragmentRootImpl(res.getFolder(folder), this)
  override def getPackageFragmentRoots = getLoadPath.collect {
    case ProjectSourceLoadPath(folder, output)
        if (res == folder.getProject) =>
      new CoqPackageFragmentRootImpl(folder, this)
    case ProjectBinaryLoadPath(folder)
        if (res == folder.getProject) =>
      new CoqPackageFragmentRootImpl(folder, this)
  }
  
  override def getChildren = getPackageFragmentRoots
}

trait ICoqPackageFragmentRoot extends ICoqElement with IParent {
  override def getElementType = classOf[ICoqPackageFragmentRoot]
  
  def getPackageFragment(folder : IPath) : ICoqPackageFragment
  def getPackageFragments : Seq[_ <: ICoqPackageFragment]
  def hasPackageFragments : Boolean = (!getPackageFragments.isEmpty)
  
  def getCreateOperation : IUndoableOperation
  def getDeleteOperation : IUndoableOperation
}

private class CoqPackageFragmentRootImpl(
    private val res : IFolder,
    private val parent : ICoqProject)
    extends ParentImpl(res, parent) with ICoqPackageFragmentRoot {
  import CoqPackageFragmentRootImpl._
  
  import org.eclipse.ui.ide.undo.CreateFolderOperation
  
  override def getCreateOperation = {
    new CreateFolderOperation(res, null, "New package fragment root")
  }
  
  override def getDeleteOperation = {
    new DeleteResourcesOperation(
        Array(res), "Delete package fragment root", false)
  }
  
  private def gpfRecurse(res : IFolder) : List[ICoqPackageFragment] = {
    var results = List[ICoqPackageFragment]()
    if (!res.members.collect(CoqPackageFragmentImpl.fileCollector).isEmpty)
      results = results :+ new CoqPackageFragmentImpl(res, this)
    for (i <- res.members.collect(folderCollector))
      results = results ++ gpfRecurse(i)
    results
  }
  
  override def getPackageFragment(folder : IPath) =
    new CoqPackageFragmentImpl(res.getFolder(folder), this)
  override def getPackageFragments = gpfRecurse(res)
  
  override def getChildren = getPackageFragments
}
private object CoqPackageFragmentRootImpl {
  def folderCollector : PartialFunction[AnyRef, IFolder] = {
    case a : IFolder => a
  }
}

trait ICoqPackageFragment extends ICoqElement with IParent {
  override def getElementType = classOf[ICoqPackageFragment]
  
  def getVernacFile(file : IPath) : ICoqVernacFile
  def getVernacFiles : Seq[_ <: ICoqVernacFile]
  def hasVernacFiles : Boolean = (!getVernacFiles.isEmpty)
  
  def getObjectFile(file : IPath) : ICoqObjectFile
  def getObjectFiles : Seq[_ <: ICoqObjectFile]
  def hasObjectFiles : Boolean = (!getObjectFiles.isEmpty)
  
  def hasCoqFiles : Boolean = (hasVernacFiles || hasObjectFiles)
  
  def getNonCoqFiles : Seq[_ <: IFile]
  def hasNonCoqFiles : Boolean = (!getNonCoqFiles.isEmpty)
  
  def getCreateOperation : IUndoableOperation
  def getDeleteOperation : IUndoableOperation
}

private class CoqPackageFragmentImpl(
    private val res : IFolder,
    private val parent : ICoqPackageFragmentRoot)
    extends ParentImpl(res, parent) with ICoqPackageFragment {
  import CoqPackageFragmentImpl._
  
  import org.eclipse.ui.ide.undo.CreateFolderOperation
  
  override def getCreateOperation =
    new CreateFolderOperation(res, null, "New package fragment")
  
  override def getDeleteOperation =
    new DeleteResourcesOperation(Array(res), "Delete package fragment", false)
  
  override def getVernacFile(file : IPath) =
    new CoqVernacFileImpl(res.getFile(file), this)
  override def getVernacFiles = {
    res.members.collect(fileCollector).filter(vernacFilter).map(
        new CoqVernacFileImpl(_, this))
  }
  
  override def getObjectFile(file : IPath) =
    new CoqObjectFileImpl(res.getFile(file), this)
  override def getObjectFiles = {
    res.members.collect(fileCollector).filter(objectFilter).map(
        new CoqObjectFileImpl(_, this))
  }
  
  override def getNonCoqFiles = {
    res.members.collect(fileCollector).filterNot(vernacFilter).
        filterNot(objectFilter)
  }
  
  override def getChildren = getVernacFiles ++ getObjectFiles
}
private object CoqPackageFragmentImpl {
  val fileCollector : PartialFunction[AnyRef, IFile] = {
    case a : IFile => a
  }
  
  val vernacFilter = (a : IFile) => {
    a.getContentDescription.getContentType.isKindOf(
        ICoqVernacFile.CONTENT_TYPE)
  }
  
  val objectFilter = (a : IFile) => {
    a.getContentDescription.getContentType.isKindOf(
        ICoqObjectFile.CONTENT_TYPE)
  }
}

trait ICoqFile extends ICoqElement

import java.io.InputStream

private object EmptyInputStream extends InputStream {
  override def read = -1
}

trait ICoqVernacFile extends ICoqFile {
  override def getElementType = classOf[ICoqVernacFile]
  
  def setContents(is : InputStream, monitor : IProgressMonitor)
  def getCreateOperation : IUndoableOperation
  def getDeleteOperation : IUndoableOperation
}
object ICoqVernacFile {
  import org.eclipse.core.runtime.Platform
  final def CONTENT_TYPE =
      Platform.getContentTypeManager.getContentType(CONTENT_TYPE_ID)
  final val CONTENT_TYPE_ID = "dk.itu.sdg.kopitiam.CoqFile"
}

private class CoqVernacFileImpl(
    private val res : IFile,
    private val parent : ICoqPackageFragment)
    extends CoqElementImpl(res, parent) with ICoqVernacFile {
  if (!res.getName.endsWith(".v"))
    throw new IllegalArgumentException(res.getName)
  
  import org.eclipse.ui.ide.undo.CreateFileOperation
  
  override def setContents(is : InputStream, monitor : IProgressMonitor) =
    res.setContents(is, IResource.NONE, monitor)
  
  override def getCreateOperation =
    new CreateFileOperation(res, null, EmptyInputStream, "New Vernac file")
  
  override def getDeleteOperation =
    new DeleteResourcesOperation(Array(res), "Delete Vernac file", false)
}

trait ICoqObjectFile extends ICoqFile {
  override def getElementType = classOf[ICoqObjectFile]
  
  def getVernacFile : Option[ICoqVernacFile]
}
object ICoqObjectFile {
  import org.eclipse.core.runtime.Platform
  final def CONTENT_TYPE =
    Platform.getContentTypeManager.getContentType(CONTENT_TYPE_ID)
  final val CONTENT_TYPE_ID = "dk.itu.sdg.kopitiam.CoqObjectFile"
}

private class CoqObjectFileImpl(
    private val res : IFile,
    private val parent : ICoqPackageFragment)
    extends CoqElementImpl(res, parent) with ICoqObjectFile {
  if (!res.getName.endsWith(".vo"))
    throw new IllegalArgumentException(res.getName)
  
  override def getVernacFile = None
}