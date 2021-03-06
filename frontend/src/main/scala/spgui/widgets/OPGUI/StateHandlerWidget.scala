package spgui.widgets.OPGUI

import scala.util.Try
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import play.api.libs.json.JsValue
import sp.devicehandler.APIVirtualDevice
import sp.domain.Logic._
import sp.domain._
import sp.models.APIModel
import sp.runners.APIOperationRunner
import sp.runners.APIOperationRunner.Setup
import spgui.communication._
import spgui.components.SPWidgetElements


/** Widget for matching the Driver with a Operation */
object StateHandlerWidget {
  case class Runner(id: Option[ID] = None, runInAuto: Boolean = true,
                    startOperation: Option[ID] = None, stepBackward: Boolean = false)
  case class ExtractedThings(allOperations: List[Thing] = List(),
                             allDrivers: List[Thing] = List(), operation2Driver: Map[ID, ID] = Map())

  /** The React-State of the Widget.
    * This Widget should be able to:
    *     1. stop the runner and go into manual-mode
    *     2. update the [[Thing]]:s from the model
    *     3. update the states of the VD
    *
    * @param activeRunner Current Runner
    * @param extractedThings The things from the model
    * @param virtualDeviceState The states of the Virtual Device
    * @param hiddenIDs The ids of widgets filtered out according to search query
    */
  case class State(
                    activeRunner:           Option[Runner] = None,
                    extractedThings:        ExtractedThings = ExtractedThings(),
                    virtualDeviceState:     Map[ID, SPValue] = Map(),
                    hiddenIDs:              Set[ID] = Set()
                  )

  private class Backend($: BackendScope[Unit, State]) {
    val modelHandler =
      BackendCommunication.getMessageObserver(onModelMessage, APIModel.topicResponse)
    val deviceDriverHandler =
      BackendCommunication.getMessageObserver(onVDMessage, APIVirtualDevice.topicResponse)

    /** Handle VirtualDevice-messages.
      *
      * If a [[APIModel.SPItems]] response is noticed,
      * update the local lists of driverThings and operationThings.
      *
      * If something else, Empty Callback.
      *
      * @param mess SPMessage from APIModel
      */
    def onModelMessage(mess: SPMessage): Unit = {
      val callback: Option[CallbackTo[Unit]] = mess.getBodyAs[APIModel.Response].map {
        case APIModel.SPItems(items) => {
          $.modState { state =>
            // get all extracted things
            val e: ExtractedThings = extractVariablesFromModel(items)
            // for all drivers in extracted things
            // map it against a the driverState if it does already exist in driverStateMapper
            // else map it against a new ID
            val newDriverStates: Map[ID, SPValue] = e.allDrivers.map{driver =>
              if (state.virtualDeviceState.contains(driver.id))
                driver.id -> state.virtualDeviceState(driver.id)
              else
                driver.id -> SPValue("Not connected")
            }.toMap
            // update state
            state.copy(extractedThings = e, virtualDeviceState = state.virtualDeviceState ++ newDriverStates)
          }
        }
        case x => Callback.empty
      }
      callback.foreach(_.runNow())
    }

    /** Filter out a list of operationThings, driverThings and the map between operations and drivers
      *
      * @param model The model as a List of IDAble
      * @return The class ExtractedThings (containging the list of operationThings, driverThings and mapping)
      */
    def extractVariablesFromModel(model: List[IDAble]): ExtractedThings = {
      // get runner
      val runnerSetupThings: List[Thing] = model.collect{case t: Thing if t.attributes.keys.contains("runnerID") => t}
      val runners: List[Setup] = runnerSetupThings.map(APIOperationRunner.runnerThingToSetup)

      val r = runners.headOption // assume one runner
      val mapping: Map[ID, ID] = r.map(_.variableMap).getOrElse(Map())
      val driverThings = model.collect{case t: Thing if t.attributes.keys.contains("driverName") => t}
      val operationThings = model.collect{ case t: Thing if t.attributes.keys.contains("domain") => t}

      ExtractedThings(operationThings, driverThings, mapping)
    }

