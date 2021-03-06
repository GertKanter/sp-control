package sp.modelSupport

import akka.actor._
import sp.abilityhandler.APIAbilityHandler
import sp.devicehandler._
import sp.domain.Logic._
import sp.domain._
import sp.models.{APIModel, APIModelMaker}
import sp.runners._
import sp.service.MessageBussSupport
import sp.vdtesting.APIVDTracker
import scala.concurrent.duration._


object ModelService {
  def props(models: Map[String, ModelDSL]) = Props(classOf[ModelService], models)
}

class ModelService(models: Map[String, ModelDSL]) extends Actor with MessageBussSupport{
  import context.dispatcher

  subscribe(APIModel.topicResponse)
  subscribe(APIModelMaker.topicResponse)
  subscribe(APIVDTracker.topicRequest)

  def launchVDAbilities(ids : List[IDAble]): Unit= {
    // Extract model data from IDAbles
    val operations = ids.collect { case op: Operation => op }
    val things = ids.collect { case thing: Thing => thing }
    val setupRunnerThings = things.filter(_.attributes.keys.contains("runnerID"))
    val exAbilities = operations.flatMap(APIAbilityHandler.operationToAbility)

    val resourceThings = things.filter(_.attributes.keys.contains("stateMap"))
    val resources = resourceThings.map(VD.thingToResource)

    val driverThings = things.filter(_.attributes.keys.contains("driverType"))
    val drivers = driverThings.map(VD.thingToDriver)


    // TODO Why is this unused?
    val runnerSetup = setupRunnerThings.headOption.map(thing => APIOperationRunner.CreateRunner(runnerSetupFor(thing)))

    //Direct launch of the VD and abilities below
    val (virtualDeviceId, abilityId) = (ID.newID, ID.newID)

    publish(APIVirtualDevice.topicRequest,
      SPMessage.makeJson(
        SPHeader(from = "ModelService"),
        APIVirtualDevice.SetUpVD(
          name = "VD",
          id = virtualDeviceId,
          resources, //= resources.map(_.resource),
          drivers, // = resources.map(_.driver),
          attributes = SPAttributes()
        )
      )
    )

    publish(APIAbilityHandler.topicRequest,
      SPMessage.makeJson(
        SPHeader(from = "ModelService"),
        APIAbilityHandler.SetUpAbilityHandler(
          name = "Abilites",
          id = abilityId,
          exAbilities,
          vd = virtualDeviceId
        ))
    )
  }

  def launchOpRunner(h: SPHeader, ids : List[IDAble])= {

    // Extract setup data from IDAbles
    val things = ids.collect { case thing: Thing => thing }
    val setupRunnerThings = things.filter(_.attributes.keys.contains("runnerID"))

    println("CREATING RUNNERS" + setupRunnerThings)


    setupRunnerThings.foreach { thing =>
      println("STARTING RUNNER" + thing.toString)
      val runnerSetup = APIOperationRunner.runnerThingToSetup(thing).copy(runnerID = ID.newID)
      val exSetupRunner = APIOperationRunner.CreateRunner(runnerSetup)

      //println("RUNNER SETUP: " + exSetupRunner.toString)

      publish(
        APIOperationRunner.topicRequest,
        SPMessage.makeJson(
          SPHeader(from = "ModelService", to = APIOperationRunner.service),
          exSetupRunner
        )
      )

      println("RUNNER STARTED")

      publish(APIVDTracker.topicResponse, SPMessage.makeJson(h, APIVDTracker.OpRunnerCreated(runnerSetup.runnerID)))

      println("SENT TO FRONTEND")
    }

  }

  def createModel(name: String, modelID: ID): Unit = {
    //val modelID = ID.makeID("0d80d1d6-48cd-48ec-bfb1-d69714ef35be").get // hardcoded model id so we do not get a new model every time

    val model = models(name)

    val idables = model.buildModel()
    val attributes = SPAttributes("isa" -> "VD")

    val newModel = sp.models.APIModelMaker.CreateModel(name, attributes, id = modelID)

    val virtualDevice = Struct(name, makeStructNodes(idables), attributes)

    val items = APIModel.PutItems(virtualDevice :: idables, SPAttributes("info" -> "initial items"))

    context.system.scheduler.scheduleOnce(0.1 seconds) {
      publish(
        APIModelMaker.topicRequest,
        SPMessage.makeJson(SPHeader(from = "ModelService", to = APIModelMaker.service), newModel)
      )
    }

    context.system.scheduler.scheduleOnce(1 seconds) {
      publish(
        APIModel.topicRequest,
        SPMessage.makeJson(SPHeader(from = "ModelService", to = newModel.id.toString), items)
      )
    }
  }

  def setupToThing(setup : APIOperationRunner.Setup): Thing = {
    Thing(
      name = setup.name,
      id = ID.newID,
      attributes = SPAttributes(
        "name" -> setup.name,
        "runnerID" -> setup.runnerID,
        "ops" -> setup.ops,
        "opAbilityMap" -> setup.opAbilityMap,
        "initialState" -> setup.initialState,
        "variableMap" -> setup.variableMap,
        "abilityParameters" -> setup.abilityParameters.toList
      )
    )
  }

  def runnerSetupFor(thing: Thing): APIOperationRunner.Setup = {
    val attrs = thing.attributes

    val name = attrs.getWithDefault("name", "")
    val runnerID = attrs.getWithDefault("runnerID", ID.newID)
    val ops = attrs.getWithDefault("ops", Set[Operation]())
    val opAbilityMap = attrs.getWithDefault("opAbilityMap", Map[ID, ID]())
    val initialState = attrs.getWithDefault("initialState", Map[ID, SPValue]())
    val variableMap = attrs.getWithDefault("variableMap", Map[ID, ID]())
    val abilityParameters = attrs.getWithDefault("abilityParameters", List[(ID,Set[ID])]()).toMap

    APIOperationRunner.Setup(name, runnerID, ops, opAbilityMap, initialState, variableMap, abilityParameters)
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
            launchOpRunner(responseHeader,idAbles)

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
