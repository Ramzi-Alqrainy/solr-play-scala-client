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
import anorm._
import play.api.cache.Cache
import java.text.SimpleDateFormat
import java.sql.Timestamp
import java.sql.Date
import util.control.Breaks._

/**
 * Class IR performs many functions about information retrieval .
 * @author : Ramzi Sh. Alqrainy
 * @copyright Copyright (c) 2014
 * @see https://www.assembla.com/spaces/opensooq/wiki/Solr_-_a_Lucene-based_search_server
 * @version 0.1
 */
object IRLib {

  /**
   * is a main function that handling search process.
   * with more information and details about collections, please visit Assembla wiki "Search APIs"
   * @param query
   * @param countryId
   * @param request
   * @see https://www.assembla.com/spaces/opensooq/wiki/Solr_Search_APIs
   * @version 0.1
   */
  def get(query: String, countryId: Integer, request: Request[AnyContent]): JsObject = {
    // Construct the solr query and handling the parameters
    var queryBuilder = this.buildQuery(query, countryId, request)
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
   *  Related Posts function returns related posts for specific content, and you can ignore some posts.
   *  @param content
   *  @param countryId
   *  @param noOfResults
   *  @param request
   *  @see https://www.assembla.com/spaces/opensooq/wiki/Related_Posts_-_Solr
   *  @version 0.1
   */
  def getRelatedPosts(content: String, countryId: Integer, noOfResults: Integer, request: Request[AnyContent]): JsObject = {

    var queryBuilder = this.buildQuery(content, countryId, request)
    var resultsInfo = Json.obj(
      "num_of_results" -> 0,
      "results" -> List[JsString]())
    // Construct the solr query and handling the parameters
    var mm = request.getQueryString("mm").getOrElse("95%").replace("\"", "")
    var catId = Integer.parseInt(request.getQueryString("catId").getOrElse(0).toString())
    var jobIds: Map[Int, Int] = Map(46 -> 1, 110 -> 1, 170 -> 1, 294 -> 1, 358 -> 1, 422 -> 1, 482 -> 1, 542 -> 1, 602 -> 1, 664 -> 1, 724 -> 1,
      786 -> 1, 848 -> 1, 908 -> 1, 968 -> 1, 1028 -> 1, 1088 -> 1, 1148 -> 1, 1208 -> 1)

    try {
      // Get Results from Solr.
      queryBuilder = queryBuilder.groupBy("member_id").setParamter("group.limit", "1").setParamter("group.offset", "0")
        .setParamter("group.format", "simple").setParamter("group.main", "true").setParamter("group.cache.percent", "90")
        .setParamter("qf", "title^2 description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1 city_name^1 price^2")
        .setParamter("bf", "product(recip(rord(record_posted_day),1,1000,1000),400)^60 if(or(exists(post_image_name),not(price)),100,0)^10")
        .setParamter("mm", mm)
        .start(0).rows(noOfResults)
      // if category is jobs then we ignore 
      if (catId > 0 && jobIds.get(catId).getOrElse(0) == 1) queryBuilder = queryBuilder.setParamter("bf", "product(recip(rord(record_posted_day),1,1000,1000),400)^60")
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
   * isPostRelevance is a function that determine post's content related to specific category.
   * @param content
   * @param countryId
   * @param request
   * @see https://www.assembla.com/spaces/opensooq/wiki/Check_Relevancy_-_Solr
   */
  def isPostRelevance(content: String, countryId: Int, request: Request[AnyContent]): JsBoolean = {
    ///////////// Check Params ////////////////////////
    var categoryId = Integer.parseInt(request.getQueryString("categoryId").getOrElse(0).toString())
    var subCategoryId = Integer.parseInt(request.getQueryString("subCategoryId").getOrElse(0).toString())
    var mm = request.getQueryString("mm").getOrElse("95%").replace("\"", "")
    var threshold = Integer.parseInt(request.getQueryString("threshold").getOrElse(10).toString())

    var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
      + ":" + play.Play.application().configuration().getString("ir.engine.port") +
      play.Play.application().configuration().getString("ir.engine.indexPath") +
      "collection" + countryId)
    //////////////////////////////////////////////

    // Build Solr query
    var queryBuilder = client.query(content).fields("score").facetFields("tag_1_id", "tag_2_id")
      .setParamter("qf", "title description tag_1_name tag_2_name")
      .setParamter("mm", mm)
      .setParamter("defType", "edismax")
      .start(0).rows(10)

    if (categoryId > 0) queryBuilder = queryBuilder.addFilterQuery("tag_1_id:" + categoryId.toString)
    if (subCategoryId > 0) queryBuilder = queryBuilder.addFilterQuery("tag_2_id:" + subCategoryId.toString)

    var results = queryBuilder.getResultAsMap()

    if (results.numFound < threshold) {
      JsBoolean(false) // No
    } else {
      JsBoolean(true) // Yes, it's relevant
    }
  }

  /**
   * The original purpose of word prediction software was to help people with
   * physical disabilities increase their typing speed.
   * @param content
   * @param countryId
   * @param request
   */
  def autocomplete(query: String, countryId: Integer, request: Request[AnyContent]): List[JsString] = {
    var resultPerPage = Integer.parseInt(request.getQueryString("resultPerPage").getOrElse(10).toString())

    var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
      + ":" + play.Play.application().configuration().getString("ir.engine.port") +
      play.Play.application().configuration().getString("ir.engine.indexPath") +
      play.Play.application().configuration().getString("ir.engine.terms"))

    var results = List[JsString]()
    try {
      var queryBuilder = client.query("*:*").facetFields("term").setParamter("facet.mincount", "1").setParamter("facet.sort", "count")
        .setParamter("facet.prefix", query).addFilterQuery("country_id:" + countryId).setParamter("spellcheck", "false").setParamter("qt", "/spell")
        .setParamter("spellcheck.collate", "true").setParamter("spellcheck.maxCollationTries", "17")
        .setParamter("spellcheck.accuracy", "0.6").setParamter("spellcheck.count", "18")
        .setParamter("facet.limit", resultPerPage.toString)
        .setParamter("spellcheck.extendedResults", "true").setParamter("spellcheck.onlyMorePopular", "true")
        .start(0).rows(resultPerPage).getResultAsMap()

      queryBuilder.facetFields.foreach {
        case (field, counts) =>
          counts.foreach {
            case (term, count) =>
              results ::= JsString(term)
          }

      }

    } catch {
      case e: Exception =>
        println("exception caught: " + e);
    }

    results
  }

  /**
   * generate tags from post
   * @param content
   * @param countryId
   * @param request
   */
  def getGeneratedTagsFromPost(content: String, countryId: Int = 0, request: Request[AnyContent]): List[JsObject] = {
    var newCatId = Integer.parseInt(request.getQueryString("newCatId").getOrElse(0).toString())
    var cityId = Integer.parseInt(request.getQueryString("cityId").getOrElse(0).toString())

    var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
      + ":" + play.Play.application().configuration().getString("ir.engine.port") +
      play.Play.application().configuration().getString("ir.engine.indexPath") +
      play.Play.application().configuration().getString("ir.engine.keywords"))
    // Build Solr query
    var queryBuilder = client.query(content).fields("keyword", "parent", "tag", "id")
      .setParamter("qf", "keyword^10 tags tag parent^10")
      .setParamter("q.op", "OR")
      .setParamter("defType", "edismax")

    if (newCatId > 0) {
      queryBuilder = queryBuilder.addFilterQuery("new_cat_id:" + newCatId)
    }
    // Filter by city
    if (cityId > 0) {
      queryBuilder = queryBuilder.facetFields("city_id").addFilterQuery("city_id:(" + cityId + ")")
    }
    // Filter by country
    if (countryId > 0) {
      queryBuilder = queryBuilder.facetFields("country_id").addFilterQuery("country_id:(" + countryId + ")")
    }
    var tags = List[JsObject]();

    try {
      // Get Results from Solr.
      var results = queryBuilder.getResultAsMap()
      results.documents.foreach { doc =>
        tags ::= Json.obj(
          "tag" -> doc("tag").toString,
          "id" -> doc("id").toString)
      }
    } catch {
      case e: Exception => println("exception caught: " + e);
    }
    tags
  }

