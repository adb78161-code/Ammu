#!/usr/bin/env python3
"""Evaluate Ammu model(s) against a fixed non-overlapping development phrase set.

This is a classifier evaluation, not a test of general intelligence. The benchmark
is intentionally excluded from training. Candidate promotion is opt-in and requires
an explicit --promote flag plus a measured improvement over the bundled model.
"""
from __future__ import annotations
import argparse
import json
import math
import shutil
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
import re
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets"
BASE_MODEL = ASSET_DIR / "brain.json"
BENCHMARK = ASSET_DIR / "brain-benchmark-v1.json"
TOKEN_RE = re.compile(r"[\w']+", re.UNICODE)


def canonical(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", str(text)).casefold().split())


def tokenize(text: str) -> list[str]:
    normalized = unicodedata.normalize("NFC", text).lower()
    tokens: list[str] = []
    current: list[str] = []
    for char in normalized:
        category = unicodedata.category(char)
        if category[0] in {"L", "M", "N"} or char in {"_", "'"}:
            current.append(char)
        else:
            if current:
                tokens.append("".join(current)); current = []
    if current:
        tokens.append("".join(current))
    return tokens


def features(text: str) -> set[str]:
    words = tokenize(text)
    result = set(words)
    result.update(f"bi:{words[i]}_{words[i + 1]}" for i in range(len(words) - 1))
    for word in words:
        for size in (3, 4):
            for start in range(len(word) - size + 1):
                result.add(f"ch{size}:{word[start:start + size]}")
    return result


def flatten(value):
    if isinstance(value, list) and len(value) == 1 and isinstance(value[0], list):
        return value[0]
    return value


def load_model(path: Path) -> dict:
    model = json.loads(path.read_text(encoding="utf-8"))
    required = ("labels", "vocab", "W1", "b1", "W2", "b2")
    if any(key not in model for key in required):
        raise ValueError(f"{path}: model is missing required fields")
    labels, vocab = model["labels"], model["vocab"]
    b1, b2 = flatten(model["b1"]), flatten(model["b2"])
    if not isinstance(labels, list) or not labels or len(set(labels)) != len(labels):
        raise ValueError(f"{path}: invalid/duplicate labels")
    if not isinstance(vocab, list) or not vocab or len(set(vocab)) != len(vocab):
        raise ValueError(f"{path}: invalid/duplicate vocabulary")
    if len(model["W1"]) != len(vocab) or any(len(row) != len(b1) for row in model["W1"]):
        raise ValueError(f"{path}: W1 dimensions do not match vocabulary/hidden size")
    if len(model["W2"]) != len(b1) or any(len(row) != len(b2) for row in model["W2"]):
        raise ValueError(f"{path}: W2 dimensions do not match hidden/output size")
    if len(b2) != len(labels):
        raise ValueError(f"{path}: output bias does not match labels")
    threshold = float(model.get("threshold", 0.48))
    if not math.isfinite(threshold) or not 0 < threshold < 1:
        raise ValueError(f"{path}: invalid confidence threshold")
    for layer in (model["W1"], b1, model["W2"], b2):
        for row in layer if isinstance(layer, list) and layer and isinstance(layer[0], list) else [layer]:
            if any(not isinstance(x, (int, float)) or not math.isfinite(float(x)) for x in row):
                raise ValueError(f"{path}: non-numeric or non-finite weights")
    model["b1_flat"] = b1
    model["b2_flat"] = b2
    model["vocab_index"] = {term: i for i, term in enumerate(vocab)}
    return model


def predict(text: str, model: dict) -> tuple[str, float]:
    vocab = model["vocab"]
    active = features(text)
    x = [1.0 if feature in active else 0.0 for feature in vocab]
    hidden = []
    for j, bias in enumerate(model["b1_flat"]):
        z = float(bias)
        for i, xi in enumerate(x):
            if xi:
                z += float(model["W1"][i][j])
        hidden.append(math.tanh(z))
    logits = []
    for k, bias in enumerate(model["b2_flat"]):
        logits.append(float(bias) + sum(hidden[j] * float(model["W2"][j][k]) for j in range(len(hidden))))
    max_logit = max(logits)
    probs = [math.exp(z - max_logit) for z in logits]
    denom = sum(probs)
    if not math.isfinite(denom) or denom <= 0:
        raise ValueError("invalid softmax denominator")
    probs = [p / denom for p in probs]
    best = max(range(len(probs)), key=probs.__getitem__)
    confidence = probs[best]
    label = model["labels"][best] if confidence >= float(model.get("threshold", 0.48)) else "unknown"
    return label, confidence


def load_benchmark(path: Path = BENCHMARK) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("format") not in {"ammu-development-eval-v1", "ammu-development-eval-v2"} or not isinstance(data.get("examples"), list):
        raise ValueError(f"{path}: benchmark has an unsupported format")
    rows = data["examples"]
    if len(rows) < 100:
        raise ValueError("fixed benchmark unexpectedly small")
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("text"), str) or not isinstance(row.get("label"), str):
            raise ValueError("benchmark row must contain text and label strings")
        key = canonical(row["text"])
        if key in seen:
            raise ValueError(f"duplicate benchmark phrase: {row['text']!r}")
        if not 2 <= len(row["text"].strip()) <= 180:
            raise ValueError(f"invalid benchmark phrase length: {row['text']!r}")
        seen.add(key)
    intents = json.loads((ASSET_DIR / "intents.json").read_text(encoding="utf-8"))
    train_texts = {canonical(item) for spec in intents.values() for item in spec.get("examples", [])}
    leaks = [row["text"] for row in rows if canonical(row["text"]) in train_texts]
    if leaks:
        raise ValueError(f"benchmark contains phrases present in training data: {leaks[:5]}")
    expected_labels = set(intents)
    actual_labels = {row["label"] for row in rows}
    if actual_labels != expected_labels:
        raise ValueError(f"benchmark labels differ from training labels: missing={expected_labels-actual_labels}, extra={actual_labels-expected_labels}")
    if any(model_label not in expected_labels for model_label in actual_labels):
        raise ValueError("benchmark references an unknown class")
    counts = defaultdict(int)
    for row in rows: counts[row["label"]] += 1
    if any(counts[label] < 4 for label in expected_labels):
        raise ValueError("each intent must have at least four independent evaluation phrases")
    return rows


