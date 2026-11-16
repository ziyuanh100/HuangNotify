package dev.huang.notify;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Local substring search. Each word must match a field of the same notification version. */
final class SearchQuery {
  // Bound both the number of SQL parameters and the size of pasted search patterns.
  static final int MAX_INPUT = 512;
  private static final int CONTEXT = 8, EXCERPT_LENGTH = 92;
  private final List<String> words;

  SearchQuery(String input) {
    if (input.length() > MAX_INPUT) input = input.substring(0, MAX_INPUT);
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String word : input.split("[\\p{javaWhitespace}\\p{Zs}]+")) {
      if (!word.isEmpty()) unique.add(fold(word));
    }
    words = new ArrayList<>(unique);
  }

  static boolean hasText(String input) {
    for (int i = 0; i < Math.min(input.length(), MAX_INPUT); i++) {
      char c = input.charAt(i);
      if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return true;
    }
    return false;
  }

  String predicate(String textTable, int firstParameter) {
    if (words.isEmpty()) return "1=1";
    List<String> clauses = new ArrayList<>(words.size());
    for (int i = 0; i < words.size(); i++) {
      String arg = " GLOB ?" + (firstParameter + i);
      clauses.add("(" + textTable + ".title" + arg + " OR " + textTable + ".body" + arg
          + " OR r.app" + arg + " OR r.note" + arg + ")");
    }
    return String.join(" AND ", clauses);
  }

  void addPatterns(List<String> args) {
    for (String word : words) args.add(glob(word));
  }

  private static String glob(String word) {
    StringBuilder pattern = new StringBuilder("*");
    for (int i = 0; i < word.length(); i++) {
      char c = word.charAt(i);
      if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')) {
        pattern.append('[').append(c).append((char) (c + 0xfee0));
        if (c >= 'a') {
          char upper = Character.toUpperCase(c);
          pattern.append(upper).append((char) (upper + 0xfee0));
        }
        pattern.append(']');
      } else if (c == '*' || c == '?' || c == '[') {
        pattern.append('[').append(c).append(']');
      } else pattern.append(c);
    }
    return pattern.append('*').toString();
  }

  private static String fold(String text) {
    StringBuilder folded = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c >= '０' && c <= '９') || (c >= 'Ａ' && c <= 'Ｚ') || (c >= 'ａ' && c <= 'ｚ')) {
        c -= 0xfee0;
      }
      if (c >= 'A' && c <= 'Z') c = Character.toLowerCase(c);
      folded.append(c);
    }
    return folded.toString();
  }

  boolean matchesField(String value) {
    return firstMatch(fold(value)) >= 0;
  }

  private int firstMatch(String folded) {
    int first = folded.length();
    for (String word : words) {
      int index = folded.indexOf(word);
      if (index >= 0) first = Math.min(first, index);
    }
    return first == folded.length() ? -1 : first;
  }

  String excerpt(String value) {
    if (value.length() <= EXCERPT_LENGTH) return value;
    String folded = fold(value);
    int first = firstMatch(folded);
    int start = Math.max(0, first - CONTEXT);
    if (start > 0 && Character.isSurrogatePair(value.charAt(start - 1), value.charAt(start))) start--;
    int end = Math.min(value.length(), start + EXCERPT_LENGTH);
    for (String word : words) {
      if (first >= 0 && folded.startsWith(word, first)) end = Math.max(end, first + word.length());
    }
    if (end < value.length() && Character.isSurrogatePair(value.charAt(end - 1), value.charAt(end))) end++;
    return (start == 0 ? "" : "…") + value.substring(start, end) + (end == value.length() ? "" : "…");
  }

  CharSequence highlight(String value, int color) {
    if (value.isEmpty()) return value;
    String folded = fold(value);
    SpannableString styled = new SpannableString(value);
    for (String word : words) {
      int start = folded.indexOf(word);
      for (int hits = 0; start >= 0 && hits < 16; hits++) {
        int end = start + word.length();
        styled.setSpan(new BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = folded.indexOf(word, end);
      }
    }
    return styled;
  }
}
