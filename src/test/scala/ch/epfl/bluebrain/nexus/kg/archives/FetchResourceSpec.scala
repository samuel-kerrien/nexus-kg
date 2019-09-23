package ch.epfl.bluebrain.nexus.kg.archives

import java.nio.file.Paths
import java.time.{Clock, Instant, ZoneId}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import cats.data.EitherT
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlList, AccessControlLists, Caller, Permission}
import ch.epfl.bluebrain.nexus.kg.archives.Archive.{File, Resource}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Files, Id, Rejection, ResourceF, Resources}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.Fetch
import ch.epfl.bluebrain.nexus.kg.storage.{AkkaSource, Storage}
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path./
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.syntax._
import io.circe.{Json, Printer}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class FetchResourceSpec
    extends TestKit(ActorSystem("FetchResourceSpec"))
    with WordSpecLike
    with Matchers
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with BeforeAndAfter
    with TestHelper
    with EitherValues
    with ScalaFutures
    with OptionValues {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(5 second, 150 milliseconds)

  private implicit val mt                         = ActorMaterializer()
  private implicit val resources: Resources[Task] = mock[Resources[Task]]
  private implicit val files: Files[Task]         = mock[Files[Task]]
  private val myUser                              = User("mySubject", "myRealm")
  private val caller: Caller                      = Caller(myUser, Set(myUser, Anonymous))
  private val readCustom: Permission              = Permission.unsafe("some/read")
  private val acls                                = AccessControlLists(/ -> resourceAcls(AccessControlList(myUser -> Set(read, readCustom))))
  private val printer: Printer                    = Printer.spaces2.copy(dropNullValues = true)
  private implicit val clock                      = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

  private def fetchResource(
      implicit caller: Caller = caller,
      acls: AccessControlLists = acls
  ): FetchResource[Task, ArchiveSource] =
    FetchResource.akkaSource[Task]

  private def randomProject() = {
    val instant = Instant.EPOCH
    // format: off
    Project(genIri, genString(), genString(), None, genIri, genIri, Map.empty, genUUID, genUUID, 1L, false, instant, genIri, instant, genIri)
    // format: on
  }

  private abstract class Ctx {
    implicit val project = randomProject()
    val json             = Json.obj("text" -> Json.fromString(genString()), "number" -> Json.fromInt(genInt()))
    val ctx =
      Json.obj(
        "@context" -> Json.obj(
          "text"   -> Json.fromString("http://example.com/text"),
          "number" -> Json.fromString("http://example.com/number")
        )
      )
    val id    = genIri
    val idRes = Id(project.ref, id)
  }

  private abstract class FileCtx extends Ctx {
    val attr =
      FileAttributes("file:///tmp/file.ext", Uri.Path("some/path"), genString(), `text/plain(UTF-8)`, 10L, Digest.empty)
    val storage: Storage =
      DiskStorage(project.ref, genIri, 1L, false, true, genString(), Paths.get("some"), readCustom, write, 10L)
  }

  before {
    Mockito.reset(resources, files)
  }

  "Fetching content from a ResourceDescription" should {

    "succeed on resource with original payload" in new Ctx {
      val somePath    = Path.rootless("some/path").right.value
      val description = Resource(id, project, None, None, originalSource = true, Some(somePath))
      resources.fetchSource(idRes) shouldReturn EitherT.rightT[Task, Rejection](json)
      val fetch                                  = fetchResource()
      val ArchiveSource(bytes, rPath, _, source) = fetch(description).value.runToFuture.futureValue.value
      val response                               = printer.pretty(json.sortKeys(AppConfig.orderedKeys))
      rPath shouldEqual somePath.asString
      bytes.toInt shouldEqual response.size
      consume(source) shouldEqual response
    }

    "succeed on resource value" in new Ctx {
      val description = Resource(id, project, Some(1L), None, originalSource = false, None)
      val jsonWithCtx = json deepMerge ctx
      val finalJson   = jsonWithCtx deepMerge Json.obj("@id" -> Json.fromString(id.asString))
      // format: off
      val resourceValue = Value(jsonWithCtx, ctx.contextValue, jsonWithCtx.deepMerge(finalJson).asGraph(IriNode(id)).right.value)
      // format: on
      val resourceV = ResourceF.simpleV(idRes, resourceValue)

      resources.fetch(Id(description.project.ref, id), 1L) shouldReturn EitherT.rightT[Task, Rejection](resourceV)
      val fetch                                  = fetchResource()
      val ArchiveSource(bytes, rPath, _, source) = fetch(description).value.runToFuture.futureValue.value
      val response                               = printer.pretty(finalJson.addContext(resourceCtxUri).sortKeys(AppConfig.orderedKeys))
      rPath shouldEqual Iri.Path.rootless(s"${project.show}/${urlEncode(id.asString)}.json").right.value.pctEncoded
      bytes.toInt shouldEqual response.size
      consume(source) shouldEqual response
    }

    "return none on resource when caller has no read permissions" in new Ctx {
      val description = Resource(id, project, None, None, originalSource = true, None)
      val acls =
        AccessControlLists(Path(s"/${genString()}").right.value -> resourceAcls(AccessControlList(myUser -> Set(read))))
      resources.fetchSource(idRes) shouldReturn EitherT.rightT[Task, Rejection](json)
      val fetch = fetchResource(acls = acls)
      fetch(description).value.runToFuture.futureValue shouldEqual None
    }

    "return none when resource does not exist" in new Ctx {
      val description = Resource(id, project, None, None, originalSource = true, None)
      resources.fetchSource(idRes) shouldReturn EitherT.leftT[Task, Json](notFound(id.ref))
      val fetch = fetchResource()
      fetch(description).value.runToFuture.futureValue shouldEqual None
    }

    "succeed on file" in new FileCtx {
      val description = File(id, project, None, Some("mytag"), None)
      val content     = List.fill(100)(genString()).mkString("\n")
      files.fetch[AkkaSource](eqTo(idRes), eqTo("mytag"))(any[Fetch[Task, AkkaSource]]) shouldReturn
        EitherT.rightT[Task, Rejection]((storage, attr, produce(content)))
      val fetch                                  = fetchResource()
      val ArchiveSource(bytes, rPath, _, source) = fetch(description).value.runToFuture.futureValue.value
      rPath shouldEqual s"${project.show}/${attr.filename}"
      bytes shouldEqual attr.bytes
      consume(source) shouldEqual content
    }

    "return none on file when caller has no read permissions" in new FileCtx {
      val description = File(id, project, None, None, None)
      val acls        = AccessControlLists(/ -> resourceAcls(AccessControlList(myUser -> Set(read))))
      files.fetch[AkkaSource](eqTo(idRes))(any[Fetch[Task, AkkaSource]]) shouldReturn
        EitherT.rightT[Task, Rejection]((storage, attr, produce(genString())))
      val fetch = fetchResource(acls = acls)
      fetch(description).value.runToFuture.futureValue shouldEqual None
    }

    "return none on file when file does not exists" in new FileCtx {
      val description = File(id, project, None, None, None)
      files.fetch[AkkaSource](eqTo(idRes))(any[Fetch[Task, AkkaSource]]) shouldReturn
        EitherT.leftT[Task, (Storage, FileAttributes, AkkaSource)](notFound(id.ref))
      val fetch = fetchResource()
      fetch(description).value.runToFuture.futureValue shouldEqual None
    }
  }
}