def score(model: dict, rows: list[dict]) -> dict:
    labels = list(model["labels"])
    if set(labels) != {row["label"] for row in rows}:
        raise ValueError("model's labels differ from fixed benchmark labels")
    tp = defaultdict(int); actual_n = defaultdict(int); predicted_n = defaultdict(int)
    errors = []; confidence_total = 0.0
    for row in rows:
        got, confidence = predict(row["text"], model)
        expected = row["label"]
        actual_n[expected] += 1; predicted_n[got] += 1; confidence_total += confidence
        if got == expected:
            tp[expected] += 1
        else:
            errors.append({"text": row["text"], "expected": expected, "predicted": got, "confidence": round(confidence, 4)})
    per_label = {}
    precisions, recalls, f1s = [], [], []
    for label in labels:
        precision = tp[label] / predicted_n[label] if predicted_n[label] else 0.0
        recall = tp[label] / actual_n[label] if actual_n[label] else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {"correct": tp[label], "support": actual_n[label], "precision": precision, "recall": recall, "f1": f1}
        precisions.append(precision); recalls.append(recall); f1s.append(f1)
    total = len(rows)
    return {"accuracy": sum(tp.values()) / total, "macro_precision": sum(precisions) / len(precisions), "macro_recall": sum(recalls) / len(recalls), "macro_f1": sum(f1s) / len(f1s), "mean_top_confidence": confidence_total / total, "total": total, "correct": sum(tp.values()), "per_label": per_label, "errors": errors}


