import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("train_brain", ROOT / "tools" / "train_brain.py")
train_brain = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(train_brain)


class TrainingPipelineTests(unittest.TestCase):
    def test_tokenizer_is_stable_and_lowercases(self):
        self.assertEqual(train_brain.tokenize("Hello, Ammu 2!"), ["hello", "ammu", "2"])

    def test_tokenizer_preserves_hindi_and_kannada_words(self):
        tokens = train_brain.tokenize("नमस्ते ಅಮ್ಮು hello")
        self.assertIn("नमस्ते", tokens)
        self.assertIn("ಅಮ್ಮು", tokens)
        self.assertIn("hello", tokens)

    def test_features_include_unigrams_bigrams_and_character_ngrams(self):
        features = train_brain.make_features("hello ammu now")
        self.assertTrue({"hello", "ammu", "now", "bi:hello_ammu", "bi:ammu_now", "ch3:hel", "ch4:hell", "ch3:mmu"}.issubset(features))

    def test_fixed_benchmark_phrases_are_protected_from_training(self):
        source = (ROOT / "tools/train_brain.py").read_text(encoding="utf-8")
        benchmark = ROOT / "app/src/main/assets/brain-benchmark-v1.json"
        self.assertTrue(benchmark.exists())
        benchmark_v2 = ROOT / "app/src/main/assets/brain-benchmark-v2.json"
        self.assertTrue(benchmark_v2.exists())
        self.assertIn("protected evaluation phrases were excluded", source)
        self.assertIn("brain-benchmark-v1.json", source)
        self.assertIn("brain-benchmark-v2.json", source)

    def test_v1_and_v2_phrases_are_protected_from_training(self):
        protected = train_brain.load_protected_eval_phrases()
        import json
        for filename in ("brain-benchmark-v1.json", "brain-benchmark-v2.json"):
            data = json.loads((ROOT / "app/src/main/assets" / filename).read_text(encoding="utf-8"))
            self.assertTrue(data["examples"])
            self.assertIn(data["examples"][0]["text"].casefold().strip(), protected)

    def test_training_source_reports_precision_recall_and_confusions(self):
        source = (ROOT / "tools/train_brain.py").read_text(encoding="utf-8")
        self.assertIn("macro precision", source)
        self.assertIn("macro recall", source)
        self.assertIn("CONFUSION", source)

    def test_training_labels_have_examples(self):
        import json
        model = json.loads((ROOT / "app/src/main/assets/brain.json").read_text(encoding="utf-8"))
        texts, labels = train_brain.load_examples(model["labels"])
        self.assertGreaterEqual(len(texts), 300)
        self.assertEqual(set(labels), set(model["labels"]))

    def test_stratified_split_is_deterministic_and_keeps_all_classes(self):
        labels = ["a", "b"]
        tags = ["a"] * 5 + ["b"] * 5
        train1, test1 = train_brain.stratified_split(tags, labels, 123)
        train2, test2 = train_brain.stratified_split(tags, labels, 123)
        self.assertEqual(train1.tolist(), train2.tolist())
        self.assertEqual(test1.tolist(), test2.tolist())
        self.assertEqual(set(tags[i] for i in test1), set(labels))

    def test_small_network_learns_separable_examples_from_random_initialization(self):
        import numpy as np
        X = np.asarray([[1, 0], [1, 0], [0, 1], [0, 1]], dtype=np.float32)
        y = np.asarray([0, 0, 1, 1], dtype=np.int64)
        weights = train_brain.fit(X, y, hidden=6, seed=7, epochs=100, lr=0.02)
        predicted, _ = train_brain.predict(X, weights)
        self.assertEqual(predicted.tolist(), y.tolist())

    def test_model_serialization_uses_six_decimal_weights(self):
        import numpy as np
        weights = {
            "W1": np.asarray([[0.12345678]], dtype=np.float32),
            "b1": np.asarray([0.11111118], dtype=np.float32),
            "W2": np.asarray([[0.22222228, 0.33333338]], dtype=np.float32),
            "b2": np.asarray([0.44444448, 0.55555558], dtype=np.float32),
        }
        model = train_brain.to_json(["a", "unknown"], ["hello"], weights, 1, 1, 0.55, 2, 4, 0.008, 0.001)
        self.assertEqual(model["weight_precision_decimal_places"], 6)
        self.assertEqual(model["W1"][0][0], round(float(weights["W1"][0][0]), 6))


if __name__ == "__main__":
    unittest.main()
