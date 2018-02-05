package ch.epfl.bluebrain.nexus.kg.indexing.query.builder

import java.util.regex.Pattern

import akka.http.scaladsl.model.Uri
import cats.instances.string._
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity.{Anonymous, GroupRef, UserRef}
import ch.epfl.bluebrain.nexus.commons.iam.identity.IdentityId
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.commons.types.search.{Pagination, Sort, SortList}
import ch.epfl.bluebrain.nexus.kg.core.{ConfiguredQualifier, Qualifier}
import ch.epfl.bluebrain.nexus.kg.core.domains.DomainId
import ch.epfl.bluebrain.nexus.kg.core.instances.InstanceId
import ch.epfl.bluebrain.nexus.kg.core.organizations.OrgId
import ch.epfl.bluebrain.nexus.kg.core.schemas.{SchemaId, SchemaName}
import ch.epfl.bluebrain.nexus.kg.core.queries.filtering.Expr.NoopExpr
import ch.epfl.bluebrain.nexus.kg.core.queries.filtering.FilteringSettings
import ch.epfl.bluebrain.nexus.kg.indexing.query.QuerySettings
import ch.epfl.bluebrain.nexus.kg.indexing.query.SearchVocab.PrefixUri._
import ch.epfl.bluebrain.nexus.kg.core.ConfiguredQualifier
import ch.epfl.bluebrain.nexus.kg.core.queries.filtering.Filter
import io.circe.Json
import org.scalatest.{EitherValues, Matchers, WordSpecLike}
import ch.epfl.bluebrain.nexus.kg.indexing.query.builder.FilteredQuerySpec._

class FilteredQuerySpec extends WordSpecLike with Matchers with Resources with EitherValues {

  private val base              = "http://localhost/v0"
  private val nexusBaseVoc: Uri = s"https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/"
  private val replacements =
    Map(Pattern.quote("{{base-uri}}") -> base, Pattern.quote("{{vocab}}") -> nexusBaseVoc.toString())
  private val context                    = jsonContentOf("/schemas/nexus/core/search/search_expanded.json", replacements)
  private implicit val filteringSettings = FilteringSettings(nexusBaseVoc, context)
  private implicit val qSettings =
    QuerySettings(Pagination(0, 10), 10, "index", nexusBaseVoc, s"$base", s"$base/acls/graphs")

  private val prov                                                  = Uri("http://www.w3.org/ns/prov#")
  private val rdf                                                   = Uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
  private val bbpprod                                               = Uri(s"$base/voc/bbp/productionentity/core/")
  private val bbpagent                                              = Uri(s"$base/voc/bbp/agent/core/")
  private implicit val stringQualifier: ConfiguredQualifier[String] = Qualifier.configured[String](nexusBaseVoc)
  private val identities = Set(
    UserRef(IdentityId("http://localhost/prefix/realms/BBP/users/alice")),
    GroupRef(IdentityId("http://localhost/prefix/realms/BBP/groups/group1")),
    Anonymous(IdentityId("http://localhost/prefix/anonymous"))
  )

