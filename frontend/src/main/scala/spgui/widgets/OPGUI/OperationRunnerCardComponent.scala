package spgui.widgets.OPGUI
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import sp.domain._
import spgui.communication._
import sp.devicehandler.APIDeviceDriver
import japgolly.scalajs.react.vdom.all.svg
import scala.scalajs.js

object SPCardGrid {
  case class State(expandedId: Option[ID] = None)
  case class Props(cards: List[OperationRunnerCard])

  case class OperationRunnerCard(cardId: ID, ab: OperationRunnerWidget.AbilityWithState, op: OperationRunnerWidget.OperationWithState)

  class Backend($: BackendScope[Props, State]) {
    def render(p:Props, s: State) = {
      val isExpanded = s.expandedId.isDefined
      <.div(
        ^.className := OperationRunnerWidgetCSS.rootDiv.htmlClass,
        <.div(
          { isExpanded match {
            case true  => ^.className := OperationRunnerWidgetCSS.cardGroupExpanded.htmlClass
            case false => ^.className := OperationRunnerWidgetCSS.cardGroupCollapsed.htmlClass
          }},
          p.cards.map(
            c => c match {
              case opab: OperationRunnerCard => {
                val smallCard = cardSmall(opab)
                val expandedCard = cardExpanded(opab)
                renderCard(opab.cardId, s.expandedId, expandedCard, smallCard)
              }
            }
          ).toTagMod
        )
      )
    }
    
    def cardSmall(opab: OperationRunnerCard) = {
      <.div(
        ^.className := OperationRunnerWidgetCSS.card.htmlClass,
        <.span(
          ^.className := OperationRunnerWidgetCSS.sopOuter.htmlClass,
          sop(opab.op.operation.name, 0, 0)
        ),
        <.span(
          ^.className := OperationRunnerWidgetCSS.sopOuter.htmlClass,
          sop(opab.ab.ability.name, 0, 0)
        )
      )
    }

    def cardExpanded(opab: OperationRunnerCard) = {
      <.div(
        ^.className := OperationRunnerWidgetCSS.card.htmlClass,
        <.span(
          ^.className := OperationRunnerWidgetCSS.sopOuter.htmlClass,
          sop(opab.op.operation.name, 0, 0)
        ),
        <.span(
          ^.className := OperationRunnerWidgetCSS.sopOuter.htmlClass,
          sop(opab.ab.ability.name, 0, 0)
        )
      )
    }

    def renderCard(
      cardId: ID,
      expandedId: Option[ID],
      cardContentsExpanded: TagMod,
      cardContentsCollapsed: TagMod
    ): TagMod = {
      val isExpanded = expandedId == Some(cardId)
      List(
        <.span(
          ^.className := OperationRunnerWidgetCSS.cardPlaceholder.htmlClass,
          expandedId match {
            case None => EmptyVdom
            case _ =>
              if(expandedId == Some(cardId)) ^.className := OperationRunnerWidgetCSS.cardExpanded.htmlClass
              else ^.className := OperationRunnerWidgetCSS.cardCollapsed.htmlClass
          },
          <.span(
            ^.className := OperationRunnerWidgetCSS.cardOuter.htmlClass,
            expandedId match {
              case None => EmptyVdom
              case _ =>
                if(expandedId == Some(cardId)) ^.className := OperationRunnerWidgetCSS.unsetHeight.htmlClass
                else EmptyVdom
            },
            {
              isExpanded match {
                case true => cardContentsExpanded
                case false => cardContentsCollapsed
              }
            },
            ^.onClick --> $.modState(s =>
              if(s.expandedId == Some(cardId)) s.copy(expandedId = None)
              else s.copy(expandedId = Some(cardId))
            )
          )
        )
      ).toTagMod
    }
  }

  val opHeight = 80f
  val opWidth = 120f
  val opHorizontalBarOffset = 12f
  def sop(label: String, x: Int, y: Int) =
    <.span(
      svg.svg(
        svg.width := opWidth.toInt,
        svg.height:= opHeight.toInt,
        svg.svg(
          svg.width := opWidth.toInt,
          svg.height:= opHeight.toInt,
          svg.x := 0,
          svg.y := 0,
          svg.rect(
            svg.x := 0,
            svg.y := 0,
            svg.width := opWidth.toInt,
            svg.height:= opHeight.toInt,
            svg.rx := 6, svg.ry := 6,
            svg.fill := "white",
            svg.stroke := "black",
            svg.strokeWidth := 1
          ),
          // Top horizontal line
          svg.rect(
            svg.x := 0,
            svg.y := opHorizontalBarOffset.toInt,
            svg.width := opWidth.toInt,
            svg.height:= 1,
            svg.rx := 0, svg.ry := 0,
            svg.fill := "black",
            svg.stroke := "black",
            svg.strokeWidth := 1
          ),
          // Bottom horizontal line
          svg.rect(
            svg.x := 0,
            svg.y :=  (opHeight - opHorizontalBarOffset).toInt,
            svg.width := opWidth.toInt,
            svg.height:= 1,
            svg.rx := 0, svg.ry := 0,
            svg.fill := "black",
            svg.stroke := "black",
            svg.strokeWidth := 1
          ),
          svg.svg(
            svg.text(
              svg.x := "50%",
              svg.y := "50%",
              svg.textAnchor := "middle",
              svg.dy := ".3em",
              label
            )
          )
        )
      )
    )

  private val component = ScalaComponent.builder[Props]("OperationAbilityCardGrid")
    .initialState(State())
    .renderBackend[Backend]
    .build

  def apply(cards: List[OperationRunnerCard]) = component(Props(cards))
}
