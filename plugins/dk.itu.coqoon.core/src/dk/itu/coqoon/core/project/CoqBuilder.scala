/* CoqBuilder.scala
 * Coq project configuration managers and the Coq project builder
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

package dk.itu.coqoon.core.project

import dk.itu.coqoon.core.ManifestIdentifiers
import dk.itu.coqoon.core.model._
import dk.itu.coqoon.core.coqtop.CoqProgram
import dk.itu.coqoon.core.coqtop.CoqSentence
import dk.itu.coqoon.core.utilities.{
  TryCast, JobRunner, Substring, CacheSlot, TotalReader}

import org.eclipse.core.resources.IncrementalProjectBuilder
import org.eclipse.core.resources.{IResourceDelta, IResourceDeltaVisitor}
import org.eclipse.core.runtime.{Path, IPath, SubMonitor, IProgressMonitor}
import org.eclipse.core.resources.{IFile, IFolder, IMarker, IProject,
  IResource, IContainer, IWorkspace}
import org.eclipse.core.resources.IProjectNature

class CoqBuilder extends IncrementalProjectBuilder {
  import java.util.{Map => JMap}
  import CoqBuilder._
  import DependencyTracker._

  override protected def getRule(
      type_ : Int, args : JMap[String, String]) = getProject

  private val coqProject = CacheSlot[ICoqProject] {
    ICoqModel.toCoqProject(getProject)
  }

  private var deps : Option[DependencyTracker] = None

  private def partBuild(
      args : Map[String, String], monitor : SubMonitor) : Array[IProject] = {
    if (deps == None)
      return fullBuild(args, monitor)

    var changedFiles = Set[IFile]()

    val delta = getDelta(getProject())
    delta.accept(new IResourceDeltaVisitor {
      override def visit(d : IResourceDelta) : Boolean = {
        TryCast[IFile](d.getResource).flatMap(extensionFilter("v")).foreach(
            f => changedFiles += f)
        true
      }
    })

    buildFiles(changedFiles, args, monitor)
  }

  private def sourceToObject(s : IPath) =
    CoqBuilder.sourceToObject(coqProject.get)(s)
  private def objectToSource(o : IPath) =
    CoqBuilder.objectToSource(coqProject.get)(o)

  private def makePathRelative(f : IPath) =
    CoqBuilder.makePathRelative(getProject.getLocation, f)
  private def makePathRelativeFile(f : IPath) =
    makePathRelative(f).map(getProject.getFile)

  private var completeLoadPath : Seq[(Seq[String], java.io.File)] = Seq()

  private def buildFiles(files : Set[IFile],
      args : Map[String, String], monitor : SubMonitor) : Array[IProject] = {
    if (!CoqProgram("coqtop").check) {
      createResourceErrorMarker(getProject, "Can't find the Coq compiler")
      return Array()
    }
    val dt = deps.get

    /* Delete any objects in the output folders that don't have a corresponding
     * source file */
    traverse[IFile](getProject,
        a => TryCast[IFile](a).flatMap(extensionFilter("vo")).filter(
            f => objectToSource(f.getLocation).size == 0),
        a => a.delete(IResource.NONE, null))

    /* Recalculate the dependencies for all of the files that have changed (if
     * those files are actually buildable in this project) */
    for (i <- files;
         j <- sourceToObject(i.getLocation)) {
      if (i.exists) {
        dt.setDependencies(j, generateDeps(i))
      } else dt.clearDependencies(j)
      dt.unresolveDependenciesUpon(i.getLocation, j)
    }

    /* Pre-create all of the possible output directories so that the complete
     * load path is actually complete */
    for ((path, _) <- dt.getDependencies;
         file <- makePathRelativeFile(path))
      new FolderCreationRunner(file).run(null)
    completeLoadPath = coqProject.get.getLoadPath.flatMap(_.expand)

    getProject.deleteMarkers(
        ManifestIdentifiers.MARKER_PROBLEM, true, IResource.DEPTH_INFINITE)

    def isUpToDate(path : IPath) : Boolean = {
      val f = path.toFile
      if (f.exists) {
        val lm = f.lastModified
        dt.getDependencies(path).flatMap(_._3).forall(
            d => isUpToDate(d) && d.toFile.lastModified < lm)
      } else false
    }

    def canBuild(path : IPath) =
      dt.getDependencies(path).flatMap(_._3).forall(isUpToDate)
    def mustBuild(path : IPath) = {
      val lm = path.toFile.lastModified
      dt.getDependencies(path).flatMap(_._3).exists(_.toFile.lastModified > lm)
    }

    val taskMonitor = new Object

    class BuildTask(val out : IPath) extends Thread {
      import BuildTask._
      import org.eclipse.core.runtime.{Status, IStatus, CoreException}

      private var result_ : Result = Waiting
      def getResult() = taskMonitor synchronized { result_ }
      def setResult(r : Result) = taskMonitor synchronized {
        result_ = r
        taskMonitor.notifyAll
      }

      override def run() = setResult(try {
        objectToSource(out) match {
          case in :: Nil =>
            try {
              val inF = makePathRelativeFile(in)
              val vernac = inF.flatMap(coqProject.get.getModel.toCoqElement)
              (inF, vernac) match {
                case (Some(inF), Some(vernac : ICoqVernacFile)) =>
                  val runner = new CoqCompilerRunner(inF,
                      vernac.getParent.get.getCoqdir.get)
                  runner.setTicker(
                    Some(() => !isInterrupted && !monitor.isCanceled))
                  CompilerDone(runner.run(null))
                case f =>
                  println(":-( " + f)
                  Error("Couldn't retrieve source file handle for " + out)
              }
            }
          case Nil => Error("Not enough source files for " + out)
          case _ => Error("Too many source files for " + out)
        }
      } catch {
        case e : CoreException => Error(e.getStatus.getMessage.trim)
      })
    }
    object BuildTask {
      sealed abstract class Result
      case object Waiting extends Result
      case class CompilerDone(val r : CoqCompilerResult) extends Result
      case class Error(val s : String) extends Result
    }

    monitor.beginTask(
        "Building " + getProject.getName, dt.getDependencies().size)

    var completed = Set[IPath]()
    var candidates : Seq[IPath] = Seq()
    do {
      taskMonitor synchronized {
        import BuildTask._

        val tasks = candidates.map(a => new BuildTask(a))
        tasks.foreach(_.start)

        var last = tasks.count(a => a.getResult == Waiting)
        while (tasks.exists(a => a.getResult == Waiting)) {
          monitor.subTask(
              "Compiling " + tasks.filter(a => a.getResult == Waiting).map(
                  _.out.lastSegment).mkString(", "))
          taskMonitor.wait
          val now = tasks.count(a => a.getResult == Waiting)
          monitor.worked(last - now)
          last = now
        }

        for (i <- tasks) {
          val source = objectToSource(i.out).flatMap(makePathRelativeFile)
          i.getResult match {
            case CompilerDone(s : CoqCompilerSuccess) =>
              makePathRelativeFile(i.out).foreach(p => s.save(p, null))
            case CompilerDone(CoqCompilerFailure(
                _, _, CompilationError(_, line, _, _, message))) =>
              source.foreach(
                createLineErrorMarker(_, line.toInt, message.trim))
            case CompilerDone(CoqCompilerFailure(_, _, GeneralError(text))) =>
              source.foreach(createResourceErrorMarker(_, text.trim))
            case CompilerDone(CoqCompilerFailure(_, _, text)) =>
              source.foreach(createResourceErrorMarker(_, text.trim))
            case Error(text) =>
              source.foreach(createResourceErrorMarker(_, text.trim))
          }
        }
      }
      completed ++= candidates
      dt.resolveDependencies

      candidates = dt.getResolved().filter(
          a => !completed.contains(a)).partition(canBuild) match {
        case (Nil, Nil) => Nil
        case (Nil, cannot) =>
          /* This should only happen if there's a broken dependency on
           * something we can't rebuild (for example, if a file we were once
           * able to find in another project has been deleted). Re-resolve the
           * dependencies so that the error handling code below will have
           * something to show */
          dt.unresolveDependencies(cannot : _*)
          dt.resolveDependencies
          Nil
        case (can, cannot) => can.partition(mustBuild) match {
          case (need, needNot) =>
            need.flatMap(makePathRelativeFile).foreach(
                _.delete(IResource.NONE, null))
            completed ++= needNot
            monitor.setWorkRemaining(need.size + cannot.size)
            need.take(2)
        }
      }
    } while (candidates.size != 0 && !isInterrupted && !monitor.isCanceled)

    /* Create error markers for the files that never became build candidates */
    for (i <- dt.getUnresolved;
         j :: Nil <- Some(objectToSource(i));
         f <- makePathRelativeFile(j);
         dep <- dt.getDependencies(i).filter(_._3 == None).map(_._1)) {
      val errorMessage = s"Cannot find library ${dep} in loadpath"
      depSources.get(f).flatMap(_.get(dep)) match {
        case Some(l) =>
          val leadingWhitespace = l.getText.takeWhile(_.isWhitespace).size
          createRegionErrorMarker(f, errorMessage,
              (l.getOffset + leadingWhitespace,
               l.getOffset + l.getLength))
        case None =>
          createResourceErrorMarker(f, errorMessage)
      }
    }

    /* Copy the external build script into the project, if it's not there
     * already (or if we're sure that it's outdated) */
    CoqBuildScript.perhapsInstall(getProject)

    /* Remove any unused output directories */
    cleanProject(coqProject.get)

    coqProject.get.getLoadPathProviders.collect {
      case ProjectLoadPath(p) => p
    }.toArray
  }

  private def fullBuild(
      args : Map[String, String], monitor : SubMonitor) : Array[IProject] = {
    val dt = new DependencyTracker
    deps = Some(dt)

    traverse[IFile](getProject,
        a => TryCast[IFile](a).flatMap(extensionFilter("v")),
        a => sourceToObject(a.getLocation).foreach(
            b => dt.setDependencies(b, generateDeps(a))))
    buildFiles(Set(), args, monitor)
  }

  override protected def clean(monitor : IProgressMonitor) = {
    def deleteObjects(f : IFolder) = if (f.exists)
      traverse[IFile](f,
          a => TryCast[IFile](a).flatMap(extensionFilter("vo")),
          a => a.delete(IResource.NONE, monitor))
    deps = None
    getProject.deleteMarkers(
        ManifestIdentifiers.MARKER_PROBLEM, true, IResource.DEPTH_INFINITE)
    for (i <- coqProject.get.getLoadPathProviders) i match {
      case SourceLoadPath(src, Some(bin)) => deleteObjects(bin)
      case DefaultOutputLoadPath(bin) => deleteObjects(bin)
      case _ =>
    }
  }

  override protected def build(kind : Int, args_ : JMap[String, String],
      monitor_ : IProgressMonitor) : Array[IProject] = {
    /* Check that our project dependencies are in order */
    val description = getProject.getDescription
    val descriptionDependencies = description.getReferencedProjects.toSet
    val currentDependencies = coqProject.get.getLoadPathProviders.collect {
      case ProjectLoadPath(p) => p
    }.toSet
    if (descriptionDependencies != currentDependencies) {
      description.setReferencedProjects(currentDependencies.toArray)
      getProject.setDescription(description, IResource.KEEP_HISTORY, monitor_)

      needRebuild()
      rememberLastBuiltState()

      return Array()
    }

    /* Make sure that the output directories are marked as derived (XXX: when,
     * if ever, should the derived flag be cleared?) */
    for (i <- coqProject.get.getLoadPathProviders) i match {
      case DefaultOutputLoadPath(bin) =>
        if (!bin.exists()) {
          bin.create(IResource.FORCE | IResource.DERIVED, true, null)
        } else bin.setDerived(true, null)
      case SourceLoadPath(_, Some(bin)) =>
        if (!bin.exists()) {
          bin.create(IResource.FORCE | IResource.DERIVED, true, null)
        } else bin.setDerived(true, null)
      case _ =>
    }

    getProject.deleteMarkers(
        ManifestIdentifiers.MARKER_PROBLEM, true, IResource.DEPTH_ZERO)
    val monitor = SubMonitor.convert(monitor_, "Building", 1)
    val args = scala.collection.JavaConversions.mapAsScalaMap(args_).toMap
    try {
      val delta = getDelta(getProject())
      kind match {
        case IncrementalProjectBuilder.AUTO_BUILD if delta != null =>
          partBuild(args, monitor.newChild(1, SubMonitor.SUPPRESS_NONE))
        case IncrementalProjectBuilder.INCREMENTAL_BUILD if delta != null =>
          partBuild(args, monitor.newChild(1, SubMonitor.SUPPRESS_NONE))
        case _ =>
          fullBuild(args, monitor.newChild(1, SubMonitor.SUPPRESS_NONE))
      }
    } finally {
      monitor.done
    }
  }

  private def resolveLoad(t : String) : Option[IPath] = {
    val dt = deps.get
    for ((_, location) <- completeLoadPath) {
      val p = new Path(location.getAbsolutePath).
          append(t).addFileExtension("v")
      val f = p.toFile
      if (f.exists || deps.get.hasDependencies(p))
        return Some(p)
    }
    return None
  }

  private def resolveRequire(t : String) : Option[IPath] = {
    val (libdir, libname) = {
      val i = t.split('.').toSeq
      (i.init, i.last)
    }

    for ((coqdir, location) <- completeLoadPath) {
      /* If we're looking for "Utilities.Foo.Bar", and we're in the "Utilities"
       * folder, then drop "Utilities" (this is approximately what Coq does) */
      val adjusted =
        if (libdir.startsWith(coqdir)) {
          libdir.drop(coqdir.length)
        } else libdir
      val p = new Path(location.getAbsolutePath).append(
          adjusted.mkString("/")).append(libname).addFileExtension("vo")
      val f = p.toFile
      if (f.exists || deps.get.hasDependencies(p))
        return Some(p)
    }
    None
  }

  private var depSources = Map[IFile, Map[String, ICoqScriptSentence]]()
  private def generateDeps(file : IFile) : Seq[Dependency] = {
    var deps = Seq.newBuilder[Dependency]
    deps +=
        ("(self)", (_ : String) => Some(file.getLocation), Option.empty[IPath])
    var sources = Map[String, ICoqScriptSentence]()
    ICoqModel.getInstance.toCoqElement(file).flatMap(
        TryCast[ICoqVernacFile]).foreach(_.accept(_ match {
      case l : ICoqLoadSentence =>
        deps += (l.getIdent(), resolveLoad(_), Option.empty[IPath])
        sources += (l.getIdent() -> l)
        false
      case r : ICoqRequireSentence =>
        for (f <- r.getQualid) {
          deps += (f, resolveRequire(_), Option.empty[IPath])
          sources += (f -> r)
        }
        false
      case e : IParent => true
      case _ => false
    }))
    depSources += (file -> sources)
    deps.result
  }

  override def toString = "(CoqBuilder for " + getProject + ")"
}
private object CoqBuilder {
  def createRegionErrorMarker(
      r : IResource, s : String, region : (Int, Int)) = {
    import scala.collection.JavaConversions._
    Option(r).filter(_.exists).foreach(
        _.createMarker(ManifestIdentifiers.MARKER_PROBLEM).setAttributes(Map(
            (IMarker.MESSAGE, s),
            (IMarker.SEVERITY, IMarker.SEVERITY_ERROR),
            (IMarker.LOCATION, s"offset ${region._1}"),
            (IMarker.CHAR_START, region._1),
            (IMarker.CHAR_END, region._2))))
  }

