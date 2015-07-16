package cromwell.server

import akka.actor.ActorSystem
import cromwell.engine.db.DataAccess
import cromwell.engine.workflow.WorkflowManagerActor

trait WorkflowManagerSystem {
  val systemName = "cromwell-system"
  implicit val actorSystem = ActorSystem(systemName)
  lazy val workflowManagerActor = actorSystem.actorOf(WorkflowManagerActor.props(dataAccess))

  // Lazily created as the primary consumer is the workflowManagerActor.
  private lazy val dataAccess: DataAccess = DataAccess()

  /**
   * Should be called after the system is no longer in use.
   *
   * @return Non-blocking future that will eventually shut down this instance or return an error.
   */
  def shutdown() = dataAccess.shutdown()
}