    /** Handle VirtualDevice-messages.
      *
      * If a [[APIVirtualDevice.StateEvent]] response is noticed,
      * update the local VD-states.
      *
      * If something else, Empty Callback.
      *
      * @param mess SPMessage from APIVirtualDevice
      */
    def onVDMessage(mess: SPMessage): Unit = {
      val callback: Option[CallbackTo[Unit]] = mess.getBodyAs[APIVirtualDevice.Response].map {
        case APIVirtualDevice.StateEvent(_, _, newDriverStates,_) => {
          $.modState(state => state.copy(virtualDeviceState = state.virtualDeviceState ++ newDriverStates))
        }
        case x => Callback.empty
      }
      callback.foreach(_.runNow())
    }

    /** Filtering out of things not matching the query string, by name
      *
      * @param query String to match things the user wants to see
      * @return Callback setting to-be-hidden ids in widget State
      */
    def filterOutItems(query: String) = {
      val allThings = $.state.map(_.extractedThings).map(et => et.allDrivers ::: et.allOperations)
      val nonMatchingIDs = allThings.map(_.collect {
        case thing if(!thing.name.toLowerCase.contains(query.toLowerCase)) => thing.id
      })
      nonMatchingIDs.flatMap(ids => $.modState(s => s.copy(hiddenIDs = ids.toSet)))
    }

    /** Render-function in Backend
      *
      * @param state Current state in Backend-class
      * @return The Widget GUI
      */
    def render(state: State) = {
      <.div(
        <.div(
          ^.className := StateHandlerWidgetCSS.textBoxDiv.htmlClass,
          SPWidgetElements.TextBox("Find", str => filterOutItems(str))
        ),
        renderModel(state.extractedThings.allOperations, state.extractedThings.allDrivers,
          state.extractedThings.operation2Driver, state.virtualDeviceState, state.hiddenIDs)
      )
    }

    /** Render the model in state handler
      *
      * @param operationThings List of the operationThings in model
      * @param driverThings List of the driverThings in model
      * @param operationDriverMap The id:s of the operations that is connected to a driverValue. Map of [[ID]] to [[ID]].
      * @param virtualDeviceState The Driver-values. Map of [[ID]] to [[SPValue]])
      * @param hiddenIDs The id:s of things not matched by the filtering search function
      * @return The scene to the widget
      */
    def renderModel(operationThings: List[Thing], driverThings: List[Thing], operationDriverMap: Map[ID, ID],
                    virtualDeviceState: Map[ID, SPValue], hiddenIDs: Set[ID]) =
    {
      val sortedDriverlessOperationThings =
        operationThings.sortBy(t => t.name).filterNot(thing => operationDriverMap.contains(thing.id))
      val sortedOperationlessDriverThings =
        driverThings.sortBy(t => t.name).filterNot(thing => operationDriverMap.values.toList.contains(thing.id))

      <.div(
        <.div(
          <.details(^.open := "open", ^.className := "details-pairs",
            <.summary("Operation-Driver Pairs"),
            <.table(
              ^.className := "table table-striped", ^.className := "table-pairs",
              tableHead(),
              <.tbody(
                // for all pairs of operation-virtualDeviceState
                // print the things
                operationDriverMap.map { idPair =>
                  val opThing: Thing = operationThings.find(_.id == idPair._1).getOrElse(Thing("debug-opThing"))
                  val driverThing: Thing = driverThings.find(_.id == idPair._2).getOrElse(Thing("debug-driverThing"))
                  <.tr(
                    <.td(opThing.name),
                    printOperationDomain(opThing),
                    <.td(""),// TODO: Read or Write or No master?
                    <.td(driverThing.name),
                    <.td(virtualDeviceState(driverThing.id).toString())
                  ).unless(hiddenIDs.contains(idPair._1) || hiddenIDs.contains(idPair._2))
                }.toTagMod
              )
            )
          )
        ).when(operationDriverMap.nonEmpty),
        <.div(
          <.details(^.open := "open",  ^.className := "details-empty-operations",
            <.summary("Operation with no Driver"),
            <.table(
              ^.className := "table table-striped",  ^.className := "table-empty-operations",
              tableHead(),
              <.tbody(
                // for all operation things that do not have its id in operationDriverMap
                // print the operation
                sortedDriverlessOperationThings.map { operation =>
                  <.tr(
                    <.td(operation.name),
                    printOperationDomain(operation),
                    <.td(""),// TODO: Read or Write or No master?
                    <.td(""),
                    <.td("")
                  ).unless(hiddenIDs.contains(operation.id))
                }.toTagMod
              )
            )
          )
        ).when(sortedDriverlessOperationThings.nonEmpty),
        <.div(
          <.details(^.open := "open", ^.className := "details-empty-drivers",
            <.summary("Driver with no Operation"),
            <.table(
              ^.className := "table table-striped", ^.className := "table-empty-drivers",
              tableHead(),
              <.tbody(
                // for all driver things that do not have its id in operationDriverMap
                // print the driver
                sortedOperationlessDriverThings.map { driverThing =>
                  <.tr(
                    <.td(),
                    <.td(),
                    <.td(""),// TODO: Read or Write or No master?
                    <.td(driverThing.name),
                    <.td(virtualDeviceState(driverThing.id).toString())
                  ).unless(hiddenIDs.contains(driverThing.id))
                }.toTagMod
              )
            )
          )
        ).when(sortedOperationlessDriverThings.nonEmpty)
      )
    }

