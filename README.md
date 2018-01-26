# CustomSpellChecker
A custom spell checker that integrates to Solr 

## Idea

Modification of the Solr and Lucene standard spell checker module (classes). The overall idea is to modify the n-grams method that the
[Lucene SpellChecker](https://github.com/apache/lucene-solr/blob/master/lucene/suggest/src/java/org/apache/lucene/search/spell/SpellChecker.java)
implements. 

In relation to information about Ngram methods I refer to [Ngram](https://en.wikipedia.org/wiki/N-gram). 

### Ngrams spell checker
To up the speed small modificaitons have been applied to the n-grams indexing method. These result in a less generel mapping but still
shows very good results in relation to the quality of the spell checker. The basis of the ngrams indexing approach is:

* if input length > 5 
  * use tri- to four-grams
* if input length is between 5 and 2 (not including 2)
  * use two- to tri-grams
* if input lenght is less then or equal to 2
  * use one- to two-grams 

### Distance metrics
The spell checker can use any distance metric that implements the org.apache.lucene.search.spell.StringDistance class. Obviously one could
use the [Weighted Levenshtein](https://thegreendeveloper.github.io/WeightedLevensthein/) distance metric as well.  

### Installation
The following steps should be made in order to use the spell checker in your Solr application:

* In order to compile the module it is nessesary to add the following jars to the build path: 
  * lucene-analyzers-common-VERSIONNO.jar 
  * lucene-core-VERSIONNO.jar 
  * lucene-suggest-VERSIONNO.jar 
  * solr-core-VERSIONNO.jar 
  * solr-solrj-VERSIONNO.jar   
* The jars can be found online or in your current Solr solution in the ..Solr-VERSIONNO\server\solr-webapp\webapp\WEB-INF\lib\ folder
* Export the project as jar file to ..Solr-VERSIONNO\contrib\extraction\lib\
* Change the setup, specificly the "classname" parameter, in solrconfig.xml or solrconfig_extra.xml (or where ever your spell checker setup is located)
* Restart Solr and re-build the spell checker index. 
