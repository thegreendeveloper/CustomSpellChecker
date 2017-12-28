package org.nota.lucene.spell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.spell.StringDistance;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.search.spell.SuggestWordQueue;

public class TrigramSimilarity {

	HashMap<String, HashSet<String>> index;
	
	private Comparator<SuggestWord> comparator = SuggestWordQueue.DEFAULT_COMPARATOR;
	private StringDistance sd;
	private float accuracy = 0.5f;
	
	public TrigramSimilarity(HashSet<String> map, StringDistance sd) {
		this.sd = sd;

		index = new HashMap<String, HashSet<String>>();
		buildIndex(map);

	}

	public SuggestWord[] suggestSimilar(String word, int numSug, IndexReader ir, String field) throws IOException {

		HashMap<String, Float> res = search(word);
		return SortingUtility.sortResultSet(res, comparator, numSug, ir, field);
	}

	HashMap<String, Float> search(String searchString) {
		HashMap<String, Integer> temp = new HashMap<String, Integer>();
		ArrayList<String> inputTrigram = createNgrams(searchString);

		for (String trigram : inputTrigram) {

			if (!index.containsKey(trigram))
				continue;

			HashSet<String> documents = index.get(trigram);
			/*
			 * three grams exist in our dictionary. for each word the three gram exist in,
			 * we want to add it to our hashmap and increment the counter
			 */
			for (String document : documents) {
				if (temp.containsKey(document))
					temp.put(document, temp.get(document) + 1);
				else
					temp.put(document, 1);
			}

		}

		/*
		 * Calculated dice coefficient. Every word consist of n+2 trigrams. Multiplying
		 * by 100.
		 */
		HashMap<String, Float> final_resultSet = new HashMap<String, Float>();
		float dist = 0.0f;
		for (String key : temp.keySet()) {

			if (key.equals(searchString))
				dist = 1.0f;
			else
				dist = sd.getDistance(searchString, key);

			if (dist >= accuracy)
				final_resultSet.put(key, (dist * 100.f) / 100.f);
		}

		return final_resultSet;
	}

	void buildIndex(HashSet<String> map) {

		for (String key : map) {
			// Split string into n-grams
			ArrayList<String> threeGramsWord = createNgrams(key);

			// for each three grams in the vector insert three gram into ngramsDataSet and
			// insert the word as well.
			for (String gram : threeGramsWord) {
				if (index.containsKey(gram)) {
					index.get(gram).add(key);
				} else {
					HashSet<String> documents = new HashSet<String>();
					documents.add(key);
					index.put(gram, documents);
				}
			}
		}
	}

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

	public void setAccuracy(float accuracy) {
		// TODO Auto-generated method stub
		this.accuracy = accuracy;
	}

	public void setComparator(Comparator<SuggestWord> comparator) {
		// TODO Auto-generated method stub
		this.comparator = comparator;
	}

}