    /** Table head for all tables used in widget
      *
      * @return A pre-defined <.thead(...)
      */
    def tableHead = {
      <.thead(
        <.tr(
          <.td("Operation Name"),
          <.td("Operation Domain"),
          <.td("Read/Write"),
          <.td("Driver Name"),
          <.td("Driver Value")
        )
      )
    }

    /** Print the attributes of the operationThing
      *
      * @param operation The operation as a thing
      * @return A cell of table-data with the domain value
      */
    def printOperationDomain(operation: Thing) = {
      val query: String = "domain"
      val attributeValueMap = operation.attributes.value.filter { case (key, _) => key == query }
      val tryParseDropdown = Try{
        val dropDownElements: Seq[VdomNode] = attributeValueMap.flatMap { valMap: (String, JsValue) =>
          val parsedDomain: SPValue = valMap._2.to[SPValue].getOrElse(SPValue("Did Not Parse JsValue"))
          val seqOfDomainValues: Seq[SPValue] =
            parsedDomain.to[Seq[SPValue]].getOrElse(Seq(SPValue("Could Not Parse list")))
          seqOfDomainValues.map(value =>
            SPWidgetElements.dropdownElement(value.toString, operationDomainChange(operation.id, value))
          )
        }.toSeq
        SPWidgetElements.dropdown("domain", dropDownElements)
      }
      tryParseDropdown.map(<.td(_)).getOrElse(<.td("-"))
    }

    // TODO Implement state change for the operation
    def operationDomainChange(operationID: ID, domainClicked: SPValue): Callback = {
      Callback.log(s"ID for the operation is $operationID and domain clicked is $domainClicked")
    }
    /** When the widget is unmounting, kill message-observer
      *
      * @return Callback to kill message-Observers
      */
    def onUnmount = Callback{
      println("StateHandlerWidget Unmouting")
      modelHandler.kill()
      deviceDriverHandler.kill()
    }
  }

  private val stateHandlerComponent = ScalaComponent.builder[Unit]("StateHandlerWidget")
    .initialState(State())
    .renderBackend[Backend]
    .componentWillUnmount(_.backend.onUnmount)
    .build

  def apply() = spgui.SPWidget(spwb => stateHandlerComponent())
}
