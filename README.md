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

