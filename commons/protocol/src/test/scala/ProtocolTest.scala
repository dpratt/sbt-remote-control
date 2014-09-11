/**
 *   Copyright (C) 2012 Typesafe Inc. <http://typesafe.com>
 */
import org.junit.Assert._
import org.junit._
import com.typesafe.sbtrc._
import sbt.protocol
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import play.api.libs.json._

final case class PlayStartedEvent(port: Int)
object PlayStartedEvent extends protocol.TaskEventUnapply[PlayStartedEvent] {
  implicit val format = Json.format[PlayStartedEvent]
}

class ProtocolTest {

  private def addWhatWeWereFormatting[T, U](t: T)(body: => U): U = try body
  catch {
    case e: Throwable =>
      throw new AssertionError(s"Crash formatting ${t.getClass.getName}: ${e.getMessage}", e)
  }

  @Test
  def testRawStructure(): Unit = {
    val key = protocol.AttributeKey("name", protocol.TypeInfo("java.lang.String"))
    val build = new java.net.URI("file:///test/project")
    val scope = protocol.SbtScope(project = Some(
      protocol.ProjectReference(build, "test")))
    val scopedKey = protocol.ScopedKey(key, scope)
    val keyFilter = protocol.KeyFilter(Some("test"), Some("test2"), Some("test3"))
    val buildStructure = protocol.MinimalBuildStructure(
      builds = Seq(build),
      projects = Seq(protocol.MinimalProjectStructure(scope.project.get, Seq("com.foo.Plugin"))))

    val serializations = protocol.DynamicSerialization.defaultSerializations

    val specifics = Seq(
      // Requests
      protocol.KillServerRequest(),
      protocol.ReadLineRequest(42, "HI", true),
      protocol.ReadLineResponse(Some("line")),
      protocol.ConfirmRequest(43, "msg"),
      protocol.ConfirmResponse(true),
      protocol.ReceivedResponse(),
      protocol.RequestCompleted(),
      protocol.CommandCompletionsRequest("He", 2),
      protocol.CommandCompletionsResponse(Set(protocol.Completion("llo", "Hello", true))),
      protocol.ListenToEvents(),
      protocol.ListenToBuildChange(),
      protocol.ExecutionRequest("test command string"),
      protocol.ListenToValue(scopedKey),
      protocol.CancelExecutionRequest(1),
      // Responses
      protocol.ErrorResponse("ZOMG"),
      protocol.CancelExecutionResponse(false),
      // Events
      // TODO - CompilationFailure
      protocol.TaskStarted(47, 1, Some(scopedKey)),
      protocol.TaskFinished(48, 1, Some(scopedKey), true),
      protocol.TaskStarted(47, 1, None),
      protocol.TaskFinished(48, 1, None, true),
      protocol.TaskStarted(49, 2, Some(scopedKey)),
      protocol.TaskFinished(50, 2, Some(scopedKey), true),
      protocol.BuildStructureChanged(buildStructure),
      // equals() doesn't work on Exception so we can't actually check this easily
      //protocol.ValueChanged(scopedKey, protocol.TaskFailure("O NOES", protocol.BuildValue(new Exception("Exploded"), serializations))),
      protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue("HI", serializations))),
      protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue(42, serializations))),
      protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue(43L, serializations))),
      protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue(true, serializations))),
      // TODO make Unit work ?
      // protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue(()))),
      protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue(0.0, serializations))),
      protocol.ValueChanged(scopedKey, protocol.TaskSuccess(protocol.BuildValue(0.0f, serializations))),
      protocol.TaskLogEvent(1, protocol.LogStdOut("Hello, world")),
      protocol.CoreLogEvent(protocol.LogStdOut("Hello, world")),
      protocol.TaskEvent(4, protocol.TestEvent("name", None, protocol.TestPassed, None, 0)),
      protocol.ExecutionWaiting(41, "foo", protocol.ClientInfo(java.util.UUID.randomUUID.toString, "foo", "FOO")),
      protocol.ExecutionStarting(56),
      protocol.ExecutionFailure(42),
      protocol.ExecutionSuccess(44),
      protocol.TaskLogEvent(2, protocol.LogMessage(protocol.LogMessage.INFO, "TEST")),
      protocol.TaskLogEvent(3, protocol.LogMessage(protocol.LogMessage.ERROR, "TEST")),
      protocol.TaskLogEvent(4, protocol.LogMessage(protocol.LogMessage.WARN, "TEST")),
      protocol.TaskLogEvent(5, protocol.LogMessage(protocol.LogMessage.DEBUG, "TEST")),
      protocol.TaskLogEvent(6, protocol.LogStdErr("TEST")),
      protocol.TaskLogEvent(7, protocol.LogStdOut("TEST2")),
      protocol.TaskEvent(8, PlayStartedEvent(port = 10)),
      protocol.BackgroundJobStarted(9, protocol.BackgroundJobInfo(id = 67, humanReadableName = "foojob", spawningTask = scopedKey)),
      protocol.BackgroundJobFinished(9, 67),
      protocol.BackgroundJobEvent(67, PlayStartedEvent(port = 10)))

    for (s <- specifics) {
      import protocol.WireProtocol.{ fromRaw, toRaw }
      val roundtrippedOption = addWhatWeWereFormatting(s)(fromRaw(toRaw(s), protocol.DynamicSerialization.defaultSerializations))
      assertEquals(s"Failed to serialize:\n$s\n\n${toRaw(s)}\n\n", Some(s), roundtrippedOption)
    }

    protocol.TaskEvent(4, protocol.TestEvent("name", Some("foo"), protocol.TestPassed, Some("bar"), 0)) match {
      case protocol.TestEvent(taskId, test) =>
        assertEquals(4, taskId)
        assertEquals("name", test.name)
        assertEquals(Some("foo"), test.description)
        assertEquals(Some("bar"), test.error)
    }

    // check TaskEvent unpacking using TaskEventUnapply
    protocol.TaskEvent(8, PlayStartedEvent(port = 10)) match {
      case PlayStartedEvent(taskId, playStarted) =>
        assertEquals(8, taskId)
        assertEquals(10, playStarted.port)
      case other => throw new AssertionError("nobody expects PlayStartedEvent to be " + other)
    }
  }

  private trait Roundtripper {
    def roundtrip[T: Manifest](t: T): Unit
  }

  private def roundtripTest(roundtripper: Roundtripper): Unit = {
    val ds = protocol.DynamicSerialization.defaultSerializations

    def roundtrip[T: Manifest](t: T): Unit = roundtripper.roundtrip(t)

    val key = protocol.AttributeKey("name", protocol.TypeInfo("java.lang.String"))
    val build = new java.net.URI("file:///test/project")
    val projectRef = protocol.ProjectReference(build, "test")
    val scope = protocol.SbtScope(project = Some(projectRef))
    val scopedKey = protocol.ScopedKey(key, scope)
    val keyFilter = protocol.KeyFilter(Some("test"), Some("test2"), Some("test3"))
    val buildStructure = protocol.MinimalBuildStructure(
      builds = Seq(build),
      projects = Seq(protocol.MinimalProjectStructure(scope.project.get, Seq("com.foo.Plugin"))))
    object FakePosition extends xsbti.Position {
      override def line = xsbti.Maybe.just(10)
      override def offset = xsbti.Maybe.just(11)
      override def pointer = xsbti.Maybe.just(12)
      override def pointerSpace = xsbti.Maybe.just("foo")
      override def sourcePath = xsbti.Maybe.just("foo")
      override def sourceFile = xsbti.Maybe.just(new java.io.File("bar"))
      override def lineContent = "this is some stuff on the line"
      // note, this does not have a useful equals/hashCode with other instances of Position
    }

    roundtrip("Foo")
    roundtrip(new java.io.File("/tmp"))
    roundtrip(true)
    roundtrip(false)
    roundtrip(10: Short)
    roundtrip(11)
    roundtrip(12L)
    roundtrip(13.0f)
    roundtrip(14.0)
    roundtrip(None: Option[String])
    roundtrip(Some("Foo"))
    roundtrip(Some(true))
    roundtrip(Some(10))
    roundtrip(Nil: Seq[String])
    roundtrip(Seq("Bar", "Baz"))
    roundtrip(Seq(1, 2, 3))
    roundtrip(Seq(true, false, true, true, false))
    roundtrip(key)
    roundtrip(build)
    roundtrip(projectRef)
    roundtrip(scope)
    roundtrip(scopedKey)
    roundtrip(keyFilter)
    roundtrip(buildStructure)
    roundtrip(protocol.Stamps.empty)
    roundtrip(protocol.APIs.empty)
    roundtrip(protocol.Relations.empty)
    roundtrip(protocol.SourceInfos.empty)
    roundtrip(protocol.Analysis.empty)
    roundtrip(new Exception(null, null))
    roundtrip(new Exception("fail fail fail", new RuntimeException("some cause")))
    roundtrip(new protocol.CompileFailedException("the compile failed", null,
      Seq(protocol.Problem("something", xsbti.Severity.Error, "stuff didn't go well", FakePosition))))
  }

  @Test
  def testDynamicSerialization(): Unit = {
    val ds = protocol.DynamicSerialization.defaultSerializations
    roundtripTest(new Roundtripper {
      override def roundtrip[T: Manifest](t: T): Unit = {
        val formatOption = ds.lookup(implicitly[Manifest[T]])
        formatOption map { format =>
          val json = addWhatWeWereFormatting(t)(format.writes(t))
          //System.err.println(s"${t} = ${Json.prettyPrint(json)}")
          val parsed = addWhatWeWereFormatting(t)(format.reads(json)).asOpt.getOrElse(throw new AssertionError(s"could not re-parse ${json} for ${t}"))
          (t, parsed) match {
            // Throwable has a not-very-useful equals() in this case
            case (t: Throwable, parsed: Throwable) =>
              assertEquals("round trip of message " + t.getMessage, t.getMessage, parsed.getMessage)
            case _ =>
              assertEquals("round trip of " + t, t, parsed)
          }
        } getOrElse { throw new AssertionError(s"No dynamic serialization for ${t.getClass.getName}: $t") }
      }
    })
  }

  @Test
  def testBuildValueSerialization(): Unit = {
    val serializations = protocol.DynamicSerialization.defaultSerializations
    implicit def format[T]: Format[protocol.BuildValue[T]] = protocol.BuildValue.format[T](serializations)
    def roundtripBuildValue[T: Manifest](buildValue: protocol.BuildValue[T]): Unit = {
      val json = Json.toJson(buildValue)
      //System.err.println(s"${buildValue} = ${Json.prettyPrint(json)}")
      val parsed = Json.fromJson[protocol.BuildValue[T]](json).asOpt.getOrElse(throw new AssertionError(s"Failed to parse ${buildValue} serialization ${json}"))
      val buildValueClass: Class[_] = buildValue match {
        case bv: protocol.SerializableBuildValue[T] => bv.manifest.toManifest(this.getClass.getClassLoader)
          .map(_.runtimeClass).getOrElse(throw new AssertionError("don't have class for this build value"))
        case other => throw new AssertionError("non-serializable build value $other")
      }
      // Throwable has a not-very-useful equals() in this case
      if (classOf[Throwable].isAssignableFrom(buildValueClass)) {
        val t = buildValue.value.map(_.asInstanceOf[Throwable]).get
        val p = parsed.value.map(_.asInstanceOf[Throwable]).get
        assertEquals("round trip of message " + t.getMessage, t.getMessage, p.getMessage)
      } else {
        assertEquals(buildValue, parsed)
      }
    }
    roundtripTest(new Roundtripper {
      override def roundtrip[T: Manifest](t: T): Unit = {
        roundtripBuildValue(protocol.BuildValue(t, serializations))
      }
    })
  }
}
