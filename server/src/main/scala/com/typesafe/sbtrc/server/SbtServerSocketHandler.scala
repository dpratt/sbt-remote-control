package com.typesafe.sbtrc
package server

import ipc.{ MultiClientServer => IpcServer }
import sbt.protocol.{ Envelope, Request }
import java.net.ServerSocket
import java.net.SocketTimeoutException
import sbt.server.ServerRequest
import com.typesafe.sbtrc.ipc.HandshakeException

/**
 * A class that will spawn up a thread to handle client connection requests.
 *
 * TODO - We should use netty for this rather than spawning so many threads.
 */
class SbtServerSocketHandler(serverSocket: ServerSocket, msgHandler: ServerRequest => Unit) {
  private val running = new java.util.concurrent.atomic.AtomicBoolean(true)
  private val TIMEOUT_TO_DEATH: Int = 3 * 60 * 1000
  // TODO - This should be configurable.
  private val log = new FileLogger(new java.io.File(".sbtserver/connections/master.log"))

  @volatile
  private var count = 0L
  def nextCount = {
    count += 1
    count
  }
  private val clientLock = new AnyRef {}
  private val clients = collection.mutable.ArrayBuffer.empty[SbtClientHandler]

  private val thread = new Thread("sbt-server-socket-handler") {
    // TODO - Check how long we've been running without a client connected
    // and shut down the server if it's been too long.
    final override def run(): Unit = {
      // TODO - Is this the right place to do this?
      serverSocket.setSoTimeout(TIMEOUT_TO_DEATH)
      while (running.get) {
        try {
          log.log(s"Taking next connection to: ${serverSocket.getLocalPort}")
          val socket = serverSocket.accept()
          log.log(s"New client attempting to connect on port: ${socket.getPort}-${socket.getLocalPort}")
          log.log(s"  Address = ${socket.getLocalSocketAddress}")
          val server = new IpcServer(socket)
          // For ID we'll use the address of the socket...
          val id = s"client-port-${socket.getPort}-connection-${nextCount}"
          // TODO - Clear out any client we had before with this same port *OR* take over that client....
          def onClose(): Unit = {
            clientLock.synchronized(clients.remove(clients.indexWhere(_.id == id)))
            // TODO - See if we can reboot the timeout on the server socket waiting
            // for another connection.
          }
          val client = new SbtClientHandler(id, server, msgHandler, onClose)
          clientLock.synchronized {
            clients.append(client)
            log.log(s"Connected Clients: ${clients map (_.id) mkString ", "}")
          }
        } catch {
          case e: HandshakeException =>
            // For now we ignore these, as someone trying to discover if we're listening to ports
            // will connect and close before we can read the handshake.
            // We should formalize what constitutes a catastrophic failure, and make sure we
            // down the server in that instance.
            log.error(s"Handshake exception on socket: ${e.socket.getPort}", e)
          case _: InterruptedException | _: SocketTimeoutException =>
            log.log("Checking to see if clients are empty...")
            // Here we need to check to see if we should shut ourselves down.
            if (clients.isEmpty) {
              log.log("No clients connected after 3 min.  Shutting down.")
              running.set(false)
            } else {
              log.log("We have a client, continuing serving connections.")
            }
          case e: Throwable =>
            // On any other failure, we'll just down the server for now.
            log.error("Unhandled throwable, shutting down server.", e)
            running.set(false)
        }
      }
      log.log("Shutting down server socket.")
      // Cleanup clients, waiting for them to notify their users.
      clientLock.synchronized {
        clients.foreach(_.shutdown())
        clients.foreach(_.join())
      }
      // TODO - better shutdown semantics?
      System.exit(0)
    }
  }
  thread.start()

  // Tells the server to stop running.
  def stop(): Unit = running.set(false)

  // Blocks the server until we've been told to shutdown by someone.
  def join(): Unit = thread.join()
}