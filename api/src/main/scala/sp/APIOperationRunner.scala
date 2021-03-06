package sp.runners

import sp.domain._
import sp.domain.Logic._

object APIOperationRunner {
  val service = "OperationRunner"
  val topicRequest = "operationRunnerRequest"
  val topicResponse = "operationRunnerResponse"
  sealed trait Request
  sealed trait Response

  case class CreateRunner(setup: Setup) extends Request

  case class SetState(runnerID: ID, state: Map[ID, SPValue]) extends Request
  case class AddOperations(runnerID: ID, ops: Set[Operation], opAbilityMap: Map[ID, ID]) extends Request
  case class RemoveOperations(runnerID: ID, ops: Set[ID]) extends Request
  case class ForceComplete(ability: ID) extends Request
  case class TerminateRunner(runnerID: ID) extends Request
  case class GetState(runnerID: ID) extends Request
  case class GetRunner(runnerID: ID) extends Request
  case object GetRunners extends Request

  /**
    * This command changes the mode of the runner and can pause the execution to allow to execute one op at the time
    * @param runnerID The id of the runner to change mode
    * @param runInAuto Set to true to run in full auto, if false, it is possible to run in steps or run manually
    *                  When changing to pause, the current operations will complete their execution
    * @param disableConditionGroups Conditions that belong to a group defined in the set will not be evaluated.
    *                               The group property is defined in the operation attribute with the key "group"
    */
  case class RunnerControl(runnerID: ID,
                           runInAuto: Boolean = true,
                           disableConditionGroups: Set[SPValue] = Set()
                          ) extends Request

  /**
    * To start operations when not in auto mode and to step backward to previous states
    *
    * @param runnerID The id of the runner to change mode*
    * @param startOperation if not in auto, tries to start the operation if it is enabled
    * @param forceStartOperation if not in auto, will start the operation even if not enabled
    * @param forceCompleteOperation if not in auto, will force complete an operation and run its postcondition
    * @param stepBackward in not in auto, will change the complete runner state to when the last operation started.
    *                     backward is not implemented yet
    */
  case class ManualControl(runnerID: ID,
                           startOperation: Option[ID] = None,
                           forceStartOperation: Boolean = false,
                           forceCompleteOperation: Option[ID] = None,
                           stepBackward: Boolean = false
                          ) extends Request


  case class StateEvent(runnerID: ID, state: Map[ID, SPValue], runInAuto: Boolean, disableConditionGroups: Set[SPValue]) extends Response
  case class Runner(setup: Setup) extends Response
  case class Runners(ids: List[Setup]) extends Response

  /**
    * Defining an operation runner
    * @param name The name of the runner
    * @param runnerID The if of the runner
    * @param ops The operations
    * @param opAbilityMap operation id -> ability id
    * @param initialState The initial state of the runner
    * @param variableMap operationModel variable id -> vd model variable id
    * @param abilityParameters ability id -> list of operation model variable id, must be mapped
    *                          in variableMap
    */
  case class Setup(name: String,
                   runnerID: ID,
                   ops: Set[Operation],
                   opAbilityMap: Map[ID, ID] = Map(),
                   initialState: Map[ID, SPValue],
                   variableMap: Map[ID, ID] = Map(),
                   abilityParameters: Map[ID, Set[ID]] = Map()
                  )

  def runnerSetupToThing(setup : Setup): Thing = {
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

  def runnerThingToSetup(thing : Thing): Setup = {
    val name = thing.attributes.getAs[String]("name").getOrElse("")
    val runnerID = thing.attributes.getAs[ID]("runnerID").getOrElse(ID.newID)
    val ops = thing.attributes.getAs[Set[Operation]]("ops").getOrElse(Set())
    val opAbilityMap = thing.attributes.getAs[Map[ID,ID]]("opAbilityMap").getOrElse(Map())
    val initialState = thing.attributes.getAs[Map[ID,SPValue]]("initialState").getOrElse(Map())
    val variableMap = thing.attributes.getAs[Map[ID,ID]]("variableMap").getOrElse(Map())
    val abilityParameters = thing.attributes.getAs[List[(ID,Set[ID])]]("abilityParameters").getOrElse(List()).toMap
    Setup(name, runnerID,ops,opAbilityMap,initialState,variableMap,abilityParameters)
  }

  object Formats {
    import play.api.libs.json._

    implicit lazy val idSetidReads: JSReads[Map[ID, Set[ID]]] = new JSReads[Map[ID, Set[ID]]] {
      override def reads(json: JsValue): JsResult[Map[ID, Set[ID]]] = {
        json.validate[Map[String, Set[String]]].map(xs => xs.collect{case (k, v) if ID.isID(k)  => ID.makeID(k).get -> v.flatMap(ID.makeID)})
      }
    }
    implicit lazy val idSetidWrites: JSWrites[Map[ID, Set[ID]]] = new OWrites[Map[ID, Set[ID]]] {
      override def writes(xs: Map[ID, Set[ID]]): JsObject = {
        val toFixedMap = xs.map{case (k, v) => k.toString -> SPValue(v)}
        JsObject(toFixedMap)
      }
    }

    implicit lazy val fSetup: JSFormat[Setup] = Json.format[Setup]
    implicit lazy val fCreateRunner: JSFormat[CreateRunner] = Json.format[CreateRunner]
    implicit lazy val fSetState: JSFormat[SetState] = Json.format[SetState]
    implicit lazy val fAddOperations: JSFormat[AddOperations] = Json.format[AddOperations]
    implicit lazy val fRemoveOperations: JSFormat[RemoveOperations] = Json.format[RemoveOperations]
    implicit lazy val fForceComplete: JSFormat[ForceComplete] = Json.format[ForceComplete]
    implicit lazy val fTerminateRunner: JSFormat[TerminateRunner] = Json.format[TerminateRunner]
    implicit lazy val fGetState: JSFormat[GetState] = Json.format[GetState]
    implicit lazy val fRunnerControl: JSFormat[RunnerControl] = Json.format[RunnerControl]
    implicit lazy val fManualControl: JSFormat[ManualControl] = Json.format[ManualControl]
    implicit lazy val fGetRunner: JSFormat[GetRunner] = Json.format[GetRunner]
    implicit lazy val fGetRunners : JSFormat[GetRunners.type] = deriveCaseObject[GetRunners.type ]
    implicit lazy val fStateEvent: JSFormat[StateEvent] = Json.format[StateEvent]
    implicit lazy val fRunner: JSFormat[Runner] = Json.format[Runner]
    implicit lazy val fRunners: JSFormat[Runners] = Json.format[Runners]
    def fOperationRunnerRequest: JSFormat[Request] = Json.format[Request]
    def fOperationRunnerResponse: JSFormat[Response] = Json.format[Response]
  }

  object Request {
    implicit lazy val fOperationRunnerRequest: JSFormat[Request] = Formats.fOperationRunnerRequest
  }

  object Response {
    implicit lazy val fOperationRunnerResponse: JSFormat[Response] = Formats.fOperationRunnerResponse
  }
}
