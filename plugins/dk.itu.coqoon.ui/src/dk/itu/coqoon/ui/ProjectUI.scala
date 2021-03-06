/* ProjectUI.scala
 * User interfaces for Coq project creation and manipulation
 * Copyright © 2013 Alexander Faithfull
 *
 * You may use, copy, modify and/or redistribute this code subject to the terms
 * of either the license of Kopitiam or the Apache License, version 2.0 */

package dk.itu.coqoon.ui

import dk.itu.coqoon.ui.utilities.UIUtils
import dk.itu.coqoon.core.model._
import dk.itu.coqoon.core.utilities.{TryCast, CacheSlot}

import org.eclipse.jface.operation.IRunnableWithProgress
import org.eclipse.core.resources.{IFile, IProject, IResource}
import org.eclipse.core.runtime.IProgressMonitor

import org.eclipse.ui.INewWizard
import org.eclipse.jface.wizard.Wizard

class NewCoqProjectWizard extends Wizard with INewWizard {
  import org.eclipse.ui.dialogs.WizardNewProjectCreationPage

  class NewCoqProjectCreationPage
      extends WizardNewProjectCreationPage("Coq project") {
    setTitle("Coq Project")
    setDescription("Create a new, empty Coq project.")
  }

  import org.eclipse.ui.IWorkbench
  import org.eclipse.jface.viewers.IStructuredSelection
  private var workbench : IWorkbench = null
  private var selection : IStructuredSelection = null
  override def init(w : IWorkbench, s : IStructuredSelection) = {
    workbench = w
    selection = s
  }

  private val creationPage = new NewCoqProjectCreationPage()

  override def addPages = {
    addPage(creationPage)
  }

  class ProjectCreator(private val project : ICoqProject)
      extends IRunnableWithProgress {
    import org.eclipse.core.runtime.SubMonitor
    import org.eclipse.core.resources.{IWorkspace, IWorkspaceRunnable}
    import org.eclipse.ui.ide.undo.{WorkspaceUndoUtil,
      CreateFolderOperation, CreateProjectOperation}

    override def run(monitor_ : IProgressMonitor) = {
      val monitor = SubMonitor.convert(monitor_, 4)

      monitor.beginTask("New Coq project", 4)
      import org.eclipse.core.runtime.Path
      val infoAdapter = WorkspaceUndoUtil.getUIInfoAdapter(getShell)

      project.getCorrespondingResource.foreach(pr => {
        val theories = pr.getFolder("theories")

        new CreateProjectOperation(ICoqProject.newDescription(pr),
            "New Coq project").execute(monitor.newChild(1), infoAdapter)
        new CreateFolderOperation(theories, null,
            "New source folder").execute(monitor.newChild(1), infoAdapter)
        new CreateFolderOperation(pr.getFolder("bin"), null,
            "New output folder").execute(monitor.newChild(1), infoAdapter)

        /* Find the default SourceLoadPath entry, referring to the "src"
         * folder, and replace it with one pointing to "theories" instead */
        val lp = project.getLoadPathProviders
        Option(project.getLoadPathProviders.indexWhere {
          case SourceLoadPath(_, _, _) => true
          case _ => false
        }).filter(_ != -1) foreach {
          case index =>
            project.setLoadPathProviders(
                lp.updated(index, SourceLoadPath(theories, None)),
                monitor.newChild(1))
        }
      })
    }
  }

  def createProject : IProject = {
    val project = creationPage.getProjectHandle()
    if (!project.exists()) {
      getContainer().run(
          true, true, new ProjectCreator(
              ICoqModel.getInstance.getProject(project.getName)))
    }
    return project
  }

  import org.eclipse.ui.PlatformUI

  override def performFinish = {
    val project = createProject
    PlatformUI.getWorkbench().getWorkingSetManager().addToWorkingSets(
        project, creationPage.getSelectedWorkingSets())
    true
  }
}

import org.eclipse.core.resources.IFolder
import org.eclipse.jface.viewers.{Viewer, ViewerFilter}

class NoOutputFoldersFilter extends ViewerFilter {
  override def select(viewer : Viewer,
      parent : AnyRef, element : AnyRef) : Boolean = element match {
    case f : IFolder =>
      for (project <- Option(ICoqModel.toCoqProject(f.getProject));
           i <- project.getLoadPathProviders) i match {
        case DefaultOutputLoadPath(out) if f == out =>
          return false
        case SourceLoadPath(_, out, _) if f == out =>
          return false
        case _ =>
      }
      true
    case _ => true
  }
}

class OnlyFoldersFilter extends ViewerFilter {
  override def select(viewer : Viewer,
      parent : AnyRef, element : AnyRef) : Boolean = element match {
    case f : IFolder => true
    case _ => false
  }
}

class OnlyProjectsFilter extends ViewerFilter {
  override def select(viewer : Viewer,
      parent : AnyRef, element : AnyRef) : Boolean = element match {
    case p : IProject => true
    case _ => false
  }
}

class OmitResourcesFilter(resources : IResource*) extends ViewerFilter {
  override def select(viewer : Viewer, parent : AnyRef, element : AnyRef) =
    !resources.contains(element)
}

class NoHiddenResourcesFilter extends ViewerFilter {
  import org.eclipse.core.filesystem.EFS
  override def select(viewer : Viewer,
      parent : AnyRef, element : AnyRef) : Boolean = element match {
    case r : IResource if r.isHidden || r.getName()(0) == '.' || EFS.getStore(
        r.getLocationURI()).fetchInfo().getAttribute(EFS.ATTRIBUTE_HIDDEN) =>
      false
    case _ => true
  }
}

class MultiFilter extends ViewerFilter {
  private var filters : Seq[ViewerFilter] = Nil

  override def select(viewer : Viewer,
      parent : AnyRef, element : AnyRef) : Boolean = {
    for (f <- filters)
      if (!f.select(viewer, parent, element))
        return false
    true
  }

  override def isFilterProperty(
      element : AnyRef, property : String) : Boolean = {
    for (f <- filters)
      if (f.isFilterProperty(element, property))
        return true
    false
  }

  def setFilters(filters : Seq[ViewerFilter]) = (this.filters = filters)
}
object MultiFilter {
  def apply(filters : ViewerFilter*) : MultiFilter = {
    val f = new MultiFilter
    f.setFilters(filters)
    f
  }
}

import org.eclipse.ui.dialogs.ISelectionStatusValidator

abstract class SelectionValidator extends ISelectionStatusValidator {
  import org.eclipse.core.runtime.{Status, IStatus}

  def check(selection : Object) : Option[String]

  override def validate(selection : Array[Object]) : IStatus = {
    for (i <- selection;
         j <- check(i))
      return new Status(IStatus.ERROR, ManifestIdentifiers.PLUGIN, j)
    Status.OK_STATUS
  }
}
