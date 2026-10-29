#!/usr/bin/env python3
"""Validate Ammu's bundled custom intent-network asset without third-party packages."""
import json
import math
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "app" / "src" / "main" / "assets" / "brain.json"


def flatten_vector(value):
    if len(value) == 1 and isinstance(value[0], list):
        return value[0]
    return value


def load_model():
    model = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    labels = model["labels"]
    vocab = model["vocab"]
    w1 = model["W1"]
    b1 = flatten_vector(model["b1"])
    w2 = model["W2"]
    b2 = flatten_vector(model["b2"])
    assert labels and vocab, "labels/vocab must not be empty"
    assert len(set(labels)) == len(labels), "duplicate intent labels"
    intent_data = json.loads((ROOT / "app/src/main/assets/intents.json").read_text(encoding="utf-8"))
    assert set(labels) == set(intent_data), f"model labels and training labels differ: model-only={set(labels)-set(intent_data)}, data-only={set(intent_data)-set(labels)}"
    assert len(set(vocab)) == len(vocab), "duplicate features"
    assert len(w1) == len(vocab), "W1 rows must match vocabulary"
    assert all(len(row) == len(b1) for row in w1), "W1 hidden dimensions mismatch"
    assert len(w2) == len(b1), "W2 rows must match hidden layer"
    assert all(len(row) == len(b2) for row in w2), "W2 output dimensions mismatch"
    assert len(b2) == len(labels), "output bias must match label count"
    flat_numeric = list(b1) + list(b2) + [value for row in w1 for value in row] + [value for row in w2 for value in row]
    assert all(isinstance(value, (int, float)) and math.isfinite(float(value)) for value in flat_numeric), "model contains a non-finite or non-numeric weight"
    assert 0 < float(model.get("threshold", 0.48)) < 1, "invalid confidence threshold"
    if int(model.get("format_version", 0)) >= 6:
        assert model.get("feature_schema") == "word-bigram-char3-char4-v1", "unsupported model feature schema"
        assert any(token.startswith("ch3:") for token in vocab), "model has no character trigram features"
        assert any(token.startswith("ch4:") for token in vocab), "model has no character 4-gram features"
        assert model.get("weight_precision_decimal_places") == 6, "model weights should use compact six-decimal serialization"
    return model, labels, vocab, {token: i for i, token in enumerate(vocab)}, w1, b1, w2, b2


def tokenize(text):
    normalized = unicodedata.normalize("NFC", text).lower()
    tokens, current = [], []
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


def predict(text, model_parts):
    model, labels, vocab, vocab_index, w1, b1, w2, b2 = model_parts
    tokens = tokenize(text)
    features = set(tokens)
    features.update(f"bi:{tokens[i]}_{tokens[i+1]}" for i in range(len(tokens) - 1))
    for token in tokens:
        for size in (3, 4):
            for start in range(len(token) - size + 1):
                features.add(f"ch{size}:{token[start:start + size]}")
    x = [1.0 if token in features else 0.0 for token in vocab]
    hidden = []
    for j, bias in enumerate(b1):
        z = bias
        for i, xi in enumerate(x):
            if xi:
                z += xi * w1[i][j]
        hidden.append(math.tanh(z))
    logits = [b2[k] + sum(hidden[j] * w2[j][k] for j in range(len(hidden))) for k in range(len(labels))]
    max_logit = max(logits)
    exp_logits = [math.exp(value - max_logit) for value in logits]
    total = sum(exp_logits)
    probabilities = [value / total for value in exp_logits]
    best = max(range(len(labels)), key=probabilities.__getitem__)
    confidence = probabilities[best]
    if not math.isfinite(confidence):
        raise AssertionError(f"non-finite confidence for {text!r}")
    return labels[best], confidence


def main():
    model_parts = load_model()
    model, labels, vocab, *_ = model_parts
    cases = {
        "hello": "greeting",
        "what time is it": "time",
        "check battery": "battery",
        "save a note: buy milk": "note_add",
        "open settings": "open_settings",
        "write in my journal today I studied": "journal_add",
        "yo ammu": "greeting",
        "tell me what the clock says": "time",
        "how much charge remains": "battery",
        "which phone do I own": "device_info",
        "jot down groceries": "note_add",
        "put revise dbms on my to do list": "task_add",
        "store fact that i prefer dark mode": "memory_save",
        "back up my data": "backup",
        "xyzzy blorb": "unknown",
        "explain quantum gravity in simple words": "unknown",
        "make a complete website for me": "unknown",
        "अभी कितने बजे हैं": "time",
        "ಇಂದಿನ ದಿನಾಂಕ ಹೇಳು": "date",
        "बैटरी कितनी बची है": "battery",
        "ನನ್ನ ಟಿಪ್ಪಣಿಗಳನ್ನು ತೋರಿಸು": "note_list",
        "मदद करो": "help",
        "नोट सेव करो: दूध खरीदना": "note_add",
        "ಕೆಲಸ ಸೇರಿಸು: DBMS ಓದು": "task_add",
        "नमस्ते अम्मू": "greeting",
    }
    print(f"Model shape OK: {len(labels)} intents, {len(vocab)} features, {len(model_parts[5])} hidden units; format={model.get('format_version', 'legacy')}")
    threshold = float(model.get("threshold", 0.48))
    for text, expected in cases.items():
        label, confidence = predict(text, model_parts)
        print(f"{text!r}: {label} ({confidence:.3f})")
        assert label == expected, f"expected {expected!r} for {text!r}, got {label!r}"
        assert confidence >= threshold, f"confidence below threshold for {text!r}"
    print(f"PASS: {len(cases)} intent and out-of-domain smoke tests")


if __name__ == "__main__":
    main()
