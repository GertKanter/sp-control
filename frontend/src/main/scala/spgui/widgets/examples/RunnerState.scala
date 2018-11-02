package spgui.widgets.examples

import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import spgui.SPWidgetBase
import spgui.components.SPWidgetElements
import spgui.communication._
import sp.domain._
import Logic._
import spgui.circuits.main.{FrontendState, MainCircuit}
import sp.devicehandler.{APIVirtualDevice => api}
import spgui.circuits.main.handlers._

case class Props(proxy: ModelProxy[FrontendState]) {
  val activeModel: Option[ModelMock] = proxy.value.models.activeModel
}

object RunnerStateWidgetState {
  import spgui.widgets.examples.{RunnerStateCSS => css}

  case class State(state: Map[ID, SPValue], force: Map[ID, SPValue], activeForce: Set[ID])

  private class Backend($: BackendScope[Props, State]) {

    val messObs = BackendCommunication.getMessageObserver( mess => {
      for {
        b <- mess.getBodyAs[api.Response]
      } yield {
        val callback = b match {
          case api.StateEvent(_, _, state, _) =>
            $.modState{s =>
              s.copy(state = s.state ++ state)
            }
          case _ => Callback.empty
        }
        callback.runNow()
      }
    },
      api.topicResponse
    )

    def setForce(id: ID, value: SPValue): Callback = {
      $.modState { s =>
        s.copy(force = s.force + (id -> value))
      }
    }

    def itemType(item: IDAble): String = {
      val input = item.attributes.getAs[Boolean]("input").getOrElse(false)
      val output = item.attributes.getAs[Boolean]("output").getOrElse(false)
      val op = item.isInstanceOf[Operation]
      if(op) "operation"
      else if(input) "input"
      else if(output) "output"
      else "internal"
    }

    def toggleForce(id: ID) = {
      $.modState{s =>
        val isForce = s.activeForce.contains(id)

        val newState =
          if(isForce) s.copy(activeForce = s.activeForce - id)
          else s.copy(activeForce = s.activeForce + id)

        // send new force table to backend
        val f = newState.force.filterKeys(newState.activeForce.contains)
        send(api.SetForceTable(f))

        newState
      }
    }

    def renderState(p: Props, s: State) = {
      <.table(
        ^.className := "table table-striped",
        ^.width:="900px",
        <.thead(
          <.tr(
            <.th(^.width:="200px","Name"),
            <.th(^.width:="200px","Value"),
            <.th(^.width:="30px","Type"),
            <.th(^.width:="200px","Forced value"),
            <.th(^.width:="200px","Force"),
          )
        ),
        <.tbody(
          p.activeModel.map { m =>
            s.state.flatMap { case (id, v) =>
              m.items.get(id).map(item => item -> v)
            }.toList.sortBy(_._1.name).map { case (item, v) =>
                val internalValue = 0
                val domain = item.attributes.getAs[List[SPValue]]("domain").getOrElse(List())
                val dd = domain.map(d => <.div(d.toString, ^.onClick --> setForce(item.id, d)))
                val selectedForce = s.force.get(item.id).getOrElse(SPValue("[set force]"))
                val activeForce = s.activeForce.contains(item.id)

                val forceButtonStyle =
                  if (activeForce) css.activeModelButton.htmlClass
                  else css.inactiveModelButton.htmlClass

                <.tr(
                  <.td(item.name),
                  <.td(v.toString),
                  <.td(itemType(item)),
                  <.td(
                    SPWidgetElements.dropdown(selectedForce.toString, dd)
                      // <.input(
                      //   ^.width := "80px",
                      //   ^.value     := internalValue,
                      //   // ^.onChange ==> updateInternalValue(s, n.name)
                      // )),
                  ),
                  <.td(
                    <.button(
                      ^.className := s"btn btn-sm ${forceButtonStyle}",
                      ^.title := "Force value",
                      ^.onClick --> toggleForce(item.id),
                      <.i(^.className := (if (activeForce) "fa fa-circle" else "fa fa-circle-thin")),
                    )
                  ))
                }
            }.getOrElse(List()).toTagMod
          )
        )
      }

      def render(p: Props, s: State) = {
        <.div(
          <.button(
            ^.className := "btn btn-small",
            ^.onClick --> send(api.StartAuto), "start auto"
          ),
          <.button(
            ^.className := "btn btn-small",
            ^.onClick --> send(api.StopAuto), "stop auto"
          ),
          renderState(p, s)
        )
      }

      def onUnmount() = {
        println("Unmounting")
        //messObs.kill()
        Callback.empty
      }
    }

    def send(mess: api.Request): Callback = {
      VDCommunication.postRequest(mess)
      Callback.empty
    }

    val connectCircuit: ReactConnectProxy[FrontendState] = MainCircuit.connectComponent(identity)

    private val component = ScalaComponent.builder[Props]("RunnerStateWidgetState")
      .initialState(State(Map(), Map(), Set()))
      .renderBackend[Backend]
      .componentWillUnmount(_.backend.onUnmount())
      .build

    def apply() = spgui.SPWidget(_ => connectCircuit { proxy =>  component(Props(proxy)) })

  }