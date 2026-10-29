#!/usr/bin/env python3
"""Compile and run selected real Java logic extracted from MainActivity without Android SDK."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java"
CONVERSATION_LOGIC = ROOT / "app/src/main/java/com/ammu/assistant/ConversationLogic.java"
PIPELINE = ROOT / "app/src/main/java/com/ammu/assistant/ConversationPipeline.java"


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

    harness = r'''package com.ammu.assistant;
import java.util.*;
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
        check("identity".equals(ConversationLogic.classify("What's your name?")), "identity question recognized separately");
        check("wellbeing".equals(ConversationLogic.classify("Are you okay?")), "assistant wellbeing question recognized separately");
        check("wellbeing".equals(ConversationLogic.classify("You all right, Ammu?")), "informal wellbeing question recognized");
        check("identity".equals(ConversationLogic.classify("What should I call you?")), "alternate identity question recognized");
        check("follow_up".equals(ConversationLogic.classify("What is it?")), "ambiguous follow-up recognized separately");
        check("ordinary_question".equals(ConversationLogic.classify("Who is the author?")), "ordinary question recognized");
        check("correction".equals(ConversationLogic.classify("I need to correct what I said")), "correction request recognized");
        check("greeting".equals(ConversationLogic.classify("Good morning, Ammu")), "greeting recognized");
        check("correction".equals(ConversationLogic.classify("Actually, my name is Mira")), "correction recognized");
        check(ConversationLogic.followUpResponse("what is it?", "I mentioned my exam", "What worries you most?").contains("I mentioned my exam"), "follow-up uses the immediately previous topic");
        check("favorite color".equals(ConversationLogic.personalMemoryQuestionSubject("What's my favorite color?")), "personal memory question extracts its subject");
        check("name".equals(ConversationLogic.personalMemoryQuestionSubject("What is my name?")), "personal name question extracts its subject");
        check(ConversationLogic.followUpResponse("what is it?", "", "").contains("What does"), "context-free follow-up asks for clarification");
        check(ConversationLogic.questionClarification("Who is that?").contains("Which person"), "unknown who-question requests relevant clarification");
        check("personal_fact".equals(ConversationLogic.memoryType("my name is Mira")), "stable fact has separate memory type");
        check("preference".equals(ConversationLogic.memoryType("I prefer concise replies")), "preference has separate memory type");
        check("temporary".equals(ConversationLogic.memoryType("for today: bring water")), "temporary context has separate memory type");
        check("my name".equals(ConversationLogic.memoryKey("my name is Mira")), "fact correction key uses the stable subject");
        check(ConversationLogic.isExplicitCorrection("Actually, my name is Mira"), "explicit correction is recognized");
        check(!ConversationLogic.isExplicitCorrection("maybe my name is Mira"), "uncertain inference is not treated as an explicit correction");
        check(ConversationLogic.isExplicitCorrection("Actually, I prefer Kannada replies"), "explicit preference correction is recognized");
        check(ConversationLogic.isExplicitPreference("I prefer Kannada replies"), "explicit preference is saved locally");
        check(ConversationLogic.isSensitive("remember that my password is secret"), "sensitive memories require confirmation");
        check(ConversationLogic.supportsOnDeviceLanguage(Arrays.asList("en-US", "hi-IN"), "hi-IN"), "installed offline locale is accepted");
        check(!ConversationLogic.supportsOnDeviceLanguage(Arrays.asList("en-US"), "kn-IN"), "missing offline locale is rejected");
        ConversationPipeline pipeline = new ConversationPipeline(text -> "unknown");
        check("identity".equals(pipeline.respond("What's your name?", Arrays.asList()).intent), "pipeline recognizes identity paraphrase");
        check("wellbeing".equals(pipeline.respond("Are you okay?", Arrays.asList()).intent), "pipeline recognizes wellbeing paraphrase");
        check("knowledge_question".equals(pipeline.respond("Explain artificial intelligence.", Arrays.asList()).intent), "pipeline explains local knowledge topic");
        pipeline.respond("Explain artificial intelligence.", Arrays.asList());
        check("follow_up".equals(pipeline.respond("What is it?", Arrays.asList()).intent), "pipeline resolves ambiguous it after a topic");
        pipeline.respond("Explain artificial intelligence.", Arrays.asList());
        check("follow_up".equals(pipeline.respond("Why?", Arrays.asList()).intent), "pipeline resolves why after a topic");
        check(pipeline.respond("Tell me about quantum teleportation economics", Arrays.asList()) == null, "unknown knowledge remains explicitly unknown");
        check("memory_recall".equals(pipeline.respond("What colour do I like?", Arrays.asList("I prefer blue")).intent), "pipeline recalls a saved preference");
        System.out.println("PASS: 54 Java logic tests covering conversation intent/context, memory classification, offline language checks, multilingual normalization, emotion, safety and calculator");
    }
    static void check(boolean passed, String what) { if (!passed) throw new AssertionError(what); }
}
'''
    with tempfile.TemporaryDirectory(prefix="ammu-v9-java-test-") as directory:
        package_dir = Path(directory) / "com" / "ammu" / "assistant"
        package_dir.mkdir(parents=True)
        java_file = package_dir / "AmmuLogicHarness.java"
        java_file.write_text(harness, encoding="utf-8")
        logic_file = package_dir / "ConversationLogic.java"
        logic_file.write_text(CONVERSATION_LOGIC.read_text(encoding="utf-8"), encoding="utf-8")
        pipeline_file = package_dir / "ConversationPipeline.java"
        pipeline_file.write_text(PIPELINE.read_text(encoding="utf-8"), encoding="utf-8")
        subprocess.run(["javac", "-d", directory, str(java_file), str(logic_file), str(pipeline_file)], check=True, cwd=directory)
        subprocess.run(["java", "-cp", directory, "com.ammu.assistant.AmmuLogicHarness"], check=True, cwd=directory)


if __name__ == "__main__":
    main()
