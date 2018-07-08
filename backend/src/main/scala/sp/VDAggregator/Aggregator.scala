package sp.VDAggregator

import akka.actor.{Actor, ActorLogging, Props}
import sp.SPMessageUtil.BetterSPMessage
import sp.devicehandler.{APIVirtualDevice, VD, APIDeviceDriver => apiDriver}
import sp.abilityhandler.APIAbilityHandler
import sp.VDAggregator.APIVDAggregator._
import sp.domain._
import sp.runners.APIOperationRunner

class AggregatorService extends Actor  with ActorLogging with  sp.service.ServiceSupport{

  val instanceID = ID.newID

  val statusResponse = AggregatorServiceInfo.attributes.copy( instanceID = Some(this.instanceID) )
  triggerServiceRequestComm(statusResponse)

  subscribe(APIVDAggregator.topicRequest)
  subscribe(sp.vdtesting.APIVDTracker.topicRequest)

  subscribe(apiDriver.topicResponse)
  subscribe(APIVirtualDevice.topicResponse)
  subscribe(APIAbilityHandler.topicResponse)
  subscribe(APIOperationRunner.topicResponse)

  val Online = "Online"
  val Offline  = "Offline"
  val Unresponsive  = "Unresponsive"

  var drivers = Map[ID , driverInfo]();                 var driversOld = Map[ID , driverInfo]()
  var resources = List[VD.ResourceWithState]();         var resourcesOld = List[VD.ResourceWithState]()
  var abilities = List[APIAbilityHandler.Ability]();    var abilitiesOld = List[APIAbilityHandler.Ability]()
  var abilityStates = Map[ID, SPValue]();               var abilityStatesOld = Map[ID, SPValue]();
  var runnerStates = Map[ID, Map[ID, SPValue]]();        var runnerStatesOld = Map[ID, Map[ID, SPValue]]();

  var firstTick = true

