package spgui.widgets.labkit

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.all._

import sp.domain._
import spgui.communication._
import sp.domain.Logic._

object AvailableModels {
  import sp.models.{APIModelMaker => mmapi}
  def extractMMResponse(m: SPMessage) = for {
    h <- m.getHeaderAs[SPHeader]
    b <- m.getBodyAs[mmapi.Response]
  } yield (h, b)
  def makeMess(h: SPHeader, b: mmapi.Request) = SPMessage.make[SPHeader, mmapi.Request](h, b)


  case class Props(onChange: Set[ID] => Callback)
  case class State(models: Set[ID])

  class Backend($: BackendScope[Props, State]) {
    def sendToHandler(mess: mmapi.Request): Callback = {
      val h = SPHeader(from = "ModelWidget", to = mmapi.service,
        reply = SPValue("ModelWidget"))
      val json = makeMess(h, mess)
      BackendCommunication.publish(json, mmapi.topicRequest)
      Callback.empty
    }

    def oc(models: Set[ID])(p: Props) = {
      p.onChange(models)
    }

    def handleMess(mess: SPMessage): Unit = {
      extractMMResponse(mess).map{ case (h, b) =>
        val res = b match {
          case mmapi.ModelList(models) =>
            ($.state >>= (s => $.props >>= oc(models.toSet))).runNow()
            $.modState(s => s.copy(models = models.toSet))
          case mmapi.ModelCreated(name, attr, modelid) =>
            ($.state >>= (s => $.props >>= oc(s.models + modelid))).runNow()
            $.modState(s => s.copy(models = s.models + modelid))
          case mmapi.ModelDeleted(modelid) =>
            ($.state >>= (s => $.props >>= oc(s.models - modelid))).runNow()
            $.modState(s => s.copy(models = s.models - modelid))
          case x => Callback.empty
        }
        res.runNow()
      }
    }
    val topic = mmapi.topicResponse
    val wsObs = BackendCommunication.getWebSocketStatusObserver(  mess => {
      if (mess) sendToHandler(mmapi.GetModels)
    }, topic)
    val topicHandler = BackendCommunication.getMessageObserver(handleMess, topic)


    def render(s: State) = div() // EmptyVdom does not compile

  }

  private val component = ScalaComponent.builder[Props]("SPTextBox")
    .initialState(State(models = Set()))
    .renderBackend[Backend]
    .build

  def apply(onChange: Set[ID] => Callback) =
    component(Props(onChange))
}