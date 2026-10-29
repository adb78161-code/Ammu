package com.ammu.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Local conversation orchestrator. Rules, memories, knowledge, and the neural intent model remain separate. */
final class ConversationPipeline {
    interface IntentModel {
        String predict(String text);
    }

    static final class Result {
        final String intent;
        final String entities;
        final String answer;
        final String topic;

        Result(String intent, String entities, String answer, String topic) {
            this.intent = intent;
            this.entities = entities;
            this.answer = answer;
            this.topic = topic;
        }
    }

    private final IntentModel intentModel;
    private final Map<String, String> knowledge = new LinkedHashMap<>();
    private String activeTopic = "";
    private String lastUser = "";
    private String lastAssistant = "";

    ConversationPipeline(IntentModel intentModel) {
        this.intentModel = intentModel;
        knowledge.put("artificial intelligence", "Artificial intelligence is the field of building computer systems that perform tasks associated with perception, reasoning, learning, or language. It is important not to confuse this with consciousness or human-like understanding: a system can perform well without possessing those qualities.");
        knowledge.put("ai", "AI stands for artificial intelligence: computer systems designed to perform tasks such as classification, prediction, language processing, or planning. Ammu uses small local classifiers and deterministic rules, not a large pretrained language model.");
        knowledge.put("machine learning", "Machine learning is a way of fitting a computational model to examples so it can make predictions for new inputs. A trained classifier can be useful and still fail outside its training examples.");
        knowledge.put("privacy", "Ammu keeps local data on this phone, has no Android INTERNET permission, and does not send typed conversations or voice recordings to a remote AI service.");
    }

    synchronized Result respond(String raw, List<String> memories) {
        String input = normalize(raw);
        if (input.isEmpty()) return null;
        String modelIntent = intentModel == null ? "unknown" : intentModel.predict(input);
        String intent;
        String entities = entities(input);

        if (matches(input, "^(what is|whats|what's)\\s+(it|this|that|one|the thing)\\s*[?!.]*$")) intent = "follow_up";
        else if (matches(input, "^(hi|hello|hey|good morning|good afternoon|good evening|namaste)\\b")) intent = "greeting";
        else if (matches(input, "^(what|who).*\\b(your name|you called|call you|who are you)\\b|^what are you\\b")) intent = "identity";
        else if (matches(input, "^(how are you|are you (okay|ok|well|alright|all right))\\b|^you (okay|alright)\\b")) intent = "wellbeing";
        else if (matches(input, "^(tell me more|go on|continue|explain it|why\\??)$")) intent = "follow_up";
        else if (matches(input, "^(what did i just tell you|what did i tell you|summarize what i said|do you remember what i said)\\b")) intent = "conversation_recall";
        else if (matches(input, "^(remember that|remember |keep in mind that|save to memory)\\b")) intent = "memory_save";
        else if (matches(input, "^(correct|update) memory\\b|^(actually|correction)\\b")) intent = "memory_correction";
        else if (matches(input, "^(what colour do i like|what color do i like|what.*\\b(my favorite|my favourite).*)\\?*$")) intent = "memory_recall";
        else if (matches(input, "^(explain|tell me about|what is|whats)\\b")) intent = "knowledge_question";
        else intent = ConversationLogic.classify(input);

        Result result;
        switch (intent) {
            case "greeting": result = resolved("greeting", entities, "Hello. What would you like to work on or discuss?", ""); break;
            case "identity": result = resolved("identity", entities, "I'm Ammu, a local Android assistant. I use deterministic rules, retrieved memories, and a small custom intent classifier; I do not have a human identity.", "ammu identity"); break;
            case "wellbeing": result = resolved("wellbeing", entities, "I'm operating normally on this phone. I don't have feelings, but I can listen or help with a task. How are you doing?", ""); break;
            case "conversation_recall": result = resolved("conversation_recall", entities, lastUser.isEmpty() ? "I don't have a previous message in this conversation session." : "You most recently told me: \"" + lastUser + "\".", activeTopic); break;
            case "memory_save": result = null; break;
            case "memory_correction": result = null; break;
            case "memory_recall": result = memoryRecall(input, memories); break;
            case "follow_up": result = followUp(input, entities); break;
            case "knowledge_question": result = knowledgeAnswer(input, entities); break;
            case "ordinary_question": result = null; break;
            default: result = null;
        }
        remember(raw, result == null ? "" : result.answer);
        return result;
    }

