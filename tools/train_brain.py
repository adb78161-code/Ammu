#!/usr/bin/env python3
"""Train Ammu's small neural intent classifier from scratch; no pretrained model.

Requires NumPy. The bundled brain.json is never overwritten. A candidate is written
only when --write-candidate is passed, so a poorly performing model cannot silently
replace the known-good asset.
"""
from __future__ import annotations
import argparse
import json
import math
import re
import unicodedata
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets"
INTENTS_PATH = ASSET_DIR / "intents.json"
BASE_MODEL_PATH = ASSET_DIR / "brain.json"
TOKEN_RE = re.compile(r"[\w']+", re.UNICODE)


def tokenize(text: str) -> list[str]:
    # Preserve letters, combining marks (critical for Indic scripts), digits and apostrophes.
    normalized = unicodedata.normalize("NFC", text).lower()
    tokens: list[str] = []
    current: list[str] = []
    for char in normalized:
        category = unicodedata.category(char)
        if category[0] in {"L", "M", "N"} or char in {"_", "'"}:
            current.append(char)
        else:
            if current:
                tokens.append("".join(current))
                current = []
    if current:
        tokens.append("".join(current))
    return tokens

def make_features(text: str) -> set[str]:
    tokens = tokenize(text)
    result = set(tokens)
    result.update(f"bi:{tokens[i]}_{tokens[i + 1]}" for i in range(len(tokens) - 1))
    # Character 3/4-grams make unseen spellings and inflections less brittle.
    # Features are derived from the training corpus; no pretrained vectors are used.
    for token in tokens:
        for size in (3, 4):
            for start in range(len(token) - size + 1):
                result.add(f"ch{size}:{token[start:start + size]}")
    return result



