package libs
import libs.solr.scala.QueryBuilderBase
import libs.solr.scala.QueryBuilder
import libs.solr.scala.SolrClient
import libs.solr.scala.MapQueryResult
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsString
import play.api._
import play.api.mvc._
import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue
import play.api.libs.json.JsBoolean
import play.api.db.DB
import play.api.Play.current
import util.control.Breaks._

/**
 * Class Search performs many functions about information retrieval .
 * @author : Ramzi Sh. Alqrainy
 * @copyright Copyright (c) 2014
 * @version 0.1
 */
object SearchLib {

  /**
   * is a main function that handling search process.
   * @param query
   * @param request
   * @version 0.1
   */
  def get(query: String,request: Request[AnyContent]): JsObject = {
    // Construct the solr query and handling the parameters
    var queryBuilder = this.buildQuery(query, request)
    var resultsInfo = Json.obj(
      "num_of_results" -> 0,
      "results" -> List[JsString]())

    try {
      // Get Results from Solr.
      var results = queryBuilder.getResultAsMap()

      // prepare results
      resultsInfo = this.prepareResults(results, request)
    } catch {
      case e: Exception =>
        println("exception caught: " + e);
    }

    resultsInfo
  }

 

  /**
   *
   * constructQuery : constructs the query and handling the user params
   * @param  query
   * @param  countryId
   * @param  request
   */

  def buildQuery(query: String, request: Request[AnyContent]): QueryBuilder = {
    // Checking URI Parameters
    var query = request.getQueryString("query").getOrElse("*:*")
    var page = Integer.parseInt(request.getQueryString("page").getOrElse(1).toString())
    var queryOperator = request.getQueryString("q.op").getOrElse("AND").toString()
    var mm = request.getQueryString("mm").getOrElse("<NULL>").replace("\"", "")
    var resultsPerPage = Integer.parseInt(request.getQueryString("noOfResults").getOrElse(20).toString())
    var date_from = request.getQueryString("date_from").getOrElse("<NULL>")
    var date_to = request.getQueryString("date_to").getOrElse("<NULL>")
    var sort = request.getQueryString("sort").getOrElse("<NULL>")

    var client = new SolrClient("http://" + play.Play.application().configuration().getString("solr.engine.host")
      + ":" + play.Play.application().configuration().getString("solr.engine.port") + 
       play.Play.application().configuration().getString("solr.engine.indexPath") + 
        play.Play.application().configuration().getString("solr.engine.collection"))

    var offset: Int = 0
    if (!request.getQueryString("offset").isEmpty) {
      offset = Integer.parseInt(request.getQueryString("offset").getOrElse(0).toString())
    } else {
      offset = (page - 1) * resultsPerPage
    }

    ///////////////////////////////////////////////////////////////////////

    // The current time in milliseconds.
    val timestamp: Long = System.currentTimeMillis

    // Strip whitespace (or other characters) from the beginning and end of a string
    query.trim


    var queryBuilder = client.query(query)
      .setParameter("bf", "product(recip(sub(" + timestamp + ",record_posted_time),1.27e-11,0.08,0.05),1000)^50")
      .setParameter("defType", "edismax")
      .start(offset)
      .rows(resultsPerPage)

    // When you assign mm (Minimum 'Should' Match), we remove q.op
    // becuase we can't set two params to the same function
    // q.op=AND == mm=100% | q.op=OR == mm=0%
    if (!mm.equals("<NULL>")) {
      queryBuilder = queryBuilder.setParameter("mm", "100%")
    } else {
      queryBuilder = queryBuilder.setParameter("q.op", queryOperator)
    }

    if (!query.equals("*:*")) {
      queryBuilder = queryBuilder.setParameter("qf", "title^1 description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1  city_name^1 price^1")
      queryBuilder = queryBuilder.setParameter("bf", "product(recip(rord(record_posted_day),1,1000,1000),400)^60")
    }
    if (!date_from.equals("<NULL>") && !date_to.equals("<NULL>")) {
      queryBuilder = queryBuilder.facetFields("record_posted_time").addFilterQuery("record_posted_time:[" + date_from + " TO " + date_to + "]")
    }

    queryBuilder
  }

  /**
   * Prepare the results and build mapping between Solr and Application Level
   * @author Ramzi Sh. Alqrainy
   */
  def prepareResults(results: MapQueryResult, request: Request[AnyContent]): JsObject = {
    var resultsInfo = List[JsObject]()
    results.documents.foreach {
      doc =>
        resultsInfo ::= Json.obj(
          "id" -> doc("id").toString,
          "title" -> doc("title").toString,
          "description" -> doc("description").toString
          )
    }

    var resultsJson = Json.obj(
      "noOfResults" -> results.numFound,
      "results" -> resultsInfo)
    resultsJson
  }

  

}