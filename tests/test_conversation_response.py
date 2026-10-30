import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java").read_text(encoding="utf-8")
JNI = (ROOT / "app/src/main/cpp/ammu_qwen_jni.cpp").read_text(encoding="utf-8")


class ConversationResponseTests(unittest.TestCase):
    def test_normal_conversation_routes_to_qwen_without_a_qwen_readiness_gate(self):
        start = MAIN.index("private boolean shouldUseQwen")
        end = MAIN.index("void runCommand", start)
        method = MAIN[start:end]
        self.assertIn("return pipelineResult == null", method)
        self.assertIn('dialogueIntent.equals("follow_up")', method)
        self.assertNotRegex(method, re.compile(r"if\s*\(!qwenReady"))

    def test_qwen_failure_has_specific_local_error_not_generic_fallback(self):
        self.assertIn("Native Qwen engine is not initialized", MAIN)
        self.assertIn("local inference error", MAIN)
        self.assertIn("generation=failed", MAIN)
        self.assertNotIn("I don't know about it, I have limitations but you can ask help.", MAIN)

    def test_prompt_has_system_memories_history_and_current_message(self):
        self.assertIn("<|im_start|>system", MAIN)
        self.assertIn("Relevant saved local memories:", MAIN)
        self.assertIn("conversationHistory.length()-6", MAIN)
        self.assertIn("boundedQwenText(currentMessage,2000)", MAIN)
        self.assertIn("<|im_start|>assistant\\n<think>", MAIN)

    def test_native_generation_uses_sampler_eos_and_context_reset(self):
        self.assertIn("llama_sampler_chain_init", JNI)
        self.assertIn("llama_sampler_init_temp(0.7f)", JNI)
        self.assertIn("llama_memory_clear(llama_get_memory(session->context), true)", JNI)
        self.assertIn("llama_vocab_is_eog(vocab, next)", JNI)

    def test_conversation_diagnostics_do_not_include_message_text(self):
        self.assertIn("AmmuConversation", MAIN)
        self.assertIn("output_chars=", MAIN)
        self.assertNotRegex(MAIN, re.compile(r"Log\.[diwe]\([^\\n]*\\b(raw|message)\\b"))


if __name__ == "__main__":
    unittest.main()
