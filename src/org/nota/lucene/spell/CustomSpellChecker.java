package org.nota.lucene.spell;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import org.apache.lucene.analysis.Token;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.spell.LevensteinDistance;
import org.apache.lucene.search.spell.StringDistance;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.search.spell.SuggestWordFrequencyComparator;
import org.apache.lucene.search.spell.SuggestWordQueue;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.spelling.AbstractLuceneSpellChecker;
import org.apache.solr.spelling.SolrSpellChecker;
import org.apache.solr.spelling.SpellingOptions;
import org.apache.solr.spelling.SpellingResult;

public class CustomSpellChecker extends SolrSpellChecker {
	

	public static final String ACCURACY = "accuracy";
	public static final String STRING_DISTANCE = "distanceMeasure";
	public static final String COMPARATOR_CLASS = "comparatorClass";

	public static final String SCORE_COMP = "score";
	public static final String FREQ_COMP = "freq";
	
	protected float accuracy = 0.5f;
	public static final String FIELD = "field";
	protected StringDistance sd;
	protected TrigramSimilarity spellChecker;

	@Override
	public String init(NamedList config, SolrCore core) {
		super.init(config, core);
		String accuracy = (String) config.get(ACCURACY);
		
		String strDistanceName = (String) config.get(STRING_DISTANCE);
		if (strDistanceName != null) {
			sd = core.getResourceLoader().newInstance(strDistanceName, StringDistance.class);
		} else {
			sd = new LevensteinDistance();
		}

		if (accuracy != null) {
			try {
				this.accuracy = Float.parseFloat(accuracy);
				spellChecker.setAccuracy(this.accuracy);
			} catch (NumberFormatException e) {
				throw new RuntimeException("Unparseable accuracy given for dictionary: " + name, e);
			}
		}
		return name;
	}


	@Override
	public void build(SolrCore core, SolrIndexSearcher indexSearcher) throws IOException {
		// Extract data
		
		HashSet<String> map = new HashSet<String>();
		IndexReader reader = indexSearcher.getIndexReader();

		Dictionary dictionary = new HighFrequencyDictionary(reader, field, 0.0f);

		InputIterator iter = dictionary.getEntryIterator();
		BytesRef text;
		while ((text = iter.next()) != null) {
			if (map.contains(text.utf8ToString())) {
				continue;
			}
			map.add(text.utf8ToString());
		}

		// Initialize spell checker
		spellChecker = new TrigramSimilarity(map, sd);
	}

	public SpellingResult getSuggestions(SpellingOptions options) throws IOException {

		SpellingResult result = new SpellingResult();
		IndexReader reader = options.reader;
		Term term;

		int count = Math.max(options.count, AbstractLuceneSpellChecker.DEFAULT_SUGGESTION_COUNT);

		for (Token token : options.tokens) {
			String tokenText = new String(token.buffer(), 0, token.length());
			term = new Term(field, tokenText);
			int docFreq = 0;
			if (reader != null) {
				docFreq = reader.docFreq(term);
			}

			SuggestWord[] suggestions = spellChecker.suggestSimilar(tokenText, options.count, reader, field);

			// Borrowed from
			// https://github.com/apache/lucenex-solr/blob/master/solr/core/src/java/org/apache/solr/spelling/AbstractLuceneSpellChecker.java
			// If extendedResults is enabled, we send back document frequency of the
			// resultset
			if (options.extendedResults == true && reader != null && field != null) {
				result.addFrequency(token, docFreq);
				int countLimit = Math.min(options.count, suggestions.length);
				if (countLimit > 0) {
					for (int i = 0; i < countLimit; i++) {
						// term = new Term(field, suggestions[i].string);
						result.add(token, suggestions[i].string, suggestions[i].freq);
					}
				} else {
					List<String> suggList = Collections.emptyList();
					result.add(token, suggList);
				}
				// If not we simply add the results to the resultset.
			} else {
				if (suggestions.length > 0) {
					// List<String> suggList = Arrays.asList(suggestions);
					List<String> suggList = new ArrayList<String>();
					for (int i = 0; i < suggestions.length; i++) {
						suggList.add(suggestions[i].string);
					}
					if (suggestions.length > options.count) {
						suggList = suggList.subList(0, options.count);
					}
					result.add(token, suggList);
				} else {
					List<String> suggList = Collections.emptyList();
					result.add(token, suggList);
				}
			}
		}

		return result;
	}

	@Override
	public void reload(SolrCore arg0, SolrIndexSearcher arg1) throws IOException {
		// TODO Auto-generated method stub

	}

}
