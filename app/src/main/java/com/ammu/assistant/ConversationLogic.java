package com.ammu.assistant;

import java.util.Locale;
import java.util.List;
import java.util.regex.Pattern;

final class ConversationLogic {
    private static final Pattern QUESTION = Pattern.compile(
        "^(what|who|where|when|why|how|which|whose|whom)\\b.*|.*\\?$",
        Pattern.CASE_INSENSITIVE
    );

    private ConversationLogic() {}

    static String classify(String text) {
        String question = normalize(text);
        if (question.isEmpty()) return "other";
        if (isCorrection(question)) return "correction";
        if (question.matches("^(what'?s|what is|tell me) (your name|your identity)\\b.*")
            || question.matches("^(who are you|what are you|what should i call you|what can i call you|are you (a person|human|an ai|an assistant))\\b.*")
            || question.matches("^(तुम्हारा नाम क्या है|तुम कौन हो|ನಿನ್ನ ಹೆಸರೇನು|ನೀನು ಯಾರು)(?:\\s|$|[?!.]).*")) {
            return "identity";
        }
        if (question.matches("^(how are you|how are you doing|are you (okay|ok|well|all right|alright))\\b.*")
            || question.matches("^(you doing okay|you all right|you alright|how is everything|how's everything)\\b.*")
            || question.matches("^(क्या तुम ठीक हो|कैसी हो|ನೀನು ಚೆನ್ನಾಗಿದ್ದೀಯಾ|ಹೇಗಿದ್ದೀಯ)(?:\\s|$|[?!.]).*")) {
            return "wellbeing";
        }
        if (question.matches("^(hello|hi|hey|good morning|good afternoon|good evening|namaste|ನಮಸ್ಕಾರ|ನಮಸ್ತೆ)(\\b|$).*")) {
            return "greeting";
        }
        if (question.matches("^(what is|what's|what does|what do you mean|can you explain|tell me more|go on|and what about|what about)\\b.*")
            || question.matches("^(continue|explain that|explain it)\\b.*")) {
            return "follow_up";
        }
        return QUESTION.matcher(question).matches() ? "ordinary_question" : "other";
    }

    static String followUpResponse(String question, String previousUser, String previousAssistant) {
        String q = normalize(question);
        String userContext = cleanContext(previousUser);
        String assistantContext = cleanContext(previousAssistant);
        if (userContext.isEmpty()) {
            return "I don't have an earlier topic to connect that to yet. What does “it” or “that” refer to?";
        }
        if (q.matches("^(tell me more|go on|continue)\\b.*") && !assistantContext.isEmpty()) {
            return "I was responding to “" + userContext + "”. Which part of my last reply would you like me to expand on?";
        }
        return "Are you referring to “" + userContext + "” from your previous message? I don't want to guess what “it” means.";
    }

    static String personalMemoryQuestionSubject(String question) {
        String q = normalize(question);
        if(!q.matches("^(what is|what's|what are|what was|do you remember|did i tell you).*(my|mine)\\b.*"))return "";
        String subject=q.replaceFirst("^(what is|what's|what are|what was|do you remember|did i tell you)\\s*(about|that)?\\s*(my|mine)?\\s*", "")
            .replaceFirst("^(name|favorite|favourite|preferred|preference for)", "$1")
            .replaceAll("[?!.]+$", "").trim();
        return subject;
    }

    static String questionClarification(String question) {
        String q = normalize(question);
        String whatSubject=q.replaceFirst("^(what|which)\\s+(is|are)\\s+","").replaceAll("[?!.]+$","").trim();
        if(q.matches("^(what|which)\\s+(is|are)\\b.*")&&!whatSubject.isEmpty()&&!whatSubject.matches("^(it|this|that|one|thing|something)$")) {
            return "I don't have a reliable local answer about "+whatSubject+". What specific part would you like me to clarify? I won't guess.";
        }
        if (q.matches("^(who|whose|whom)\\b.*")) {
            return "I don't know who you mean from this message alone. Which person or group are you asking about?";
        }
        if (q.matches("^where\\b.*")) {
            return "I don't have enough context to identify the place. What location or situation are you asking about?";
        }
        if (q.matches("^when\\b.*")) {
            return "I can't determine the time without more context. Which event or plan are you asking about?";
        }
        if (q.matches("^(why|how)\\b.*")) {
            return "I don't have enough details to answer that reliably. What happened, or what subject should I use?";
        }
        if (q.matches("^(what|which)\\b.*")) {
            return "I don't know which thing you mean yet. What subject or details should I use to answer?";
        }
        return "I can't verify an answer from the information here. Could you tell me the subject or context?";
    }

