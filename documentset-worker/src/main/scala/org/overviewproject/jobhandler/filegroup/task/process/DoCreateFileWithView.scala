package org.overviewproject.jobhandler.filegroup.task.process

import org.overviewproject.models.File
import org.overviewproject.jobhandler.filegroup.task.step.TaskStep
import org.overviewproject.jobhandler.filegroup.task.step.CreateFileWithView
import org.overviewproject.models.GroupedFileUpload

object DoCreateFileWithView {

  def apply(documentSetId: Long) = new StepGenerator[GroupedFileUpload, File] {
    
    override def generate(uploadedFile: GroupedFileUpload): TaskStep = 
      CreateFileWithView(documentSetId, uploadedFile, nextStepFn)
  }
}