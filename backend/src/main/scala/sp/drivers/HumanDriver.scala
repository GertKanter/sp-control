package sp.drivers

import akka.actor._
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import org.apache.kafka.clients.producer.ProducerRecord
import sp.bluetooth.{BluetoothProxy, ProxyApplication}
import sp.devicehandler.VD.DriverState
import sp.devicehandler._
import sp.domain.Logic._
import sp.domain._
import sp.devicehandler.{APIDeviceDriver => api}
import sp.driver.APIHumanDriver

import scala.util.{Failure, Success}










/**
  * A driver for talking to human services, like widgets or a watch
  */
object HumanDriver {
  val driverType = "HumanDriver"
  def props = DriverBase.props(driverType, HumanDriverInstance.props)
}

object HumanDriverInstance {
  def props(d: VD.Driver) = Props(classOf[HumanDriverInstance], d)
}

class HumanDriverInstance(d: VD.Driver) extends Actor
  with ActorLogging
  with sp.service.MessageBussSupport{


  // The state of the op
  var blueToothConnected  = false

  var humanName = "humanName"
  var humanID = "humanID"
  var loggedIn = "loggedIn"
  var cmd = "cmd"
  var ack = "ack"
  var done = "done"

  val defaultState = Map[String, SPValue](
    humanName -> "",
    humanID -> "",
    loggedIn -> false,
    cmd -> "",
    ack -> false,
    done -> false
  )

  var driverState = defaultState

  def updS(key: String, v: SPValue) = driverState += (key -> v)

  // The commands that we send to the operator
  val instructions = d.setup.getAs[Map[String, String]]("instructions").getOrElse(List())
  val bluetooth = context.actorOf(Props(classOf[BluetoothConnector], self))

  subscribe(api.topicRequest)
  subscribe(APIHumanDriver.topicFromHuman)

  // The driver state is based on what we get back from the various human services.

  publish(api.topicResponse, SPMessage.makeJson(SPHeader(from = d.id.toString), api.TheDriver(d, driverState)))

  def receive = {
    case BlueToothConnect =>
      blueToothConnected = false

    case x: GotMessage =>
      if (driverState.get(ack).contains(SPValue(x.ack)) || driverState.get(done).contains(SPValue(x.done))){
        updS(ack, x.ack)
        updS(done, x.done)
        sendStateToBus(driverState)
      }


    case "tick" =>
      sendStateToBus(driverState)

    case x: String =>
      println("HUMANDRIVER GOT:" + x)
      SPMessage.fromJson(x).foreach{ mess =>
        for {
          h <- mess.getHeaderAs[SPHeader]
          b <- mess.getBodyAs[api.Request]
        } yield {
          val header = h.swapToAndFrom().copy(from = d.name)
          log.debug(s"HumanDRIVER req: " +b)
          b match {
              case api.GetDriver =>
                publish(api.topicResponse, SPMessage.makeJson(header, api.TheDriver(d, driverState)))
                publish(api.topicResponse, SPMessage.makeJson(header, APIDeviceDriver.DriverStateChange(d.name, d.id, driverState)))
                publish(api.topicResponse, SPMessage.makeJson(header, APISP.SPDone()))

              case api.DriverCommand(driverid, state) if driverid == d.id  =>
                publish(api.topicResponse, SPMessage.makeJson(header, APISP.SPACK()))
                val myHeader = SPHeader(from = d.id.toString, to = d.name, reply = SPAttributes(
                  "reqID" -> h.reqID, "from" -> h.from, "reply" -> h.reply
                ))

                val updState = defaultState ++ state

                sendStateToBluetooth()

                // sendStateToUI
//

              // Terminating the driver
              case api.TerminateDriver(driverid) if driverid == d.id =>
                self ! PoisonPill
                publish(api.topicResponse, SPMessage.makeJson(header, api.DriverTerminated(d.id)))
                publish(api.topicResponse, SPMessage.makeJson(header, APISP.SPDone()))

              case _ =>
            }
          }

        for {
          h <- mess.getHeaderAs[SPHeader]
          b <- mess.getBodyAs[APIHumanDriver.FromHuman]
        } yield {
          b match {
            case he: APIHumanDriver.HumanEvent =>
              println("EEEEEEEEEEEEEEEEEEEEEEEEEE")
              println(he.state , b)
              println("EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE")
              driverState = driverState ++ he.state

              for {
                attr <- h.reply.to[SPAttributes].toOption
                reqID <- attr.getAs[ID]("reqID")
                from <- attr.getAs[String]("from")
                reply <- attr.getAs[SPValue]("reply")
              } yield {
                val header = SPHeader(from = d.id.toString, to = from, reqID = reqID, reply = reply)
                publish(api.topicResponse, SPMessage.makeJson(header, api.DriverCommandDone(reqID, true)))
                publish(api.topicResponse, SPMessage.makeJson(header, APISP.SPDone()))

              }

              publish(api.topicResponse, SPMessage.makeJson(SPHeader(from = d.id.toString), APIDeviceDriver.DriverStateChange(d.name, d.id, driverState)))

          }
        }
      }

  }



  import context.dispatcher

  import scala.concurrent.duration._
  val ticker = context.system.scheduler.schedule(1 seconds, 1 seconds, self, "tick")





  /**
    * Listens for the pong for the last ping
    */



  // Sending a message to the bus
  def sendStateToBus(state: Map[String, SPValue]) = {
    val updH = SPHeader(from = d.name)
    val b = api.DriverStateChange(d.name, d.id, state, false)
    publish(api.topicResponse, SPMessage.makeJson(updH, b))

  }

  def sendStateToBluetooth() = {
    val cmdS = driverState.get(cmd).flatMap(_.getAs[String]()).getOrElse("")
    val ackS = driverState.get(ack).flatMap(_.getAs[Boolean]()).getOrElse(false)
    val doneS = driverState.get(done).flatMap(_.getAs[Boolean]()).getOrElse(false)
    val mess = SendMessage(cmdS, ackS, doneS)

    if (blueToothConnected) bluetooth ! mess
  }









}