  def receive = {
    case x: String =>
      for {
        mess <- SPMessage.fromJson(x)
        (h,b) <- mess.oneOf[APIVDAggregator.Request].or[apiDriver.Request].or[apiDriver.Response].or[sp.vdtesting.APIVDTracker.Request].or[APIVirtualDevice.Response].or[APIAbilityHandler.Response].or[APIOperationRunner.Response].get
      } yield {
        val spHeader = h.swapToAndFrom()
        sendAnswer(SPMessage.makeJson(spHeader, APISP.SPACK()))

        b match {
              case APIVDAggregator.GetDrivers=>
                sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheDrivers(drivers.values.toList)))
              case APIVDAggregator.GetResources =>
                publish(APIVirtualDevice.topicRequest, SPMessage.makeJson(spHeader, APIVirtualDevice.GetVD))
                sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheResources(resources))) //Todo: would like the previous mess to complete first..
              case APIVDAggregator.GetAbilities =>
                publish(APIAbilityHandler.topicRequest, SPMessage.makeJson(spHeader, APIAbilityHandler.GetAbilities))
                sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheAbilities(abilities))) //Todo: would like the previous mess to complete first..

              case apiDriver.TheDriver(driver, driverState) => // if a new or already existing driver is received, the map should be updated with the driver, state and active status: Online
                drivers += driver.id -> driverInfo(driver, driverState, Online)
              case apiDriver.DriverStateChange(name,id,state,diff) => // if we receive a state change, update the driver
                if (drivers.get(id).nonEmpty) drivers += id -> drivers(id).copy(driverState = state)
              case apiDriver.DriverTerminated(id) => // if the driver is terminated, its active status is set to Offline // Todo: Sometimes this message is not received, instead the driver will show as unresponsive even tho it is terminated
                drivers += id -> drivers(id).copy(status = Offline)

              case APIVirtualDevice.TheVD(_, _, newResources, _ , _) =>
                resources ++= newResources
              case APIVirtualDevice.StateEvent(_, id, newState, _) => // in case of StateEvent from VD, update the resource-state to the new value
              resources.map { res => if(res.r.id == id) res.copy(state = res.state ++ newState) else res}

              case APIAbilityHandler.Abilities(abs) =>
                abilities = abs
              case APIAbilityHandler.AbilityState(id, state) =>
                abilityStates ++= state

              case APIOperationRunner.StateEvent(runnerID, state, _, _) =>
                runnerStates ++= Map(runnerID -> (runnerStates.get(runnerID).getOrElse(Map()) ++ state))
              case sp.vdtesting.APIVDTracker.ResetGUI =>
                drivers = Map()


              case other =>
        }
        sendAnswer(SPMessage.makeJson(spHeader, APISP.SPACK()))
      }



    case Throttle =>
      val spHeader = SPHeader(from = AggregatorServiceInfo.attributes.service, reqID = instanceID)
      if(!firstTick) {
        if (! theSameDrivers(drivers, driversOld)) { // Check if the maps have the same keys and active drivers..
          driversOld = drivers
          sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheDrivers(drivers.values.toList)))
        }
        if(resources != resourcesOld) {
          resourcesOld = resources
          sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheResources(resources)))
        }
        if(abilities != abilitiesOld) {
          abilitiesOld = abilities
          sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheAbilities(abilities)))
        }
        if(abilityStates != abilityStatesOld) {
          abilityStatesOld = abilityStates
          sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheAbilityStates(abilityStates)))
        }
        if(runnerStates != runnerStatesOld) {
          runnerStatesOld = runnerStates
          sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheRunnerStates(runnerStates)))
        }
      }
      else
        firstTick = false

    case Responsiveness =>
      val spHeader = SPHeader(from = AggregatorServiceInfo.attributes.service, reqID = instanceID)
      if(!firstTick) {
        if (! theSameDrivers(drivers, driversOld, statusCheck = true)) { // Check if the maps have the same keys and active drivers..
          driversOld = drivers
          sendAnswer(SPMessage.makeJson(spHeader, APIVDAggregator.TheDrivers(drivers.values.toList)))
        }
        drivers = drivers.map(d => d._1 -> (d._2.copy(status = if (d._2.status == Online) Unresponsive else d._2.status))) // Set all active drivers status to Unresponsive, (the active drivers should be updated between ticks)
      }
      publish(apiDriver.topicRequest, SPMessage.makeJson(spHeader, apiDriver.GetDriver))

    case other =>
  }
  def sendAnswer(mess: String) = publish(APIVDAggregator.topicResponse, mess)

  def theSameDrivers(M1 : Map[ID , driverInfo], M2 : Map[ID , driverInfo], statusCheck : Boolean =false) : Boolean = {
    if (M1.keySet != M2.keySet) return false // not the same drivers
    if (M1.values.map(_.driverState).toList != M2.values.map(_.driverState).toList) return false // not the same driver state
    if(statusCheck){if (M1.values.map(_.status).toList != M2.values.map(_.status).toList) return false} // not the same driver status
    return true // they are the same
  }

  // A "ticker" that sends a "tick" string to self every 4 second
  import context.dispatcher

  import scala.concurrent.duration._
  val ticker = context.system.scheduler.schedule(200 milliseconds, 200 milliseconds, self, Throttle)
  val responsivenessTicker = context.system.scheduler.schedule(5 seconds, 5 seconds, self, Responsiveness)

}
case object Throttle
case object Responsiveness


object AggregatorService {
  def props = Props(classOf[AggregatorService])
}


object AggregatorServiceInfo {
  import sp.domain.SchemaLogic._
  case class VDAggregatorRequest(request: APIVDAggregator.Request)
  case class VDAggregatorResponse(response: APIVDAggregator.Response)

  lazy val req: com.sksamuel.avro4s.SchemaFor[VDAggregatorRequest] = com.sksamuel.avro4s.SchemaFor[VDAggregatorRequest]
  lazy val resp: com.sksamuel.avro4s.SchemaFor[VDAggregatorResponse] = com.sksamuel.avro4s.SchemaFor[VDAggregatorResponse]

  val apischema = makeMeASchema(
    req(),
    resp()
  )

  val attributes: APISP.StatusResponse = APISP.StatusResponse(
    service = APIVDAggregator.service,
    tags = List(APIVDAggregator.service),
    api = apischema,
    version = 1,
    topicRequest = APIVDAggregator.topicRequest,
    topicResponse = APIVDAggregator.topicResponse,
    attributes = SPAttributes.empty
  )
}

