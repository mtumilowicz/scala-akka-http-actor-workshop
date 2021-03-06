package app.gateway

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import app.domain.cash.NonNegativeAmount
import app.domain.user.{UserBalance, UserId}
import app.gateway.JsonFormats._
import app.gateway.in.{BuyerIdApiInput, NewVenueApiInput}
import app.gateway.out.VenueApiOutput
import app.infrastructure.config.{PurchaseConfig, UserBalanceConfig, VenueConfig}
import org.scalatest.EitherValues._
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers._

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

class VenueRoutesSpec extends AnyFeatureSpec with GivenWhenThen with ScalaFutures with ScalatestRouteTest {

  lazy val testKit = ActorTestKit()

  implicit def typedSystem = testKit.system

  implicit val routeTestTimeout = RouteTestTimeout(Duration(5, TimeUnit.SECONDS))
  lazy val routes = new VenueRoutes(
    venueService,
    purchaseService
  ).routes
  val venueService = VenueConfig.inMemoryService()
  val userBalanceService = UserBalanceConfig.inMemoryService()
  val purchaseService = PurchaseConfig.service(userBalanceService, venueService)

  override def createActorSystem(): akka.actor.ActorSystem =
    testKit.system.classicSystem

  Feature("venues - CRUD") {
    Scenario("return no venues if no present") {
      When("create request")
      val request = HttpRequest(uri = "/venues")

      Then("list is empty")
      request ~> routes ~> check {
        status should ===(StatusCodes.OK)
        entityAs[List[VenueApiOutput]] should be(List())
      }
    }

    Scenario("return venues if they are present") {
      Given("create random venue")
      createRandomVenue()

      When("create request")
      val request = HttpRequest(uri = "/venues")

      Then("list is not empty")
      request ~> routes ~> check {
        status should ===(StatusCodes.OK)
        entityAs[List[VenueApiOutput]] should not be empty
      }
    }

    Scenario("create venue") {
      Given("prepare input")
      val venueInput = NewVenueApiInput(
        price = 100,
        name = "ABC"
      )
      val id = UUID.randomUUID().toString
      val venueEntity = Marshal(venueInput).to[MessageEntity].futureValue

      When("create  request")
      val request = Put(s"/venues/$id").withEntity(venueEntity)

      Then("return id of created venue")
      request ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val output = entityAs[String]
        output shouldBe id
      }
    }

    Scenario("get existing venue by id") {
      Given("create venue")
      val id = createRandomVenue()

      When("get venue by id")
      val get = Get(uri = "/venues/" + id)

      Then("return venue")
      get ~> routes ~> check {
        status should ===(StatusCodes.OK)
        val outputOfGet = entityAs[VenueApiOutput]
        outputOfGet.id should be(id)
        outputOfGet.name should be("XYZ")
        outputOfGet.price should be(500)
        outputOfGet.owner should be(None)
      }
    }

    Scenario("get not existing venue by id") {
      When("get not existing venue")
      val get = Get(uri = s"/venues/${UUID.randomUUID().toString}")

      Then("not found")
      get ~> routes ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    Scenario("remove existing venue by id") {
      Given("create random venue")
      val id = createRandomVenue()

      When("delete venue")
      val delete = Delete(uri = "/venues/" + id)

      Then("venue deleted")
      delete ~> routes ~> check {
        status should ===(StatusCodes.OK)
        entityAs[String] should be(id)
      }
    }

    Scenario("remove not existing venue by id") {
      When("deleting non existing venue")
      val delete = Delete(uri = s"/venues/${UUID.randomUUID().toString}")

      Then("not found")
      delete ~> routes ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    Scenario("replace existing venue") {
      Given("create random venue")
      val id = createRandomVenue()

      And("prepare replace")
      val venuePut = NewVenueApiInput("DEF", 333)
      val venueEntity = Marshal(venuePut).to[MessageEntity].futureValue

      When("replaced")
      val requestPut = Put(uri = "/venues/" + id).withEntity(venueEntity)

      Then("venue is replaced")
      requestPut ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputPut = entityAs[String]
        outputPut should be(id)
      }

      And("get the replaced venue")
      val requestGet = Get(uri = "/venues/" + id)

      Then("venue has updated fields")
      requestGet ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputGet = entityAs[VenueApiOutput]
        outputGet.name shouldBe "DEF"
        outputGet.price shouldBe 333
      }
    }

