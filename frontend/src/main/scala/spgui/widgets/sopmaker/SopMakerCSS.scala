package spgui.widgets.sopmaker

import scalacss.DevDefaults._
import spgui.theming.Theming

object SopMakerCSS extends Theming.SPStyleSheet {
  import dsl._

  val sopContainer = style(
    position.relative
  )

  val sopComponentSVG = style(
    overflow.visible.important,
  )

  val sopComponent = style(
    overflow.visible.important,
    touchAction:="none",
    userSelect := "none",
    position.absolute,
    zIndex := "1"
  )

  val opText = style(
    userSelect := "none"
  )

  val dropZone = style(
    position.absolute,
    zIndex(100),
    opacity:= "0.5"
  )
  val dropZoneOuter = style(
    position.absolute,
    zIndex(99),
    opacity:= "0.5"
  )

  val disableDropZone = style(
    pointerEvents := "none",
    visibility.hidden
  )

  val blue = style(
    backgroundColor:= "blue"
  )

  val menuOp = style(
    overflow.visible.important,
    touchAction:="none",
    userSelect := "none",
    position.relative,
    zIndex := "1",
  )

  val menuOpInner = style(
    marginBottom := -12.px
  )
  val menuOpText = style(
    fontSize := 28.px
  )

  this.addToDocument()
}
