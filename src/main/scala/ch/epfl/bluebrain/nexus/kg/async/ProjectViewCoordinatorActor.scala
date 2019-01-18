package ch.epfl.bluebrain.nexus.kg.async

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Stash}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.stream.ActorMaterializer
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.{withUnmarshaller, UntypedHttpClient}
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinatorActor.Msg._
import ch.epfl.bluebrain.nexus.kg.async.ViewCache.RevisionedViews
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticView, SingleView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.indexing.{ElasticIndexer, SparqlIndexer, View}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{ProjectRef, Resources}
import ch.epfl.bluebrain.nexus.service.indexer.cache.KeyValueStoreSubscriber.KeyValueStoreChange._
import ch.epfl.bluebrain.nexus.service.indexer.cache.KeyValueStoreSubscriber.KeyValueStoreChanges
import ch.epfl.bluebrain.nexus.service.indexer.cache.OnKeyValueStoreChange
import ch.epfl.bluebrain.nexus.service.indexer.retryer.RetryStrategy
import ch.epfl.bluebrain.nexus.service.indexer.retryer.RetryStrategy.Backoff
import ch.epfl.bluebrain.nexus.service.indexer.stream.StreamCoordinator.{Stop => StreamCoordinatorStop}
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.jena.query.ResultSet
import shapeless.TypeCase
import ch.epfl.bluebrain.nexus.service.indexer.retryer.syntax._

import scala.collection.immutable.Set
import scala.collection.mutable
import scala.concurrent.Future

import scala.concurrent.duration._

/**
  * Coordinator backed by akka actor which runs the views' streams inside the provided project
  */
private abstract class ProjectViewCoordinatorActor(viewCache: ViewCache[Task])
    extends Actor
    with Stash
    with ActorLogging {

  private val children = mutable.Map.empty[SingleView, ActorRef]

  def receive: Receive = {
    case Start(_, project, views) =>
      log.debug("Started coordinator for project '{}' with initial views '{}'", project.projectLabel.show, views)
      context.become(initialized(project))
      viewCache.subscribe(onChange(project.ref))
      children ++= views.map(view => view -> startActor(view, project))
      unstashAll()
    case other =>
      log.debug("Received non Start message '{}', stashing until the actor is initialized", other)
      stash()
  }

  /**
    * Triggered in order to build an indexer actor for a provided view
    *
    * @param view    the view from where to create the indexer actor
    * @param project the project of the current coordinator
    * @return the actor reference
    */
  def startActor(view: SingleView, project: Project): ActorRef

  /**
    * Triggered once an indexer actor has been stopped to clean up the indices
    *
    * @param view    the view linked to the indexer actor
    * @param project the project of the current coordinator
    */
  def deleteViewIndices(view: SingleView, project: Project): Task[Unit]

  /**
    * Triggered when a change to key value store occurs.
    *
    * @param projectRef the project unique reference
    */
  def onChange(projectRef: ProjectRef): OnKeyValueStoreChange[UUID, RevisionedViews]

  private def stopActor(ref: ActorRef): Unit = {
    ref ! StreamCoordinatorStop
    context.stop(ref)

  }

  def initialized(project: Project): Receive = {
    def stopView(v: SingleView, ref: ActorRef, deleteIndices: Boolean = true) = {
      stopActor(ref)
      children -= v
      if (deleteIndices) deleteViewIndices(v, project).runToFuture else Future.unit
    }

    def startView(view: SingleView) = {
      val ref = startActor(view, project)
      children += view -> ref
    }

    {
      case ViewsChanges(_, views) =>
        views.map {
          case view if !children.keySet.exists(_.id == view.id) => startView(view)
          case view: ElasticView =>
            children
              .collectFirst {
                case (v: ElasticView, ref) if v.id == view.id && view != v.copy(rev = view.rev) => v -> ref
              }
              .foreach {
                case (oldView, ref) =>
                  startView(view)
                  stopView(oldView, ref)
              }
          case _ =>
        }

        val toRemove = children.filterNot { case (v, _) => views.exists(_.id == v.id) }
        toRemove.foreach { case (v, ref) => stopView(v, ref) }

      case ProjectChanges(_, newProject) =>
        val _ = Future.sequence(children.map { case (view, ref) => stopView(view, ref).map(_ => view) }).map { views =>
          context.become(initialized(newProject))
          self ! ViewsChanges(project.uuid, views.toSet)
        }
      case Stop(_) =>
        children.foreach { case (view, ref) => stopView(view, ref, deleteIndices = false) }
    }
  }

}

