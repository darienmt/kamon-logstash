import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import kamon.Kamon

import scala.concurrent.duration._

/**
 * Runs the sample
 * http://doc.akka.io/docs/akka/current/scala/fault-tolerance-sample.html#full-source-code-of-the-fault-tolerance-sample
 */
object FaultHandlingDocSample extends App {
  import Worker._

  Kamon.start()

  val system = ActorSystem("FaultToleranceSample")
  val worker = system.actorOf(Props[Worker], name = "worker")
  val listener = system.actorOf(Props[Listener], name = "listener")
  // start the work and listen on progress
  // note that the listener is used as sender of the tell,
  // i.e. it will receive replies from the worker
  worker.tell(Start, sender = listener)

  import system.dispatcher
  system.whenTerminated.andThen {
    case _ => Kamon.shutdown()
  }
}

/**
 * Listens on progress from the worker and shuts down the system when enough
 * work has been done.
 */
class Listener extends Actor with ActorLogging {
  import Worker._
  // If we don't get any progress within 15 seconds then the service is unavailable
  context.setReceiveTimeout(15 seconds)

  def receive: Receive = {
    case Progress(percent) =>
      log.info("Current progress: {} %", percent)
      if (percent >= 100.0) {
        log.info("That's all, shutting down")
        context.system.terminate()
      }

    case ReceiveTimeout =>
      // No progress within 15 seconds, ServiceUnavailable
      log.error("Shutting down due to unavailable service")
      context.system.terminate()
  }
}

object Worker {
  case object Start
  case object Do
  final case class Progress(percent: Double)
}

/**
 * Worker performs some work when it receives the `Start` message.
 * It will continuously notify the sender of the `Start` message
 * of current ``Progress``. The `Worker` supervise the `CounterService`.
 */
class Worker extends Actor with ActorLogging {
  import CounterService._
  import Worker._
  implicit val askTimeout = Timeout(5 seconds)

  // Stop the CounterService child if it throws ServiceUnavailable
  override val supervisorStrategy = OneForOneStrategy() {
    case _: CounterService.ServiceUnavailable => Stop
  }

  // The sender of the initial Start message will continuously be notified
  // about progress
  var progressListener: Option[ActorRef] = None
  val counterService = context.actorOf(Props[CounterService], name = "counter")
  val totalCount = 51
  import context.dispatcher // Use this Actors' Dispatcher as ExecutionContext

  def receive: Receive = LoggingReceive {
    case Start if progressListener.isEmpty =>
      progressListener = Some(sender())
      context.system.scheduler.schedule(Duration.Zero, 1 second, self, Do)

    case Do =>
      counterService ! Increment(1)
      //      counterService ! Increment(1)
      //      counterService ! Increment(1)

      // Send current progress to the initial sender
      counterService ? GetCurrentCount map {
        case CurrentCount(_, count) => Progress(100.0 * count / totalCount)
      } pipeTo progressListener.get
  }
}

object CounterService {
  final case class Increment(n: Int)
  sealed abstract class GetCurrentCount
  case object GetCurrentCount extends GetCurrentCount
  final case class CurrentCount(key: String, count: Long)
  class ServiceUnavailable(msg: String) extends RuntimeException(msg)

  private case object Reconnect
}

/**
 * Adds the value received in `Increment` message to a persistent
 * counter. Replies with `CurrentCount` when it is asked for `CurrentCount`.
 * `CounterService` supervise `Storage` and `Counter`.
 */
class CounterService extends Actor {
  import Counter._
  import CounterService._
  import Storage._