    static String memoryType(String fact) {
        String value = normalize(fact);
        if (value.matches(".*\\b(i prefer|i like|my preference is|i would rather|keep it short|be concise|short answers|be more gentle|be more direct)\\b.*")) return "preference";
        if (value.matches(".*\\b(today|today only|for now|temporarily|this week|until tomorrow|right now|currently)\\b.*")) return "temporary";
        return "personal_fact";
    }

    static String memoryKey(String fact) {
        String value = normalize(fact)
            .replaceFirst("^(actually|correction|correct(ion)?)[,:]?\\s*", "")
            .replaceAll("[.!?]+$", "")
            .trim();
        if (value.matches(".*\\b(language|speak|respond|english|hindi|kannada)\\b.*")
            && value.matches(".*\\b(prefer|rather|like)\\b.*")) return "preference:language";
        if (value.matches(".*\\b(reply|answer|response)s?\\b.*")
            && value.matches(".*\\b(prefer|rather|like)\\b.*")) return "preference:reply_style";
        String[] relations = {" is ", " are ", " am ", " live in ", " lives in ", " work as ", " works as ", " study ", " studies "};
        for (String relation : relations) {
            int index = value.indexOf(relation);
            if (index > 0) return value.substring(0, index).trim();
        }
        return value;
    }

    static boolean isSensitive(String fact) {
        String value = normalize(fact);
        return value.matches(".*\\b(password|passcode|pin number|bank account|credit card|debit card|medical diagnosis|medication|prescription|health condition|religion|political affiliation|sexual orientation|social security|aadhaar|aadhar|home address|phone number|email address|date of birth|passport number)\\b.*");
    }

    static boolean isUncertain(String statement) {
        return normalize(statement).matches(".*\\b(maybe|perhaps|probably|i guess|might be|could be|not sure if|seems like|i suspect)\\b.*");
    }

    static boolean supportsOnDeviceLanguage(List<String> installedLanguages, String requestedLanguage) {
        if (installedLanguages == null || requestedLanguage == null || requestedLanguage.trim().isEmpty()) return false;
        String requested = Locale.forLanguageTag(requestedLanguage).toLanguageTag();
        for (String installed : installedLanguages) {
            if (installed != null && requested.equalsIgnoreCase(Locale.forLanguageTag(installed).toLanguageTag())) return true;
        }
        return false;
    }

    static boolean isExplicitCorrection(String text) {
        String value = normalize(text);
        if (!value.matches("^(actually|correction|correct(ion)?|you misunderstood me|that's not what i meant|that is not what i meant)[,:]?\\s+.+")) return false;
        String fact = value.replaceFirst("^(actually|correction|correct(ion)?|you misunderstood me|that's not what i meant|that is not what i meant)[,:]?\\s+", "");
        return fact.matches("^(my|i|we)\\s+(prefer|like|would rather)\\s+.+")
            || fact.matches("^(my|i|we)\\s+.+\\b(is|are|am|live in|lives in|work as|works as|study|studies)\\b.+");
    }

    static boolean isExplicitPreference(String text) {
        return normalize(text).matches("^(i prefer|i like|my preference is|i would rather)\\s+.+");
    }

    private static boolean isCorrection(String text) {
        return text.matches("^(actually|correction|correct(ion)?)[,:]?\\s+.+")
            || text.matches("^(i need to correct|let me correct|i want to correct)\\b.*")
            || text.matches("^(you misunderstood me|that's not what i meant|that is not what i meant)\\b.*");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String cleanContext(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }
}
