package com.texttwist.server;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class WordDictionary {
	private String[] words;
	
	public WordDictionary(String filename) {
		ArrayList<String> wordlist = new ArrayList<>(10000);
		try(BufferedReader in = new BufferedReader(new FileReader(filename))) {
			String line;
			while((line = in.readLine()) != null) {
				wordlist.add(line);
			}
		}
		catch(FileNotFoundException e) {
			this.words = null;
			System.err.println("Impossibile trovare il file");
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		this.words = wordlist.toArray(new String[1]); // Implementare ricerca binaria, per ora, non me ne voglia la JVM
	}
	
	public static String shuffle(String word) {
		// Shuffles letters of a string
		char[] letters = word.toCharArray();
		//Random rnd = new Random(System.currentTimeMillis());
		for (int i = letters.length - 1; i > 0; i--) {
			int index = ThreadLocalRandom.current().nextInt(i + 1);
			// Just swap
			char a = letters[index];
			letters[index] = letters[i];
			letters[i] = a;
		}
		return new String(letters);
	}
	
	public static String removeDuplicates(String str) {
		char[] chars = str.toCharArray();
		Set<Character> charSet = new LinkedHashSet<Character>(); // HashSet to remove duplicates
		for (char c : chars)
		    charSet.add(c);

		StringBuilder sb = new StringBuilder();
		for (char character : charSet)
		    sb.append(character);
		return sb.toString();
	}
	
	public String getFirstWord() {
		while(true) {
			int randomIndex = ThreadLocalRandom.current().nextInt(this.words.length);
			System.out.println("Numero casuale: "+randomIndex);
			if(this.words[randomIndex].length() >= 8) // CONFIGURAZIONE!
				return removeDuplicates(this.words[randomIndex]);
		}
	}
	
	public boolean checkWord(String word, String letters) {
		String regex = "^[" + letters + "]*$";
		return word.matches(regex) && binarySearch(word, 0, this.words.length); // Contains no forbidden letters and is a correct word
	}
	
	private boolean binarySearch(String word, int from, int to) { // from included, to excluded
		//System.out.println("Ricerca binaria di: "+word+" tra "+from+" e "+to);
		if(to-1 < from) {
			return false;
		}
		/*if(from == to-1) {
			return this.words[from].equalsIgnoreCase(word);
		}*/
		int center = (to-1+from)/2;
		if(this.words[center].compareToIgnoreCase(word) == 0) {
			return true;
		}
		else if(this.words[center].compareToIgnoreCase(word) < 0) {
			return binarySearch(word, center+1, to);
		}
		return binarySearch(word, from, center);
	}
}
