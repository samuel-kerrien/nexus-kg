package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.{Clock, Instant, ZoneId}

import cats.data.{EitherT, OptionT}
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test
import ch.epfl.bluebrain.nexus.commons.test.io.{IOEitherValues, IOOptionValues}
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.async.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.config.{Schemas, Settings}
import ch.epfl.bluebrain.nexus.kg.resources.Event.Created
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.{KgError, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest._

import scala.concurrent.duration._

class ViewIndexerMappingSpec
    extends ActorSystemFixture("ViewIndexerMappingSpec")
    with WordSpecLike
    with Matchers
    with IOEitherValues
    with IOOptionValues
    with BeforeAndAfter
    with test.Resources
    with IdiomaticMockito
    with TestHelper {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(3 seconds, 15 milliseconds)

  private implicit val appConfig          = Settings(system).appConfig
  private implicit val indexingConfig     = appConfig.indexing.keyValueStore
  private implicit val ioTimer: Timer[IO] = IO.timer(system.dispatcher)

  private val resources    = mock[Resources[IO]]
  private val projectCache = mock[ProjectCache[IO]]
  private val mapper       = new ViewIndexerMapping(resources, projectCache)

  before {
    Mockito.reset(resources)
    Mockito.reset(projectCache)
  }

  "An View event mapping function" when {
    implicit val clock: Clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
    val base                  = Iri.absolute("http://example.com").right.value
    val voc                   = base + "voc"
    val iri                   = base + "id"
    val subject               = base + "anonymous"
    val projectRef            = ProjectRef(genUUID)
    val id                    = Id(projectRef, iri)
    val project = Project(id.value,
                          "proj",
                          "org",
                          None,
                          base,
                          voc,
                          Map.empty,
                          projectRef.id,
                          genUUID,
                          1L,
                          deprecated = false,
                          Instant.now(clock),
                          subject,
                          Instant.now(clock),
                          subject)
    val schema = Ref(Schemas.resolverSchemaUri)

    val types = Set[AbsoluteIri](nxv.View, nxv.SparqlView)

    val json      = jsonContentOf("/view/sparqlview.json").appendContextOf(viewCtx)
    val resource  = ResourceF.simpleF(id, json, rev = 2, schema = schema, types = types)
    val resourceV = simpleV(id, json, rev = 2, schema = schema, types = types)
    val view      = View(resourceV).right.value
    val ev        = Created(id, schema, types, json, clock.instant(), Anonymous)

    "return a view" in {
      resources.fetch(id, None) shouldReturn OptionT.some(resource)
      resources.materialize(resource)(project) shouldReturn EitherT.rightT[IO, Rejection](resourceV)
      projectCache.get(projectRef) shouldReturn IO.pure(Some(project))

      mapper(ev).some shouldEqual view
    }

    "return none when the resource cannot be found" in {
      projectCache.get(projectRef) shouldReturn IO.pure(Some(project))
      resources.fetch(id, None) shouldReturn OptionT.none[IO, Resource]
      mapper(ev).ioValue shouldEqual None
    }

    "raise error when the resource cannot be materialized" in {
      resources.fetch(id, None) shouldReturn OptionT.some(resource)
      val err = IO.raiseError[Either[Rejection, ResourceV]](KgError.InternalError(""))
      resources.materialize(resource)(project) shouldReturn EitherT(err)
      projectCache.get(projectRef) shouldReturn IO.pure(Some(project))

      mapper(ev).failed[KgError.InternalError]
    }
  }
}
