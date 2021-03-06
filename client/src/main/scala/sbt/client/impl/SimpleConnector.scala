
package sbt.client.impl

import sbt.impl.ipc
import sbt.client._
import java.io.File
import scala.concurrent._
import sbt.protocol._
import sbt.serialization._
import scala.util.control.NonFatal
import scala.util.{ Try, Success, Failure }
import java.io.Closeable
import java.util.concurrent.{ LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit }

// Move this somewhere shared as soon as we want to use it elsewhere
// and thus know where it would go and what package it would be private to.
private[client] object ExecutionContexts {
  /**
   * An ExecutionContext which runs only one thing at a time,
   * and runs things in the order they are received. It uses at most
   * one thread but shuts the thread down if not used lately.
   */
  implicit val serializedCachedExecutionContext: ExecutionContext = {
    val factory = new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = {
        new Thread(runnable, "serializedCachedExecutionContext")
      }
    }
    // we don't worry about ever shutting down this executor because
    // it can go down to 0 threads anyway
    val service = new ThreadPoolExecutor(
      0, // corePoolSize of 0 means we can time out threads all the way back down to 0
      1, // maxPoolSize of 1 means we only ever run one thread (i.e. we serialize everything)
      5L, TimeUnit.SECONDS, // how long to keep around a thread we aren't using
      new LinkedBlockingQueue[Runnable](), // buffer any number of items
      factory)
    ExecutionContext.fromExecutorService(service)
  }
}

// This thread is responsible for ONE attempt to connect, then we drop
// the thread (don't want to keep it around when it's typically needed
// only briefly).
// Important: closeHandler guaranteed to be called only AFTER doneHandler
private final class ConnectThread(doneHandler: Try[SbtChannel] => Unit,
  closeHandler: () => Unit,
  sleepMilliseconds: Long, configName: String, humanReadableName: String,
  directory: File, locator: SbtServerLocator) extends Thread with Closeable {

  import ExecutionContexts.serializedCachedExecutionContext

  val sleepRemaining = new java.util.concurrent.atomic.AtomicLong(sleepMilliseconds)
  @volatile var closed = false

  // we want to ALWAYS call doneHandler first and then closeHandler only if
  // we successfully opened the client and it's now been closed.
  val donePromise = Promise[SbtChannel]()
  val closedPromise = Promise[Unit]()
  donePromise.future.onComplete { maybeChannel =>
    doneHandler(maybeChannel)
    if (maybeChannel.isSuccess) {
      // wait on the client to complete closePromise. Note that it
      // may have ALREADY completed.
      closedPromise.future.onComplete(_ => closeHandler())
    } else {
      // fail closePromise since it will never happen, but it should have no listeners,
      // so this is just to be be tidy
      closedPromise.tryFailure(new RuntimeException("never opened client so not going to close"))
    }
  }

  private def adjustRemaining(block: Long => Long): Unit = {
    while (!{
      val remaining = sleepRemaining.get
      sleepRemaining.compareAndSet(remaining, block(remaining))
    }) {
      // do nuthin'
    }
  }

  override def run(): Unit = try {
    while (sleepRemaining.get > 0) {
      Thread.sleep(Math.min(200, sleepRemaining.get))
      // if this puts us below 0 it's fine
      adjustRemaining(_ - 200)
    }
    if (closed)
      throw new RuntimeException("Not reconnecting because SbtConnector was closed")

    donePromise.success(connectToSbt())
  } catch {
    case NonFatal(e) =>
      donePromise.failure(e)
  }

  def connectInNoMoreThan(ms: Long): Unit = {
    adjustRemaining(r => Math.min(r, ms))
  }

  override def close(): Unit = {
    closed = true
    adjustRemaining(_ => 0)
  }

  private[this] def connectToSbt(): SbtChannel = {
    val uri = locator.locate(directory)
    val socket = new java.net.Socket(uri.getHost, uri.getPort)
    val rawClient = new ipc.Client(socket)
    val uuid = java.util.UUID.randomUUID()
    val registerSerial = rawClient.serialGetAndIncrement()
    rawClient.sendJson[Message](RegisterClientRequest(ClientInfo(uuid.toString, configName, humanReadableName, protocolVersion = ProtocolVersion1, featureTags = Vector.empty)), registerSerial)
    Envelope(rawClient.receive()) match {
      case Envelope(_, `registerSerial`, ErrorResponse(message)) =>
        throw new RuntimeException(s"Failed to register client with sbt: ${message}")
      case Envelope(_, `registerSerial`, reply: RegisterClientResponse) =>
      // TODO extract the ServerInfo and make it available in an API somewhere
      case wtf => {
        rawClient.close()
        throw new RuntimeException(s"unexpected initial message from server was not a reply to ${registerSerial}: ${wtf}")
      }
    }
    new SimpleSbtChannel(uuid, configName, humanReadableName, rawClient, () => closedPromise.success(()))
  }
}

final class SimpleConnector(configName: String, humanReadableName: String, directory: File, locator: SbtServerLocator) extends SbtConnector {

  private sealed trait ConnectState
  // open() never called
  private final case object NotYetOpened extends ConnectState
  // close() has been called (not same as client being closed)
  private final case object Closed extends ConnectState
  // connecting thread working on a connect
  private final case class Connecting(thread: ConnectThread) extends ConnectState
  // connecting ended in success
  private final case class Open(channel: SbtChannel) extends ConnectState

