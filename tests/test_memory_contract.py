import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = (ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java").read_text(encoding="utf-8")


def method_body(signature: str) -> str:
    start = JAVA.index(signature)
    opening = JAVA.index("{", start)
    depth = 0
    for index in range(opening, len(JAVA)):
        if JAVA[index] == "{":
            depth += 1
        elif JAVA[index] == "}":
            depth -= 1
            if depth == 0:
                return JAVA[opening + 1:index]
    raise AssertionError(f"Unclosed Java method: {signature}")


class LocalMemoryContractTests(unittest.TestCase):
    def test_explicit_memories_are_typed_and_persisted_for_restart(self):
        saving = method_body("String persistMemoryFact(String fact,String type,long expiry)")
        loading = method_body("void loadData()")
        self.assertIn('"type",type', saving)
        self.assertIn('"key",key', saving)
        self.assertIn('"source","unverified_guess".equals(type)?"user_confirmed_uncertain":"explicit_user"', saving)
        self.assertIn('save("memory",memory)', saving)
        self.assertIn('memory=load("memory")', loading)

    def test_corrections_replace_the_matching_memory_key(self):
        saving = method_body("String persistMemoryFact(String fact,String type,long expiry)")
        self.assertIn("type.equals(savedType)&&key.equals(savedKey)", saving)
        self.assertIn('item.put("fact",fact)', saving)
        self.assertIn("memory.put(i,item);save(\"memory\",memory)", saving)
        self.assertIn("handleExplicitMemoryCorrection(userText,raw)", JAVA)

    def test_sensitive_and_uncertain_memories_require_confirmation(self):
        saving = method_body("String saveMemoryFact(")
        self.assertIn("if(sensitive||uncertain)", saving)
        self.assertIn(".setPositiveButton(\"Save on this phone\"", saving)
        self.assertIn(".setNegativeButton(\"Don't save\"", saving)
        self.assertLess(saving.index("if(sensitive||uncertain)"), saving.index("return persistMemoryFact(clean,type,expiry)"))

    def test_expiring_context_and_unverified_guesses_remain_distinct(self):
        migration = method_body("void migrateMemoryRecords()")
        saving = method_body("String saveMemoryFact(")
        self.assertIn('"temporary"', migration)
        self.assertIn('"expires_at_ms"', migration)
        self.assertIn('"unverified_guess"', saving)
        self.assertIn('"personal_fact"', JAVA)
        self.assertIn('"preference"', JAVA)

    def test_clearing_conversation_history_does_not_clear_saved_memory(self):
        clear_history = method_body("void confirmClearConversation()")
        self.assertIn('conversationHistory=new JSONArray();save("conversation",conversationHistory)', clear_history)
        self.assertNotRegex(clear_history, re.compile(r'\bmemory\s*='))
        self.assertNotIn('save("memory"', clear_history)
        self.assertIn("memory=load(\"memory\")", method_body("void loadData()"))

    def test_memory_commands_cover_inspect_search_correct_and_delete(self):
        self.assertIn('s.startsWith("show saved memories")', JAVA)
        self.assertIn('s.startsWith("search memory")', JAVA)
        self.assertIn('s.matches("^(correct|update) memory ', JAVA)
        self.assertIn('s.matches("^(forget|delete) memory ', JAVA)
        self.assertIn("String recalledMemoryAnswer(String raw, String dialogueIntent)", JAVA)
        self.assertIn("personalMemoryQuestionSubject(raw)", JAVA)

if __name__ == "__main__":
    unittest.main()
