package org.nota.spell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.spell.LevensteinDistance;
import org.apache.lucene.search.spell.StringDistance;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.search.spell.SuggestWordQueue;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;

public class NotaNgramSimSpellChecker implements java.io.Closeable {

	public static final float DEFAULT_ACCURACY = 0.5f;
	public static final String F_WORD = "word";

	Directory spellIndex;

	private IndexSearcher searcher;

	private final Object searcherLock = new Object();
	private final Object modifyCurrentIndexLock = new Object();
	private volatile boolean closed = false;
	private float accuracy = DEFAULT_ACCURACY;
	private StringDistance sd;
	private Comparator<SuggestWord> comparator;

	public NotaNgramSimSpellChecker(Directory spellIndex, StringDistance sd) throws IOException {
		this(spellIndex, sd, SuggestWordQueue.DEFAULT_COMPARATOR);
	}

	public NotaNgramSimSpellChecker(Directory spellIndex) throws IOException {
		this(spellIndex, new LevensteinDistance());
	}

	public NotaNgramSimSpellChecker(Directory spellIndex, StringDistance sd, Comparator<SuggestWord> comparator)
			throws IOException {
		setSpellIndex(spellIndex);
		setStringDistance(sd);
		this.comparator = comparator;
	}

	// TODO: we should make this final as it is called in the constructor
	public void setSpellIndex(Directory spellIndexDir) throws IOException {
		// this could be the same directory as the current spellIndex
		// modifications to the directory should be synchronized
		synchronized (modifyCurrentIndexLock) {
			ensureOpen();
			if (!DirectoryReader.indexExists(spellIndexDir)) {
				IndexWriter writer = new IndexWriter(spellIndexDir, new IndexWriterConfig(null));
				writer.close();
			}
			swapSearcher(spellIndexDir);
		}
	}

	public void setComparator(Comparator<SuggestWord> comparator) {
		this.comparator = comparator;
	}

	public Comparator<SuggestWord> getComparator() {
		return comparator;
	}

	public void setStringDistance(StringDistance sd) {
		this.sd = sd;
	}

	public StringDistance getStringDistance() {
		return sd;
	}

	public void setAccuracy(float acc) {
		this.accuracy = acc;
	}

	public float getAccuracy() {
		return accuracy;
	}

	public String[] suggestSimilar(String word, int numSug) throws IOException {
		return this.suggestSimilar(word, numSug, null, null, SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX);
	}

	public String[] suggestSimilar(String word, int numSug, float accuracy) throws IOException {
		return this.suggestSimilar(word, numSug, null, null, SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX, accuracy);
	}

	public String[] suggestSimilar(String word, int numSug, IndexReader ir, String field, SuggestMode suggestMode)
			throws IOException {
		return suggestSimilar(word, numSug, ir, field, suggestMode, this.accuracy);
	}

	public String[] suggestSimilar(String word, int numSug, IndexReader ir, String field) throws IOException {
		return suggestSimilar(word, numSug, ir, field, SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX, this.accuracy);
	}