  private var connectState: ConnectState = NotYetOpened

  private var listeners: List[OpenListener] = Nil

  // still retrying or have we been closed? This flag is almost the same
  // as connectState == Closed, but this boolean is what has been requested,
  // while Closed is the actual notified state. So if we've asked to close
  // but haven't gotten back the closed callback, reconnecting=false but
  // connectState != Closed.
  private var reconnecting: Boolean = true

  private final class OpenListener(onConnect: SbtChannel => Unit,
    onError: (Boolean, String) => Unit,
    ctx: ExecutionContext) {
    def emitConnected(channel: SbtChannel): Unit =
      ctx.prepare.execute(new Runnable() {
        override def toString = s"Runnable(onConnect(${channel.configName} ${channel.uuid}))"
        override def run(): Unit = {
          onConnect(channel)
        }
      })
    def emitError(reconnecting: Boolean, message: String): Unit =
      ctx.prepare.execute(new Runnable() {
        override def toString = s"Runnable(onError(reconnecting=${reconnecting}, ${message}))"
        override def run(): Unit = {
          onError(reconnecting, message)
        }
      })
  }

  private def startConnectAttempt(afterMilliseconds: Long): Unit = {
    require(connectState != Closed)
    require(reconnecting)

    val state = Connecting(new ConnectThread(onConnectionAttempt, onClientClose,
      afterMilliseconds,
      configName, humanReadableName, directory, locator))
    connectState = state
    state.thread.start()
  }

  override def open(onConnect: SbtClient => Unit, onError: (Boolean, String) => Unit)(implicit ex: ExecutionContext): Subscription = {
    openChannel(channel => onConnect(SbtClient(channel)), onError)(ex)
  }

  override def openChannel(onConnect: SbtChannel => Unit, onError: (Boolean, String) => Unit)(implicit ex: ExecutionContext): Subscription = {
    val listener = new OpenListener(onConnect, onError, ex)
    SimpleConnector.this.synchronized(listeners = listener :: listeners)
    object sub extends Subscription {
      def cancel(): Unit = {
        SimpleConnector.this.synchronized(listeners = listeners.filterNot(_ == listener))
      }
    }
    handleNewConnectSubscriber(listener)

    synchronized {
      connectState match {
        case NotYetOpened =>
          startConnectAttempt(0 /* immediate attempt */ )
        case Connecting(thread) =>
          // force immediate retry
          thread.connectInNoMoreThan(0)
        case Closed | Open(_) =>
        // nothing to do
      }
    }

    sub
  }

  private[this] def handleNewConnectSubscriber(listener: OpenListener): Unit = synchronized {
    connectState match {
      case Open(channel) => listener.emitConnected(channel)
      case Closed => listener.emitError(reconnecting = false, "Connection closed")
      case NotYetOpened | Connecting(_) => // a later event is guaranteed
    }
  }

  private def reconnectOrCloseOnError(message: String): Unit = synchronized {
    for (listener <- listeners)
      listener.emitError(reconnecting, message)

    if (reconnecting) {
      // transitions state to Connecting
      startConnectAttempt(4000 /* retry every this many milliseconds */ )
    } else {
      connectState = Closed

      // It's impossible for these to be called again
      // because we never leave the Closed state,
      // AND we just notified on the final fatal error.
      // So clear them out to allow GC - these may well capture a big
      // hunk of application functionality.
      listeners = Nil
    }
  }

  // A callback from our connecting thread when it's done; always called before
  // onClose
  private def onConnectionAttempt(result: Try[SbtChannel]): Unit = synchronized {
    connectState match {
      case Connecting(thread) =>
        thread.join()
        result match {
          case Failure(error) =>
            reconnectOrCloseOnError(error.getMessage)
          case Success(channel) =>
            connectState = Open(channel)
            for (listener <- listeners)
              listener.emitConnected(channel)
            if (!reconnecting) {
              // close() was called after the thread made the client
              // but before we received notification of the client,
              // so close the client here, which results in onClientClose
              // which then transitions us to Closed state.
              channel.close()
            }
        }
      case Closed | NotYetOpened | Open(_) =>
        throw new RuntimeException(s"State ${connectState} should have been impossible in onConnectionAttempt")
    }
  }

  // A callback from the server handling thread. This means one client
  // is closed, not that the connector is closed.
  private def onClientClose(): Unit = synchronized {
    // we shouldn't have received a close callback without first getting
    // an onConnectionAttempt with a client
    connectState match {
      case Open(client) =>
        // transition state to Connecting or Closed
        reconnectOrCloseOnError("Connection closed")
      case Closed => // nothing to do
      case NotYetOpened =>
        throw new RuntimeException("close callback should be impossible if open() never called")
      case Connecting(thread) =>
        throw new RuntimeException("should not have entered Connecting state with a client still open")
    }
  }

  def close(): Unit = synchronized {
    // We mark our desired state with this flag
    reconnecting = false

    // then we update connectState, usually async when the actual
    // closing has taken place.
    connectState match {
      // the onClientClose handler above should transition our state to Closed
      case Open(client) => client.close()
      // the onConnectionAttempt handler should transition us to Closed
      case Connecting(thread) => thread.close()
      // we can transition ourselves to closed right away
      case NotYetOpened => connectState = Closed
      // nothing to do here
      case Closed =>
    }
  }
}
