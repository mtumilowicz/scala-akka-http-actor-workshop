package app.user

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import app.domain.user.UserId
import app.gateway.user.in.{NewUserApiInput, ReplaceUserApiInput}
import app.gateway.user.out.{UserApiOutput, UsersApiOutput}
import app.infrastructure.config.UserConfig
import app.infrastructure.http.JsonFormats._
import app.infrastructure.http.user.UserRouteConfig
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

class UserRouteSpec extends AnyWordSpec with Matchers with ScalaFutures with ScalatestRouteTest {

  lazy val testKit = ActorTestKit()

  implicit def typedSystem = testKit.system

  implicit val routeTestTimeout = RouteTestTimeout(Duration(5, TimeUnit.SECONDS))
  val route = prepareRoute()

  def prepareRoute(): Route = {
    val userActor = testKit.spawn(UserConfig.inMemoryActor().behavior())
    UserRouteConfig.config(userActor).route
  }

  override def createActorSystem(): akka.actor.ActorSystem =
    testKit.system.classicSystem

  "UserRoutes" should {
    "return no users if no present" in {
      //      when
      val request = HttpRequest(uri = "/users")

      //      then
      request ~> route ~> check {
        status should ===(StatusCodes.OK)
        entityAs[UsersApiOutput] should be(UsersApiOutput(Seq()))
      }
    }

    "return users if they are present" in {
      //      given
      createUser()

      //      when
      val request = HttpRequest(uri = "/users")

      //      then
      request ~> route ~> check {
        status should ===(StatusCodes.OK)
        entityAs[UsersApiOutput] should not be UsersApiOutput(Seq())
      }
    }

    "create user" in {
      //      given
      val user = NewUserApiInput("Kapi", 42)
      val userEntity = Marshal(user).to[MessageEntity].futureValue

      //      when
      val request = Post("/users").withEntity(userEntity)

      //      then
      request ~> route ~> check {
        status should ===(StatusCodes.Created)

        val output = entityAs[UserApiOutput]
        output.id should not be (null)
        output.name should be("Kapi")
        output.budget should be(42)

        header("location").map(_.value()) should ===(Some(s"http://localhost:8080/users/${output.id}"))
      }
    }

    "get existing user by id" in {
      //      given
      val id = createUser().id

      //        when
      val get = Get(uri = "/users/" + id)

      //        then
      get ~> route ~> check {
        status should ===(StatusCodes.OK)
        val outputOfGet = entityAs[UserApiOutput]
        outputOfGet.id should be(id)
        outputOfGet.name should be("Kapi")
        outputOfGet.budget should be(42)
      }
    }

    "get not existing user by id" in {
      //        when
      val get = Get(uri = "/users/not-present")

      //        then
      get ~> route ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    "remove existing user by id" in {
      //      given
      val id = createUser().id

      //        when
      val delete = Delete(uri = "/users/" + id)

      //        then
      delete ~> route ~> check {
        status should ===(StatusCodes.OK)
        entityAs[UserId].raw should be(id)
      }

      // and
      val get = Get(uri = "/users/" + id)
      get ~> route ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    "remove not existing user by id" in {
      //        when
      val delete = Delete(uri = "/users/not-present")

      //        then
      delete ~> route ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    "update existing user" in {
      //      given
      val id = createUser().id
      val userPut = ReplaceUserApiInput("Kapi2", 123)
      val userEntity = Marshal(userPut).to[MessageEntity].futureValue

      //        when
      val requestPut = Put(uri = "/users/" + id).withEntity(userEntity)

      //        then
      requestPut ~> route ~> check {
        status should ===(StatusCodes.OK)

        val outputPut = entityAs[UserApiOutput]
        outputPut.id should be(id)
        outputPut.name should be("Kapi2")
        outputPut.budget should be(123)
      }

      // and
      val get = Get(uri = "/users/" + id)
      get ~> route ~> check {
        status should ===(StatusCodes.OK)

        val outputPut = entityAs[UserApiOutput]
        outputPut.id should be(id)
        outputPut.name should be("Kapi2")
        outputPut.budget should be(123)
      }
    }

    "update not existing user" in {
      //      given
      val userPut = ReplaceUserApiInput("Kapi2", 123)
      val userEntity = Marshal(userPut).to[MessageEntity].futureValue

      //        when
      val requestPut = Put(uri = "/users/not-present").withEntity(userEntity)

      //        then
      requestPut ~> route ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    def createUser(): UserApiOutput = {
      val user = NewUserApiInput("Kapi", 42)
      val userEntity = Marshal(user).to[MessageEntity].futureValue

      val request = Post("/users").withEntity(userEntity)

      request ~> route ~> check {
        entityAs[UserApiOutput]
      }
    }
  }
}