object ProjectViewCoordinatorActor {

  private[async] sealed trait Msg {

    /**
      * @return the project unique identifier
      */
    def uuid: UUID
  }
  private[async] object Msg {

    final case class Start(uuid: UUID, project: Project, views: Set[SingleView]) extends Msg
    final case class Stop(uuid: UUID)                                            extends Msg
    final case class ViewsChanges(uuid: UUID, views: Set[SingleView])            extends Msg
    final case class ProjectChanges(uuid: UUID, project: Project)                extends Msg

  }

  private[async] def shardExtractor(shards: Int): ExtractShardId = {
    case msg: Msg                    => math.abs(msg.uuid.hashCode) % shards toString
    case ShardRegion.StartEntity(id) => (id.hashCode                % shards) toString
  }

  private[async] val entityExtractor: ExtractEntityId = {
    case msg: Msg => (msg.uuid.toString, msg)
  }

  /**
    * Starts the ProjectViewCoordinator shard that coordinates the running views' streams inside the provided project
    *
    * @param resources        the resources operations
    * @param viewCache        the view Cache
    * @param shardingSettings the sharding settings
    * @param shards           the number of shards to use
    */
  final def start(resources: Resources[Task],
                  viewCache: ViewCache[Task],
                  shardingSettings: Option[ClusterShardingSettings],
                  shards: Int)(implicit esClient: ElasticClient[Task],
                               config: AppConfig,
                               mt: ActorMaterializer,
                               ul: UntypedHttpClient[Task],
                               ucl: HttpClient[Task, ResultSet],
                               as: ActorSystem): ActorRef = {

    val props = {
      Props(
        new ProjectViewCoordinatorActor(viewCache) {
          private implicit val strategy: RetryStrategy = Backoff(1 minute, 0.2)

          private val sparql                                      = config.sparql
          private implicit val jsonClient: HttpClient[Task, Json] = withUnmarshaller[Task, Json]

          override def startActor(view: SingleView, project: Project): ActorRef =
            view match {
              case v: ElasticView => ElasticIndexer.start(v, resources, project)
              case v: SparqlView  => SparqlIndexer.start(v, resources, project)
            }

          override def deleteViewIndices(view: SingleView, project: Project): Task[Unit] = view match {
            case v: ElasticView =>
              log.info("ElasticView index '{}' is removed from project '{}'", v.index, project.projectLabel.show)
              esClient.deleteIndex(v.index).retryWhenNot({ case true => () }, 10)
            case _: SparqlView =>
              log.info("Blazegraph keyspace '{}' is removed from project '{}'", view.name, project.projectLabel.show)
              val client = BlazegraphClient[Task](sparql.base, view.name, sparql.akkaCredentials)
              client.deleteNamespace.retryWhenNot({ case true => () }, 10)
          }

          override def onChange(projectRef: ProjectRef): OnKeyValueStoreChange[UUID, RevisionedViews] =
            onViewChange(projectRef, self)

        }
      )
    }
    start(props, shardingSettings, shards)
  }

  private[async] final def start(props: Props, shardingSettings: Option[ClusterShardingSettings], shards: Int)(
      implicit as: ActorSystem): ActorRef = {

    val settings = shardingSettings.getOrElse(ClusterShardingSettings(as)).withRememberEntities(true)
    ClusterSharding(as).start("project-view-coordinator", props, settings, entityExtractor, shardExtractor(shards))
  }

  private[async] def onViewChange(projectRef: ProjectRef,
                                  actorRef: ActorRef): OnKeyValueStoreChange[UUID, RevisionedViews] =
    new OnKeyValueStoreChange[UUID, RevisionedViews] {

      private def singleViews(values: Set[View]): Set[SingleView] = values.collect { case v: SingleView => v }
      private val SetView                                         = TypeCase[RevisionedViews]
      private val projectUuid                                     = projectRef.id

      override def apply(onChange: KeyValueStoreChanges[UUID, RevisionedViews]): Unit = {
        val views = onChange.values.foldLeft(Set.empty[SingleView]) {
          case (acc, ValueAdded(`projectUuid`, SetView(revValue)))    => acc ++ singleViews(revValue.value)
          case (acc, ValueModified(`projectUuid`, SetView(revValue))) => acc ++ singleViews(revValue.value)
          case (acc, _)                                               => acc
        }
        if (views.nonEmpty) actorRef ! ViewsChanges(projectUuid, views)

      }
    }
}