package spgui.widgets.OPGUI

import scalacss.DevDefaults._
import scalacss.ScalaCssReact._
import spgui.theming.Theming

/** Define the css-classes for the OperationRunner with ScalaCSS */
object OperationRunnerWidgetCSS extends Theming.SPStyleSheet {
  import dsl._

  val widgetRoot = style(
    height(100 %%)
  )

  val cardGroupCollapsed = style(
    flexGrow(1),
    display.block
   )

  val cardGroupExpanded = style(
    display.flex,
    flexGrow(1)
  )

  val rootDiv = style(
    height(100.%%),
    width(100.%%),
    display.flex,
    flexDirection.column
  )

  val cardPlaceholder = style(
    padding(8.px),
    display.inlineTable
  )

  val cardOuter = style(
    boxShadow:= "0px 1px 5px 0px rgba(0, 0, 0, 0.2), 0px 2px 2px 0px rgba(0, 0, 0, 0.14), 0px 3px 1px -2px rgba(0, 0, 0, 0.12)",
    display.flex,
    flexGrow(1),
    overflow.auto,
    position.relative
  )

  val cardCollapsed = style(
    width(0.px),
    height(0.px),
    display.none
  )

  val cardExpanded = style(
    display.flex,
    width(100.%%)
  )


  val marginCTS = 3.px
  val cardTitleSmall = style(
    fontSize(12.px),
    textOverflow:= "ellipsis",
    overflow.hidden,
    marginTop(marginCTS),
    marginLeft(marginCTS),
    marginRight(marginCTS),
    borderBottom(1.px),
    fontFamily:=!"'Arial'"
  )

  val unsetHeight = style(
    height.unset
  )

  val cardState = style(
    margin(0 px,0 px,0 px,25 px)
  )
  val cardGroupTitle = style(
    fontSize(20 px)
  )

  val card = style(
    width(100.%%),
    backgroundColor.rgb(204, 204, 204),
    display.flex
  )

  // val sopComponent = style(
  //   overflow.visible.important,
  //   touchAction:="none",
  //   userSelect := "none",
  //   position.relative,
  //   zIndex := "1",
  //   width(80 px),
  //   height(120 px),
  //   margin(10 px)
  // )

  val smallOpOuter = style(
    margin(10 px),
    display.flex,
    flexDirection.column
  )

  val opOuter = style(
    width(100 %%),
    margin(10 px),
    display.flex,
    flexDirection.column
  )

  val opInner = style(
    height(100 %%),
    width(100 %%),
    borderRadius(15 px),
    borderWidth(2 px),
    borderStyle.solid,
    color.rgb(0, 0, 0),
    backgroundColor.rgb(255, 255, 255),
    display.flex,
    flexDirection.column
  )

  val smallOpInner = style(
    height(70 px),
    width(120 px),
    borderRadius(15 px),
    borderWidth(2 px),
    borderStyle.solid,
    color.rgb(0, 0, 0),
    backgroundColor.rgb(255, 255, 255),
    display.flex,
    flexDirection.column
  )

  val opPrecondition = style(
    textAlign.center,
    borderBottomColor.rgb(0, 0, 0),
    borderBottomStyle.solid,
    borderBottomWidth(1 px),
    marginLeft(5 px),
    marginRight(5 px),
    fontFamily:=!"'monospace'"
  )

  val opNameOuter = style(
    height(100 %%),
    overflow.auto,
    overflowWrap := "break-word",
    margin(4 px)
  )

  val smallOpName = style(
    position.relative,
    textAlign.center,
    fontSize(12 px),
    overflow.hidden,
    textOverflow:= "ellipsis"
  )

  val opName = style(
    position.relative,
    textAlign.center,
    fontSize(16 px),
    overflow.hidden,
    textOverflow:= "ellipsis"
  )

  val opPostcondition = style(
    textAlign.center,
    borderTopColor :=! "#000000",
    borderTopStyle.solid,
    borderTopWidth(1 px),
    marginLeft(5 px),
    marginRight(5 px),
    fontFamily:=!"'monospace'"
  )

  val emphasizeText = style(
    fontWeight.bold,
    textAlign.center,
    textTransform.capitalize
  )

  val blue = style(
    color(c"#1F7AA3")
  )

  val orange = style(
    color(c"#CA5D27")
  )

  val green = style(
    color(c"#4aab46")
  )


  this.addToDocument()
}
