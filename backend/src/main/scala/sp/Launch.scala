package sp

import akka.actor._
import sp.example._
import sp.modelImport._
import sp.virtcom._
import scala.concurrent.Await
import scala.concurrent.duration._


object Launch extends App {
  implicit val system = ActorSystem("SP")
  val cluster = akka.cluster.Cluster(system)

  val models = Map("URModel" -> sp.unification.URModel(),
    "TurtleModel" -> sp.unification.TurtleModel(),
    "TurtleDummyModel" -> sp.unification.DummyTurtleModel("TurtleDummyModel")
  )

  cluster.registerOnMemberUp {
    // Start all you actors here.
    println("spcontrol node has joined the cluster")
    sp.SPCore.launch(system)

    system.actorOf(sp.abilityhandler.AbilityHandler.props, "abilityHandlerMaker")
    system.actorOf(sp.devicehandler.VirtualDeviceMaker.props)
    system.actorOf(sp.drivers.ROSFlatStateDriver.props, "ROSFlatStateDriver")
    system.actorOf(sp.drivers.URDriver.props, "URDriver")
    system.actorOf(sp.runners.OperationRunner.props, "oprunner")
    system.actorOf(sp.modelService.ModelService.props(models))
    system.actorOf(sp.modelImport.SPModelImport.props)
    system.actorOf(sp.drivers.DriverService.props)

  }

  scala.io.StdIn.readLine("Press ENTER to exit cluster.\n")
  cluster.leave(cluster.selfAddress)

  scala.io.StdIn.readLine("Press ENTER to exit application.\n")
  system.terminate()

  Await.ready(system.whenTerminated, Duration(30, SECONDS))
  System.exit(0)
}
