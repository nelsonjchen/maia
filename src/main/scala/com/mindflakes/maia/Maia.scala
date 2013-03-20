package com.mindflakes.maia

import akka.actor._
import org.pircbotx.PircBotX
import org.pircbotx.hooks.ListenerAdapter
import org.pircbotx.hooks.events._
import com.typesafe.config.ConfigFactory


case class PlayPause()
case class NowPlaying()
case class Tired()
case class Hate()
case class Respond(message: String)

object Maia extends App {
  val system = ActorSystem("Maia")
  val irc_bot = system.actorOf(Props[MaiaIRCActor],"irc")

  println("Press 'Return' key to exit.")
  readLine()
  system.shutdown()
}

class MaiaIRCActor extends Actor with ActorLogging {
  val cfg = ConfigFactory.load()
  val irc_bot = new PircBotX
  irc_bot.setName(cfg.getString("maia.nick"))
  irc_bot.setAutoNickChange(true)
  irc_bot.connect(cfg.getString("maia.host"))
  irc_bot.joinChannel(cfg.getString("maia.channel"))
  irc_bot.setAutoReconnect(true)
  irc_bot.getListenerManager.addListener(new LogAdapter)
  val logger = context.actorOf(Props[MaiaIRCLogger],"logger")
  val hermes = context.actorOf(Props[MaiaHermes],"hermes")
  val trigger = context.actorOf(Props(new MaiaTriggerActor(cfg.getString("maia.trigger"))),"trigger")


  override def postStop() {
    irc_bot.setAutoReconnect(false)
    irc_bot.shutdown()
  }

  def receive = {
    case Respond(msg) => {
      irc_bot.sendMessage(cfg.getString("maia.channel"), msg)
    }
    case _ => {}
  }

  class LogAdapter extends ListenerAdapter {
    override def onMessage(event: MessageEvent[Nothing]) {
      context.system.eventStream.publish(event)
    }
  }

}

class MaiaIRCLogger extends Actor with ActorLogging {
  context.system.eventStream.subscribe(self, classOf[MessageEvent[_]])
  def receive = {
    case e: MessageEvent[_] => {
      val chan_name = e.getChannel.getName
      val msg = e.getMessage
      log.info(s"$chan_name $msg")
    }
    case _ => {}
  }
}

class MaiaTriggerActor(trigger: String) extends Actor with ActorLogging {
  val hermes = "/user/irc/hermes"

  context.system.eventStream.subscribe(self, classOf[MessageEvent[_]])

  def receive = {
    case MessageEvent(_,_,message) => {
      message.drop(trigger.length) match {
        case "playpause" | "pauseplay" => {
          context.actorFor(hermes) ! PlayPause
        }
        case "tired" => {
          context.actorFor(hermes) ! Tired
        }
        case "hate" => {
          context.actorFor(hermes) ! Hate
        }
        case "np" => {
          context.actorFor(hermes) ! NowPlaying
        }
        case "help" => {
          context.actorFor("/user/irc") ! Respond("https://github.com/crazysim/maia")
        }
      }
    }
  }
}

class MaiaHermes extends Actor with ActorLogging with ActorAppleScript {
  context.system.eventStream.subscribe(self, classOf[MessageEvent[_]])

  def hermes(command: String): String = {
    log.info("Command: " + command)
    val res = ascript("tell application \"Hermes\" to " + command)
    res
  }

  def np = hermes("get {title, artist, album} of current song")

  def receive = {
    case PlayPause => {
      hermes("playpause")
    }
    case Tired => {
      hermes("tired of song")
    }
    case Hate => {
      hermes("thumbs down")
    }
    case NowPlaying => {
      context.actorFor("/user/irc") ! Respond(np)
    }
    case _ => {}
  }
}

object MessageEvent {
  def unapply(m: MessageEvent[_]): Option[(String, String, String)] = {
    Some(m.getChannel.toString, m.getUser.toString, m.getMessage)
  }
}

trait ActorAppleScript {
  def ascript(script: String): String = {
    val runtime = Runtime.getRuntime
    val args = Array("osascript", "-e", script)
    val result = runtime.exec(args)
    scala.io.Source.fromInputStream(result.getInputStream).getLines().mkString("")
  }
}