    private Result memoryRecall(String input, List<String> memories) {
        List<String> hits = new ArrayList<>();
        for (String memory : memories) {
            String fact = memory.toLowerCase(Locale.ROOT);
            if ((input.contains("colour") || input.contains("color") || input.contains("favorite") || input.contains("favourite")) && matches(fact, "\\b(favorite|favourite|colour|color|like|prefer|preference)\\b")) hits.add(memory);
            else if (input.contains("name") && matches(fact, "\\b(name)\\b")) hits.add(memory);
        }
        if (hits.isEmpty()) return resolved("memory_recall", entities(input), "I don't have a saved fact for that yet. Teach it explicitly with 'Remember that ...'.", activeTopic);
        return resolved("memory_recall", entities(input), "My saved memory says: " + hits.get(hits.size() - 1), activeTopic);
    }

    private Result knowledgeAnswer(String input, String entities) {
        String subject = knowledgeSubject(input);
        String answer = knowledge.get(subject);
        if (answer == null) {
            for (Map.Entry<String, String> entry : knowledge.entrySet()) {
                if (subject.contains(entry.getKey()) || entry.getKey().contains(subject)) { answer = entry.getValue(); break; }
            }
        }
        if (answer != null) return resolved("knowledge_question", entities, answer, subject);
        return null;
    }

    private Result followUp(String input, String entities) {
        if (activeTopic.isEmpty()) return resolved("follow_up", entities, "What are “it”, “that”, or “why” referring to? Please name the topic so I don't guess.", "");
        if (normalize(input).equals("why")) return resolved("follow_up", entities, "Which part of “" + activeTopic + "” should I explain why: its purpose, how it works, or its effect?", activeTopic);
        return resolved("follow_up", entities, "About “" + activeTopic + "”: which aspect would you like me to expand on?", activeTopic);
    }

    private String knowledgeSubject(String input) {
        String subject = normalize(input);
        subject = subject.replaceFirst("^(please\\s+)?(explain|tell me about|what is|whats)\\s*", "")
            .replaceAll("[?!.]+$", "").trim();
        subject = subject.replaceFirst("^(the|an|a)\\s+", "");
        return subject;
    }

    private String entities(String input) {
        StringBuilder found = new StringBuilder();
        for (String topic : knowledge.keySet()) if (input.contains(topic)) found.append(found.length() == 0 ? topic : "," + topic);
        return found.toString();
    }

    private void remember(String raw, String answer) {
        lastUser = raw == null ? "" : raw.trim();
        lastAssistant = answer == null ? "" : answer.trim();
        if (!lastAssistant.isEmpty()) activeTopic = inferTopic(lastAssistant);
    }

    private String inferTopic(String text) {
        String lower = normalize(text);
        for (String topic : knowledge.keySet()) if (lower.contains(topic)) return topic;
        return activeTopic;
    }

    private Result resolved(String intent, String entities, String answer, String topic) {
        if (!topic.isEmpty()) activeTopic = topic;
        return new Result(intent, entities, answer, topic);
    }

    private static boolean matches(String text, String regex) {
        String normalized = normalize(text);
        return Pattern.compile(regex).matcher(normalized).find() || normalized.matches(regex);
    }

    static String normalize(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        value = value.replace("’", "'").replaceAll("\\s+", " ");
        value = value.replace("whats", "what's");
        return value;
    }
}
