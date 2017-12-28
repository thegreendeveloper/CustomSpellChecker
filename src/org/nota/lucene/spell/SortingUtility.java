package org.nota.lucene.spell;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.search.spell.SuggestWordQueue;

import java.util.Map.Entry;

public class SortingUtility {

	public static SuggestWord[] sortResultSet(HashMap<String, Float> res, Comparator<SuggestWord> comparator,
			int numSug, IndexReader ir, String field) throws IOException {

		SuggestWordQueue sugQueue = new SuggestWordQueue(res.size(), comparator);
		SuggestWord sugWord = new SuggestWord();

		for (String key : res.keySet()) {
			sugWord.string = key;
			sugWord.score = res.get(key);
			sugWord.freq = ir.docFreq(new Term(field, sugWord.string));

			sugQueue.add(sugWord);
			sugWord = new SuggestWord();
		}

		// convert to array string
		SuggestWord[] resultSet = new SuggestWord[sugQueue.size()];
		for (int i = sugQueue.size() - 1; i >= 0; i--) {
			resultSet[i] = sugQueue.pop();
		}
		if (resultSet.length <= numSug)
			return resultSet;
		return Arrays.copyOfRange(resultSet, 0, numSug);// SortingUtility.resultSetSort(final_resultSet, 3);

	}

}