def fmt(metrics: dict, title: str) -> str:
    lines = [title, f"Accuracy: {metrics['correct']}/{metrics['total']} = {metrics['accuracy']*100:.2f}%", f"Macro precision: {metrics['macro_precision']*100:.2f}% | macro recall: {metrics['macro_recall']*100:.2f}% | macro F1: {metrics['macro_f1']*100:.2f}%", f"Mean top-class confidence (not calibrated probability): {metrics['mean_top_confidence']*100:.2f}%", "", "Per-intent results:"]
    for label, m in metrics["per_label"].items():
        lines.append(f"  {label:18s} {m['correct']}/{m['support']} recall={m['recall']:.2f} precision={m['precision']:.2f}")
    lines.append("")
    if metrics["errors"]:
        lines.append("Misclassified phrases (first 30):")
        for error in metrics["errors"][:30]:
            lines.append(f"  {error['text']!r}: expected={error['expected']}, got={error['predicted']} (confidence={error['confidence']:.2f})")
    else:
        lines.append("No misclassifications on this fixed set.")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=BASE_MODEL, help="model to evaluate (default: bundled brain.json)")
    parser.add_argument("--benchmark", type=Path, default=BENCHMARK, help="benchmark JSON to use (default: brain-benchmark-v1.json)")
    parser.add_argument("--candidate", type=Path, help="compare this candidate against the bundled model")
    parser.add_argument("--report", type=Path, help="write a machine-readable JSON report")
    parser.add_argument("--gate", action="store_true", help="exit nonzero unless candidate passes the promotion gate")
    parser.add_argument("--promote", action="store_true", help="copy candidate into brain.json only if the promotion gate passes")
    parser.add_argument("--min-improvement", type=float, default=0.02, help="required absolute accuracy gain, default 0.02 (2 percentage points)")
    parser.add_argument("--max-class-regression", type=float, default=0.20, help="maximum allowed per-intent recall drop, default 0.20")
    args = parser.parse_args()
    if args.min_improvement < 0 or not 0 <= args.max_class_regression <= 1:
        parser.error("min improvement must be >=0 and max class regression must be between 0 and 1")
    if args.promote and not args.candidate:
        parser.error("--promote requires --candidate")
    if args.promote and args.model.resolve() != BASE_MODEL.resolve():
        parser.error("--promote compares against the bundled brain.json; do not override --model")
    if args.promote and args.benchmark.resolve() != BENCHMARK.resolve():
        parser.error("Promotion must use the designated v1 promotion benchmark; evaluate v2 separately")
    rows = load_benchmark(args.benchmark)
    model = load_model(args.model)
    benchmark_labels = {row["label"] for row in rows}
    model_labels = set(model["labels"])
    if not model_labels.issubset(benchmark_labels):
        raise SystemExit("Model labels are not covered by the fixed benchmark")
    comparable_rows = [row for row in rows if row["label"] in model_labels]
    metrics = score(model, comparable_rows)
    benchmark_data = json.loads(args.benchmark.read_text(encoding="utf-8"))
    benchmark_id = benchmark_data.get("format", "unknown")
    print(fmt(metrics, f"Ammu benchmark {benchmark_id} — {args.model.name}"))
    if len(comparable_rows) != len(rows):
        print(f"Baseline model does not yet include {len(rows) - len(comparable_rows)} new conversational examples; those are evaluated against the candidate below.")
    report = {"benchmark": benchmark_id, "benchmark_path": str(args.benchmark), "model": str(args.model), "metrics": metrics}
    gate_pass = None
    candidate_metrics = None
    if args.candidate:
        candidate_path = args.candidate.resolve()
        base_path = BASE_MODEL.resolve()
        if candidate_path == base_path:
            raise SystemExit("Candidate path cannot be the bundled production brain itself")
        candidate = load_model(candidate_path)
        if set(candidate["labels"]) != benchmark_labels:
            raise SystemExit("Candidate labels must match the complete fixed benchmark")
        candidate_metrics = score(candidate, rows)
        print("\n" + fmt(candidate_metrics, f"Candidate evaluation — {candidate_path.name}"))
        base_per = metrics["per_label"]
        candidate_per = candidate_metrics["per_label"]
        regressions = {label: round(base_per[label]["recall"] - candidate_per[label]["recall"], 4) for label in base_per if base_per[label]["recall"] - candidate_per[label]["recall"] > args.max_class_regression + 1e-12}
        new_labels = benchmark_labels - model_labels
        baseline_correct = sum(predict(row["text"], model)[0] == row["label"] for row in rows)
        baseline_accuracy = baseline_correct / len(rows)
        baseline_macro_recall = sum(item["recall"] for item in metrics["per_label"].values()) / len(benchmark_labels)
        accuracy_gain = candidate_metrics["accuracy"] - baseline_accuracy
        macro_recall_gain = candidate_metrics["macro_recall"] - baseline_macro_recall
        new_label_recall = {label: candidate_metrics["per_label"][label]["recall"] for label in new_labels}
        gate_pass = accuracy_gain >= args.min_improvement and macro_recall_gain >= 0 and not regressions and all(recall >= 0.6 for recall in new_label_recall.values())
        print("\nPROMOTION GATE: " + ("PASS" if gate_pass else "FAIL"))
        print(f"Accuracy change over all benchmark rows: {accuracy_gain*100:+.2f} percentage points (required +{args.min_improvement*100:.2f}); macro recall change: {macro_recall_gain*100:+.2f} points")
        if regressions:
            print("Intent recall regressions beyond allowed tolerance: " + ", ".join(f"{k}={v*100:.1f} points" for k, v in sorted(regressions.items())))
        if new_label_recall:
            print("New intent held-out recall: " + ", ".join(f"{label}={recall*100:.1f}%" for label, recall in sorted(new_label_recall.items())))
        report["candidate"] = str(candidate_path)
        report["candidate_metrics"] = candidate_metrics
        report["promotion_gate"] = {"passed": gate_pass, "min_accuracy_improvement": args.min_improvement, "accuracy_change": accuracy_gain, "macro_recall_change": macro_recall_gain, "max_class_regression": args.max_class_regression, "class_regressions": regressions, "new_label_recall": new_label_recall}
        if args.promote:
            if not gate_pass:
                raise SystemExit("Candidate did not pass the independent benchmark gate; bundled brain.json was not changed.")
            backup_dir = ROOT / "evaluation" / "model-backups"
            backup_dir.mkdir(parents=True, exist_ok=True)
            backup = backup_dir / f"brain.backup-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
            shutil.copy2(BASE_MODEL, backup)
            shutil.copy2(candidate_path, BASE_MODEL)
            print(f"PROMOTED candidate after gate passed. Previous model backup: {backup.relative_to(ROOT)}")
            report["promoted"] = True
            report["backup"] = str(backup)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Machine-readable report written to {args.report}")
    if args.gate and args.candidate and not gate_pass:
        return 2
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, json.JSONDecodeError, KeyError, ValueError, TypeError) as exc:
        print(f"EVALUATION ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
