package spgui.widgets.abilityhandler

import java.util.UUID

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import spgui.communication._
import sp.domain._
import Logic._
import sp.VDAggregator.APIVDAggregator

object AbilityHandlerWidget {
  import sp.devicehandler._
  import sp.devicehandler.{APIVirtualDevice => vdapi}
  import sp.abilityhandler.{APIAbilityHandler => abapi}

  // TODO: update to list the availible VDs

  case class State(resources: List[VD.Resource], abilities: List[abapi.Ability], abilityState: Map[ID, SPValue])

  private class Backend($: BackendScope[Unit, State]) {

    val aggregatorHandler = BackendCommunication.getMessageObserver(onAggregatorMessage, APIVDAggregator.topicResponse)

    def onAggregatorMessage(mess : SPMessage) ={
      mess.body.to[APIVDAggregator.Response].map{
        case APIVDAggregator.TheResources(resourceWithStates) =>
          println("AbilityHandler got resources")
          $.modState(_.copy(resources = resourceWithStates.map(_.r))).runNow()
        case APIVDAggregator.TheAbilities(abilities) =>
          println("AbilityHandler got abilities")
          $.modState(_.copy(abilities = abilities)).runNow()
        case APIVDAggregator.TheAbilityStates(abilityStates) =>
          println("AbilityHandler got ability states")
          $.modState{_.copy(abilityState = abilityStates)}.runNow()
        case x =>
      }
    }

    def render(s: State) = {
      <.div(
        <.h2("Ability Handler"),
        <.br(),
        <.button(
          ^.className := "btn btn-default",
          ^.onClick --> sendToAggregator(APIVDAggregator.GetResources), "Get resources"
        ),
        <.button(
          ^.className := "btn btn-default",
          ^.onClick --> sendToAggregator(APIVDAggregator.GetAbilities), "Get abilities"
        ),
        renderResources(s),
        renderAbilities(s)
      )
    }

    def renderResources(s: State) = {
      <.table(
        ^.width:="400px",
        <.caption("Resources"),
        <.thead(
          <.tr(
            <.th(^.width:="100px","Name")
          )
        ),
        <.tbody(
          s.resources.map(r=> {
            <.tr(
              <.td(r.name)
            )
          }).toTagMod
        )
      )
    }

    def getAbilityState(s: SPValue): String = {
      s.getAs[String]("state").getOrElse("")
    }

    def getAbilityCount(s: SPValue): Int = {
      s.getAs[Int]("count").getOrElse(0)
    }

    def renderAbilities(s: State) = {
      <.table(
        ^.width:="550px",
        <.caption("Abilties"),
        <.thead(
          <.tr(
            <.th(^.width:="200px","Name"),
            <.th(^.width:="100px","State"),
            <.th(^.width:="50px","Count"),
            <.th(^.width:="100px","Start"),
            <.th(^.width:="100px","Reset")
          )
        ),
        <.tbody(
          s.abilities.sortBy(a=>a.name).map(a=> {
            <.tr(
              <.td(a.name),
              <.td(getAbilityState(s.abilityState.getOrElse(a.id, SPValue.empty))),
              <.td(getAbilityCount(s.abilityState.getOrElse(a.id, SPValue.empty))),
              <.td(<.button(
                ^.className := "btn btn-sm",
                ^.onClick --> sendToAB(abapi.StartAbility(a.id)), "Start"
              )),
              <.td(<.button(
                ^.className := "btn btn-sm",
                ^.onClick --> sendToAB(abapi.ForceResetAbility(a.id)), "Reset"
              ))
            )
          }).toTagMod
        )
      )
    }

    def onUnmount() = {
      println("Unmounting")
      aggregatorHandler.kill()
      Callback.empty
    }


    def sendToAggregator(mess :APIVDAggregator.Request)  = Callback{
      val h = SPHeader(from = "AbilityHandlerWidget", to = APIVDAggregator.service)
      val json = SPMessage.make(SPValue(h), SPValue(mess))
      BackendCommunication.publish(json, APIVDAggregator.topicRequest)
    }

    def sendToAB(mess: abapi.Request): Callback = {
      val h = SPHeader(from = "AbilityHandlerWidget", to = abapi.service,
        reply = SPValue("AbilityHandlerWidget"), reqID = java.util.UUID.randomUUID())
      val json = SPMessage.make(SPValue(h), SPValue(mess))
      BackendCommunication.publish(json, abapi.topicRequest)
      Callback.empty
    }


  }

  private val component = ScalaComponent.builder[Unit]("AbilityHandlerWidget")
    .initialState(State(resources = List(), abilities = List(), abilityState = Map()))
    .renderBackend[Backend]
    .componentWillUnmount(_.backend.onUnmount())
    .build

  def apply() = spgui.SPWidget(spwb => component())
}