  def createResourceErrorMarker(r : IResource, s : String) = {
    import scala.collection.JavaConversions._
    Option(r).filter(_.exists).foreach(
        _.createMarker(ManifestIdentifiers.MARKER_PROBLEM).setAttributes(Map(
            (IMarker.MESSAGE, s),
            (IMarker.SEVERITY, IMarker.SEVERITY_ERROR))))
  }

  def createLineErrorMarker(f : IFile, line : Int, s : String) = {
    import scala.collection.JavaConversions._
    Option(f).filter(_.exists).foreach(
        _.createMarker(ManifestIdentifiers.MARKER_PROBLEM).setAttributes(Map(
            (IMarker.MESSAGE, s),
            (IMarker.LOCATION, "line " + line),
            (IMarker.LINE_NUMBER, line),
            (IMarker.SEVERITY, IMarker.SEVERITY_ERROR))))
  }
  def sourceToObject(project : ICoqProject)(location : IPath) : Option[IPath] = {
    for (i <- project.getLoadPathProviders) i match {
      case SourceLoadPath(src, bin)
          if src.getLocation.isPrefixOf(location) =>
        val base = location.removeFirstSegments(
            src.getLocation.segmentCount).removeFileExtension
        val output = bin.getOrElse(
            project.getDefaultOutputLocation.get).getLocation
        return Some(output.append(base).addFileExtension("vo"))
      case _ =>
    }
    None
  }
  def objectToSourceRaw(project : ICoqProject)(location : IPath) : Seq[IPath] = {
    var candidates : Seq[IPath] = Seq()
    for (i <- project.getLoadPathProviders) i match {
      case SourceLoadPath(src, bin_)
          if bin_.getOrElse(project.getDefaultOutputLocation.get).
              getLocation.isPrefixOf(location) =>
        val bin = bin_.getOrElse(project.getDefaultOutputLocation.get)
        val base = location.removeFirstSegments(
            bin.getLocation.segmentCount).removeFileExtension
        candidates :+= src.getLocation.append(base).addFileExtension("v")
      case _ =>
    }
    candidates
  }
  def objectToSource(project : ICoqProject)(location : IPath) : Seq[IPath] = {
    val handle = project.getCorrespondingResource.get
    for (i <- objectToSourceRaw(project)(location);
         j <- makePathRelative(handle.getLocation, i);
         k <- Option(handle.findMember(j)))
      yield i
  }
  def makePathRelative(base : IPath, path : IPath) : Option[IPath] =
    if (base.isPrefixOf(path)) {
      Some(path.setDevice(null).removeFirstSegments(base.segmentCount))
    } else None

