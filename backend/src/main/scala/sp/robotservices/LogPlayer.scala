package sp.robotservices

import java.io.File

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import com.codemettle.reactivemq.ReActiveMQExtension
import com.codemettle.reactivemq.model.{AMQMessage, Topic}
import play.api.libs.json._
import sp.domain.{ID, JSFormat, SPHeader, SPMessage}
import com.codemettle.reactivemq._
import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.model._
import sp.robotservices.APIRobotServices._
import sp.robotservices.APIRobotServices.ModulesReadEvent._
import akka.actor.Timers

import scala.io.Source

case object Tick
case object TickKey

class LogPlayer extends  Actor with ActorLogging with
  sp.service.ServiceSupport with Timers{
  println("Logplayer started")
  val instanceID = ID.newID

  // Setting up the status response that is used for identifying the service in the cluster
  val statusResponse = LogPlayerInfo.attributes.copy(
    instanceID = Some(this.instanceID)
  )
  triggerServiceRequestComm(statusResponse)

  subscribe(APIRobotServices.topicRequest)
  // State
  var theBus: Option[ActorRef] = None


  import scala.concurrent.duration._
 // import context.dispatcher
//  def ticker = context.system.scheduler.schedule(0 seconds, 0.1 seconds, self, Tick)
  var workCellLoc: String = ""
  var progLocation: String = ""
  var jsonFile: JsValue = JsNull
  var wcellfile: List[JsValue] = List.empty
  var rcdProgs: List[JsValue] = List.empty
  var evts: List[JsValue] = List.empty
  var fileLoaded= false
  var wcellmap: Map[String,APIRobotServices.WorkCell] =Map.empty
  var robotIdToModues: Map[String,APIRobotServices.ModulesReadEvent] = Map.empty
  var playLog :Boolean = true
  def receive = {
    case ConnectionEstablished(request, c) =>
      log.info("Connected: " + request)
      c ! ConsumeFromTopic(APIRobotServices.activeMQRequestTopic)
      theBus = Some(c)
    case ConnectionFailed(request, reason) =>
      log.error("Connection failed: " + reason)
    case mess@AMQMessage(body, prop, headers) =>
      val req = Json.parse(body.toString)
      log.info(s"Got request $req")
        if(has(req,"requestModules"))
          publish(APIRobotServices.topicRequest,SPMessage.makeJson(SPHeader(to = APIRobotServices.logPlayer, from = APIRobotServices.logPlayer),APIRobotServices.LoadRobotModules(progLocation) ))
        else if ((req \ "event").as[String] == "newWorkCellEncountered")
          publish(APIRobotServices.topicRequest,SPMessage.makeJson(SPHeader(to = APIRobotServices.logPlayer, from = APIRobotServices.logPlayer),APIRobotServices.LoadWorkCells(workCellLoc) ))


    case Tick =>
      playFirstEvent(evts)
    case x: String =>
      //println(s"Got ${x} from frontend")
      // extract the body if it is a case class from my api as well as the header.to has my name
      // act on the messages from the API. Always add the logic in a trait to enable testing
      val bodyAPI = for {
        mess <- SPMessage.fromJson(x)
        h <- mess.getHeaderAs[SPHeader] if h.to == APIRobotServices.logPlayer
        b <- mess.getBodyAs[APIRobotServices.Request]
      } yield {

        b match {
          case APIRobotServices.Connect =>
            log.info("connecting to amq")
            ReActiveMQExtension(context.system).manager ! GetConnection(s"nio://${APIRobotServices.activeMQUrl}:${APIRobotServices.activeMQPort}")
          case l: APIRobotServices.LoadLog =>
            log.info(s"loading logs ${l.path}")

            jsonFile = loadFile(l.path)

            evts =
              if (jsonFile != JsNull) {
                log.info("Making events")
                jsonFile.as[List[JsValue]]
              }
              else
                List.empty

            log.info(s" done loading logs of size ${evts.length}")

          case l: APIRobotServices.LoadRobotModules =>
            progLocation = l.folderPath
            log.info("loading robot modules")
            loadRobotModules(l.folderPath)
            log.info("loading robot modules done")
          case  APIRobotServices.PlayLogs =>
            def playLog ={
              publish(APIRobotServices.topicResponse,SPMessage.makeJson(SPHeader(), APIRobotServices.Started))
              timers.startPeriodicTimer(TickKey,Tick,0.0001 seconds)
            }
            playLog
          case w :APIRobotServices.LoadWorkCells => log.info("Loading Workcells")
            workCellLoc = w.path
            val source: String = Source.fromFile(workCellLoc).getLines.mkString
            sendToBusWithTopic(APIRobotServices.activeMQTopic, source)
          case APIRobotServices.StopPlayingLogs =>
            publish(APIRobotServices.topicResponse,SPMessage.makeJson(SPHeader(), APIRobotServices.Finished))
            timers.cancel(TickKey)
          case _ =>
        }

      }
  }

  def has(json:JsValue,childString: String): Boolean = {
    if ((json \ childString).isInstanceOf[JsUndefined])
      false
    else
      true
  }
  def loadRobotModules(path: String): Unit ={
    def getListOfFiles(dir: String):List[File] = {
      val d = new File(dir)
      if (d.exists && d.isDirectory) {
        d.listFiles.filter(_.isFile).toList
      } else {
        List[File]()
      }
    }

    log.info(s"Loading list of files: ${getListOfFiles(path)}")
    getListOfFiles(path).foreach{f =>
      //val prog = Json.parse(Source.fromFile(f).getLines.mkString)
      //val jProg= prog.as[APIRobotServices.ModulesReadEvent]
      //publish(APIRobotServices.topic,SPMessage.makeJson(SPHeader(from = APIRobotServices.logPlayer),jProg))}
      val prog = Source.fromFile(f).getLines().mkString
      sendToBusWithTopic(APIRobotServices.activeMQTopic,prog)}
  }
  def playFirstEvent(e: List[JsValue]): Unit = {
    e match {
      case Nil =>
        log.info("Finished playing log")
        publish(APIRobotServices.topicResponse,SPMessage.makeJson(SPHeader(), APIRobotServices.Finished))
        timers.cancel(TickKey)
      case x :: xs =>
        //log.info(s"${x.toString}")
        sendToBusWithTopic(APIRobotServices.activeMQTopic, x.toString())
        evts = xs
      //Thread.sleep(500)
    }
  }
  def sendToBusWithTopic(topic: String, json: String) = {
    theBus.foreach{bus => bus ! SendMessage(Topic(topic), AMQMessage(json))}
  }

  def robotModules(json:List[JsValue]){
    val modules = json.map(_.asInstanceOf[APIRobotServices.ModulesReadEvent])
    modules.foreach(x => robotIdToModues += (x.robotId -> x))
  }
  def loadFile(path: String): JsValue ={
    import scala.io.Source
    import java.nio.file.{Paths, Files}
    val d = new File(path)
    //d.listFiles.filter(f =>f.isFile).foreach(x =>println( x.getName))

    if (!d.exists || !d.isFile ) {
      log.info("File does not exist")
      JsNull
  }
    else {
      val source: String = Source.fromFile(d).getLines.mkString
      val parsedFile = Json.parse(source)
      log.info("Finished parsing file")
      parsedFile
    }
  }





}
object LogPlayer {
  def props = Props[LogPlayer]
}

object LogPlayerInfo {
  import sp.domain.SchemaLogic._

  import sp.domain._


  val apischema =  SPAttributes()
  val attributes: APISP.StatusResponse = APISP.StatusResponse(
    service = "LogPlayer",
    tags = List("LogPlayer"),
    api = apischema,
    version = 1,
    topicRequest = APIRobotServices.topicRequest,
    topicResponse = APIRobotServices.topic,
    attributes = SPAttributes.empty
  )
}
