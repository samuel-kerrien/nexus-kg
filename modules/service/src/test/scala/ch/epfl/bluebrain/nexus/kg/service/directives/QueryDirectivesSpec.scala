package ch.epfl.bluebrain.nexus.kg.service.directives

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.commons.types.search.Sort.OrderType
import ch.epfl.bluebrain.nexus.commons.types.search.{Pagination, Sort, SortList}
import ch.epfl.bluebrain.nexus.kg.indexing.IndexingVocab.PrefixMapping
import ch.epfl.bluebrain.nexus.kg.indexing.filtering.FilteringSettings
import ch.epfl.bluebrain.nexus.kg.indexing.query.QuerySettings
import ch.epfl.bluebrain.nexus.kg.service.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.service.prefixes.ErrorContext
import ch.epfl.bluebrain.nexus.kg.service.routes.{ExceptionHandling, RejectionHandling}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.scalatest.{Matchers, WordSpecLike}

class QueryDirectivesSpec extends WordSpecLike with ScalatestRouteTest with Matchers {

  private case class Response(pagination: Pagination,
                              qOpt: Option[String],
                              deprecatedOpt: Option[Boolean],
                              fields: Set[String],
                              sort: SortList)

  private def route(implicit qs: QuerySettings, fs: FilteringSettings) = {
    (handleExceptions(ExceptionHandling.exceptionHandler(ErrorContext)) & handleRejections(
      RejectionHandling.rejectionHandler(ErrorContext))) {
      (get & searchQueryParams) { (pagination, _, qOpt, deprecatedOpt, fields, sort) =>
        complete(Response(pagination, qOpt, deprecatedOpt, fields, sort))
      }
    }
  }

  "An searchQueryParams directive" should {
    val base        = "http://localhost"
    implicit val fs = FilteringSettings(s"$base/voc/nexus/core", s"$base/voc/nexus/search")
    implicit val qs = QuerySettings(Pagination(0, 20), 100, "index", fs.nexusBaseVoc, base, s"$base/acls/graph")

    "extract default page when not provided" in {
      Get("/") ~> route ~> check {
        responseAs[Response] shouldEqual Response(qs.pagination, None, None, Set.empty, SortList.Empty)
      }
    }

    "extract provided page" in {
      Get("/?from=1&size=30") ~> route ~> check {
        responseAs[Response] shouldEqual Response(Pagination(1L, 30), None, None, Set.empty, SortList.Empty)
      }
    }

    "extract 0 when size and from are negative" in {
      Get("/?from=-1&size=-30") ~> route ~> check {
        responseAs[Response] shouldEqual Response(Pagination(0L, 1), None, None, Set.empty, SortList.Empty)
      }
    }

    "extract maximum page size when provided is greater" in {
      Get("/?from=1&size=300") ~> route ~> check {
        responseAs[Response] shouldEqual Response(Pagination(1L, 100), None, None, Set.empty, SortList.Empty)
      }
    }

    "extract deprecated and q query params when provided" in {
      Get("/?deprecated=false&q=something") ~> route ~> check {
        responseAs[Response] shouldEqual Response(qs.pagination,
                                                  Some("something"),
                                                  Some(false),
                                                  Set.empty,
                                                  SortList.Empty)
      }
    }

    "extract fields, pagination, q and deprecated when provided" in {
      Get("/?deprecated=true&q=something&from=1&size=30&fields=one,two,three,,") ~> route ~> check {
        responseAs[Response] shouldEqual Response(Pagination(1L, 30),
                                                  Some("something"),
                                                  Some(true),
                                                  Set("one", "two", "three"),
                                                  SortList.Empty)
      }
    }
    "extract sort when provided" in {
      val rdfType = PrefixMapping.rdfTypeKey.replace("#", "%23")
      Get(s"/?sort=$base/createdAtTime,${rdfType},,,") ~> route ~> check {
        val expectedSort =
          SortList(List(Sort(OrderType.Asc, s"$base/createdAtTime"), Sort(OrderType.Asc, PrefixMapping.rdfTypeKey)))
        responseAs[Response] shouldEqual Response(qs.pagination, None, None, Set.empty, expectedSort)
      }
    }
  }

}