  private val GeneralError = """(?ms)Error: (.*)$""".r.unanchored
  private val CompilationError =
    ("""(?s)File "(.*)", line (\d+), characters (\d+)-(\d+):""" +
     """\s+Error:\s+(.*)$""").r.unanchored

  def cleanProject(project : ICoqProject) : Unit =
    for (i <- project.getLoadPathProviders) i match {
      case SourceLoadPath(_, Some(output)) => cleanHierarchy(output, true)
      case DefaultOutputLoadPath(output) => cleanHierarchy(output, true)
      case _ =>
    }

  def cleanHierarchy(
      dir : IContainer, exempt : Boolean) : Unit = if (dir.exists) {
    for (i <- dir.members;
         j <- TryCast[IContainer](i))
      cleanHierarchy(j, false)
    if (dir.members().length == 0 && !exempt)
      dir.delete(IResource.NONE, null)
  }

  def traverse[A <: IResource](folder : IContainer,
      filter : IResource => Option[A], f : A => Unit) : Unit = {
    for (i <- folder.members(IContainer.INCLUDE_HIDDEN)) {
      filter(i).map(f)
      TryCast[IContainer](i).foreach(traverse(_, filter, f))
    }
  }

  def extensionFilter[A <: IResource](ext : String)(r : A) : Option[A] =
    Option(r).filter(_.getFileExtension == ext)

