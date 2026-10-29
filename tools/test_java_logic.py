#!/usr/bin/env python3
"""Compile and run selected real Java logic extracted from MainActivity without Android SDK."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java"


def exact_line(prefix: str) -> str:
    for line in SOURCE.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith(prefix):
            return line.strip()
    raise RuntimeError(f"Could not find Java source line beginning with {prefix!r}")


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    start = source.index("    boolean isCalculationRequest(")
    end = source.index("    int nextTaskId()", start)
    helpers = source[start:end]
    helpers += "\n" + exact_line("boolean isExplicitDeleteAllNotes(") + "\n"
    helpers += "\n" + exact_line("boolean isExplicitJournalAddCommand(") + "\n"
    strip_prefix = exact_line("String stripPrefix(")
    calc = exact_line("static class Calc{")
    normalizer = source[source.index("    String normalizeOfflinePhrase("):source.index("    void send()", source.index("    String normalizeOfflinePhrase("))]
    emotions = source[source.index("    boolean isFeelingStatement("):source.index("    String negatedFeelingReply(", source.index("    boolean isFeelingStatement("))]
    safety = source[source.index("    String safetyResponse("):source.index("    // The small model", source.index("    String safetyResponse("))]
    char_features = source[source.index("    void addCharacterFeatures("):source.index("    // Correct only an unknown", source.index("    void addCharacterFeatures("))]

    harness = r'''import java.util.*;
import java.util.regex.*;
public class AmmuLogicHarness {
''' + helpers + '\n' + normalizer + '\n' + emotions + '\n' + safety + '\n' + char_features + "\n" + strip_prefix + "\n" + calc + r'''
    public static void main(String[] args) {
        AmmuLogicHarness g = new AmmuLogicHarness();
        check(!g.isExplicitSingleNoteDelete("don't delete note 2"), "negated note delete blocked");
        check(g.isExplicitSingleNoteDelete("delete note 2"), "explicit note delete accepted");
        check(!g.isExplicitDeleteAllNotes("do not clear my notes"), "negated bulk delete blocked");
        check(g.isExplicitDeleteAllNotes("clear my notes"), "explicit bulk delete accepted");
        check(!g.isExplicitTaskCompletion("I have not completed task 1"), "negated task completion blocked");
        check(g.isExplicitTaskCompletion("I have completed task 1"), "explicit completion accepted");
        check(!g.isExplicitTaskAddCommand("don't add task 2"), "negated task addition blocked");
        check(!g.isExplicitNoteAddCommand("don't write down note 2"), "negated note write blocked");
        check(!g.isExplicitMemorySaveCommand("don't remember this"), "negated memory save blocked");
        check(g.isExplicitSettingsOpenCommand("open android settings"), "explicit settings command allowed");
        check(!g.isExplicitSettingsOpenCommand("I was reading about settings"), "discussion of settings does not open settings");
        check(!g.isCalculationRequest("what is quantum gravity", "unknown"), "general question not calculator");
        check(g.isCalculationRequest("what is 2+2", "unknown"), "numeric expression routes to calculator");
        Set<String> charGrams = new HashSet<>(); g.addCharacterFeatures(charGrams, "ammu");
        check(charGrams.contains("ch3:amm") && charGrams.contains("ch4:ammu"), "character n-gram feature parity");
        Set<String> nativeGrams = new HashSet<>(); g.addCharacterFeatures(nativeGrams, "ನಮಸ್ಕಾರ");
        check(nativeGrams.contains("ch3:ನಮಸ"), "Kannada character n-grams");
        check("this".equals(g.taskBodyFromCommand("I need to add this to my tasks")), "task body extraction");
        check("revise dbms".equals(g.taskBodyFromCommand("put revise dbms on my to do list")), "task suffix removal");
        check("I need to call dad".equals(g.noteBodyFromCommand("write down that I need to call dad")), "note body extraction");
        check("what time is it".equals(g.normalizeOfflinePhrase("अभी कितने बजे हैं")), "Hindi time command normalization");
        check("save a note: दूध खरीदना".equals(g.normalizeOfflinePhrase("नोट सेव करो: दूध खरीदना")), "Hindi note add command normalization");
        check("add task: DBMS ಓದು".equals(g.normalizeOfflinePhrase("ಕೆಲಸ ಸೇರಿಸು: DBMS ಓದು")), "Kannada task command normalization");
        check("दूध खरीदना".equals(g.noteBodyFromCommand("नोट सेव करो: दूध खरीदना")), "Hindi note body extraction");
        check("DBMS ಓದು".equals(g.taskBodyFromCommand("ಕೆಲಸ ಸೇರಿಸು: DBMS ಓದು")), "Kannada task body extraction");
        check("open app camera".equals(g.normalizeOfflinePhrase("ಕ್ಯಾಮೆರಾ ತೆರೆಯು")), "Kannada app-launch normalization");
        check("open app calculator".equals(g.normalizeOfflinePhrase("कैलकुलेटर खोलो")), "Hindi app-launch normalization");
        check(g.isFeelingStatement("ನನಗೆ ದುಃಖವಾಗಿದೆ") && "sad".equals(g.detectEmotion("ನನಗೆ ದುಃಖವಾಗಿದೆ")), "Kannada sadness cue");
        check(g.isNegatedFeeling("ನನಗೆ ದುಃಖವಾಗಿಲ್ಲ"), "Kannada negation cue");
        check(g.safetyResponse("ನನಗೆ ಬದುಕಲು ಇಷ್ಟವಿಲ್ಲ") != null, "Kannada crisis phrase guard");
        check(Math.abs(new Calc("2^5").parse() - 32.0) < 1e-9, "power operation");
        check(Math.abs(new Calc("(12+4)*3").parse() - 48.0) < 1e-9, "parentheses and precedence");
        check(Math.abs(new Calc("17%5").parse() - 2.0) < 1e-9, "modulo operation");
        check(Math.abs(new Calc("2^3^2").parse() - 512.0) < 1e-9, "right-associative exponentiation");
        boolean rejected = false;
        try { new Calc("3/0").parse(); } catch (RuntimeException expected) { rejected = true; }
        check(rejected, "division by zero rejected");
        System.out.println("PASS: 32 Java logic, multilingual normalization, character features, emotion, settings gate and calculator tests");
    }
    static void check(boolean passed, String what) { if (!passed) throw new AssertionError(what); }
}
'''
    with tempfile.TemporaryDirectory(prefix="ammu-v9-java-test-") as directory:
        java_file = Path(directory) / "AmmuLogicHarness.java"
        java_file.write_text(harness, encoding="utf-8")
        subprocess.run(["javac", str(java_file)], check=True, cwd=directory)
        subprocess.run(["java", "-cp", directory, "AmmuLogicHarness"], check=True, cwd=directory)


if __name__ == "__main__":
    main()
