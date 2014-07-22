import play.Project._

name := "Apache-Solr-Library"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache
)  
   
resolvers += "amateras-repo" at "http://amateras.sourceforge.jp/mvn/"

libraryDependencies += "org.apache.solr" % "solr-solrj" % "4.8.0"
            
play.Project.playScalaSettings