    Scenario("insert venue") {
      Given("create venue for insert")
      val venuePut = NewVenueApiInput("AAA", 123)
      val venueEntity = Marshal(venuePut).to[MessageEntity].futureValue
      val id = UUID.randomUUID().toString

      When("inserting venue")
      val requestPut = Put(uri = s"/venues/$id").withEntity(venueEntity)

      Then("insert is successful")
      requestPut ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputPut = entityAs[String]
        outputPut should be(id)
      }

      And("get the inserted venue")
      val requestGet = Get(uri = "/venues/" + id)

      Then("venue has correct fields")
      requestGet ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputGet = entityAs[VenueApiOutput]
        outputGet.id shouldBe id
        outputGet.name shouldBe "AAA"
        outputGet.price shouldBe 123
        outputGet.owner shouldBe None
      }
    }
  }
  Feature("venues - business") {
    Scenario("buying fails when you cannot afford property") {
      Given("prepare players")
      val user1Id = UUID.randomUUID().toString
      userBalanceService.save(UserBalance(UserId(user1Id), NonNegativeAmount(500).value))

      Given("prepare input")
      val trumpTower = createVenue(1000, "Trump Tower")
      val buyerIdInput = BuyerIdApiInput(user1Id)
      val buyerIdEntity = Marshal(buyerIdInput).to[MessageEntity].futureValue

      When("buy venue")
      val requestPost = Post(uri = s"/venues/${trumpTower}/buy").withEntity(buyerIdEntity)

      Then("insufficient funds")
      requestPost ~> routes ~> check {
        status should ===(StatusCodes.BadRequest)

        val outputPost = entityAs[String]
        outputPost should be(s"${user1Id} can't afford Trump Tower")
      }
    }

    Scenario("venue without owner: buying succeeds when you can afford property") {
      Given("prepare players")
      val user1Id = UUID.randomUUID().toString
      userBalanceService.save(UserBalance(UserId(user1Id), NonNegativeAmount(1000).value))

      Given("prepare input")
      val trumpTower = createVenue(1000, "Trump Tower")
      val buyerIdInput = BuyerIdApiInput(user1Id)
      val buyerIdEntity = Marshal(buyerIdInput).to[MessageEntity].futureValue

      When("buying venue")
      val requestPost = Post(uri = s"/venues/${trumpTower}/buy").withEntity(buyerIdEntity)

      Then("success")
      requestPost ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputPost = entityAs[String]
        outputPost should be(s"Trump Tower was bought by ${user1Id} for 1000")
      }

      And("get the bought venue")
      val requestGet = Get(uri = "/venues/" + trumpTower)

      Then("new owner")
      requestGet ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputGet = entityAs[VenueApiOutput]
        outputGet.owner.value shouldBe user1Id
      }
    }

    Scenario("venue with owner: buying succeeds when you can afford property") {
      Given("prepare players")
      val user1Id = UUID.randomUUID().toString
      val user2Id = UUID.randomUUID().toString
      userBalanceService.save(UserBalance(UserId(user1Id), NonNegativeAmount(500).value))
      userBalanceService.save(UserBalance(UserId(user2Id), NonNegativeAmount(1000).value))

      And("setup venue")
      val venueId = createRandomVenue()
      val buyerIdInput = BuyerIdApiInput(user1Id)
      val buyerIdEntity = Marshal(buyerIdInput).to[MessageEntity].futureValue
      val requestPost = Post(uri = s"/venues/$venueId/buy").withEntity(buyerIdEntity)
      requestPost ~> routes ~> check {
        status should ===(StatusCodes.OK)
      }

      When("buying property")
      val nextBuyerIdInput = BuyerIdApiInput(user2Id)
      val nextBuyerIdEntity = Marshal(nextBuyerIdInput).to[MessageEntity].futureValue
      val requestPost2 = Post(uri = s"/venues/$venueId/buy").withEntity(nextBuyerIdEntity)

      Then("success")
      requestPost2 ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputPost = entityAs[String]
        outputPost should be(s"XYZ was bought by $user2Id for 500")
      }

      And("get the bought venue")
      val requestGet = Get(uri = "/venues/" + venueId)

      Then("new owner")
      requestGet ~> routes ~> check {
        status should ===(StatusCodes.OK)

        val outputGet = entityAs[VenueApiOutput]
        outputGet.owner.value shouldBe user2Id
      }
    }
  }

  def createRandomVenue(): String = {
    createVenue(500, "XYZ")
  }

  def createVenue(price: Int, name: String): String = {
    val venueInput = NewVenueApiInput(
      price = price,
      name = name
    )

    val venueEntity = Marshal(venueInput).to[MessageEntity].futureValue

    val request = Put(s"/venues/${UUID.randomUUID()}").withEntity(venueEntity)

    request ~> routes ~> check {
      entityAs[String]
    }
  }
}