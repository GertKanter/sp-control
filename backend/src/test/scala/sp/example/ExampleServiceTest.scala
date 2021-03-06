package sp.example

import akka.actor._
import akka.testkit._
import com.typesafe.config._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import sp.domain.Logic._
import sp.domain._

import scala.concurrent.duration._
import scala.util.Try


/**
  * Created by kristofer on 2016-05-04.
  */
class OPMakerTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("SP", ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |akka.persistence.snapshot-store.local.dir = "target/snapshotstest/"
      |akka.loglevel = "OFF"
      |akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      |akka.remote.netty.tcp.hostname="127.0.0.1"
      |akka.remote.netty.tcp.port=2555
      |akka.cluster.seed-nodes=["akka.tcp://SP@127.0.0.1:2555"]
    """.stripMargin)))


  import akka.cluster.pubsub.DistributedPubSubMediator._
  val mediator = akka.cluster.pubsub.DistributedPubSub(system).mediator
  val id = ID.newID


  override def beforeAll: Unit = {
    val exampleS = system.actorOf(ExampleService.props, "exampleService")
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val logic = new ExampleServiceLogic {}


  "The example service logic" must {
    "include a new pie" in {
      val res = logic.commands(APIExampleService.StartTheTicker(id))
      val map = logic.thePies

      assert(map.size == 1 && map.contains(id) && map(id) == Map("first"->10, "second"-> 30, "third" -> 60))
    }

    "remove a pie" in {
      logic.commands(APIExampleService.StartTheTicker(id))
      assert(logic.thePies.size == 1)

      val res = logic.commands(APIExampleService.StopTheTicker(id))
      assert(logic.thePies.isEmpty)

    }

    "removing non existing pie" in {

      val res = logic.commands(APIExampleService.StopTheTicker(id))
      assert(logic.thePies.isEmpty)

    }

    "include a predef pie" in {
      val xs = Map("foo"->10, "bar"-> 30)
      val res = logic.commands(APIExampleService.SetTheTicker(id, xs))
      val map = logic.thePies

      assert(map.size == 1 && map.contains(id) && map(id) == xs)
    }

    "multiple pies" in {
      val xs = Map("foo"->10, "bar"-> 30)
      val res = logic.commands(APIExampleService.SetTheTicker(id, xs))
      logic.commands(APIExampleService.StartTheTicker(ID.newID))
      val map = logic.thePies

      assert(map.size == 2)
    }

    "upd pie" in {
      val xs = Map("foo"->10, "bar"-> 30)
      val res = logic.updPie(xs)
      println(res)
      println(logic.updPie(res))
    }

    "testing schema" in {
      println("LLLLLLLL")
      println(sp.example.ExampleServiceInfo.attributes)
    }

  }



  val header = SPHeader(to = sp.example.APIExampleService.service, from = "testing")

  "The example service actor" must {
    val p = TestProbe()
    val e = TestProbe()
    mediator ! Subscribe("answers", p.ref)
    "start ticking on new pie" in {
      val body = APIExampleService.StartTheTicker(id)
      val mess = SPMessage.makeJson(header, body)


      println("a mess")
      println(body)
      println(SPValue(body).toJson)

      mediator ! Publish("services", mess)

      p.fishForMessage(10 seconds){
        case mess @ _ if {println(s"answers probe got: $mess"); false} => false

        case x: String if SPMessage.fromJson(x).nonEmpty =>
          val mess = SPMessage.fromJson(x).get
          val b = mess.getBodyAs[APIExampleService.Response]
          b.nonEmpty
      }
    }


    mediator ! Subscribe("spevents", e.ref)

//    "answer to status request" in {
//      val body = APISP.StatusRequest()
//      val mess = SPMessage.makeJson(header, body)
//      mediator ! Publish("spevents", mess)
//
//      e.fishForMessage(10 seconds){
//        case mess @ _ if {println(s"spevents probe got: $mess"); false} => false
//
//        case x: String if SPMessage.fromJson(x).isSuccess =>
//          val mess = SPMessage.fromJson(x).get
//          val b = mess.getBodyAs[APISP.StatusResponse]
//          b.isSuccess
//      }
//    }

  }


}
