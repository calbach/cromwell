package cromwell.backend.wdl

import java.io.File

import better.files.File.OpenOptions
import cats.instances.future._
import cats.syntax.functor._
import cromwell.core.io.AsyncIoFunctions
import cromwell.core.path.{Path, PathFactory}
import wom.expression.IoFunctionSet
import wom.values.WomSingleFile

import scala.concurrent.Future

trait WriteFunctions extends PathFactory with IoFunctionSet with AsyncIoFunctions {

  /**
    * Directory that will be used to write files.
    */
  def writeDirectory: Path

  private lazy val _writeDirectory = writeDirectory.createPermissionedDirectories()

  override def writeFile(path: String, content: String): Future[WomSingleFile] = {
    val file = _writeDirectory / path
    asyncIo.existsAsync(file) flatMap {
      case false => asyncIo.writeAsync(file, content, OpenOptions.default) as { WomSingleFile(file.pathAsString) }
      case true => Future.successful(WomSingleFile(file.pathAsString))
    }
  }

  def qualifyPath(pathFrom: String): String = if (new File(pathFrom).isAbsolute) pathFrom else  System.getProperty("user.dir") + "/" + pathFrom

  override def copyFile(pathFrom: String, targetName: String): Future[WomSingleFile] = {
    val source = buildPath(qualifyPath(pathFrom))
    val destination = _writeDirectory / targetName

    asyncIo.copyAsync(source, destination).as(WomSingleFile(destination.pathAsString))
  }
}