def load_protected_eval_phrases() -> set[str]:
    """Load exact phrases that must never enter training, from either evaluation set."""
    benchmark_paths = [ASSET_DIR / "brain-benchmark-v1.json", ASSET_DIR / "brain-benchmark-v2.json"]
    protected: set[str] = set()
    for benchmark_path in benchmark_paths:
        try:
            benchmark_data = json.loads(benchmark_path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise ValueError(f"Protected benchmark {benchmark_path.name} is missing or invalid: {exc}") from exc
        expected = "ammu-development-eval-v1" if benchmark_path.name.endswith("v1.json") else "ammu-development-eval-v2"
        if benchmark_data.get("format") != expected or not isinstance(benchmark_data.get("examples"), list):
            raise ValueError(f"Protected benchmark {benchmark_path.name} has an unsupported format.")
        for row in benchmark_data["examples"]:
            if isinstance(row, dict) and isinstance(row.get("text"), str):
                protected.add(unicodedata.normalize("NFC", row["text"]).casefold().strip())
    return protected

def load_examples(labels: list[str]) -> tuple[list[str], list[str]]:
    intents = json.loads(INTENTS_PATH.read_text(encoding="utf-8"))
    texts, tags = [], []
    for label in labels:
        item = intents.get(label)
        if not item:
            raise ValueError(f"Training examples missing for bundled label: {label}")
        seen = set()
        for raw in item.get("examples", []):
            text = " ".join(str(raw).strip().split())
            key = text.casefold()
            if text and key not in seen:
                texts.append(text)
                tags.append(label)
                seen.add(key)
    return texts, tags


def make_matrix(texts: list[str], vocab: list[str]) -> np.ndarray:
    index = {feature: i for i, feature in enumerate(vocab)}
    X = np.zeros((len(texts), len(vocab)), dtype=np.float32)
    for row, text in enumerate(texts):
        for feature in make_features(text):
            col = index.get(feature)
            if col is not None:
                X[row, col] = 1.0
    return X


def stratified_split(tags: list[str], labels: list[str], seed: int) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    train, test = [], []
    for label in labels:
        indices = np.asarray([i for i, item in enumerate(tags) if item == label], dtype=np.int64)
        if len(indices) < 4:
            raise ValueError(f"Intent {label!r} needs at least 4 examples for a useful holdout; found {len(indices)}")
        rng.shuffle(indices)
        n_test = max(1, int(round(len(indices) * 0.2)))
        test.extend(indices[:n_test].tolist())
        train.extend(indices[n_test:].tolist())
    return np.asarray(train, dtype=np.int64), np.asarray(test, dtype=np.int64)


def softmax(logits: np.ndarray) -> np.ndarray:
    shifted = logits - logits.max(axis=1, keepdims=True)
    exp = np.exp(shifted)
    return exp / np.maximum(exp.sum(axis=1, keepdims=True), 1e-12)


def fit(X: np.ndarray, y: np.ndarray, hidden: int, seed: int, epochs: int, lr: float = 0.008, weight_decay: float = 0.005) -> dict:
    rng = np.random.default_rng(seed)
    n, inputs = X.shape
    classes = int(y.max()) + 1
    W1 = rng.normal(0, math.sqrt(2 / (inputs + hidden)), (inputs, hidden)).astype(np.float32)
    b1 = np.zeros(hidden, dtype=np.float32)
    W2 = rng.normal(0, math.sqrt(2 / (hidden + classes)), (hidden, classes)).astype(np.float32)
    b2 = np.zeros(classes, dtype=np.float32)
    params = [W1, b1, W2, b2]
    m = [np.zeros_like(p) for p in params]
    v = [np.zeros_like(p) for p in params]
    step = 0
    batch_size = min(32, max(8, n // 4))
    # Keep the same tanh -> softmax architecture used by MainActivity.java.
    for epoch in range(epochs):
        order = rng.permutation(n)
        for start in range(0, n, batch_size):
            ids = order[start:start + batch_size]
            xb, yb = X[ids], y[ids]
            h = np.tanh(xb @ W1 + b1)
            probs = softmax(h @ W2 + b2)
            probs[np.arange(len(ids)), yb] -= 1.0
            probs /= max(len(ids), 1)
            gW2 = h.T @ probs + weight_decay * W2
            gb2 = probs.sum(axis=0)
            gh = probs @ W2.T
            gz = gh * (1.0 - h * h)
            gW1 = xb.T @ gz + weight_decay * W1
            gb1 = gz.sum(axis=0)
            grads = [gW1, gb1, gW2, gb2]
            step += 1
            for i, (param, grad) in enumerate(zip(params, grads)):
                m[i] = 0.9 * m[i] + 0.1 * grad
                v[i] = 0.999 * v[i] + 0.001 * (grad * grad)
                m_hat = m[i] / (1.0 - 0.9 ** step)
                v_hat = v[i] / (1.0 - 0.999 ** step)
                param -= lr * m_hat / (np.sqrt(v_hat) + 1e-8)
    return {"W1": W1, "b1": b1, "W2": W2, "b2": b2}


def predict(X: np.ndarray, weights: dict) -> tuple[np.ndarray, np.ndarray]:
    h = np.tanh(X @ weights["W1"] + weights["b1"])
    probs = softmax(h @ weights["W2"] + weights["b2"])
    return probs.argmax(axis=1), probs.max(axis=1)


def to_json(labels: list[str], vocab: list[str], w: dict, seed: int, hidden: int, threshold: float, epochs: int, example_count: int, lr: float, weight_decay: float) -> dict:
    # Values are rounded after conversion to Python floats. Six decimal places
    # reduce the asset substantially while preserving the held-out predictions.
    def vector(values):
        return [round(float(value), 6) for value in values]
    def matrix(values):
        return [vector(row) for row in values]
    return {
        "format_version": 6,
        "feature_schema": "word-bigram-char3-char4-v1",
        "weight_precision_decimal_places": 6,
        "name": "Ammu Custom Neural Intent Brain",
        "description": "A small tanh-softmax intent classifier trained from Ammu's own examples from scratch. Not a general-purpose language model.",
        "seed": seed,
        "training_method": "numpy_adam_tanh_softmax_from_scratch",
        "training_epochs": epochs,
        "training_examples": example_count,
        "learning_rate": lr,
        "weight_decay": weight_decay,
        "labels": labels,
        "vocab": vocab,
        "hidden_size": hidden,
        "W1": matrix(w["W1"]),
        "b1": [vector(w["b1"])],
        "W2": matrix(w["W2"]),
        "b2": [vector(w["b2"])],
        "threshold": threshold,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--epochs", type=int, default=200)
    parser.add_argument("--hidden", type=int, default=72)
    parser.add_argument("--lr", type=float, default=0.008)
    parser.add_argument("--weight-decay", type=float, default=0.001)
    parser.add_argument("--threshold", type=float, default=0.55)
    parser.add_argument("--min-heldout-accuracy", type=float, default=0.60, help="fail if held-out top-1 accuracy falls below this value")
    parser.add_argument("--write-candidate", action="store_true", help="write brain-candidate.json without replacing brain.json")
    parser.add_argument("--extra-examples", type=Path, help="optional JSON exported by Ammu Brain Learning Lab; examples are added only for training/evaluation")
    args = parser.parse_args()
    if args.epochs < 1 or args.hidden < 4 or not 0.05 < args.threshold < 0.95 or args.lr <= 0 or args.weight_decay < 0:
        raise SystemExit("Use epochs >= 1, hidden >= 4, threshold between 0.05 and 0.95, lr > 0 and weight_decay >= 0")
    base = json.loads(BASE_MODEL_PATH.read_text(encoding="utf-8"))
    intent_labels = list(json.loads(INTENTS_PATH.read_text(encoding="utf-8")))
    labels = [label for label in base["labels"] if label != "unknown" and label in intent_labels]
    labels.extend(label for label in intent_labels if label not in labels and label != "unknown")
    if "unknown" in intent_labels or "unknown" in base["labels"]:
        labels.append("unknown")
    texts, tags = load_examples(labels)
    # The fixed independent benchmark must never become training data, even if a
    # user imports/corrects one of its phrases. This preserves its value across runs.
    try:
        protected = load_protected_eval_phrases()
    except ValueError as exc:
        raise SystemExit(str(exc))
    before = len(texts)
    kept = [(t, tag) for t, tag in zip(texts, tags) if unicodedata.normalize("NFC", t).casefold().strip() not in protected]
    texts = [item[0] for item in kept]
    tags = [item[1] for item in kept]
    protected_count = before - len(texts)
    if protected_count:
        print(f"Excluded {protected_count} protected benchmark phrases from training across v1 and v2.")
    if args.extra_examples:
        extra_path = args.extra_examples.expanduser().resolve()
        try:
            extra = json.loads(extra_path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise SystemExit(f"Cannot read extra examples JSON: {exc}")
        if extra.get("format") != "ammu-brain-examples-v1" or not isinstance(extra.get("examples"), list):
            raise SystemExit("Extra examples must use format 'ammu-brain-examples-v1'.")
        valid_labels = set(labels) - {"unknown"}
        seen = {(unicodedata.normalize("NFC", t).casefold(), tag) for t, tag in zip(texts, tags)}
        added = 0
        for item in extra["examples"]:
            if not isinstance(item, dict):
                continue
            text = " ".join(str(item.get("text", "")).strip().split())
            label = str(item.get("label", "")).strip()
            if not text or len(text) > 180 or label not in valid_labels:
                continue
            key = (unicodedata.normalize("NFC", text).casefold(), label)
            if key in seen:
                continue
            if key[0] in protected:
                continue
            texts.append(text); tags.append(label); seen.add(key); added += 1
        print(f"Imported {added} valid, non-duplicate user-labeled examples from {extra_path.name}; protected evaluation phrases were excluded")
    # Feature vocabulary is derived only from supplied examples, not from a pretrained model.
    vocab = sorted(set().union(*(make_features(t) for t in texts)))
    X = make_matrix(texts, vocab)
    label_index = {label: i for i, label in enumerate(labels)}
    y = np.asarray([label_index[tag] for tag in tags], dtype=np.int64)
    train_ids, test_ids = stratified_split(tags, labels, args.seed)
    split_model = fit(X[train_ids], y[train_ids], args.hidden, args.seed, args.epochs, args.lr, args.weight_decay)
    predicted, confidence = predict(X[test_ids], split_model)
    correct = predicted == y[test_ids]
    accuracy = float(correct.mean())
    recalls, precisions = [], []
    confusion: dict[tuple[str, str], int] = {}
    print(f"Dataset: {len(texts)} labeled examples; labels={len(labels)}; features={len(vocab)}; hidden={args.hidden}")
    for class_id, label in enumerate(labels):
        actual = y[test_ids] == class_id
        predicted_class = predicted == class_id
        support = int(actual.sum())
        if support == 0:
            continue
        recall = float((predicted[actual] == class_id).mean())
        precision = float(((predicted == class_id) & actual).sum() / max(1, predicted_class.sum()))
        recalls.append(recall); precisions.append(precision)
        print(f"CLASS {label}: precision={precision:.3f} recall={recall:.3f} support={support}")
    for expected_id, got_id in zip(y[test_ids], predicted):
        if int(expected_id) != int(got_id):
            pair = (labels[int(expected_id)], labels[int(got_id)])
            confusion[pair] = confusion.get(pair, 0) + 1
    print(f"Held-out accuracy: {accuracy:.3f}; macro precision: {float(np.mean(precisions)):.3f}; macro recall: {float(np.mean(recalls)):.3f}; holdout={len(test_ids)}; epochs={args.epochs}")
    for (expected, got), count in sorted(confusion.items(), key=lambda item: (-item[1], item[0]))[:10]:
        print(f"CONFUSION {count}x: expected={expected}, predicted={got}")
    errors = []
    for row, pred_id, conf, ok in zip(test_ids, predicted, confidence, correct):
        if not ok:
            errors.append((texts[int(row)], tags[int(row)], labels[int(pred_id)], float(conf)))
    for text, expected, got, conf in errors[:25]:
        print(f"ERROR ({conf:.2f}) {text!r}: expected={expected}, got={got}")
    # Enforce the holdout gate before creating an artifact.
    if accuracy < args.min_heldout_accuracy:
        raise SystemExit(f"Held-out accuracy {accuracy:.3f} is below required {args.min_heldout_accuracy:.3f}; no candidate was written.")
    # Generate a fresh full-data fit, but never overwrite the bundled production model.
    if args.write_candidate:
        final_weights = fit(X, y, args.hidden, args.seed, args.epochs, args.lr, args.weight_decay)
        candidate = to_json(labels, vocab, final_weights, args.seed, args.hidden, args.threshold, args.epochs, len(texts), args.lr, args.weight_decay)
        out = ASSET_DIR / "brain-candidate.json"
        out.write_text(json.dumps(candidate, separators=(",", ":")), encoding="utf-8")
        print(f"Candidate written to {out.relative_to(ROOT)}; inspect evaluation before promoting it.")
    print("Reminder: held-out text accuracy is a small-dataset metric, not proof of general reasoning.")

if __name__ == "__main__":
    main()
