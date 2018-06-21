package spgui.communication

import sp.domain.SPMessage
import spgui.SPMessageUtil.BetterSPMessage
import spgui.availablemodelscircuit._

object VDCommunication extends CommunicationAPI.Communicator[AbilityAction] {
  import sp.devicehandler.{APIVirtualDevice => API}
  val responseTopic: String = API.topicResponse

  def onReceiveMessage(message: SPMessage): Unit = {
    val response = message.as[API.Response]
    val state = VDCircuit.readModelState

    for ((header, body) <- response) {
      case API.StateEvent(resource, id, stateData, diff) =>

      case API.TheVD(name, id, resources, drivers, attributes) =>

      case API.TerminatedVD(id) =>

      case API.TerminatedAllVDs =>

    }
  }

  def postRequest(request: API.Request): Unit = {
    post(
      request,
      from = "VDCommunication",
      to = API.service,
      topic = API.topicRequest
    )
  }
}