  // Restart the storage child when StorageException is thrown.
  // After 3 restarts within 5 seconds it will be stopped.
  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 3,
    withinTimeRange = 5 seconds
  ) {
    case _: Storage.StorageException => Restart
  }

  val key = self.path.name
  var storage: Option[ActorRef] = None
  var counter: Option[ActorRef] = None
  var backlog = IndexedSeq.empty[(ActorRef, Any)]
  val MaxBacklog = 10000

  import context.dispatcher // Use this Actors' Dispatcher as ExecutionContext

  override def preStart(): Unit = {
    initStorage()
  }

  /**
   * The child storage is restarted in case of failure, but after 3 restarts,
   * and still failing it will be stopped. Better to back-off than continuously
   * failing. When it has been stopped we will schedule a Reconnect after a delay.
   * Watch the child so we receive Terminated message when it has been terminated.
   */
  def initStorage(): Unit = {
    storage = Some(context.watch(context.actorOf(Props[Storage], name = "storage")))
    // Tell the counter, if any, to use the new storage
    counter foreach { _ ! UseStorage(storage) }
    // We need the initial value to be able to operate
    storage.get ! Get(key)
  }

  def receive: Receive = LoggingReceive {

    case Entry(k, v) if k == key && counter == None =>
      // Reply from Storage of the initial value, now we can create the Counter
      val c = context.actorOf(Props(classOf[Counter], key, v))
      counter = Some(c)
      // Tell the counter to use current storage
      c ! UseStorage(storage)
      // and send the buffered backlog to the counter
      for ((replyTo, msg) <- backlog) c.tell(msg, sender = replyTo)
      backlog = IndexedSeq.empty

    case msg: Increment => forwardOrPlaceInBacklog(msg)

    case msg: GetCurrentCount => forwardOrPlaceInBacklog(msg)

    case Terminated(actorRef) if Some(actorRef) == storage =>
      // After 3 restarts the storage child is stopped.
      // We receive Terminated because we watch the child, see initStorage.
      storage = None
      // Tell the counter that there is no storage for the moment
      counter foreach { _ ! UseStorage(None) }
      // Try to re-establish storage after while
      context.system.scheduler.scheduleOnce(10 seconds, self, Reconnect)

    case Reconnect =>
      // Re-establish storage after the scheduled delay
      initStorage()
  }

  def forwardOrPlaceInBacklog(msg: Any): Unit = {
    // We need the initial value from storage before we can start delegate to
    // the counter. Before that we place the messages in a backlog, to be sent
    // to the counter when it is initialized.
    counter match {
      case Some(c) => c forward msg
      case None =>
        if (backlog.size >= MaxBacklog) {
          throw new ServiceUnavailable(
            "CounterService not available, lack of initial value"
          )
        }
        backlog :+= (sender() -> msg)
    }
  }

}

object Counter {
  final case class UseStorage(storage: Option[ActorRef])
}

/**
 * The in memory count variable that will send current
 * value to the `Storage`, if there is any storage
 * available at the moment.
 */
class Counter(key: String, initialValue: Long) extends Actor {
  import Counter._
  import CounterService._
  import Storage._

  var count = initialValue
  var storage: Option[ActorRef] = None

  def receive: Receive = LoggingReceive {
    case UseStorage(s) =>
      storage = s
      storeCount()

    case Increment(n) =>
      count += n
      storeCount()

    case GetCurrentCount =>
      sender() ! CurrentCount(key, count)

  }

  def storeCount(): Unit = {
    // Delegate dangerous work, to protect our valuable state.
    // We can continue without storage.
    storage foreach { _ ! Store(Entry(key, count)) }
  }

}

object Storage {
  final case class Store(entry: Entry)
  final case class Get(key: String)
  final case class Entry(key: String, value: Long)
  class StorageException(msg: String) extends RuntimeException(msg)
}

/**
 * Saves key/value pairs to persistent storage when receiving `Store` message.
 * Replies with current value when receiving `Get` message.
 * Will throw StorageException if the underlying data store is out of order.
 */
class Storage extends Actor {
  import Storage._

  val db = DummyDB

  def receive: Receive = LoggingReceive {
    case Store(Entry(key, count)) => db.save(key, count)
    case Get(key) => sender() ! Entry(key, db.load(key).getOrElse(0L))
  }
}

object DummyDB {
  import Storage.StorageException
  private var db = Map[String, Long]()

  @throws(classOf[StorageException])
  def save(key: String, value: Long): Unit = synchronized {
    if (11 <= value && value <= 14) {
      throw new StorageException("Simulated store failure " + value)
    }
    db += (key -> value)
  }

  @throws(classOf[StorageException])
  def load(key: String): Option[Long] = synchronized {
    db.get(key)
  }
}
