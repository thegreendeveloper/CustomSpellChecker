package org.nota.spell;

import java.util.ArrayList;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;

/*
 * This class implements all associated ngrams methods necessary to create the spell checker index and for searching. 
 * A lot of these methods have been separated from the NotaNgramSpellChecker in order
 * to ease the readability of the classes
 * 
 * */
public class NGram {
	private static final String F_WORD = "word";

	private static void add(BooleanQuery.Builder q, String name, String value) {
		q.add(new BooleanClause(new TermQuery(new Term(name, value)), BooleanClause.Occur.SHOULD));
	}

	public static BooleanQuery.Builder buildNgramQuery(String word) {
		BooleanQuery.Builder query = new BooleanQuery.Builder();

		final int lengthWord = word.length();
		String[] grams;
		String key;
		for (int ng = getMin(lengthWord); ng <= getMax(lengthWord); ng++) {

			key = "gram" + ng; // form key

			grams = formGrams(word, ng); // form word into ngrams (allow dups too)

			if (grams.length == 0) {
				continue; // hmm
			}

			for (int i = 0; i < grams.length; i++) {
				add(query, key, grams[i]);
			}
		}

		return query;

	}

	public static Document createDocument(String text) {
		Document doc = new Document();
		// the word field is never queried on... it's indexed so it can be quickly
		// checked for rebuild (and stored for retrieval). Doesn't need norms or TF/pos
		Field f = new StringField(F_WORD, text, Field.Store.YES);
		doc.add(f); // orig term
		addGram(text, doc, getMin(text.length()), getMax(text.length()));
		return doc;
	}

	private static void addGram(String text, Document doc, int ng1, int ng2) {
		int len = text.length();
		for (int ng = ng1; ng <= ng2; ng++) {
			String key = "gram" + ng;
			String end = null;
			for (int i = 0; i < len - ng + 1; i++) {
				String gram = text.substring(i, i + ng);
				FieldType ft = new FieldType(StringField.TYPE_NOT_STORED);
				ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
				Field ngramField = new Field(key, gram, ft);
				// spellchecker does not use positional queries, but we want freqs
				// for scoring these multivalued n-gram fields.
				doc.add(ngramField);
				if (i == 0) {
					// only one term possible in the startXXField, TF/pos and norms aren't needed.
					Field startField = new StringField("start" + ng, gram, Field.Store.NO);
					doc.add(startField);
				}
				end = gram;
			}
			if (end != null) { // may not be present if len==ng1
				// only one term possible in the endXXField, TF/pos and norms aren't needed.
				Field endField = new StringField("end" + ng, end, Field.Store.NO);
				doc.add(endField);
			}
		}
	}

	/**
	 * Form all ngrams for a given word.
	 * 
	 * @param text
	 *            the word to parse
	 * @param ng
	 *            the ngram length e.g. 3
	 * @return an array of all ngrams in the word and note that duplicates are not
	 *         removed
	 */
	private static String[] formGrams(String text, int ng) {
		int len = text.length();
		String[] res = new String[len - ng + 1];
		for (int i = 0; i < len - ng + 1; i++) {
			res[i] = text.substring(i, i + ng);
		}
		return res;
	}

	private static int getMin(int l) {
		if (l > 5) {
			return 3;
		}
//		modified as this results in a way too general mapping. 
//		if (l == 5) {
		if (l <= 5 && l > 2) {
			return 2;
		}
		return 1;
	}

	private static int getMax(int l) {
		if (l > 5) {
			return 4;
		}
//		modified as this results in a way too general mapping. 
//		if (l == 5) {
		if (l <= 5 && l > 2) {
			return 3;
		}
		return 2;
	}
}