  "A FilteredQuery" should {
    val pagination = Pagination(13, 17)

    "build the appropriate SPARQL query" when {

      "using a noop filter expression" in {
        val expected =
          s"""
             |PREFIX bds: <${bdsUri.toString()}>
             |SELECT DISTINCT ?total ?s
             |WITH {
             |  SELECT DISTINCT ?s
             |  WHERE {
             |
             |?s ?p ?o .
             |
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/organization> ?orgId
             |GRAPH <${qSettings.aclGraph}> { {<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>}}
             |  }
             |
             |} AS %resultSet
             |WHERE {
             |  {
             |    SELECT (COUNT(DISTINCT ?s) AS ?total)
             |    WHERE { INCLUDE %resultSet }
             |  }
             |  UNION
             |  {
             |    SELECT *
             |    WHERE { INCLUDE %resultSet }
             |    ORDER BY ?s
             |    LIMIT 17
             |    OFFSET 13
             |  }
             |}
             |""".stripMargin
        val result = FilteredQuery[DomainId](Filter(NoopExpr), pagination, identities = identities)
        result shouldEqual expected
      }

      "using a filter" in {
        val json =
          jsonContentOf("/query/builder/filter-only.json", replacements)
        val (filterJson, cxtJson) = contextAndFilter(json)
        implicit val _            = Filter.filterDecoder(cxtJson)
        val filter                = filterJson.as[Filter].right.value
        val expectedWhere =
          s"""
             |?s <${nexusBaseVoc}schema>/<${nexusBaseVoc}schemaGroup> <http://localhost/v0/bbp/experiment/subject> .
             |?s <${prov}wasDerivedFrom> <http://localhost/v0/bbp/experiment/subject/v0.1.0/073b4529-83a8-4776-a5a7-676624bfad90> .
             |?s <${nexusBaseVoc}rev> ?var_1 .
             |FILTER ( ?var_1 <= 5 )
             |
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_2 . }
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_3 . }
             |FILTER ( ?var_2 = "v1.0.0" || ?var_3 = "v1.0.1" )
             |
             |?s <${nexusBaseVoc}deprecated> ?var_4 .
             |?s <${rdf}type> ?var_5 .
             |FILTER ( ?var_4 != false && ?var_5 IN (<${prov}Entity>, <${bbpprod}Circuit>) )
             |
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER NOT EXISTS {
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER ( ?var_6 = "v1.0.2" || ?var_7 <= 2 )
             |}
             |
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_8 . }
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_9 . }
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |FILTER NOT EXISTS {
             |?s <${prov}wasAttributedTo> ?var_8 .
             |?s <${prov}wasAttributedTo> ?var_9 .
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |}
             |
             |OPTIONAL{?s <http://localhost/v0/createdAtTime> ?sort0}
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/domain> ?domainId .
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/organization> ?orgId .
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/schema> ?schemaId .
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/schema>/<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/schemaGroup> ?schemaGroupId
             |GRAPH <${qSettings.aclGraph}> { {<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?schemaId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?schemaGroupId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?schemaId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?schemaGroupId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?schemaId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?schemaGroupId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>}}""".stripMargin
        val expected =
          s"""
             |PREFIX bds: <${bdsUri.toString()}>
             |SELECT DISTINCT ?total ?s
             |WITH {
             |  SELECT DISTINCT ?s ?sort0
             |  WHERE {
             |
             |$expectedWhere
             |  }
             |
             |} AS %resultSet
             |WHERE {
             |  {
             |    SELECT (COUNT(DISTINCT ?s) AS ?total)
             |    WHERE { INCLUDE %resultSet }
             |  }
             |  UNION
             |  {
             |    SELECT *
             |    WHERE { INCLUDE %resultSet }
             |    ORDER BY ASC(?sort0)
             |    LIMIT 17
             |    OFFSET 13
             |  }
             |}
             |""".stripMargin
        val result = FilteredQuery[InstanceId](filter,
                                               pagination,
                                               identities = identities,
                                               None,
                                               SortList(List(Sort(s"$base/createdAtTime"))))
        result shouldEqual expected
      }

      "using a filter with a term" in {
        val json =
          jsonContentOf("/query/builder/filter-only.json", replacements)
        val (filterJson, cxtJson) = contextAndFilter(json)
        implicit val _            = Filter.filterDecoder(cxtJson)
        val filter                = filterJson.as[Filter].right.value
        val term                  = "subject"

        val expectedWhere =
          s"""
             |?s ?matchedProperty ?matchedValue .
             |?matchedValue bds:search "$term" .
             |?matchedValue bds:relevance ?rsv .
             |?matchedValue bds:rank ?pos .
             |FILTER ( !isBlank(?s) )
             |
             |?s <${nexusBaseVoc}schema>/<${nexusBaseVoc}schemaGroup> <http://localhost/v0/bbp/experiment/subject> .
             |?s <${prov}wasDerivedFrom> <http://localhost/v0/bbp/experiment/subject/v0.1.0/073b4529-83a8-4776-a5a7-676624bfad90> .
             |?s <${nexusBaseVoc}rev> ?var_1 .
             |FILTER ( ?var_1 <= 5 )
             |
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_2 . }
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_3 . }
             |FILTER ( ?var_2 = "v1.0.0" || ?var_3 = "v1.0.1" )
             |
             |?s <${nexusBaseVoc}deprecated> ?var_4 .
             |?s <${rdf}type> ?var_5 .
             |FILTER ( ?var_4 != false && ?var_5 IN (<${prov}Entity>, <${bbpprod}Circuit>) )
             |
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER NOT EXISTS {
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER ( ?var_6 = "v1.0.2" || ?var_7 <= 2 )
             |}
             |
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_8 . }
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_9 . }
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |FILTER NOT EXISTS {
             |?s <${prov}wasAttributedTo> ?var_8 .
             |?s <${prov}wasAttributedTo> ?var_9 .
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |}
             |
             |
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/schemaGroup> ?schemaGroupId .
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/domain> ?domainId .
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/organization> ?orgId
             |GRAPH <${qSettings.aclGraph}> { {<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?schemaGroupId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?schemaGroupId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?schemaGroupId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>}}""".stripMargin.trim
        val expected =
          s"""
             |PREFIX bds: <${bdsUri.toString()}>
             |SELECT DISTINCT ?total ?s ?maxscore ?score ?rank
             |WITH {
             |  SELECT DISTINCT ?s  (max(?rsv) AS ?score) (max(?pos) AS ?rank)
             |  WHERE {
             |$expectedWhere
             |  }
             |GROUP BY ?s
             |} AS %resultSet
             |WHERE {
             |  {
             |    SELECT (COUNT(DISTINCT ?s) AS ?total) (max(?score) AS ?maxscore)
             |    WHERE { INCLUDE %resultSet }
             |  }
             |  UNION
             |  {
             |    SELECT *
             |    WHERE { INCLUDE %resultSet }
             |    ORDER BY ?s
             |    LIMIT 17
             |    OFFSET 13
             |  }
             |}
             |ORDER BY DESC(?score)""".stripMargin
        val result = FilteredQuery[SchemaId](filter, pagination, identities, Some("subject"))
        result shouldEqual expected
      }

      "selecting the outgoing links" in {
        val json =
          jsonContentOf("/query/builder/filter-only.json", replacements)
        val (filterJson, cxtJson) = contextAndFilter(json)
        implicit val _            = Filter.filterDecoder(cxtJson)
        val thisId =
          Uri(s"http://localhost/v0/bbp/experiment/subject/v0.1.0/theid")
        val targetFilter = filterJson.as[Filter].right.value
        val expectedWhere =
          s"""
             |?ss ?p ?s .
             |FILTER ( ?ss = <${thisId.toString}> )
             |
             |?s <${nexusBaseVoc}schema>/<${nexusBaseVoc}schemaGroup> <http://localhost/v0/bbp/experiment/subject> .
             |?s <${prov}wasDerivedFrom> <http://localhost/v0/bbp/experiment/subject/v0.1.0/073b4529-83a8-4776-a5a7-676624bfad90> .
             |?s <${nexusBaseVoc}rev> ?var_1 .
             |FILTER ( ?var_1 <= 5 )
             |
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_2 . }
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_3 . }
             |FILTER ( ?var_2 = "v1.0.0" || ?var_3 = "v1.0.1" )
             |
             |?s <${nexusBaseVoc}deprecated> ?var_4 .
             |?s <${rdf}type> ?var_5 .
             |FILTER ( ?var_4 != false && ?var_5 IN (<${prov}Entity>, <${bbpprod}Circuit>) )
             |
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER NOT EXISTS {
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER ( ?var_6 = "v1.0.2" || ?var_7 <= 2 )
             |}
             |
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_8 . }
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_9 . }
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |FILTER NOT EXISTS {
             |?s <${prov}wasAttributedTo> ?var_8 .
             |?s <${prov}wasAttributedTo> ?var_9 .
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |}
             |
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/domain> ?domainId .
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/organization> ?orgId
             |GRAPH <${qSettings.aclGraph}> { {<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?orgId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?domainId <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>}}""".stripMargin.trim
        val expected =
          s"""
             |PREFIX bds: <${bdsUri.toString()}>
             |SELECT DISTINCT ?total ?s
             |WITH {
             |  SELECT DISTINCT ?s
             |  WHERE {
             |
             |$expectedWhere
             |  }
             |
             |} AS %resultSet
             |WHERE {
             |  {
             |    SELECT (COUNT(DISTINCT ?s) AS ?total)
             |    WHERE { INCLUDE %resultSet }
             |  }
             |  UNION
             |  {
             |    SELECT *
             |    WHERE { INCLUDE %resultSet }
             |    ORDER BY ?s
             |    LIMIT 17
             |    OFFSET 13
             |  }
             |}
             |""".stripMargin
        val result = FilteredQuery.outgoing[SchemaName](thisId, targetFilter, pagination, identities = identities)
        result shouldEqual expected
      }

      "selecting the incoming links" in {
        val json =
          jsonContentOf("/query/builder/filter-only.json", replacements)
        val (filterJson, cxtJson) = contextAndFilter(json)
        implicit val _            = Filter.filterDecoder(cxtJson)
        val thisId =
          Uri(s"http://localhost/v0/bbp/experiment/subject/v0.1.0/theid")
        val targetFilter = filterJson.as[Filter].right.value
        val expectedWhere =
          s"""
             |?s ?p ?o .
             |FILTER ( ?o = <$thisId> )
             |
             |?s <${nexusBaseVoc}schema>/<${nexusBaseVoc}schemaGroup> <http://localhost/v0/bbp/experiment/subject> .
             |?s <${prov}wasDerivedFrom> <http://localhost/v0/bbp/experiment/subject/v0.1.0/073b4529-83a8-4776-a5a7-676624bfad90> .
             |?s <${nexusBaseVoc}rev> ?var_1 .
             |FILTER ( ?var_1 <= 5 )
             |
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_2 . }
             |OPTIONAL { ?s <${nexusBaseVoc}version> ?var_3 . }
             |FILTER ( ?var_2 = "v1.0.0" || ?var_3 = "v1.0.1" )
             |
             |?s <${nexusBaseVoc}deprecated> ?var_4 .
             |?s <${rdf}type> ?var_5 .
             |FILTER ( ?var_4 != false && ?var_5 IN (<${prov}Entity>, <${bbpprod}Circuit>) )
             |
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER NOT EXISTS {
             |?s <${nexusBaseVoc}version> ?var_6 .
             |?s <${nexusBaseVoc}rev> ?var_7 .
             |FILTER ( ?var_6 = "v1.0.2" || ?var_7 <= 2 )
             |}
             |
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_8 . }
             |OPTIONAL { ?s <${prov}wasAttributedTo> ?var_9 . }
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |FILTER NOT EXISTS {
             |?s <${prov}wasAttributedTo> ?var_8 .
             |?s <${prov}wasAttributedTo> ?var_9 .
             |FILTER ( ?var_8 = <${bbpagent}sy> || ?var_9 = <${bbpagent}dmontero> )
             |}
             |
             |
             |GRAPH <${qSettings.aclGraph}> { {<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/users/alice>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/realms/BBP/groups/group1>
             |}UNION{
             |<https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/root> <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>
             |}UNION{
             |?s <https://bbp-nexus.epfl.ch/vocabs/nexus/core/terms/v0.1.0/read> <http://localhost/prefix/anonymous>}}""".stripMargin.trim
        val expected =
          s"""
             |PREFIX bds: <${bdsUri.toString()}>
             |SELECT DISTINCT ?total ?s
             |WITH {
             |  SELECT DISTINCT ?s
             |  WHERE {
             |
             |$expectedWhere
             |  }
             |
             |} AS %resultSet
             |WHERE {
             |  {
             |    SELECT (COUNT(DISTINCT ?s) AS ?total)
             |    WHERE { INCLUDE %resultSet }
             |  }
             |  UNION
             |  {
             |    SELECT *
             |    WHERE { INCLUDE %resultSet }
             |    ORDER BY ?s
             |    LIMIT 17
             |    OFFSET 13
             |  }
             |}
             |""".stripMargin
        val result = FilteredQuery.incoming[OrgId](thisId, targetFilter, pagination, identities = identities)
        result shouldEqual expected
      }
    }
  }

}

object FilteredQuerySpec {
  private[builder] def contextAndFilter(filterWithContext: Json)(
      implicit filteringSettings: FilteringSettings): (Json, Json) = {
    val filter = filterWithContext.hcursor.get[Json]("filter").toOption.getOrElse(Json.obj())
    val ctx = filterWithContext.hcursor
      .get[Json]("@context")
      .map(_.deepMerge(filteringSettings.ctx))
      .getOrElse(filteringSettings.ctx)
    filter -> ctx
  }
}