  /**
   * related searches for certain query
   * @param query
   * @param countryId
   * @param request
   */
  def getRelatedSearches(query: String, countryId: Integer, request: Request[AnyContent]): List[JsString] = {
    var resultPerPage = Integer.parseInt(request.getQueryString("resultPerPage").getOrElse(5).toString())

    var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
      + ":" + play.Play.application().configuration().getString("ir.engine.port") +
      play.Play.application().configuration().getString("ir.engine.indexPath") +
      play.Play.application().configuration().getString("ir.engine.terms"))
    var results = List[JsString]()
    try {
      var queryBuilder = client.query("*:*").facetFields("term").setParamter("facet.mincount", "1").setParamter("facet.prefix", query)
        .addFilterQuery("country_id:" + countryId).start(0).rows(resultPerPage).getResultAsMap()

      queryBuilder.facetFields.foreach {
        case (field, counts) =>
          counts.foreach {
            case (term, count) =>
              results ::= JsString(term)
          }
      }
    } catch {
      case e: Exception => println("exception caught: " + e);
    }
    results
  }

  /**
   *
   * isSpam is a function that determine post's content contains spam or not.
   * @param string content
   * @param string typeOfContent : "post" or "comment"
   * @return boolean (true or false)
   * @see Anti Spam - Solr (https://www.assembla.com/spaces/opensooq/wiki/Anti_Spam_-_Solr)
   */
  def isSpam(content: String, typeOfContent: String): List[JsString] = {
    var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
      + ":" + play.Play.application().configuration().getString("ir.engine.port")
      + play.Play.application().configuration().getString("ir.engine.indexPath")
      + play.Play.application().configuration().getString("ir.engine.anti_spam"))
    var reasons = List[JsString]()

    var isFound: Boolean = false;
    try {
      var results = client.query(content).
        fields("text", "score")
        .facetFields("type")
        .addFilterQuery("type:" + typeOfContent)
        .setParamter("q.op", "OR")
        .setParamter("defType", "edismax")
        .setParamter("qf", "text")
        .rows(10)
        .start(0)
        .getResultAsMap()

      var isSpam = false
      if (results.numFound > 0) isSpam = true
      if (content.indexOfSlice("http") >= 0) reasons ::= JsString("http")
      results.documents.foreach { doc =>
        reasons ::= JsString(doc("text").toString)
      }

      // Verify
      reasons.foreach {
        reason => if (content.indexOfSlice(reason.toString.replace("\"", "")) >= 0) isFound = true
      }

    } catch {
      case e: Exception => println("exception caught: " + e);
    }

    if (isFound) {
      reasons
    } else {
      List[JsString]()
    }

  }

  /**
   *
   * constructQuery : constructs the query and handling the user params
   * @param  query
   * @param  countryId
   * @param  request
   */

  def buildQuery(query: String, countryId: Integer, request: Request[AnyContent]): QueryBuilder = {
    var query = request.getQueryString("query").getOrElse("*:*")
    var page = Integer.parseInt(request.getQueryString("page").getOrElse(1).toString())
    var queryOperator = request.getQueryString("q.op").getOrElse("AND").toString()
    var mm = request.getQueryString("mm").getOrElse("<NULL>").replace("\"", "")
    var countryId = Integer.parseInt(request.getQueryString("countryId").getOrElse(12).toString())
    var cityId = Integer.parseInt(request.getQueryString("cityId").getOrElse(0).toString())
    var resultsPerPage = Integer.parseInt(request.getQueryString("resultsPerPage").getOrElse(20).toString())
    var tagId = Integer.parseInt(request.getQueryString("tagId").getOrElse(0).toString())
    var priceFrom = request.getQueryString("priceFrom").getOrElse("0").toString()
    var categoryId = Integer.parseInt(request.getQueryString("categoryId").getOrElse(0).toString())
    var subCategoryId = Integer.parseInt(request.getQueryString("subCategoryId").getOrElse(0).toString())
    var childId = Integer.parseInt(request.getQueryString("childId").getOrElse(0).toString())
    var memberId = Integer.parseInt(request.getQueryString("memberId").getOrElse(0).toString())
    var categoryName = request.getQueryString("categoryName").getOrElse("<NULL>")
    var cityName = request.getQueryString("city_name").getOrElse("<NULL>")
    var subCategoryName = request.getQueryString("subCategoryName").getOrElse("<NULL>")
    var postedDate = request.getQueryString("postedDate").getOrElse("")
    var searchType = request.getQueryString("searchType").getOrElse("everything")
    var date = request.getQueryString("date").getOrElse("<NULL>")
    var date_from = request.getQueryString("date_from").getOrElse("<NULL>")
    var date_to = request.getQueryString("date_to").getOrElse("<NULL>")
    var sort = request.getQueryString("sort").getOrElse("<NULL>")
    var ignoreIds = request.getQueryString("ignoreIds").getOrElse("<NULL>")
    var keyword = request.getQueryString("keyword").getOrElse("<NULL>")
    var subkeyword = request.getQueryString("subkeyword").getOrElse("<NULL>")

    var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
      + ":" + play.Play.application().configuration().getString("ir.engine.port") + "/solr/collection" + countryId)

    var offset: Int = 0
    if (!request.getQueryString("offset").isEmpty) {
      offset = Integer.parseInt(request.getQueryString("offset").getOrElse(0).toString())
    } else {
      offset = (page - 1) * resultsPerPage
    }

    var priceTo: String = "*"
    if (!request.getQueryString("priceTo").isEmpty && !request.getQueryString("priceTo").toString().equals("0")) {
      priceTo = request.getQueryString("priceTo").toString
    }

    var showFacet: Boolean = false
    if (!request.getQueryString("showFacet").isEmpty) {
      val showFacetString = request.getQueryString("showFacet").get.toString
      showFacet = showFacetString.toBoolean
    }

    var haveImages: Boolean = false
    if (!request.getQueryString("haveImages").isEmpty) {
      val showFacetString = request.getQueryString("haveImages").toString
      haveImages = showFacetString.toBoolean
    }

    var specialAds: Boolean = false
    if (!request.getQueryString("specialAds").isEmpty) {
      val showFacetString = request.getQueryString("specialAds").get.toString
      specialAds = showFacetString.toBoolean
    }
    ///////////////////////////////////////////////////////////////////////

    // The current time in milliseconds.
    val timestamp: Long = System.currentTimeMillis

    // Strip whitespace (or other characters) from the beginning and end of a string
    query.trim

    if (countryId < 1) {
      // BadRequest("Country_id is not assigned")

    }
    if (!subkeyword.equals("<NULL>") && keyword.equals("<NULL>")) query = subkeyword
    if (!keyword.equals("<NULL>")) {
      query = keyword
      if (!subkeyword.equals("<NULL>")) query += " " + subkeyword
    }
    var queryBuilder = client.query(query)
      .setParamter("bf", "product(recip(sub(" + timestamp + ",record_posted_time),1.27e-11,0.08,0.05),1000)^50")
      .setParamter("defType", "edismax")
      .start(offset)
      .rows(resultsPerPage)

    // When you assign mm (Minimum 'Should' Match), we remove q.op
    // becuase we can't set two params to the same function
    // q.op=AND == mm=100% | q.op=OR == mm=0%
    if (!mm.equals("<NULL>")) {
      queryBuilder = queryBuilder.setParamter("mm", "100%")
    } else {
      queryBuilder = queryBuilder.setParamter("q.op", queryOperator)
    }

    searchType match {
      case "all" => queryBuilder = queryBuilder.setParamter("qf", "title^1e-10 description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1 city_name^1 price^1")
      case "title" => queryBuilder = queryBuilder.setParamter("qf", "title^1e-10 tag_1_name^1e-10 tag_2_name^1 city_name^1 price^1")
      case "description" => queryBuilder = queryBuilder.setParamter("qf", "description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1 city_name^1 price^1")
      case _ => queryBuilder = queryBuilder.setParamter("qf", "title^1e-10 description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1 city_name^1 price^1")
    }

    if (showFacet) {
      // facet.field param allows you to specify a field which should be treated as a facet.
      queryBuilder = queryBuilder.facetFields("city_name_str", "tag_1_name_str", "tag_2_name_str", "tag_1_id",
        "tag_2_id")
        .sortFacet("count")
        .setParamter("facet.mincount", "1")
        .setParamter("facet.date", "record_posted_date") // Date Faceting Parameters
        .setParamter("facet.date.start", "NOW/DAY-21DAYS")
        .setParamter("facet.date.end", "NOW/DAY+1DAY")
        .setParamter("facet.date.gap", "+1DAY")
    }

    if (!query.equals("*:*")) {
      queryBuilder = queryBuilder.setParamter("qf", "title^1 description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1  city_name^1 price^1")
      queryBuilder = queryBuilder.setParamter("bf", "product(recip(rord(record_posted_day),1,1000,1000),400)^60")
    }
    if (!date_from.equals("<NULL>") && !date_to.equals("<NULL>")) {
      queryBuilder = queryBuilder.facetFields("record_posted_time").addFilterQuery("record_posted_time:[" + date_from + " TO " + date_to + "]")
    }

    if (!priceTo.equals("<NULL>") && !(priceTo.equals("*") && priceFrom.equals("0"))) {
      queryBuilder = queryBuilder.facetFields("price").addFilterQuery("price:[" + priceFrom + " TO " + priceTo + "]")
    }

    if (specialAds) {
      queryBuilder = queryBuilder.facetFields("highlight_end_time").addFilterQuery("highlight_end_time:[" + timestamp + " TO *]")
    }

    if (cityId != 0) {
      queryBuilder = queryBuilder.facetFields("city_id").addFilterQuery("city_id:(" + cityId + ")")
    }

    if (categoryId != 0) {
      queryBuilder = queryBuilder.facetFields("tag_1_id").addFilterQuery("tag_1_id:(" + categoryId + ")")
    }

    if (subCategoryId != 0) {
      queryBuilder = queryBuilder.facetFields("tag_2_id").addFilterQuery("tag_2_id:(" + subCategoryId + ")")
    }

    if (memberId != 0) {
      queryBuilder = queryBuilder.facetFields("member_id").addFilterQuery("member_id:(" + memberId + ")")
    }

    if (categoryName.isEmpty) {
      queryBuilder = queryBuilder.facetFields("tag_1_name_str").addFilterQuery("tag_1_name_str:\"" + categoryName + "\"")
    }

    if (subCategoryName.isEmpty) {
      queryBuilder = queryBuilder.facetFields("tag_2_name_str").addFilterQuery("tag_2_name_str:\"" + subCategoryName + "\"")
    }

    if (cityName.isEmpty) {
      queryBuilder = queryBuilder.facetFields("city_name_str").addFilterQuery("city_name_str:(" + cityName + ")")
    }

    if (haveImages) {
      queryBuilder.facetFields("post_image_name").addFilterQuery("post_image_name:[* TO *]")
    }

    if (!sort.equals("<NULL>")) {
      queryBuilder = queryBuilder.setParamter("sort", sort)
    }

    if (!ignoreIds.equals("<NULL>")) {
      ignoreIds.replaceAll(",", """" OR """")
      queryBuilder = queryBuilder.facetFields("{!ex=id}id").setParamter("facet.limit", "4").addFilterQuery("{!tag=id} NOT id:(\"" + ignoreIds + "\")")
    }

    postedDate match {
      case "today" => queryBuilder = queryBuilder.facetFields("record_posted_date").addFilterQuery("record_posted_date:[NOW/DAY-1DAYS TO NOW/DAY+1DAY]")
      case "last_2_days" => queryBuilder = queryBuilder.facetFields("record_posted_date").addFilterQuery("record_posted_date:[NOW/DAY-1DAYS TO NOW/DAY+1DAY]")
      case "last_7_days" => queryBuilder = queryBuilder.facetFields("record_posted_date").addFilterQuery("record_posted_date:[NOW/DAY-6DAYS TO NOW/DAY+1DAY]")
      case "last_21_days" => queryBuilder = queryBuilder.facetFields("record_posted_date").addFilterQuery("record_posted_date:[NOW/DAY-20DAYS TO NOW/DAY+1DAY]")
      case _ =>
    }
    queryBuilder
  }

  /**
   * Prepare the results and build mapping between Solr and Application Level
   * @author Ramzi Sh. Alqrainy
   */
  def prepareResults(results: MapQueryResult, request: Request[AnyContent]): JsObject = {
    var resultsInfo = List[JsObject]()
    var dateWithoutHoursFormat = new SimpleDateFormat("yyyy.MM.dd");
    var dateWithHoursFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:SS");
    results.documents.foreach {
      doc =>
        var stamp = new Timestamp(doc("record_posted_time").toString.toLong);
        var date = new Date(stamp.getTime());
        var dateWithoutHours = dateWithoutHoursFormat.format(date).toString();
        var dateWithHours = dateWithHoursFormat.format(date).toString();
        resultsInfo ::= Json.obj(
          "id" -> doc("id").toString,
          "title" -> doc("title").toString,
          "description_search" -> doc("description").toString,
          "price" -> Integer.parseInt(doc("price").toString),
          "cities_name" -> doc("city_name").toString,
          "normalDate" -> dateWithoutHours,
          "naturalDate" -> dateWithoutHours,
          "record_insert_date" -> dateWithHours,
          "cities_id" -> Integer.parseInt(doc("city_id").toString),
          "categories_name" -> doc("tag_1_name").toString,
          "categories_id" -> Integer.parseInt(doc("tag_1_id").toString),
          "subcategories_name" -> doc("tag_2_name").toString,
          "subcategories_id" -> Integer.parseInt(doc("tag_2_id").toString),
          "new_cat_id" -> Integer.parseInt(doc("tag_1_id").toString),
          "date" -> dateWithoutHours,
          "post_images_name" -> doc.get("post_image_name").getOrElse("thumb_nophoto_adaptiveResize.gif").toString,
          "members_M_user_name" -> doc("member_name").toString,
          "members_id" -> Integer.parseInt(doc("member_id").toString),
          "location_search" -> doc("location").toString,
          "currency" -> doc("currency").toString,
          "highlight_start_date" -> Json.toJson(doc.get("highlight_start_date_name_str").toString()),
          "highlight_end_date" -> doc.get("highlight_end_date_name_str").getOrElse(None).toString,
          "highlight_type" -> Integer.parseInt(doc("highlight_type_id").toString))

    }

    var facets = List[JsObject]()

    results.facetFields.foreach {
      case (field, counts) =>
        var facetsFields = List[JsObject]()
        counts.foreach {
          case (value, count) =>
            facetsFields ::= Json.obj("tag" -> value, "count" -> count)
        }
        facets ::= Json.obj(field -> facetsFields)
    }

    results.facetDates.foreach {
      case (field, counts) =>
        var facetsDates = List[JsObject]()
        var sum : Int  = 0
        var day : Int= 1
        counts.foreach {
          case (value, count) =>
            sum = sum + count.toInt
            day match{
              case 1 => if(sum>0)facetsDates ::= Json.obj("tag" -> "اليوم", "count" -> sum, "label"->"today")
              case 2 => if(sum>0)facetsDates ::= Json.obj("tag" -> "آخر يومين", "count" -> sum, "label"->"last_2_days")
              case 7 => if(sum>0)facetsDates ::= Json.obj("tag" -> "آخر 7 أيام", "count" -> sum, "label"->"last_7_days")
              case 21 => if(sum>0)facetsDates ::= Json.obj("tag" -> "آخر 21 يوم", "count" -> sum, "label"->"last_21_days")
              case _ =>
            }
           day = day +1
          
        }
        facetsDates ::= Json.obj("tag" -> "في أي وقت", "count" -> results.numFound, "label"->"any_time")
        facets ::= Json.obj(field -> facetsDates)
    }

    var countryId = Integer.parseInt(request.getQueryString("countryId").getOrElse("0"))
    var categroyId = Integer.parseInt(request.getQueryString("categroyId").getOrElse("0"))
    var subCategoryId = Integer.parseInt(request.getQueryString("subCategoryId").getOrElse("0"))
    var keyword = request.getQueryString("keyword").getOrElse("<NULL>")
    var cityName = request.getQueryString("cityName").getOrElse("<NULL>")
    var memberId = Integer.parseInt(request.getQueryString("memberId").getOrElse("0"))
    facets ::= Json.obj("keywords" -> this.getKeyword(countryId, true, categroyId, subCategoryId, keyword, cityName, memberId))

    var rs = Json.obj(
      "num_of_results" -> results.numFound,
      "results" -> resultsInfo.reverse,
      "facets" -> facets)
    rs
  }

  /**
   * get Keyword based on category, and country.
   * @param countryId
   * @param useCache
   * @param categoryId
   */
  def getKeyword(countryId: Integer, useCache: Boolean, categroyId: Integer,
    subCategoryId: Integer = 0, keyword: String,
    cityName: String, memberId: Integer = 0): List[JsObject] = {

    var cars: Map[Int, Int] = Map(6 -> 1, 20 -> 1, 70 -> 1, 84 -> 1, 134 -> 1, 146 -> 1, 194 -> 1, 206 -> 1, 254 -> 1,
      268 -> 1, 324 -> 1, 330 -> 1, 382 -> 1, 396 -> 1, 446 -> 1, 458 -> 1, 506 -> 1,
      518 -> 1, 566 -> 1, 578 -> 1, 626 -> 1, 636 -> 1, 688 -> 1, 700 -> 1, 748 -> 1,
      760 -> 1, 812 -> 1, 824 -> 1, 872 -> 1, 884 -> 1, 932 -> 1, 944 -> 1, 992 -> 1,
      1004 -> 1, 1052 -> 1, 1064 -> 1, 1112 -> 1, 1124 -> 1, 1172 -> 1, 1184 -> 1, 1254 -> 1, 1255 -> 1)
    var realestate: Map[Int, Int] = Map(4 -> 1, 18 -> 1, 68 -> 1, 82 -> 1, 132 -> 1, 144 -> 1, 192 -> 1, 204 -> 1, 252 -> 1, 266 -> 1, 322 -> 1, 328 -> 1, 380 -> 1,
      394 -> 1, 444 -> 1, 456 -> 1, 504 -> 1, 516 -> 1, 564 -> 1, 576 -> 1, 624 -> 1, 638 -> 1, 686 -> 1, 698 -> 1, 746 -> 1, 758 -> 1,
      810 -> 1, 822 -> 1, 870 -> 1, 882 -> 1, 930 -> 1, 942 -> 1, 990 -> 1, 1002 -> 1, 1050 -> 1, 1062 -> 1, 1110 -> 1, 1122 -> 1, 1170 -> 1, 1182 -> 1)
    var electronics: Map[Int, Int] = Map(
      8 -> 1,
      72 -> 1,
      136 -> 1,
      196 -> 1,
      256 -> 1,
      320 -> 1,
      384 -> 1,
      448 -> 1,
      510 -> 1,
      570 -> 1,
      628 -> 1,
      690 -> 1,
      814 -> 1,
      874 -> 1,
      934 -> 1,
      994 -> 1,
      1054 -> 1,
      1114 -> 1,
      1174 -> 1)

    var subcategoryId: Int = 0
    var facet = List[JsObject]()
    facet = Cache.getOrElse("keywords.list." + countryId + "." + subCategoryId, 6000)(List[JsObject]())
    if (facet.isEmpty || true) {
      if (cars.get(subCategoryId).getOrElse(0) == 1) subcategoryId = 70
      if (realestate.get(subCategoryId).getOrElse(0) == 1) subcategoryId = 68
      if (electronics.get(subCategoryId).getOrElse(0) == 1) subcategoryId = 72
      var keywords = List[JsString]()
      DB.withConnection { implicit connection =>
        
        if(keyword.equals("<NULL>")){
           val result = SQL(""" select keyword  from post_tags where parent = {c} and country_id=12 and new_cat_id=""" + subcategoryId + """; """)
        .on("c"->keyword)
         keywords = result().map(row =>
          JsString(row[String]("keyword"))).toList
      
        }else{
          val result = SQL(""" select keyword  from post_tags where parent is null and country_id=12 and new_cat_id=""" + subcategoryId + """; """)
         keywords = result().map(row =>
          JsString(row[String]("keyword"))).toList
      
        }
      }
      var client = new SolrClient("http://" + play.Play.application().configuration().getString("ir.engine.host")
        + ":" + play.Play.application().configuration().getString("ir.engine.port") + "/solr/collection" + countryId)
      var reasons = Map[String, Long]()

      keywords.foreach(keyword =>

        try {
          var results = client.query(keyword.toString.replace("/", " OR "))
            .setParamter("q.op", "AND")
            .setParamter("defType", "edismax")
            .setParamter("qf", "title^1e-10 description^1e-13 location^1e-13 tag_1_name^1e-13 tag_2_name^1 city_name^1 price^1")
            .rows(10)
            .start(0)
            .getResultAsMap()

          var isSpam = false
          if (results.numFound > 5 || memberId != 0) {
            reasons += (keyword.toString -> results.numFound)

          }

        } catch {
          case e: Exception => println("exception caught: " + e);
        })

      // sort by value
      //reasons.toSeq.sorted
      reasons.toSeq.sortBy(_._2).foreach {
        case (key, value) => facet ::= Json.obj("tag" -> key, "count" -> value)
      }
      Cache.set("keywords.list." + countryId + "." + subCategoryId, facet)
    }
    facet

  }

}