package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.engine.ui.text.HunspellChecker;
import me.piitex.engine.ui.text.TextChecker;
import me.piitex.engine.ui.text.TextIssue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Spelling from the engine's dictionary plus a few grammar rules of the app's own, so the engine stays out of
// what counts as a mistake. The rules are small on purpose: roleplay is full of fragments, *actions* and dialect,
// so only things that are almost always wrong get underlined.
public final class GrammarChecker implements TextChecker {
    private static GrammarChecker shared;

    private final HunspellChecker spelling;

    private GrammarChecker(HunspellChecker spelling) {
        this.spelling = spelling;
    }

    // One checker for every text area, loading the dictionary takes a moment. Null if the dictionary is missing.
    public static synchronized GrammarChecker shared() {
        if (shared == null) {
            try {
                shared = new GrammarChecker(HunspellChecker.english());
            } catch (IOException e) {
                App.logger.error("Could not load the spelling dictionary!", e);
            }
        }
        return shared;
    }

    // Loads the dictionary in the background so the first chat or creator that wants it doesn't wait for it.
    public static void warmUp() {
        Thread.ofVirtual().name("spelling-dictionary").start(GrammarChecker::shared);
    }

    // Names the dictionary doesn't know, like a character called Aria.
    public void allow(String... words) {
        for (String name : words) {
            if (name == null) continue;
            for (String word : name.trim().split("\\s+")) {
                if (!word.isEmpty()) spelling.addWord(word);
            }
        }
    }

    // A rule finds a span and what should replace it.
    private record Fix(int start, int end, String message, String replacement) {
    }

    private record Rule(Pattern pattern, String message, Function<Matcher, Fix> fix) {
    }

