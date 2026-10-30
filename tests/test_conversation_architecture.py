import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PIPELINE = (ROOT / "app/src/main/java/com/ammu/assistant/ConversationPipeline.java").read_text(encoding="utf-8")
DATABASE = (ROOT / "app/src/main/java/com/ammu/assistant/LocalMemoryDatabase.java").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


class ConversationArchitectureTests(unittest.TestCase):
    def test_pipeline_has_separate_intent_entities_context_and_knowledge_layers(self):
        for fragment in ("IntentModel", "entities(String input)", "followUp(String input", "knowledgeAnswer(String input", "conversation_recall"):
            self.assertIn(fragment, PIPELINE)
        self.assertIn("I don't have a previous message", PIPELINE)

    def test_unknown_knowledge_is_not_hardcoded_as_success(self):
        self.assertIn("return null;", PIPELINE)
        self.assertIn("knowledgeAnswer(String input, String entities)", PIPELINE)

    def test_personal_memory_uses_sqlite_not_neural_training(self):
        self.assertIn("SQLiteOpenHelper", DATABASE)
        self.assertIn("CREATE TABLE memories", DATABASE)
        self.assertIn("ConversationPipeline", MAIN)
        self.assertIn("localMemoryDatabase.upsert", MAIN)

    def test_manifest_has_no_internet_permission(self):
        self.assertNotRegex(MANIFEST, re.compile(r"android.permission.INTERNET", re.IGNORECASE))

    def test_recognition_is_not_launched_as_activity(self):
        source = (ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java").read_text(encoding="utf-8")
        self.assertNotIn("startActivityForResult(request", source)
        self.assertNotIn("ACTION_WEB_SEARCH", source)
        self.assertNotIn("RecognizerIntent.ACTION_RECOGNIZE_SPEECH", source)


if __name__ == "__main__":
    unittest.main()
