package mesosphere.marathon.api.v2

import java.util.Collections

import mesosphere.marathon._
import mesosphere.marathon.api.{ TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{ Group, GroupManager, Timestamp }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }
import org.mockito.Mockito._
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class TasksResourceTest extends MarathonSpec with GivenWhenThen with Matchers with Mockito {

  test("killTasks") {
    Given("two apps and 1 task each")
    val app1 = "/my/app-1".toRootPath
    val app2 = "/my/app-2".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val taskId2 = Task.Id.forApp(app2).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)

    val task1 = MarathonTestHelper.stagedTask(taskId1)
    val task2 = MarathonTestHelper.runningTask(taskId2)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.forTasks(task1, task2)
    taskKiller.kill(any, any) returns Future.successful(Iterable.empty[Task])

    When("we ask to kill both tasks")
    val response = taskResource.killTasks(scale = false, force = false, body = bodyBytes, auth.request, auth.response)

    Then("The response should be OK")
    response.getStatus shouldEqual 200

    And("The response is the list of killed tasks")
    response.getEntity shouldEqual """{"tasks":[]}"""

    And("Both tasks should be requested to be killed")
    verify(taskKiller).kill(eq(app1), any)
    verify(taskKiller).kill(eq(app2), any)

    And("nothing else should be called on the TaskKiller")
    noMoreInteractions(taskKiller)
  }

  test("killTasks with force") {
    Given("two apps and 1 task each")
    val app1 = "/my/app-1".toRootPath
    val app2 = "/my/app-2".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val taskId2 = Task.Id.forApp(app2).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)
    val deploymentPlan = new DeploymentPlan("plan", Group.empty, Group.empty, Seq.empty[DeploymentStep], Timestamp.zero)

    val task1 = MarathonTestHelper.runningTask(taskId1)
    val task2 = MarathonTestHelper.stagedTask(taskId2)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.forTasks(task1, task2)
    taskKiller.killAndScale(any, any) returns Future.successful(deploymentPlan)

    When("we ask to kill both tasks")
    val response = taskResource.killTasks(scale = true, force = true, body = bodyBytes, auth.request, auth.response)

    Then("The response should be OK")
    response.getStatus shouldEqual 200

    And("Should create a deployment")
    response.getEntity shouldEqual """{"version":"1970-01-01T00:00:00.000Z","deploymentId":"plan"}"""

    And("app1 and app2 is killed with force")
    verify(taskKiller).killAndScale(eq(Map(app1 -> Iterable(task1), app2 -> Iterable(task2))), eq(true))

    And("nothing else should be called on the TaskKiller")
    noMoreInteractions(taskKiller)
  }

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response
    val taskId1 = Task.Id.forApp("/my/app".toRootPath).idString
    val taskId2 = Task.Id.forApp("/my/app".toRootPath).idString
    val taskId3 = Task.Id.forApp("/my/app".toRootPath).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

    When(s"the index as json is fetched")
    val running = taskResource.indexJson("status", Collections.emptyList(), req, resp)
    Then("we receive a NotAuthenticated response")
    running.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one index as txt is fetched")
    val cancel = taskResource.indexTxt(req, resp)
    Then("we receive a NotAuthenticated response")
    cancel.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(scale = true, force = false, body, req, resp)
    Then("we receive a NotAuthenticated response")
    killTasks.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response
    val taskId1 = Task.Id.forApp("/my/app".toRootPath).idString
    val taskId2 = Task.Id.forApp("/my/app".toRootPath).idString
    val taskId3 = Task.Id.forApp("/my/app".toRootPath).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(scale = true, force = false, body, req, resp)
    Then("we receive a not authorized response")
    killTasks.getStatus should be(auth.UnauthorizedStatus)
  }

  test("killTasks fails for invalid taskId") {
    Given("a valid and an invalid taskId")
    val app1 = "/my/app-1".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val body = s"""{"ids": ["$taskId1", "invalidTaskId"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)

    When("we ask to kill those two tasks")
    val ex = intercept[BadRequestException] {
      taskResource.killTasks(scale = false, force = false, body = bodyBytes, auth.request, auth.response)
    }

    Then("An exception should be thrown that points to the invalid taskId")
    ex.getMessage should include ("invalidTaskId")

    And("the taskKiller should not be called at all")
    verifyNoMoreInteractions(taskKiller)
  }

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var healthCheckManager: HealthCheckManager = _
  var taskResource: TasksResource = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    taskKiller = mock[TaskKiller]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    healthCheckManager = mock[HealthCheckManager]
    taskResource = new TasksResource(
      service,
      taskTracker,
      taskKiller,
      config,
      groupManager,
      healthCheckManager,
      auth.auth,
      auth.auth
    )
  }

}