	public String[] suggestSimilar(String word, int numSug, IndexReader ir, String field, SuggestMode suggestMode,
			float accuracy) throws IOException {
		// obtainSearcher calls ensureOpen
		final IndexSearcher indexSearcher = obtainSearcher();
		try {
			if (ir == null || field == null) {
				suggestMode = SuggestMode.SUGGEST_ALWAYS;
			}
			if (suggestMode == SuggestMode.SUGGEST_ALWAYS) {
				ir = null;
				field = null;
			}

			final int freq = (ir != null && field != null) ? ir.docFreq(new Term(field, word)) : 0;
			final int goalFreq = suggestMode == SuggestMode.SUGGEST_MORE_POPULAR ? freq : 0;
			// if the word exists in the real index and we don't care for word frequency,
			// return the word itself
			if (suggestMode == SuggestMode.SUGGEST_WHEN_NOT_IN_INDEX && freq > 0) {
				return new String[] { word };
			}

			BooleanQuery.Builder query = new BooleanQuery.Builder();
			ArrayList<String> grams;

			// grams = formGrams(word, ng); // form word into ngrams (allow dups too)
			grams = createNgrams(word); // form word into ngrams (allow dups too)
			String key = "trigram"; // form key
			for (int i = 0; i < grams.size(); i++) {
				add(query, key, grams.get(i));
			}

			/*
			 * TODO : This peace of code was made to fasten the search, although this truly
			 * limits the scope of the search results. Originally the value was set to 10
			 * which is way too low
			 */
			int maxHits = 100 * numSug;

			ScoreDoc[] hits = indexSearcher.search(query.build(), maxHits).scoreDocs;
			SuggestWordQueue sugQueue = new SuggestWordQueue(numSug, comparator);

			// go thru more than 'maxr' matches in case the distance filter triggers
			int stop = Math.min(hits.length, maxHits);
			System.out.println(stop);
			SuggestWord sugWord = new SuggestWord();
			for (int i = 0; i < stop; i++) {

				sugWord.string = indexSearcher.doc(hits[i].doc).get(F_WORD); // get orig word

				// don't suggest a word for itself, that would be silly
				if (sugWord.string.equals(word)) {
					continue;
				}

				// edit distance
				sugWord.score = sd.getDistance(word, sugWord.string);
				if (sugWord.score < accuracy) {
					continue;
				}

				if (ir != null && field != null) { // use the user index
					sugWord.freq = ir.docFreq(new Term(field, sugWord.string)); // freq in the index
					// don't suggest a word that is not present in the field
					if ((suggestMode == SuggestMode.SUGGEST_MORE_POPULAR && goalFreq > sugWord.freq)
							|| sugWord.freq < 1) {
						continue;
					}
				}
				sugQueue.insertWithOverflow(sugWord);
				if (sugQueue.size() == numSug) {
					// if queue full, maintain the minScore score
					accuracy = sugQueue.top().score;
				}
				sugWord = new SuggestWord();
			}

			// convert to array string
			String[] list = new String[sugQueue.size()];
			for (int i = sugQueue.size() - 1; i >= 0; i--) {
				list[i] = sugQueue.pop().string;
			}

			return list;
		} finally {
			releaseSearcher(indexSearcher);
		}
	}

	private static void add(BooleanQuery.Builder q, String name, String value) {
		q.add(new BooleanClause(new TermQuery(new Term(name, value)), BooleanClause.Occur.SHOULD));
	}

	/* Singular mapping */
	public static ArrayList<String> createNgramsSingular(int ngram, String word) {
		ArrayList<String> stringVector = new ArrayList<String>();
		for (int index = 0; index < word.length(); index++) {

			if (index - ngram + 1 < 0) {
				stringVector.add(word.substring(0, index + 1));
			}

			if (index + ngram > word.length()) {
				stringVector.add(word.substring(index));
			}

			if (index + ngram <= word.length())
				stringVector.add(word.substring(index, index + ngram));
		}
		return stringVector;
	}

	/* tri-gram or four-gram mapping */
	public static ArrayList<String> createNgrams(String word) {
		ArrayList<String> stringVector = new ArrayList<String>();
		if (word.length() < 3) {
			/* testing */
			stringVector = createNgramsSingular(3, word);
			// stringVector.add(word);
			return stringVector;
		}

		int ngram = 0;
		if (word.length() > 4)
			ngram = 4;
		else
			ngram = 3;

		/*
		 * adding the 2..ngram-1 grams of the beginning of the word. We do not want any
		 * grams only containing one character
		 */
		for (int i = 2; i < ngram; i++)
			stringVector.add(word.substring(0, i));
		/*
		 * adding the 2..ngram-1 grams of the end of the word. We do not want any grams
		 * only containing one character
		 */
		for (int i = word.length() - ngram + 1; i < word.length() - 1; i++)
			stringVector.add(word.substring(i, word.length()));

		/* creating the overlapping ngrams */
		for (int index = 0; index < word.length(); index++) {
			if (index + ngram <= word.length())
				stringVector.add(word.substring(index, index + ngram));
		}
		return stringVector;
	}

