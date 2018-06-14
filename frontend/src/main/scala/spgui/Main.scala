package spgui

import org.scalajs.dom.document
import scala.scalajs.js.annotation.{JSExport,JSExportTopLevel}

object Main extends App {
  @JSExportTopLevel("spgui.Main")
  protected def getInstance(): this.type = this

  @JSExport
  def main(): Unit = {
    Widgets.loadWidgets()
    new DashboardPresetsHandler()
    ModelCommunication.run()
    Layout().renderIntoDOM(document.getElementById("spgui-root"))
  }
}
