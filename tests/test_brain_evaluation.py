import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("evaluate_brain", ROOT / "tools" / "evaluate_brain.py")
evaluate_brain = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(evaluate_brain)

class IndependentBrainEvaluationTests(unittest.TestCase):
    def test_development_benchmark_is_fixed_balanced_and_has_130_phrases(self):
        rows = evaluate_brain.load_benchmark()
        self.assertEqual(len(rows), 130)
        counts = {}
        for row in rows: counts[row["label"]] = counts.get(row["label"], 0) + 1
        self.assertEqual(len(counts), 26)
        self.assertEqual(set(counts.values()), {5})

    def test_benchmark_phrases_are_not_exact_training_examples(self):
        rows = evaluate_brain.load_benchmark()
        intents = json.loads((ROOT / "app/src/main/assets/intents.json").read_text(encoding="utf-8"))
        training = {evaluate_brain.canonical(text) for spec in intents.values() for text in spec.get("examples", [])}
        self.assertFalse([row["text"] for row in rows if evaluate_brain.canonical(row["text"]) in training])

    def test_secondary_benchmark_is_balanced_and_disjoint(self):
        path = ROOT / "app/src/main/assets/brain-benchmark-v2.json"
        rows = evaluate_brain.load_benchmark(path)
        self.assertEqual(len(rows), 104)
        counts = {}
        for row in rows: counts[row["label"]] = counts.get(row["label"], 0) + 1
        self.assertEqual(len(counts), 26)
        self.assertEqual(set(counts.values()), {4})
        intents = json.loads((ROOT / "app/src/main/assets/intents.json").read_text(encoding="utf-8"))
        training = {evaluate_brain.canonical(text) for spec in intents.values() for text in spec.get("examples", [])}
        v1 = {evaluate_brain.canonical(row["text"]) for row in evaluate_brain.load_benchmark()}
        v2 = {evaluate_brain.canonical(row["text"]) for row in rows}
        self.assertFalse(v2 & training)
        self.assertFalse(v2 & v1)

    def test_secondary_benchmark_scores_all_rows(self):
        rows = evaluate_brain.load_benchmark(ROOT / "app/src/main/assets/brain-benchmark-v2.json")
        model = evaluate_brain.load_model(ROOT / "app/src/main/assets/brain.json")
        metrics = evaluate_brain.score(model, rows)
        self.assertEqual(metrics["total"], 104)
        self.assertEqual(metrics["correct"] + len(metrics["errors"]), 104)

    def test_bundled_model_evaluation_returns_all_metrics(self):
        rows = evaluate_brain.load_benchmark()
        model = evaluate_brain.load_model(ROOT / "app/src/main/assets/brain.json")
        score = evaluate_brain.score(model, rows)
        self.assertEqual(score["total"], 130)
        self.assertEqual(len(score["per_label"]), 26)
        self.assertGreaterEqual(score["accuracy"], 0.0)
        self.assertLessEqual(score["accuracy"], 1.0)
        self.assertEqual(score["correct"] + len(score["errors"]), 130)

    def test_character_ngrams_are_applied_to_indic_and_latin_scripts(self):
        found = evaluate_brain.features("Ammu ನಮಸ್ಕಾರ")
        self.assertIn("ch3:amm", found)
        self.assertIn("ch3:ನಮಸ", found)

if __name__ == "__main__":
    unittest.main()
