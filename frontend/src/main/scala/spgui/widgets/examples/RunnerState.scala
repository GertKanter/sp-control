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

  val typeFilters = List("type", "operation", "internal", "input", "output")

  case class State(state: Map[ID, SPValue], force: Map[ID, SPValue], activeForce: Set[ID], forceEvents: Map[ID, SPValue], idableFilter: String = "", typeFilter: String = typeFilters.head)

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
        val newForce = s.force + (id -> value)
        // if force active, send new force table to backend
        if(s.activeForce.contains(id))
          send(api.SetForceTable(newForce, s.forceEvents))
        s.copy(force = newForce)
      }
    }

    def setEvent(id: ID, value: SPValue): Callback = {
      $.modState { s =>
        val forceEvents =
          if(value == SPValue("[none]")) s.forceEvents - id
          else s.forceEvents + (id -> SPValue(value))

        send(api.SetForceTable(s.force, forceEvents))
        s.copy(forceEvents = forceEvents)
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
        send(api.SetForceTable(f, s.forceEvents))

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
            <.th(^.width:="200px","Active events"),
          )
        ),
        <.tbody(
          p.activeModel.map { m =>
            s.state.flatMap { case (id, v) =>
              m.items.get(id).map(item => item -> v)
            }.toList.filter(_._1.name.contains(s.idableFilter)).sortBy(_._1.name)
              .filter(p => s.typeFilter == typeFilters.head || s.typeFilter == itemType(p._1))
              .map { case (item, v) =>
                val internalValue = 0
                val domain = item.attributes.getAs[List[SPValue]]("domain").getOrElse(List())
                val dd = domain.map(d => <.div(d.toString, ^.onClick --> setForce(item.id, d)))
                val selectedForce = s.force.get(item.id).getOrElse(SPValue("[set force]"))
                val activeForce = s.activeForce.contains(item.id)

                val events = List("[none]", "start", "reset", "forceReset")
                val ed = events.map(e => <.div(e, ^.onClick --> setEvent(item.id, e)))
                val selectedEvent = s.forceEvents.get(item.id).getOrElse(SPValue("[none]"))

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
                  ),
                  <.td(
                    SPWidgetElements.dropdown(selectedEvent.toString, ed).when(item.isInstanceOf[Operation])
                  ),
                )
            }
          }.getOrElse(List()).toTagMod
        )
      )
    }

    def onFilterChange(e: ReactEventFromInput) = {
      val newValue = e.target.value
      $.modState(_.copy(idableFilter = newValue))
    }

    def setTypeFilter(t: String) = {
      $.modState(_.copy(typeFilter = t))
    }

    def render(p: Props, s: State) = {
      val typeLinks = typeFilters.map(t => <.div(t, ^.onClick --> setTypeFilter(t)))

      <.div(
        <.button(
          ^.className := "btn btn-small",
          ^.onClick --> send(api.StartAuto), "start auto"
        ),
        <.button(
          ^.className := "btn btn-small",
          ^.onClick --> send(api.StopAuto), "stop auto"
        ),
        <.label("Filter: "),
        <.input(
          ^.width := "150px",
          ^.value := s.idableFilter,
          ^.onChange ==> onFilterChange
        ),
        SPWidgetElements.dropdown(s.typeFilter, typeLinks),
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
    .initialState(State(Map(), Map(), Set(), Map()))
    .renderBackend[Backend]
    .componentWillUnmount(_.backend.onUnmount())
    .build

  def apply() = spgui.SPWidget(_ => connectCircuit { proxy =>  component(Props(proxy)) })

}
