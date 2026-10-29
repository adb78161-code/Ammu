#!/usr/bin/env python3
"""Source-level Ammu 1.1 guardrails; does not replace an Android SDK build or device test."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = (ROOT / "app/src/main/java/com/ammu/assistant/MainActivity.java").read_text(encoding="utf-8")
CONVERSATION = (ROOT / "app/src/main/java/com/ammu/assistant/ConversationLogic.java").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
BUILD = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/validate.yml").read_text(encoding="utf-8")
RELEASE_WORKFLOW = (ROOT / ".github/workflows/official-release.yml").read_text(encoding="utf-8")
README = (ROOT / "README.md").read_text(encoding="utf-8")
MODEL = json.loads((ROOT / "app/src/main/assets/brain.json").read_text(encoding="utf-8"))
INTENTS = json.loads((ROOT / "app/src/main/assets/intents.json").read_text(encoding="utf-8"))
TRAINER = (ROOT / "tools/train_brain.py").read_text(encoding="utf-8")

checks = {
    "Ammu 1.1 semantic version and monotonic Android build code configured": "versionName '1.1'" in BUILD and "versionCode 23" in BUILD,
    "reviewed correction command records the actual phone mistake and prior prediction": "correct last intent to " in JAVA and "last_learning_phrase" in JAVA and 'put("source","phone_mistake")' in JAVA and 'put("predicted_label",predicted)' in JAVA,
    "on-device correction picker captures real user-reported mistakes": "openPhoneCorrectionDialog()" in JAVA and "report last mistake" in JAVA and "saveLastPhoneCorrection(label)" in JAVA,
    "last model prediction is stored locally for correction context": 'putString("last_learning_predicted",intent)' in JAVA,
    "phone mistake report summarizes real correction pairs": "phoneMistakeReport()" in JAVA and 'predicted+" → "+correct' in JAVA and "phone_mistake" in JAVA,
    "phone mistakes can be exported separately in trainer-compatible format": "exportPhoneMistakes(Uri uri)" in JAVA and "REQ_EXPORT_PHONE_MISTAKES" in JAVA and "ammu-brain-examples-v1" in JAVA and "export phone mistakes" in JAVA,
    "general example export preserves phone-mistake provenance metadata": 'row.put("source",item.optString("source"))' in JAVA and 'row.put("predicted_label",item.optString("predicted_label"))' in JAVA,
    "imported reviewed examples preserve optional source and prediction metadata": 'row.optString("source","imported_reviewed")' in JAVA and 'imported.put("predicted_label"' in JAVA,
    "local learning status reports label counts": "brainLearningStatus()" in JAVA and "Per-label counts" in JAVA,
    "undo last local training example is supported": "undo last brain example" in JAVA and "brainExamples.remove(brainExamples.length()-1)" in JAVA,
    "strict offline app manifest has no INTERNET permission": "android.permission.INTERNET" not in MANIFEST,
    "speech uses the Android on-device API without launching a recognition activity": "SpeechRecognizer.createOnDeviceSpeechRecognizer(this)" in JAVA and "SpeechRecognizer.isOnDeviceRecognitionAvailable(this)" in JAVA and "startActivityForResult(i,REQ_SPEECH_INPUT)" not in JAVA,
    "supported installed offline language is checked on Android 13+": "checkRecognitionSupport(request" in JAVA and "getInstalledOnDeviceLanguages()" in JAVA and "supportsOnDeviceLanguage" in CONVERSATION,
    "offline speech failure leaves text input available and never falls back online": "No online fallback will be used" in JAVA and "Typed chat remains available" in JAVA,
    "conversation rules distinguish identity, wellbeing, follow-up, and ordinary questions": all(f'"{kind}"' in CONVERSATION for kind in ["identity", "wellbeing", "follow_up", "ordinary_question", "correction"]),
    "follow-up replies use the latest saved conversational turn": "latestConversationUser()" in JAVA and "latestConversationAssistant()" in JAVA and "followUpResponse(userText,previousUser,previousAssistant)" in JAVA,
    "memory categories and sensitive-memory confirmation are local and explicit": all(value in JAVA for value in ['"personal_fact"', '"explicit_user"', '"expires_at_ms"', "Save sensitive memory?", "This does not change Ammu's neural model."]),
    "memory corrections, inspection, deletion, and history separation are available": all(value in JAVA for value in ["correctMemoryAtIndex(", "listMemories()", "searchRelevantMemory(String query)", "confirmForgetMemoryAtIndex(n)", "Notes, tasks, journal, and memory facts will remain."]),
    "no cloud AI endpoints or pretrained LLM SDK references": not any(x in JAVA.lower() for x in ["api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com"]),
    "custom neural brain is bundled and loaded locally": 'readAsset("brain.json")' in JAVA and "void loadBrain()" in JAVA,
    "model loader rejects malformed dimensions/non-finite weights": "row size mismatch" in JAVA and "non-finite input weight" in JAVA and "brainReady=true" in JAVA,
    "oversized command input is rejected before processing": "raw.length()>2000" in JAVA and "nothing was saved" in JAVA,
    "large local collections have explicit limits": "notes.length()>=1000" in JAVA and "tasks.length()>=500" in JAVA and "memory.length()>=1000" in JAVA and "journal.length()>=500" in JAVA and "trimOldest(moodLog,200)" in JAVA,
    "corrupt local datasets are surfaced rather than silently ignored": 'addDataLoadWarning(key+" is malformed")' in JAVA and "Data read warnings:" in JAVA,
    "backup import validates section limits before replacing local data": "int[] arrayLimits={1000,500,1000,500,200,12}" in JAVA and "Too many labeled brain examples" in JAVA and "aliasesSafe.length()<100" in JAVA,
    "model label set matches training data": set(MODEL["labels"]) == set(INTENTS),
    "model has a real unknown intent class": "unknown" in MODEL["labels"],
    "model weight dimensions are consistent": len(MODEL["W1"]) == len(MODEL["vocab"]) and len(MODEL["W2"]) == MODEL["hidden_size"] and len(MODEL["b2"][0]) == len(MODEL["labels"]),
    "expanded intent dataset has 300+ labeled examples": sum(len(v.get("examples", [])) for v in INTENTS.values()) >= 300,
    "from-scratch reproducible training tool exists": "numpy_adam_tanh_softmax_from_scratch" in TRAINER and "def fit(" in TRAINER and "--write-candidate" in TRAINER,
    "training evaluation reports precision recall and confusion pairs": "macro precision" in TRAINER and "macro recall" in TRAINER and "CONFUSION" in TRAINER,
    "candidate training never overwrites production model automatically": "brain-candidate.json" in TRAINER and "never overwrite the bundled production model" in TRAINER,
    "conservative vocabulary typo recovery implemented": "String correctKnownToken(String token)" in JAVA and "editDistanceAtMost(word,token,1)==1" in JAVA,
    "taught commands are bounded and allow-listed": "customAliases.length()>=100" in JAVA and "aliasTargets()" in JAVA and "isAllowedAliasTarget(bestTarget)" in JAVA,
    "ambiguous shortcut typos need high similarity": "similarity>=0.88" in JAVA and "secondDistance-bestDistance>=1" in JAVA,
    "memory retrieval avoids stopwords and token-substring false matches": 'new HashSet<>(Arrays.asList("the","and","for"' in JAVA and "factTerms.contains(term)" in JAVA,
    "task and note mutations require explicit action wording": "isExplicitTaskAddCommand(s)" in JAVA and "isExplicitTaskCompletion(s)" in JAVA and "isExplicitSingleNoteDelete(s)" in JAVA,
    "negated delete/update requests are guarded": "hasNegationBeforeAction(q" in JAVA and "boolean isExplicitDeleteAllNotes" in JAVA,
    "unknown task completion cannot mutate tasks": "I won't mark anything complete based on a guess" in JAVA,
    "calculator supports powers, modulo and finite-result guard": "Math.pow(x,y)" in JAVA and "s.charAt(i)=='%'" in JAVA and "!Double.isFinite(x)" in JAVA,
    "tomorrow/yesterday date questions are shifted": "Calendar cal=Calendar.getInstance();cal.add(Calendar.DAY_OF_YEAR" in JAVA,
    "plan-to-tasks requires user confirmation": "showAddPlanToTasksDialog()" in JAVA and 'setPositiveButton("Add tasks"' in JAVA,
    "memory deletion requires confirmation for bulk clearing": "confirmClearMemory()" in JAVA and 'setPositiveButton("Forget all facts"' in JAVA,
    "app launcher remains allow-listed and Android package queries exist": "String[] packages={" in JAVA and "com.google.android.calculator" in MANIFEST,
    "CI runs data/model validators and a Gradle APK build": "tools/validate_brain.py" in WORKFLOW and "tools/validate_release_source.py" in WORKFLOW and "tools/train_brain.py" in WORKFLOW and "assembleDebug" in WORKFLOW,
    "CI artifact is explicitly marked debug and not for release": "ammu-1.1-ci-debug-apk-not-for-release" in WORKFLOW and "not the official signed release" in WORKFLOW,
    "official app label and welcome message no longer expose testing-build identity": 'android:label="Ammu 1.1"' in MANIFEST and "I'm Ammu 1.1, your personal assistant" in JAVA and "Testing Build 7" not in JAVA,
    "privacy and offline operation details are available in app tools and commands": "showPrivacyAndOfflineInfo()" in JAVA and "Privacy & offline details" in JAVA and "privacy and offline info" in JAVA and "on-device SpeechRecognizer only" in JAVA,
    "privacy detail accurately explains offline speech and TTS behavior": "never launches Google's speech-recognition activity" in JAVA and "typed chat remains available" in JAVA.lower() and "Speech output may depend" in JAVA,
    "official adaptive launcher icon resources are configured": 'android:icon="@mipmap/ic_ammu"' in MANIFEST and (ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_ammu.xml").exists() and (ROOT / "app/src/main/res/drawable/ic_ammu_foreground.xml").exists(),
    "Gradle official release uses environment-supplied signing credentials": all(x in BUILD for x in ["AMMU_KEYSTORE_PATH", "AMMU_KEYSTORE_PASSWORD", "AMMU_KEY_ALIAS", "AMMU_KEY_PASSWORD", "signingConfig signingConfigs.release"]),
    "Gradle fails closed on an unsigned official release task": "requestedRelease" in BUILD and "Official Ammu 1.1 release requires" in BUILD and "!signingReady" in BUILD,
    "official release workflow requires secrets and verifies APK signature": all(x in RELEASE_WORKFLOW for x in ["AMMU_KEYSTORE_BASE64", "AMMU_KEYSTORE_PASSWORD", "AMMU_KEY_ALIAS", "AMMU_KEY_PASSWORD", "assembleRelease", "APKSIGNER", "verify --verbose --print-certs", "ammu-1.1-official-signed-apk"]),
    "official release workflow is manual to avoid accidental publishing": "workflow_dispatch" in RELEASE_WORKFLOW and "push:" not in RELEASE_WORKFLOW,
    "release signing guide warns never to commit or lose the key": (ROOT / "RELEASE_SIGNING_GUIDE.md").exists() and "Do not commit a keystore" in (ROOT / "RELEASE_SIGNING_GUIDE.md").read_text(encoding="utf-8") and "losing the release key can prevent" in (ROOT / "RELEASE_SIGNING_GUIDE.md").read_text(encoding="utf-8").lower(),
    "official release source is distinguished from an APK already published or device-tested": "no signed apk is built here" in README.lower() and "not been tested on a physical device" in README.lower(),
    "Android 15 edge-to-edge system-bar insets handled": "setOnApplyWindowInsetsListener" in JAVA and "WindowInsets.Type.systemBars()" in JAVA and "baseBottom+bottom" in JAVA,
    "backup, journal, emotion and local memory features retained": all(x in JAVA for x in ["save(\"notes\"", "save(\"tasks\"", "save(\"journal\"", "detectEmotion(userText)", "searchRelevantMemory(String query)"]),
    "Unicode-aware neural tokenizer supports native scripts": "\\p{L}" in JAVA and "\\p{M}" in JAVA and "unicodedata.category" in TRAINER,
    "Hindi and Kannada native-script examples included": "नमस्ते अम्मू" in INTENTS["greeting"]["examples"] and "ನಮಸ್ಕಾರ ಅಮ್ಮು" in INTENTS["greeting"]["examples"],
    "deterministic offline phrase normalization present": "normalizeOfflinePhrase(String input)" in JAVA and "replaceNativePrefix" in JAVA,
    "Hindi and Kannada app launch commands normalize to allow-listed actions": "ಕ್ಯಾಮೆರಾ ತೆರೆಯು" in JAVA and "कैमरा खोलो" in JAVA and "open app camera" in JAVA,
    "alias normalization preserves Unicode combining marks": "normalizeAlias(String value)" in JAVA and "p{M}" in JAVA,
    "basic Hindi and Kannada emotion cues supported": "मैं दुखी हूँ" in JAVA and "ನನಗೆ ದುಃಖವಾಗಿದೆ" in JAVA,
    "local crisis phrase guard includes Hindi and Kannada phrases": "मैं मरना चाहता हूँ" in JAVA and "ನಾನು ಸಾಯಬೇಕು ಎಂದು ಅನಿಸುತ್ತಿದೆ" in JAVA,
    "training model records evidence metadata": "training_epochs" in TRAINER and "training_examples" in TRAINER and "weight_decay" in TRAINER,
    "user-labeled brain examples are bounded, local and explicit": "brainExamples.length()>=500" in JAVA and "teach intent phrase " in JAVA and 'save("brain_examples"' in JAVA,
    "learning lab exports JSON through Android document picker": "REQ_EXPORT_BRAIN_EXAMPLES" in JAVA and "ACTION_CREATE_DOCUMENT" in JAVA and "ammu-brain-examples-v1" in JAVA,
    "offline trainer can merge exported labeled examples": "--extra-examples" in TRAINER and "ammu-brain-examples-v1" in TRAINER and "valid_labels" in TRAINER,
    "learning examples do not silently alter production weights": "live brain weights have not changed" in JAVA and "brain-candidate.json" in TRAINER,
    "labeled examples survive complete backup and restore": 'o.put("brain_examples"' in JAVA and 'putString("brain_examples"' in JAVA and 'version",5' in JAVA,
    "local brain benchmark tests fixed phrases without mutating data": "runLocalBrainBenchmark()" in JAVA and "run brain benchmark" in JAVA and "does not change the model" in JAVA,
    "source upgrade wording is consistent across app and metadata": "Ammu 1.1" in MANIFEST and "source upgrade" in README.lower() and "versionName '1.1'" in BUILD,
    "learning examples can be imported through Android document picker": "REQ_IMPORT_BRAIN_EXAMPLES" in JAVA and "ACTION_OPEN_DOCUMENT" in JAVA and "importBrainExamples(Uri uri)" in JAVA,
    "imported learning examples are format-checked, bounded, deduplicated and label-allowlisted": "maxBytes=1024*1024" in JAVA and "Unsupported format" in JAVA and "merged.length()>=500" in JAVA and "imported_reviewed" in JAVA,
    "invalid import leaves existing learning queue unchanged": "Your existing learning queue was not changed" in JAVA and 'brainExamples=merged;save("brain_examples",brainExamples)' in JAVA,
    "fixed non-overlapping development benchmark has five phrases per model intent": (ROOT / "app/src/main/assets/brain-benchmark-v1.json").exists() and len(json.loads((ROOT / "app/src/main/assets/brain-benchmark-v1.json").read_text(encoding="utf-8")).get("examples", [])) == 135,
    "new model uses word, bigram and character n-gram features": MODEL.get("feature_schema") == "word-bigram-char3-char4-v1" and any(str(x).startswith("ch3:") for x in MODEL["vocab"]) and any(str(x).startswith("ch4:") for x in MODEL["vocab"]),
    "weights are compactly serialized to six decimal places": MODEL.get("weight_precision_decimal_places") == 6 and "round(float(value), 6)" in TRAINER,
    "model evaluator supports fixed development benchmark and explicit promotion gate": "PROMOTION GATE" in (ROOT / "tools/evaluate_brain.py").read_text(encoding="utf-8") and "--promote" in (ROOT / "tools/evaluate_brain.py").read_text(encoding="utf-8"),
    "fixed benchmark phrases are excluded from offline training": "protected evaluation phrases were excluded" in TRAINER and "brain-benchmark-v1.json" in TRAINER,
    "on-device development benchmark is available as a command": "run development brain benchmark" in JAVA and "runDevelopmentBrainBenchmark()" in JAVA,
    "sparse inference uses only active word and character features": "for(int activeId:activeIndices)" in JAVA and "addCharacterFeatures(fs,token)" in JAVA and "double[] x=new double[vocabIndex.size()]" not in JAVA,
    "typo correction index excludes non-word model features": "typoWordsByLength" in JAVA and '"ch3:"' in JAVA and '"ch4:"' in JAVA,
    "CI still exports evaluation reports": "evaluation/ci-independent-brain-report.json" in WORKFLOW and "evaluation/ci-secondary-brain-report.json" in WORKFLOW,
    "updated training corpus has over 600 labeled examples": sum(len(v.get("examples", [])) for v in INTENTS.values()) >= 600,
    "new v2 benchmark contains 108 phrases and is guarded from training": (ROOT / "app/src/main/assets/brain-benchmark-v2.json").exists() and len(json.loads((ROOT / "app/src/main/assets/brain-benchmark-v2.json").read_text(encoding="utf-8")).get("examples", [])) == 108 and "brain-benchmark-v2.json" in TRAINER,
    "on-device secondary benchmark validates all classes and is non-mutating": "run unseen brain benchmark" in JAVA and "runUnseenBrainBenchmark()" in JAVA and "counts.size()!=brainLabels.length" in JAVA and "entry.getValue()[1]!=4" in JAVA and "does not change saved data or model weights" in JAVA,
    "single-note delete requires review confirmation": "confirmDeleteNoteAtIndex(n)" in JAVA and "setPositiveButton(\"Delete note\"" in JAVA,
    "single-memory forget requires review confirmation": "confirmForgetMemoryAtIndex(n)" in JAVA and "setPositiveButton(\"Forget fact\"" in JAVA,
    "task completion requires confirmation and rechecks task existence": "confirmCompleteTask(id)" in JAVA and "findTaskIndexById(id)" in JAVA and "setPositiveButton(\"Mark complete\"" in JAVA,
    "settings launch is based on explicit wording rather than model guess": "isExplicitSettingsOpenCommand(s)" in JAVA and "else if(isExplicitSettingsOpenCommand(s))" in JAVA,
 }
failed=[]
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + label)
    if not ok: failed.append(label)
if failed:
    raise SystemExit("Failed checks: " + "; ".join(failed))
print(f"PASS: {len(checks)} Ammu 1.1 source checks")