  def derivedFilter[A <: IResource](der : Boolean)(r : A) : Option[A] =
    Option(r).filter(_.isDerived == der)
}

class FolderCreationRunner(a : IResource) extends JobRunner[Unit] {
  private def create(a : IFolder) : Unit = {
    if (a.exists)
      return
    TryCast[IFolder](a.getParent).foreach(create)
    a.create(IResource.NONE, true, null)
  }

  override def doOperation(monitor : SubMonitor) : Unit =
    TryCast[IFolder](a.getParent).foreach(create)
}

class CoqNature extends IProjectNature {
  import org.eclipse.core.resources.ICommand

  private var project : IProject = null

  override def setProject(project : IProject) = {
    this.project = project
  }

  override def getProject = project

  override def configure = {
    project.setDescription(
        ICoqProject.configureDescription(project.getDescription), null)
  }

  override def deconfigure = {
    project.setDescription(
        ICoqProject.deconfigureDescription(project.getDescription), null)
  }
}

private object CoqBuildScript {
  final val currentVersion = 0
  final val Version = """^_configure_coqoon_version = \d+$""".r
  def perhapsInstall(project : IProject) : Boolean = {
    import dk.itu.coqoon.core.Activator
    import org.eclipse.core.runtime.FileLocator
    val bsHandle = project.getFile("configure.coqoon.py")
    var copyScript : Option[Boolean] = None
    if (!bsHandle.exists) {
      copyScript = Some(true)
    } else {
      import java.io.{BufferedReader, InputStreamReader}
      val r = new BufferedReader(new InputStreamReader(bsHandle.getContents))
      try {
        var line : Option[String] = None
        do {
          line.foreach(line => {
            line match {
              case Version(version_) =>
                val version = Integer.parseInt(version_)
                copyScript = Some(version < currentVersion)
              case _ =>
            }
          })
          line = Option(r.readLine)
        } while (copyScript == None && line != None)
      } finally r.close
    }
    if (copyScript == Some(true)) {
      val s = FileLocator.find(Activator.getDefault.getBundle,
          new Path("lib/configure.coqoon.py"), null).openStream
      if (bsHandle.exists) {
        bsHandle.setContents(s, 0, null)
      } else bsHandle.create(s, 0, null)
    }
    return copyScript.getOrElse(false)
  }
}