	public void clearIndex() throws IOException {
		synchronized (modifyCurrentIndexLock) {
			ensureOpen();
			final Directory dir = this.spellIndex;
			final IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(null).setOpenMode(OpenMode.CREATE));
			writer.close();
			swapSearcher(dir);
		}
	}

	public boolean exist(String word) throws IOException {
		// obtainSearcher calls ensureOpen
		final IndexSearcher indexSearcher = obtainSearcher();
		try {
			// TODO: we should use ReaderUtil+seekExact, we dont care about the docFreq
			// this is just an existence check
			return indexSearcher.getIndexReader().docFreq(new Term(F_WORD, word)) > 0;
		} finally {
			releaseSearcher(indexSearcher);
		}
	}

	public final void indexDictionary(Dictionary dict, IndexWriterConfig config, boolean fullMerge) throws IOException {
		synchronized (modifyCurrentIndexLock) {
			ensureOpen();
			final Directory dir = this.spellIndex;
			final IndexWriter writer = new IndexWriter(dir, config);
			IndexSearcher indexSearcher = obtainSearcher();
			final List<TermsEnum> termsEnums = new ArrayList<>();

			final IndexReader reader = searcher.getIndexReader();
			if (reader.maxDoc() > 0) {
				for (final LeafReaderContext ctx : reader.leaves()) {
					Terms terms = ctx.reader().terms(F_WORD);
					if (terms != null)
						termsEnums.add(terms.iterator());
				}
			}

			boolean isEmpty = termsEnums.isEmpty();

			try {
				BytesRefIterator iter = dict.getEntryIterator();
				BytesRef currentTerm;

				terms: while ((currentTerm = iter.next()) != null) {

					String word = currentTerm.utf8ToString();

					if (!isEmpty) {
						for (TermsEnum te : termsEnums) {
							if (te.seekExact(currentTerm)) {
								continue terms;
							}
						}
					}

					// ok index the word
					Document doc = createDocument(word);
					writer.addDocument(doc);
				}
			} finally {
				releaseSearcher(indexSearcher);
			}
			if (fullMerge) {
				writer.forceMerge(1);
			}
			// close writer
			writer.close();

			swapSearcher(dir);
		}
	}

	private static Document createDocument(String text) {
		Document doc = new Document();
		// the word field is never queried on... it's indexed so it can be quickly
		// checked for rebuild (and stored for retrieval). Doesn't need norms or TF/pos
		Field f = new StringField(F_WORD, text, Field.Store.YES);
		doc.add(f); // orig term
		addGram(text, doc);
		return doc;
	}

	private static void addGram(String text, Document doc) {
		// int len = text.length();
		// for (int ng = ng1; ng <= ng2; ng++) {
		// String key = "gram" + ng;
		// String end = null;
		// for (int i = 0; i < len - ng + 1; i++) {
		// String gram = text.substring(i, i + ng);
		// FieldType ft = new FieldType(StringField.TYPE_NOT_STORED);
		// ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
		// Field ngramField = new Field(key, gram, ft);
		// // spellchecker does not use positional queries, but we want freqs
		// // for scoring these multivalued n-gram fields.
		// doc.add(ngramField);
		// if (i == 0) {
		// // only one term possible in the startXXField, TF/pos and norms aren't
		// needed.
		// Field startField = new StringField("start" + ng, gram, Field.Store.NO);
		// doc.add(startField);
		// }
		// end = gram;
		// }
		// if (end != null) { // may not be present if len==ng1
		// // only one term possible in the endXXField, TF/pos and norms aren't needed.
		// Field endField = new StringField("end" + ng, end, Field.Store.NO);
		// doc.add(endField);
		// }
		// }

		String key = "trigram"; // form key
		ArrayList<String> grams = createNgrams(text);
		for (String gram : grams) {
			FieldType ft = new FieldType(StringField.TYPE_NOT_STORED);
			ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
			Field ngramField = new Field(key, gram, ft);
			doc.add(ngramField);
		}
	}

	private IndexSearcher obtainSearcher() {
		synchronized (searcherLock) {
			ensureOpen();
			searcher.getIndexReader().incRef();
			return searcher;
		}
	}

	private void releaseSearcher(final IndexSearcher aSearcher) throws IOException {
		aSearcher.getIndexReader().decRef();
	}

	private void ensureOpen() {
		if (closed) {
			throw new AlreadyClosedException("Spellchecker has been closed");
		}
	}

	@Override
	public void close() throws IOException {
		synchronized (searcherLock) {
			ensureOpen();
			closed = true;
			if (searcher != null) {
				searcher.getIndexReader().close();
			}
			searcher = null;
		}
	}

	private void swapSearcher(final Directory dir) throws IOException {
		/*
		 * opening a searcher is possibly very expensive. We rather close it again if
		 * the Spellchecker was closed during this operation than block access to the
		 * current searcher while opening.
		 */
		final IndexSearcher indexSearcher = createSearcher(dir);
		synchronized (searcherLock) {
			if (closed) {
				indexSearcher.getIndexReader().close();
				throw new AlreadyClosedException("Spellchecker has been closed");
			}
			if (searcher != null) {
				searcher.getIndexReader().close();
			}
			// set the spellindex in the sync block - ensure consistency.
			searcher = indexSearcher;
			this.spellIndex = dir;
		}
	}

	IndexSearcher createSearcher(final Directory dir) throws IOException {
		return new IndexSearcher(DirectoryReader.open(dir));
	}

	boolean isClosed() {
		return closed;
	}

}
