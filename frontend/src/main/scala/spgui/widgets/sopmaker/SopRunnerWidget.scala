package spgui.widgets.sopmaker

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import sp.domain._

import spgui.components.SPWidgetElements
import diode.react.{ModelProxy, ReactConnectProxy}
import spgui.communication._
import sp.devicehandler.APIVirtualDevice
import spgui.circuits.main.MainCircuit
import spgui.circuits.main.handlers.ModelHandlerState

object SopRunnerWidget {

  case class State(
    sopSpecs: List[SOPSpec] = List(),
    modelOps: List[Operation] = List(),
    opStates: Map[ID, SPValue] = Map(),
    currentSop: Option[SOP] = None
  )
  case class Props(proxy: ModelProxy[ModelHandlerState])

  private class Backend($: BackendScope[Props, State]) {
    val operationRunnerHandler =
      BackendCommunication.getMessageObserver(onOperationRunnerMessage, APIVirtualDevice.topicResponse)

    def onOperationRunnerMessage(mess: SPMessage) =
      mess.getBodyAs[APIVirtualDevice.Response].map {
        case APIVirtualDevice.StateEvent(_, _, state, _) => {
          $.modState(s => s.copy(opStates = s.opStates ++ state)).runNow()
        }
        case _ => Unit
      }

    def onReceiveProps(props: Props) = {
      $.modState(state => {
        props.proxy.value.activeModel.map{ model =>
          val l = model.items.toList  /// cannot collect on the simpleset.. crashes. figure out at a later date
          val sopSpecs = l.collect {
            case spec: SOPSpec => spec
          }
          val ops = l.collect {
            case o: Operation => o
          }
          state.copy(
            sopSpecs = sopSpecs.toList,
            modelOps = ops.toList
          )
        }.getOrElse(state)
      })
    }

    def setSopSpec(spec: SOPSpec) = $.modState(_.copy(
      currentSop = Some(spec.sop.head)
    ))

    def render(props: Props, state: State) = {
      <.div(
        SPWidgetElements.buttonGroup(Seq(
          SPWidgetElements.dropdown(
            "Choose SOP",
            state.sopSpecs.map(
              spec => SPWidgetElements.dropdownElement(spec.name, setSopSpec(spec))
            )
          ))
        ),
        state.currentSop match {
          case Some(sop) => SopVisualiser(sop, state.modelOps, state.opStates)
          case None => EmptyVdom
        }
      )
    }
  }

  private val component = ScalaComponent.builder[Props]("SopRunnerWidget")
    .initialState(State())
    .renderBackend[Backend]
    .componentWillReceiveProps{
      scope => scope.backend.onReceiveProps(scope.nextProps)
    }
    .build

  val connectCircuit: ReactConnectProxy[ModelHandlerState] = MainCircuit.connect(_.models)

  def apply() = spgui.SPWidget(_ => connectCircuit { proxy => component(Props(proxy)) })
}
