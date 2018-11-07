package sp.modelSupport

import akka.actor._
import sp.devicehandler._
import sp.domain.Logic._
import sp.domain._
import sp.models.{APIModel, APIModelMaker}
import sp.service.MessageBussSupport
import sp.vdtesting.APIVDTracker
import scala.concurrent.duration._
import sp.virtualdevice._

import akka.stream._
import akka.stream.scaladsl._
import akka.NotUsed

import sp.virtualdevice.APISPVD._

object MiniModelService {
  def props = Props(classOf[MiniModelService])
}

class MiniModelService extends Actor with MessageBussSupport {
  import context.dispatcher

  val models = Map(
    "URTest" -> new sp.unification.urdemo.Demo(context.system),
//    "NewExtendedDummy" -> sp.unification.NewExtended()
  )




  subscribe(APIModel.topicResponse)
  subscribe(APIModelMaker.topicResponse)
  subscribe(APIVDTracker.topicRequest)

  def launchVDAbilities(ids : List[IDAble]): Unit= {

  }

  def createModel(name: String, modelID: ID): Unit = {
    //val modelID = ID.makeID("0d80d1d6-48cd-48ec-bfb1-d69714ef35be").get // hardcoded model id so we do not get a new model every time

    val model = models(name)

    val idables = model.getIDAbles()
    val attributes = SPAttributes("isa" -> "VD")
    val newModel = sp.models.APIModelMaker.CreateModel(name, attributes, id = modelID)
    val virtualDevice = Struct(name, makeStructNodes(idables), attributes)
    val items = APIModel.PutItems(virtualDevice :: idables, SPAttributes("info" -> "initial items"))

    context.system.scheduler.scheduleOnce(0.1 seconds) {
      publish(
        APIModelMaker.topicRequest,
        SPMessage.makeJson(SPHeader(from = "MiniModelService", to = APIModelMaker.service), newModel)
      )
    }

    context.system.scheduler.scheduleOnce(1 seconds) {
      publish(
        APIModel.topicRequest,
        SPMessage.makeJson(SPHeader(from = "MiniModelService", to = newModel.id.toString), items)
      )
    }

    val resources = model.makeResources(context.system)
    val initState = model.getInitialState ++ resources.foldLeft(State.empty){case (s,r) => s++r.initialState}

    // start model
    context.system.scheduler.scheduleOnce(5 seconds) {
      val vdrunner = APISPVD.SPVDRunner(
        model.operations,
        initState,
        Struct("statevars"), // TODO
        AbilityRunnerTransitions.abilityTransitionSystem)

      val spvd = APISPVD.SPVD(ID.newID, model.getIDAbles, resources, vdrunner)

      import akka.cluster.pubsub._
      val mediator = DistributedPubSub(context.system).mediator
      import DistributedPubSubMediator.{ Put, Send, Subscribe, Publish }

      mediator ! Publish(APIVirtualDevice.topicRequest, spvd)
    }

  }

  def receive: Receive = {
    case s : String =>
      for { // unpack message
        message <- SPMessage.fromJson(s)
        header <- message.getHeaderAs[SPHeader] if  header.to == APIVDTracker.service
        body <- message.getBodyAs[APIVDTracker.Request]
      } yield {
        val responseHeader = header.swapToAndFrom()
        sendAnswer(SPMessage.makeJson(responseHeader, APISP.SPACK())) // acknowledge message received

        body match { // Check if the body is any of the following classes, and execute program
          case APIVDTracker.createModel(modelName, modelID) =>
            createModel(modelName, modelID)

          case APIVDTracker.launchVDAbilities(idAbles) =>
            launchVDAbilities(idAbles)

          case APIVDTracker.launchOpRunner(idAbles) =>


          case APIVDTracker.getModelsInfo(_) =>
            sendAnswer(SPMessage.makeJson(responseHeader, APIVDTracker.sendModelsInfo(models.keys.toList)))

          case _ => Unit
        }
        sendAnswer(SPMessage.makeJson(responseHeader, APISP.SPDone()))
      }
  }
  def sendAnswer(mess: String): Unit = publish(APIVDTracker.topicResponse, mess)

  implicit class EnhancedSPAttributes(attributes: SPAttributes) {
    def getWithDefault[A](key: String, default: => A)(implicit reads: JSReads[A]): A = attributes.getAs[A](key).getOrElse(default)
  }
}