case class SendMessage(cmd: String, ack: Boolean, done: Boolean)
object SendMessage {
  implicit def fSendMess = play.api.libs.json.Format[SendMessage]
}
case class GotMessage(ack: Boolean, done: Boolean)
object GotMessage{
  implicit def fGotMessage = play.api.libs.json.Format[GotMessage]
}


case object BlueToothConnect
class BluetoothConnector extends Actor {

  //val proxy = new Proxy(self ! _)

  context.parent ! BlueToothConnect


  override def receive = {
    case mess: String =>
      val x = SPValue.fromJson(mess)
      println("GOT from bluetooth: " + x)

      x.foreach(_.to[GotMessage].foreach(res =>
        context.parent ! res
      ))

    case x: SendMessage =>
      val mess = SPValue(x).toJson
      //proxy.proxy.send(mess)

      // dummy
      import scala.concurrent.duration._
      val reply: String = if (x.cmd.nonEmpty) SPValue(GotMessage(true, true)).toJson else SPValue(GotMessage(false, false)).toJson
      context.system.scheduler.scheduleOnce(5 seconds, self, reply)

  }
}

class Proxy(callBack: String => Unit) extends sp.bluetooth.BluetoothMessageListener{
  // This builds the Bluetooth Proxy. The process will block until
  // a device connects to it. Might fail if multiple devices try to
  // connect at the same time.
  println("before bluetooth")
  val proxy = new BluetoothProxy(this)
  println(s"after: $proxy")

  // Use proxy's send method to send messages to the device
  //proxy.send("Hello! this is server")

  override def onBluetoothMessage(message: String): Unit = {
    println("[RECEIVED] " + message)
    callBack(message)
    //proxy.send("Received")
  }
}