    private static final Set<String> MAY_REPEAT = Set.of("had", "that", "no", "so", "ha", "bye", "yeah", "knock", "very");
    private static final Set<String> ABBREVIATIONS = Set.of("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "etc", "no", "inc", "ltd", "prof");
    private static final Set<String> A_BEFORE_VOWEL = Set.of("one", "once", "ones", "european", "eulogy", "euphoria", "ewe", "ouija");
    private static final Set<String> AN_BEFORE_CONSONANT = Set.of("hour", "hours", "honest", "honor", "honour", "heir", "heiress");

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(\\p{L}+)([ \\t]+)\\1\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                    "Repeated word", m -> MAY_REPEAT.contains(m.group(1).toLowerCase(Locale.ROOT)) ? null
                    : new Fix(m.start(1), m.end(), "Repeated word", m.group(1))),
            new Rule(Pattern.compile("(?<![\\p{L}\\p{N}_.'\u2019-])i(?=['\u2019](?:m|ll|ve|d)\\b|[ \\t\\n,!?;:]|$)"),
                    "\"I\" is capitalized", m -> new Fix(m.start(), m.end(), "\"I\" is capitalized", "I")),
            new Rule(Pattern.compile("\\b(?:should|could|would|must|might)[ \\t]+(of)\\b", Pattern.CASE_INSENSITIVE),
                    "Did you mean \"have\"?", m -> new Fix(m.start(1), m.end(1), "Did you mean \"have\"?", "have")),
            new Rule(Pattern.compile("\\b(?:more|less|better|worse|bigger|smaller|rather|faster|slower|larger|older|younger|taller|stronger)[ \\t]+(then)\\b",
                    Pattern.CASE_INSENSITIVE), "Did you mean \"than\"?", m -> new Fix(m.start(1), m.end(1), "Did you mean \"than\"?", "than")),
            new Rule(Pattern.compile("\\b(its)[ \\t]+(?:a|an|been|not|going|really)\\b", Pattern.CASE_INSENSITIVE),
                    "Did you mean \"it's\"?", m -> new Fix(m.start(1), m.end(1), "Did you mean \"it's\"?", match(m.group(1), "it's"))),
            new Rule(Pattern.compile("\\b(your)[ \\t]+welcome\\b", Pattern.CASE_INSENSITIVE),
                    "Did you mean \"you're\"?", m -> new Fix(m.start(1), m.end(1), "Did you mean \"you're\"?", match(m.group(1), "you're"))),
            new Rule(Pattern.compile("\\b(?:he|she|it)[ \\t]+(don't|dont|don\u2019t)\\b", Pattern.CASE_INSENSITIVE),
                    "Use \"doesn't\" here", m -> new Fix(m.start(1), m.end(1), "Use \"doesn't\" here", match(m.group(1), "doesn't"))),
            new Rule(Pattern.compile("\\b(?:they|we|you)[ \\t]+(was)\\b", Pattern.CASE_INSENSITIVE),
                    "Use \"were\" here", m -> new Fix(m.start(1), m.end(1), "Use \"were\" here", match(m.group(1), "were"))),
            new Rule(Pattern.compile("\\b(?:he|she|it)[ \\t]+(are)\\b", Pattern.CASE_INSENSITIVE),
                    "Use \"is\" here", m -> new Fix(m.start(1), m.end(1), "Use \"is\" here", match(m.group(1), "is"))),
            new Rule(Pattern.compile("\\b(?:they|we|you)[ \\t]+(is)\\b", Pattern.CASE_INSENSITIVE),
                    "Use \"are\" here", m -> new Fix(m.start(1), m.end(1), "Use \"are\" here", match(m.group(1), "are"))),
            new Rule(Pattern.compile("\\bI[ \\t]+(is|are)\\b"),
                    "Use \"am\" here", m -> new Fix(m.start(1), m.end(1), "Use \"am\" here", "am")),
            new Rule(Pattern.compile("\\b(?:he|she|it)[ \\t]+(have)\\b", Pattern.CASE_INSENSITIVE),
                    "Use \"has\" here", m -> new Fix(m.start(1), m.end(1), "Use \"has\" here", match(m.group(1), "has")))
    );

    // a/an and capitals need more than a pattern, so they are done by hand.
    private static final Pattern ARTICLE = Pattern.compile("\\b([Aa]n?)[ \\t]+(\\p{L}[\\p{L}']*)");
    private static final Pattern SENTENCE_END = Pattern.compile("([\\p{L}]*)([.!?])([ \\t]+)(\\p{Ll}[\\p{L}']*)");

    @Override
    public List<TextIssue> check(String text) {
        List<TextIssue> issues = new ArrayList<>(spelling.check(text));
        for (Fix fix : fixes(text)) {
            issues.add(new TextIssue(fix.start(), fix.end(), TextIssue.Kind.GRAMMAR, fix.message()));
        }
        return issues;
    }

    @Override
    public List<String> suggest(String text, TextIssue issue) {
        if (issue.kind() == TextIssue.Kind.SPELLING) return spelling.suggest(text, issue);
        List<String> suggestions = new ArrayList<>();
        for (Fix fix : fixes(text)) {
            if (fix.start() == issue.start() && fix.end() == issue.end() && !suggestions.contains(fix.replacement())) {
                suggestions.add(fix.replacement());
            }
        }
        return suggestions;
    }

    // One fix per span. When a word breaks two rules (a lowercase "its" starting a sentence) the fix is the
    // first rule's with the capital applied on top, and the first rule's wording is the one shown.
    private static List<Fix> fixes(String text) {
        List<Fix> found = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                Fix fix = rule.fix().apply(matcher);
                if (fix != null) found.add(fix);
            }
        }
        articles(text, found);
        List<Fix> capitals = new ArrayList<>();
        capitals(text, capitals);

        for (Fix capital : capitals) {
            boolean merged = false;
            for (int i = 0; i < found.size(); i++) {
                Fix other = found.get(i);
                if (other.start() != capital.start() || other.end() != capital.end()) continue;
                String upper = other.replacement().isEmpty() ? "" : other.replacement().substring(0, 1).toUpperCase(Locale.ROOT) + other.replacement().substring(1);
                found.set(i, new Fix(other.start(), other.end(), other.message(), upper));
                merged = true;
            }
            if (!merged) found.add(capital);
        }
        return found;
    }

    private static void articles(String text, List<Fix> found) {
        Matcher matcher = ARTICLE.matcher(text);
        while (matcher.find()) {
            String article = matcher.group(1), word = matcher.group(2);
            String lower = word.toLowerCase(Locale.ROOT);
            // "Plan A is" and "Vitamin A and" are not articles, only trust a capital A at the start of a sentence.
            if (article.equals("A") || article.equals("An")) {
                if (!startsSentence(text, matcher.start(1))) continue;
            }
            // An acronym like "an FBI agent" is read letter by letter.
            if (word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT))) continue;
            char first = lower.charAt(0);
            boolean capital = java.lang.Character.isUpperCase(article.charAt(0));
            if (article.equalsIgnoreCase("a")) {
                boolean vowel = "aeio".indexOf(first) >= 0 && !A_BEFORE_VOWEL.contains(lower);
                if (vowel) found.add(new Fix(matcher.start(1), matcher.end(1), "Use \"an\" before a vowel sound", capital ? "An" : "an"));
            } else {
                boolean consonant = "bcdfgjklmnpqrstvwyz".indexOf(first) >= 0 && !AN_BEFORE_CONSONANT.contains(lower);
                if (consonant) found.add(new Fix(matcher.start(1), matcher.end(1), "Use \"a\" before a consonant sound", capital ? "A" : "a"));
            }
        }
    }

    private static void capitals(String text, List<Fix> found) {
        // The very start of the text.
        int start = 0;
        while (start < text.length() && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) start++;
        if (start < text.length() && java.lang.Character.isLowerCase(text.charAt(start))) {
            int end = wordEnd(text, start);
            if (end - start > 1) found.add(capital(text, start, end));
        }

        Matcher matcher = SENTENCE_END.matcher(text);
        while (matcher.find()) {
            String before = matcher.group(1).toLowerCase(Locale.ROOT);
            boolean ellipsis = matcher.start(2) > 0 && text.charAt(matcher.start(2) - 1) == '.';
            if (ellipsis) continue;
            // "e.g. something" and "Mr. smith" don't end a sentence.
            if (matcher.group(2).equals(".") && (before.length() <= 1 || ABBREVIATIONS.contains(before))) continue;
            // Nor does "e.g." or "i.e." after one.
            if (matcher.end(4) < text.length() && text.charAt(matcher.end(4)) == '.') continue;
            found.add(capital(text, matcher.start(4), matcher.end(4)));
        }
    }

    private static Fix capital(String text, int start, int end) {
        String word = text.substring(start, end);
        String fixed = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
        return new Fix(start, end, "Start a sentence with a capital letter", fixed);
    }

    private static int wordEnd(String text, int start) {
        int end = start;
        while (end < text.length() && (java.lang.Character.isLetter(text.charAt(end)) || text.charAt(end) == '\'')) end++;
        return end;
    }

    private static boolean startsSentence(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') continue;
            return c == '.' || c == '!' || c == '?' || c == '\n' || c == '"' || c == '*';
        }
        return true;
    }

    // The replacement in the same case as what it replaces.
    private static String match(String original, String replacement) {
        if (original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT))) return replacement.toUpperCase(Locale.ROOT);
        if (java.lang.Character.isUpperCase(original.charAt(0))) return replacement.substring(0, 1).toUpperCase(Locale.ROOT) + replacement.substring(1);
        return replacement;
    }
}
