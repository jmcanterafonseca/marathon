package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.state.{ PathId, Timestamp, AppDefinition }

object LaunchQueueTestHelper {
  val zeroCounts = LaunchQueue.QueuedInstanceInfo(
    runSpec = AppDefinition(PathId("/thisisignored")),
    inProgress = true,
    instancesLeftToLaunch = 0,
    finalInstanceCount = 0,
    unreachableInstances = 0,
    backOffUntil = Timestamp(0),
    startedAt = Timestamp(0)
  )

  def instanceCounts(instancesLeftToLaunch: Int, finalInstanceCount: Int): LaunchQueue.QueuedInstanceInfo = {
    LaunchQueue.QueuedInstanceInfo(
      runSpec = AppDefinition(PathId("/thisisignored")),
      inProgress = true,
      instancesLeftToLaunch = instancesLeftToLaunch,
      finalInstanceCount = finalInstanceCount,
      unreachableInstances = 0,
      backOffUntil = Timestamp(0),
      startedAt = Timestamp(0)
    )
  }
}
