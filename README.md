solr-play-scala-client
======================

A Scala library in Play framework for indexing and searching documents within an [Apache Solr](http://lucene.apache.org/solr/). solr-play-scala-client uses SolrJ to communicate with Solr server, and it is compatable with Apache Solr 4.X .


Edit the following configuration of your Apache Solr Server in application.conf.

```scala
# Apache Solr Configuration
solr.engine.host="localhost"
solr.engine.port="8983"
solr.engine.indexPath="/solr/"
solr.engine.collection="collection1"
```

After installation [Play Framework](http://www.playframework.com/documentation/2.3.x/Installing) and clone this project, just do the following commands

```scala
[root@ramzi git]# cd solr-play-scala-client/
[root@ramzi solr-play-scala-client]# play
[Apache-Solr-Library] $ run
```

Now you can check [localhost](http://localhost:9000/) to make sure that everything is fine.

This example allow you to use get api for Apache Solr

http://localhost:9000/get?query=kia

Architecture of solr-play-scala-client
--------
![alt tag](http://2.bp.blogspot.com/-pSTUkVzVHsY/U85_BsxF41I/AAAAAAAACkg/wJt0Zru58_I/s1600/solr_scala_play+%25281%2529.jpg)

Query Syntax
--------

```scala
    var client = new SolrClient("http://" + play.Play.application().configuration().getString("solr.engine.host")
      + ":" + play.Play.application().configuration().getString("solr.engine.port") + 
       play.Play.application().configuration().getString("solr.engine.indexPath") + 
        play.Play.application().configuration().getString("solr.engine.collection"))
        
    val results = client.query("Kia")
      .setParameter("bf", "product(title,10)")
      .setParameter("defType", "edismax")
      .start(offset)
      .sortBy("id", Order.asc)
      .rows(10).getResultAsMap()
```

Indexing 
----------

```scala
    var client = new SolrClient("http://" + play.Play.application().configuration().getString("solr.engine.host")
      + ":" + play.Play.application().configuration().getString("solr.engine.port") + 
       play.Play.application().configuration().getString("solr.engine.indexPath") + 
        play.Play.application().configuration().getString("solr.engine.collection"))
        
    client
     .add(Map("id"->"001", "title" -> "Kia", "Description" -> "car"))
     .add(Map("id"->"002", "title" -> "BMW", "Description" -> "car"))
     .add(Map("id"->"003", "title" -> "Apple", "Description" -> "Fruit"))
     .commit
```

Returning Results
------------------

```scala
var resultsInfo = List[JsObject]()
    results.documents.foreach {
      doc =>
        resultsInfo ::= Json.obj(
          "id" -> doc("id").toString,
          "title" -> doc("title").toString,
          "description" -> doc("description").toString
          )
    }
```

Faceted Search
------------------

```scala
    var client = new SolrClient("http://" + play.Play.application().configuration().getString("solr.engine.host")
      + ":" + play.Play.application().configuration().getString("solr.engine.port") + 
       play.Play.application().configuration().getString("solr.engine.indexPath") + 
        play.Play.application().configuration().getString("solr.engine.collection"))
        
    val results = client.query("Kia")
      .setParameter("bf", "product(title,10)")
      .setParameter("defType", "edismax")
      .start(offset)
      .facetFields("id").addFilterQuery("id:1")
      .sortBy("id", Order.asc)
      .rows(10).getResultAsMap()
      
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
      
      
```

