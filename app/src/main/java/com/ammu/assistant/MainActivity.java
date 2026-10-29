package com.ammu.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.provider.Settings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.BatteryManager;
import android.speech.tts.TextToSpeech;
import android.speech.RecognizerIntent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO_PERMISSION = 8101;
    private static final int REQ_IMPORT_AUDIO = 8102;
    private static final int REQ_SPEECH_INPUT = 8103;
    private static final int REQ_EXPORT_BACKUP = 8104;
    private static final int REQ_IMPORT_BACKUP = 8105;
    private static final int REQ_EXPORT_BRAIN_EXAMPLES = 8106;
    private static final int REQ_IMPORT_BRAIN_EXAMPLES = 8107;
    private static final int REQ_EXPORT_PHONE_MISTAKES = 8108;
    private static final int MAX_BACKUP_BYTES = 24 * 1024 * 1024;
    LinearLayout messages; EditText input; ScrollView scroll; TextToSpeech tts;
    JSONObject brain; JSONArray notes, tasks, memory, journal, moodLog, conversationHistory, brainExamples; JSONObject customAliases = new JSONObject(); boolean speakEnabled=true;
    private boolean preferRecordedVoice = true;
    private boolean isRecording = false;
    private boolean refreshTtsOnResume = false;
    private MediaRecorder recorder;
    private MediaPlayer voicePlayer;
    private File activeRecording;
    private String recordingSlot = "greeting";
    private String importTargetVoiceSlot = "greeting";
    private Spinner voiceSlotPicker;
    private TextView voiceStatus;
    private Button voiceRecordButton;
    private CheckBox voicePackCheckBox, speechCheckBox;
    private final String[] voiceSlotKeys = {"greeting", "help", "confirmation", "fallback", "goodbye", "emotion_happy", "emotion_sad", "emotion_anxious", "emotion_angry", "emotion_lonely", "emotion_overwhelmed", "emotion_tired", "emotion_confused"};
    private final String[] voiceSlotLabels = {"Greeting", "Help", "Command confirmation", "Didn't understand", "Goodbye", "When you're happy", "When you're sad", "When you're anxious", "When you're angry", "When you're lonely", "When overwhelmed", "When tired", "When confused"};
    private final HashMap<String,Integer> vocabIndex = new HashMap<>();
    private final HashMap<Integer,ArrayList<String>> typoWordsByLength = new HashMap<>();
    private String[] brainLabels = new String[0];
    private boolean brainReady = false;
    private String brainLoadIssue = "";
    private String dataLoadWarnings = "";
    private double[][] weights1 = new double[0][0], weights2 = new double[0][0];
    private double[] bias1 = new double[0], bias2 = new double[0];
    String lastEmotion="", lastTopic="", lastAssistantQuestion=""; String replyStyle="balanced";
    private String lastPlanGoal="", lastPlanText="";
    android.content.SharedPreferences prefs;
    int bg=Color.rgb(12,17,31), panel=Color.rgb(24,32,52), accent=Color.rgb(117,161,255), fg=Color.rgb(240,244,255), muted=Color.rgb(164,178,204);

    @Override public void onCreate(Bundle b){super.onCreate(b); getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);getWindow().getDecorView().setSystemUiVisibility(0);
        prefs=getSharedPreferences("ammu_data",MODE_PRIVATE); loadData(); loadBrain(); replyStyle=prefs.getString("reply_style","balanced"); lastEmotion=prefs.getString("last_emotion",""); lastTopic=prefs.getString("last_topic","");
        speakEnabled=prefs.getBoolean("speak_enabled",true); preferRecordedVoice=prefs.getBoolean("prefer_recorded_voice",true);
        lastPlanGoal=prefs.getString("last_plan_goal",""); lastPlanText=prefs.getString("last_plan_text",""); buildUi();
        initializeTts();
        addMessage("Ammu", "I'm Ammu 1.0, your personal assistant with a custom local AI brain. Your notes, tasks, memories, journal, and voice clips are stored locally by Ammu. Type 'help' or 'privacy and offline info' to explore. Voice recognition is provided by Android and may use network services depending on your selected recognizer.", false);
        if (!brainReady) addMessage("Ammu", "Warning: the bundled custom brain could not be validated (" + brainLoadIssue + "). Explicit offline commands may still work, but intent recognition is unavailable. Open TOOLS → Brain diagnostics.", false);
        if (!dataLoadWarnings.isEmpty()) addMessage("Ammu", "Some local data could not be read: " + dataLoadWarnings + ". Restore from a recent Ammu backup if you have one.", false);
    }
    void initializeTts(){
        if(tts!=null){try{tts.stop();tts.shutdown();}catch(Exception ignored){}}
        tts=new TextToSpeech(this, status -> { if(status==TextToSpeech.SUCCESS && tts!=null) { int result=tts.setLanguage(Locale.getDefault()); if(result==TextToSpeech.LANG_MISSING_DATA || result==TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US); } });
    }
    void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);
        // Android 15 enforces edge-to-edge for apps targeting SDK 35. Add system-bar
        // insets to our base padding so the header/composer remain clear of status,
        // navigation, and display-cutout areas on both modern and older Android.
        final int baseLeft=dp(16), baseTop=dp(10), baseRight=dp(16), baseBottom=dp(12);
        root.setPadding(baseLeft,baseTop,baseRight,baseBottom);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                left=bars.left; top=bars.top; right=bars.right; bottom=bars.bottom;
            } else {
                left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft+left,baseTop+top,baseRight+right,baseBottom+bottom);
            return insets;
        });
        setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(0,dp(8),0,dp(14));
        TextView orb=new TextView(this);orb.setText("✦");orb.setTextColor(accent);orb.setTextSize(30);orb.setGravity(Gravity.CENTER);orb.setBackground(round(panel,24));LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(dp(54),dp(54));op.rightMargin=dp(12);header.addView(orb,op);
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);TextView title=txt("AMMU",24,fg,true);TextView subtitle=txt("PERSONAL OFFLINE ASSISTANT",10,muted,true);titleBox.addView(title);titleBox.addView(subtitle);header.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));
        Button voiceButton=button("MY VOICE",false); LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(dp(76),dp(42));vp.rightMargin=dp(6);header.addView(voiceButton,vp);voiceButton.setOnClickListener(v->showVoiceStudio());
        Button toolsButton=button("TOOLS",false); LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(58),dp(42));tp.rightMargin=dp(6);header.addView(toolsButton,tp);toolsButton.setOnClickListener(v->showToolsDialog());
        TextView status=txt("● LOCAL",10,Color.rgb(111,224,173),true);header.addView(status);root.addView(header);
        scroll=new ScrollView(this);scroll.setFillViewport(false);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(0,0,0,dp(10));scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView quick=txt("TRY A COMMAND",10,muted,true);quick.setPadding(0,dp(10),0,dp(7));root.addView(quick);
        LinearLayout chips=new LinearLayout(this);String[] quicks={"Time","Battery","Show tasks","Help"};for(String q:quicks){Button x=button(q,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.rightMargin=dp(5);chips.addView(x,p);x.setOnClickListener(v->runCommand(q.toLowerCase(Locale.ROOT)));}root.addView(chips);
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.CENTER_VERTICAL);composer.setPadding(dp(8),dp(5),dp(5),dp(5));composer.setBackground(round(panel,18));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(58));cp.topMargin=dp(10);root.addView(composer,cp);
        input=new EditText(this);input.setSingleLine(true);input.setTextColor(fg);input.setHintTextColor(muted);input.setHint("Ask Ammu anything...");input.setTextSize(15);input.setBackgroundColor(Color.TRANSPARENT);input.setImeOptions(EditorInfo.IME_ACTION_SEND);composer.addView(input,new LinearLayout.LayoutParams(0,-1,1));
        Button mic=button("🎙",false);composer.addView(mic,new LinearLayout.LayoutParams(dp(42),dp(44)));mic.setContentDescription("Speak to Ammu");mic.setOnClickListener(v->startSpeechInput());
        Button send=button("➤",true);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(42),dp(44));sp.leftMargin=dp(4);composer.addView(send,sp);send.setOnClickListener(v->send());input.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEND){send();return true;}return false;});
    }

    // Deterministic on-device phrase normalization for common Hindi and Kannada commands.
    // It is intentionally limited to explicit phrases; it does not translate arbitrary text.
    String normalizeOfflinePhrase(String input){
        if(input==null)return "";
        String raw=input.trim();String q=raw.toLowerCase(Locale.ROOT);
        String[][] exact={
            {"अभी कितने बजे हैं","what time is it"},{"समय बताओ","what time is it"},{"अम्मू समय क्या है","what time is it"},
            {"ಈಗ ಎಷ್ಟು ಗಂಟೆ","what time is it"},{"ಸಮಯ ಹೇಳು","what time is it"},{"ಅಮ್ಮು ಸಮಯ ಎಷ್ಟು","what time is it"},
            {"आज की तारीख बताओ","what is today's date"},{"आज कौन सा दिन है","what is today's date"},{"ಇಂದಿನ ದಿನಾಂಕ ಹೇಳು","what is today's date"},{"ಇವತ್ತು ಯಾವ ದಿನ","what is today's date"},
            {"बैटरी कितनी बची है","check battery"},{"फोन की बैटरी बताओ","check battery"},{"ಬ್ಯಾಟರಿ ಎಷ್ಟು ಉಳಿದಿದೆ","check battery"},{"ಫೋನ್ ಬ್ಯಾಟರಿ ಹೇಳು","check battery"},
            {"मेरा फोन कौन सा है","show device info"},{"फोन की जानकारी बताओ","show device info"},{"ನನ್ನ ಫೋನ್ ಯಾವುದು","show device info"},{"ಫೋನ್ ಮಾಹಿತಿ ಹೇಳು","show device info"},
            {"सेटिंग्स खोलो","open settings"},{"फोन की सेटिंग खोलो","open settings"},{"ಸೆಟ್ಟಿಂಗ್ಸ್ ತೆರೆಯು","open settings"},{"ಫೋನ್ ಸೆಟ್ಟಿಂಗ್ಸ್ ತೆರೆಯಿರಿ","open settings"},
            {"मदद करो","help"},{"तुम क्या कर सकती हो","help"},{"सहಾಯ ಮಾಡು","help"},{"ನೀನು ಏನು ಮಾಡಬಹುದು","help"},
            {"मेरे नोट दिखाओ","show my notes"},{"नोट्स की सूची दिखाओ","show my notes"},{"ನನ್ನ ಟಿಪ್ಪಣಿಗಳನ್ನು ತೋರಿಸು","show my notes"},{"ಉಳಿಸಿದ ಟಿಪ್ಪಣಿಗಳನ್ನು ಓದು","show my notes"},
            {"मेरे बाकी काम दिखाओ","show my tasks"},{"मेरे कामों की सूची दिखाओ","show my tasks"},{"ನನ್ನ ಬಾಕಿ ಕೆಲಸ ತೋರಿಸು","show my tasks"},{"ನನ್ನ ಕೆಲಸಗಳ ಪಟ್ಟಿ ತೋರಿಸು","show my tasks"},
            {"तुम्हें मेरे बारे में क्या याद है","what do you remember about me"},{"मेरे सेव किए तथ्य बताओ","what do you remember about me"},{"ನನ್ನ ಬಗ್ಗೆ ಏನು ನೆನಪಿದೆ","what do you remember about me"},{"ನೀನು ಏನು ನೆನಪಿಟ್ಟಿದ್ದೀಯ","what do you remember about me"},
            {"मेरी डायरी दिखाओ","show my journal"},{"डायरी की पिछली बातें पढ़ो","show my journal"},{"ನನ್ನ ದಿನಚರಿ ತೋರಿಸು","show my journal"},{"ಡೈರಿಯಲ್ಲಿ ಬರೆದದ್ದು ಓದು","show my journal"},
            {"मेरे डेटा का बैकअप बनाओ","backup"},{"बैकअप विकल्प दिखाओ","backup"},{"ನನ್ನ ಡೇಟಾ ಬ್ಯಾಕಪ್ ಮಾಡು","backup"},{"ಬ್ಯಾಕಪ್ ಆಯ್ಕೆ ತೋರಿಸು","backup"},
            {"नमस्ते अम्मू","hello ammu"},{"हैलो अम्मू","hello ammu"},{"ನಮಸ್ಕಾರ ಅಮ್ಮು","hello ammu"},{"ಹಾಯ್ ಅಮ್ಮು","hello ammu"},
            {"कैमरा खोलो","open app camera"},{"कैलकुलेटर खोलो","open app calculator"},{"घड़ी खोलो","open app clock"},{"फाइल मैनेजर खोलो","open app files"},{"मानचित्र खोलो","open app maps"},
            {"ಕ್ಯಾಮೆರಾ ತೆರೆಯು","open app camera"},{"ಕ್ಯಾಲ್ಕುಲೇಟರ್ ತೆರೆಯು","open app calculator"},{"ಗಡಿಯಾರ ತೆರೆಯು","open app clock"},{"ಫೈಲ್ಸ್ ತೆರೆಯು","open app files"},{"ನಕ್ಷೆ ತೆರೆಯು","open app maps"},
            {"अलविदा अम्मू","goodbye"},{"फिर मिलेंगे","goodbye"},{"ವಿದಾಯ ಅಮ್ಮು","goodbye"},{"ಮತ್ತೆ ಭೇಟಿಯಾಗೋಣ","goodbye"},
            {"इसे रद्द करो","cancel"},{"यह काम रोक दो","cancel"},{"ಇದನ್ನು ರದ್ದುಮಾಡು","cancel"},{"ಈ ಕಾರ್ಯ ನಿಲ್ಲಿಸು","cancel"},
            {"याद नंबर दो भूल जाओ","forget memory 2"},{"सेव की गई याद 1 हटाओ","forget memory 1"},{"मेमोरी 3 मिटाओ","forget memory 3"},
            {"ನೆನಪು ಸಂಖ್ಯೆ ಎರಡು ಮರೆತುಬಿಡು","forget memory 2"},{"ಉಳಿಸಿದ ನೆನಪು 1 ಅಳಿಸು","forget memory 1"},{"ಮೆಮೊರಿ 3 ತೆಗೆದುಹಾಕು","forget memory 3"}
        };
        for(String[] pair:exact)if(q.equals(pair[0]))return pair[1];
        Matcher nativeTaskDone=Pattern.compile("^(?:काम|कार्य)\\s*(\\d+)\\s*(?:पूरा करो|पूरा चिह्नित करो|पूर्ण करो)$").matcher(q);
        if(nativeTaskDone.matches())return "mark task "+nativeTaskDone.group(1)+" done";
        nativeTaskDone=Pattern.compile("^ಕೆಲಸ\\s*(\\d+)\\s*(?:ಪೂರ್ಣಗೊಳಿಸು|ಪೂರ್ಣಮಾಡು)$").matcher(q);
        if(nativeTaskDone.matches())return "mark task "+nativeTaskDone.group(1)+" done";
        String converted;
        converted=replaceNativePrefix(raw,new String[]{"ಇದನ್ನು ಟಿಪ್ಪಣಿಯಾಗಿ ಉಳಿಸು","ಟಿಪ್ಪಣಿ ಉಳಿಸು","ನೋಟ್ ಉಳಿಸು","टिप्पणी सेव करो","नोट सेव करो","नोट लिखो","इसे नोट में सेव करो"},"save a note: ");if(converted!=null)return converted;
        converted=replaceNativePrefix(raw,new String[]{"ನನ್ನ ಕೆಲಸಗಳಿಗೆ ಸೇರಿಸು","ಕೆಲಸದ ಪಟ್ಟಿಗೆ ಸೇರಿಸು","ಹೊಸ ಕೆಲಸ ಸೇರಿಸು","ಕೆಲಸ ಸೇರಿಸು","मेरे काम में जोड़ो","काम जोड़ो","कार्य जोड़ें","एक काम बनाओ"},"add task: ");if(converted!=null)return converted;
        converted=replaceNativePrefix(raw,new String[]{"एक निजी तथ्य सेव करो","ಇದನ್ನು ನೆನಪಿಟ್ಟುಕೋ","ನೆನಪಿಟ್ಟುಕೋ","ನನ್ನ ಇಷ್ಟ ನೆನಪಿಡು","याद रखना","इसे याद रखना","मेरी पसंद याद रखो"},"remember that ");if(converted!=null)return converted;
        converted=replaceNativePrefix(raw,new String[]{"ನನ್ನ ದಿನಚರಿಯಲ್ಲಿ ಉಳಿಸು","ದಿನಚರಿಯಲ್ಲಿ ಬರೆಯು","ಡೈರಿಯಲ್ಲಿ ದಾಖಲಿಸು","मेरी डायरी में सेव करो","डायरी में लिखो","डायरी में दर्ज करो"},"write in my journal: ");if(converted!=null)return converted;
        converted=replaceNativePrefix(raw,new String[]{"ನನ್ನ ಟಿಪ್ಪಣಿಯಲ್ಲಿ ಹುಡುಕು","ನೋಟ್‌ಗಳಲ್ಲಿ ಹುಡುಕು","टिप्पणी में खोजो","नोट्स में खोजो"},"search notes for ");if(converted!=null)return converted;
        converted=replaceNativePrefix(raw,new String[]{"ನನ್ನ ಕೆಲಸಗಳಲ್ಲಿ ಹುಡುಕು","ಕೆಲಸದಲ್ಲಿ ಹುಡುಕು","कामों में खोजो","काम में खोजो"},"search tasks for ");if(converted!=null)return converted;
        converted=replaceNativePrefix(raw,new String[]{"ನೆನಪಿನಲ್ಲಿ ಹುಡುಕು","ಮೆಮೊರಿಯಲ್ಲಿ ಹುಡುಕು","यादों में खोजो","मेमोरी में खोजो"},"search memory for ");if(converted!=null)return converted;
        return raw;
    }
    String replaceNativePrefix(String raw,String[] prefixes,String target){
        String q=raw.toLowerCase(Locale.ROOT);
        for(String prefix:prefixes){String lower=prefix.toLowerCase(Locale.ROOT);if(q.equals(lower)||q.startsWith(lower+":")||q.startsWith(lower+"：")||q.startsWith(lower+" ")){
            String rest=raw.substring(Math.min(prefix.length(),raw.length())).replaceFirst("^[\\s:：,-]+","").trim();return target+rest;
        }}return null;
    }

    void send(){String s=input.getText().toString().trim();if(s.isEmpty())return;input.setText("");runCommand(s);}
    void runCommand(String raw){
        if(raw==null)return;
        raw=raw.trim();
        if(raw.isEmpty())return;
        if(raw.length()>2000){addMessage("Ammu","That message is too long for a single command (maximum 2,000 characters). Split it into smaller messages; nothing was saved.",false);return;}
        addMessage("You",raw,true);
        String userText=raw.toLowerCase(Locale.ROOT).trim();
        String learningMeta=handleBrainLearningCommand(raw);
        if(learningMeta!=null){rememberTurn(raw,learningMeta);addMessage("Ammu",learningMeta,true);if(speakEnabled&&tts!=null)tts.speak(learningMeta,TextToSpeech.QUEUE_FLUSH,null,"ammu-learning");return;}
        String aliasMeta=handleCommandAliasCommand(raw);
        if(aliasMeta!=null){rememberTurn(raw,aliasMeta);addMessage("Ammu",aliasMeta,true);if(speakEnabled&&tts!=null)tts.speak(aliasMeta,TextToSpeech.QUEUE_FLUSH,null,"ammu-reply");return;}
        // Keep the last ordinary phrase so the user can explicitly label a mistake afterward.
        prefs.edit().putString("last_learning_phrase",raw.trim()).apply();
        String normalizedText=normalizeOfflinePhrase(raw);
        String s=resolveCommandAlias(normalizedText.toLowerCase(Locale.ROOT));
        String intent=predict(s);
        prefs.edit().putString("last_learning_predicted",intent).putString("last_learning_normalized",s).apply();
        String answer; try {
        // Safety and emotional interpretation always use the original user text, not a custom alias.
        String safety = safetyResponse(userText);
        String emotion = detectEmotion(userText);
        String preferenceResponse = handlePreferenceOrCorrection(userText, raw);
        if(safety != null) answer=safety;
        else if(preferenceResponse != null) answer=preferenceResponse;
        else if(mixedEmotionReply(userText) != null) answer=mixedEmotionReply(userText);
        else if(isNegatedFeeling(userText)) answer=negatedFeelingReply(userText);
        else if(isFollowUp(userText) && !lastEmotion.isEmpty()) answer=followUpReply(userText);
        else if(emotion != null && isFeelingStatement(userText)) { lastEmotion=emotion; lastTopic=raw; prefs.edit().putString("last_emotion",lastEmotion).putString("last_topic",lastTopic).apply(); answer=emotionReply(emotion, raw); moodLog.put(new JSONObject().put("emotion",emotion).put("text",raw).put("date",now())); trimOldest(moodLog,200); save("mood",moodLog); }
        else if(s.matches(".*\\b(hello|hi|hey ammu|good morning|good evening)\\b.*")||intent.equals("greeting")) answer="Hello! I'm here. What would you like to do?";
        else if(s.equals("cancel")||intent.equals("cancel")) answer="Okay, I'll stop this command. No saved data was changed.";
        else if(s.equals("privacy and offline info")||s.equals("offline privacy")||s.equals("privacy info")) {showPrivacyAndOfflineInfo();answer="I opened the local privacy and offline-operation details.";}
        else if(s.equals("help")||intent.equals("help")) answer="I am a custom local intent brain, not a pretrained LLM. I can reason through simple structured plans, remember facts locally, manage notes/tasks/journal, respond to emotional cues, check device status, open supported Android apps/settings, and back up local data. Try: teach intent phrase what time is it => time; show brain labels; export brain examples; break down: study DBMS; add plan to tasks; remember that my exam is Friday; what do you remember about exam; summarize recent conversation; open app camera; check brain; dashboard; privacy and offline info. No cloud AI is used by Ammu itself.";
        else if(s.equals("check brain")||s.equals("brain status")||s.equals("diagnostics")) {showBrainDiagnostics();answer="I opened the local brain diagnostics panel.";}
        else if(s.equals("dashboard")||s.equals("show dashboard")||s.equals("my summary")) answer=dashboardSummary();
        else if(isExplicitNoteAddCommand(s)){String body=noteBodyFromCommand(raw);if(body.isEmpty())answer="Write it like: save a note: buy milk.";else if(notes.length()>=1000)answer="Your local note limit (1,000) is reached. Export a backup, then remove notes you no longer need.";else{notes.put(new JSONObject().put("text",body).put("date",now()));save("notes",notes);answer="Saved that note on this phone.";}}
        else if(isExplicitDeleteAllNotes(s)){confirmClearNotes();answer="Please confirm before deleting all saved notes.";}
        else if(isExplicitSingleNoteDelete(s)){Matcher m=Pattern.compile("(\\d+)").matcher(s);if(m.find()){int n=Integer.parseInt(m.group(1))-1;if(n>=0&&n<notes.length()){confirmDeleteNoteAtIndex(n);answer="Please review the note preview and confirm deletion. The note is still saved until you confirm.";}else answer="I couldn't find that note number.";}else answer="Use a numeric note ID, for example: delete note 2.";}
        else if(isExplicitTaskAddCommand(s)){String body=taskBodyFromCommand(raw);String priority="normal";Matcher pm=Pattern.compile("(?i)^(high|urgent|low|normal)\\s*:\\s*(.*)$").matcher(body);if(pm.matches()){priority=pm.group(1).equalsIgnoreCase("urgent")?"high":pm.group(1).toLowerCase(Locale.ROOT);body=pm.group(2).trim();}if(body.isEmpty())answer="Try: add task high: study DBMS.";else if(tasks.length()>=500)answer="Your local task limit (500) is reached. Export a backup, then remove completed tasks before adding more.";else{tasks.put(new JSONObject().put("id",nextTaskId()).put("text",body).put("priority",priority).put("done",false).put("date",now()));save("tasks",tasks);answer="Added "+priority+" priority task: "+body+".";}}
        else if(isExplicitTaskCompletion(s)){Matcher m=Pattern.compile("(\\d+)").matcher(s);if(m.find()){int id=Integer.parseInt(m.group(1));if(findTaskIndexById(id)>=0){confirmCompleteTask(id);answer="Please review the task and confirm completion. No task was changed yet.";}else answer="Task not found.";}else answer="Use: mark task 1 done.";}
        else if(s.startsWith("remember that")||s.startsWith("remember ")||s.startsWith("save to memory")||(intent.equals("memory_save")&&isExplicitMemorySaveCommand(s))){String body=stripPrefix(raw,"remember that","remember","save to memory","keep in mind that","store fact that","save this personal detail","एक निजी तथ्य सेव करो","ನೆನಪಿಟ್ಟುಕೋ","ಇದನ್ನು ನೆನಪಿಟ್ಟುಕೋ","ನನ್ನ ಇಷ್ಟ ನೆನಪಿಡು","याद रखना","इसे याद रखना","मेरी पसंद याद रखो");if(body.isEmpty())answer="Try: remember that my favorite color is blue.";else answer=saveMemoryFact(body);}
        else if(isExplicitJournalAddCommand(s)){String body=stripPrefix(raw,"write in my journal","journal:","write diary","journal entry:","add an entry to my diary","save my thoughts in the journal","write in my diary","diary:","ನನ್ನ ದಿನಚರಿಯಲ್ಲಿ ಉಳಿಸು","ದಿನಚರಿಯಲ್ಲಿ ಬರೆಯು","ಡೈರಿಯಲ್ಲಿ ದಾಖಲಿಸು","मेरी डायरी में सेव करो","डायरी में लिखो","डायरी में दर्ज करो");if(body.isEmpty())answer="Try: write in my journal: today I studied.";else if(journal.length()>=500)answer="Your local journal limit (500 entries) is reached. Export a backup before clearing older entries.";else{journal.put(new JSONObject().put("text",body).put("date",now()));save("journal",journal);answer="Added to your private journal on this phone.";}}
        else if(s.startsWith("search journal")){String q=stripPrefix(raw,"search journal for","search journal").toLowerCase(Locale.ROOT);answer=search(journal,"text",q,"No matching journal entries found.");}
        else if(s.startsWith("search memory")){String q=stripPrefix(s,"search memory for","search memory").toLowerCase(Locale.ROOT);answer=searchRelevantMemory(q);}
        else if(s.startsWith("what do you remember about ")||s.startsWith("recall facts about ")){String q=stripPrefix(s,"what do you remember about","recall facts about").toLowerCase(Locale.ROOT);if(q.equals("me")||q.isEmpty())answer=list(memory,"fact","No saved facts yet.","I remember:");else answer=searchRelevantMemory(q);}
        else if(s.matches("^(forget|delete) memory \\d+$")){Matcher mm=Pattern.compile("(\\d+)").matcher(s);if(mm.find()){int n=Integer.parseInt(mm.group(1))-1;if(n>=0&&n<memory.length()){confirmForgetMemoryAtIndex(n);answer="Please review the saved fact and confirm removal. It remains saved until you confirm.";}else answer="I couldn't find that memory item number.";}else answer="Use: forget memory 2.";}
        else if(s.equals("clear all memory")||s.equals("forget all memory")){confirmClearMemory();answer="Please confirm in the dialog to clear all saved facts.";}
        else if(s.equals("summarize recent conversation")||s.equals("what did we discuss")||s.equals("recap our conversation")||s.equals("show recent conversation")){answer=summarizeRecentConversation();}
        else if(s.equals("clear conversation history")||s.equals("clear chat history")){confirmClearConversation();answer="Please confirm in the dialog to clear the conversation history.";}
        else if(s.equals("clear mood history")||s.equals("clear mood log")){confirmClearMoodHistory();answer="Please confirm in the dialog to clear mood history.";}
        else if(s.contains("time")||intent.equals("time")) answer="The time is "+new SimpleDateFormat("h:mm a",Locale.getDefault()).format(new Date())+".";
        else if((s.contains("date")||intent.equals("date"))&&(s.contains("tomorrow")||s.contains("yesterday"))){Calendar cal=Calendar.getInstance();cal.add(Calendar.DAY_OF_YEAR,s.contains("tomorrow")?1:-1);answer=(s.contains("tomorrow")?"Tomorrow is ":"Yesterday was ")+new SimpleDateFormat("EEEE, d MMMM yyyy",Locale.getDefault()).format(cal.getTime())+".";}
        else if(s.contains("date")||intent.equals("date")) answer="Today is "+new SimpleDateFormat("EEEE, d MMMM yyyy",Locale.getDefault()).format(new Date())+".";
        else if(s.contains("battery")||intent.equals("battery")){BatteryManager bm=(BatteryManager)getSystemService(BATTERY_SERVICE);answer="Battery level: "+bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)+"%.";}
        else if(s.contains("device info")||intent.equals("device_info")) answer="Device: "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+", Android "+android.os.Build.VERSION.RELEASE+".";
        else if(s.startsWith("open app ") || s.startsWith("launch app ") || s.startsWith("open ") && isKnownAppRequest(s)) { answer = openKnownApp(s); }
        else if(isExplicitSettingsOpenCommand(s)){answer="Opening Android Settings.";try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception ex){answer="I couldn't open Settings on this device.";}}
        else if(s.startsWith("break down:") || s.startsWith("break down ") || s.startsWith("plan this:") || s.startsWith("help me plan ")) { String goal=stripPrefix(raw,"break down:","break down","plan this:","help me plan").trim(); answer=makeLocalPlan(goal); }
        else if(s.startsWith("what next") || s.startsWith("what should i do next") || s.equals("continue plan")) { answer=!lastPlanText.trim().isEmpty()?nextPlanStep():makeLocalPlan(!lastTopic.isEmpty()?lastTopic:"the task I am working on"); }
        else if(s.equals("add plan to tasks")||s.equals("turn plan into tasks")||s.equals("save plan as tasks")){if(lastPlanText.trim().isEmpty())answer="Create a plan first, for example: break down: prepare for DBMS exam.";else{showAddPlanToTasksDialog();answer="I opened a preview so you can confirm which plan steps to add as tasks.";}}
        else if(isCalculationRequest(s,intent)){String expr=s.replaceFirst("(?i).*?\\b(calculate|compute|what is|solve|work out|evaluate)\\b", "").trim().replace(" multiplied by ","*").replace(" times ","*").replace(" divided by ","/").replace(" plus ","+").replace(" minus ","-").replace(" to the power of ","^").replace(" raised to ","^").replace(" modulo ","%").replace(" mod ","%");try{double v=new Calc(expr).parse();answer="The answer is "+(v==(long)v?Long.toString((long)v):Double.toString(v))+".";}catch(Exception ex){answer="I couldn't calculate that. Try: calculate (12 + 4) * 3.";}}
        else if(s.contains("show my notes")||s.equals("list notes")||intent.equals("note_list")){answer=list(notes,"text","You have no saved notes.","Your notes:");}
        else if(s.startsWith("search notes")||intent.equals("note_search")){String q=stripPrefix(s,"search notes for","search notes").toLowerCase(Locale.ROOT);answer=search(notes,"text",q,"No matching notes found.");}
        else if(intent.equals("note_delete_one")) answer="I may have misunderstood. To delete a note, use an explicit command such as 'delete note 2'. I won't delete anything based on a guess.";
        else if(s.startsWith("search tasks")||intent.equals("task_search")){String q=stripPrefix(s,"search tasks for","search tasks").toLowerCase(Locale.ROOT);answer=search(tasks,"text",q,"No matching tasks found.");}
        else if(s.contains("show my tasks")||s.contains("list tasks")||intent.equals("task_list")){StringBuilder out=new StringBuilder();for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t!=null&&!t.optBoolean("done"))out.append(t.optInt("id")).append(". [").append(t.optString("priority","normal").toUpperCase(Locale.ROOT)).append("] ").append(t.optString("text")).append("\n");}answer=out.length()==0?"No pending tasks.":"Pending tasks:\n"+out;}
        else if(intent.equals("task_done")) answer="I won't mark anything complete based on a guess. Use an explicit command such as 'mark task 2 done'.";
        else if(s.contains("what do you remember")||s.contains("recall memory")||intent.equals("memory_recall")){answer=list(memory,"fact","No saved facts yet.","I remember:");}
        else if(intent.equals("journal_add")) answer="I may have misunderstood. To save a journal entry, say 'write in my journal: ...'. I won't save a diary entry based on a guess.";
        else if(s.contains("show my journal")||intent.equals("journal_list"))answer=list(journal,"text","Your journal is empty.","Recent journal entries:");
        else if(s.contains("my mood history")||s.contains("show mood log"))answer=list(moodLog,"emotion","No mood entries yet.","Recent mood check-ins:");
        else if(s.contains("backup")||intent.equals("backup"))answer="Open TOOLS and choose Export complete backup to save your notes, tasks, memory, journal, settings, command shortcuts, and voice clips to a ZIP file. Import backup replaces current local data after confirmation.";
        else if(s.equals("exit")||s.equals("goodbye")||intent.equals("goodbye"))answer="See you later. Your local data is saved.";
        else if(s.contains("what should i do")||s.contains("what can i do")||s.equals("tell me more")||s.equals("go on")) answer=contextualAdvice();
        else answer=conversationFallback(s, raw, intent);
        // If a user-recorded slot exists, show the exact phrase that will be played so text and audio match.
        if(speakEnabled&&preferRecordedVoice){String slot=voiceSlotForReply(raw,s,intent,answer);if(slot!=null&&findVoiceClip(slot)!=null)answer=voicePromptFor(slot);}
        rememberTurn(raw, answer);
        addMessage("Ammu",answer,true);
        if(speakEnabled) speakReply(raw, s, intent, answer);
        } catch(Exception ex) { String err="I hit a local error while handling that command. Your other saved data should remain intact."; addMessage("Ammu", err, true); if(speakEnabled && tts!=null) tts.speak(err,TextToSpeech.QUEUE_FLUSH,null,"ammu-error"); }
    }

    // ---------- V4 tools: speech input, safe command aliases, and portable data backup. ----------
    void showToolsDialog(){
        String[] items={"Dashboard & data summary", "Speak to Ammu", "Set voice input language", "Teach a command phrase", "Show custom commands", "Export complete backup", "Import backup", "Brain diagnostics", "Clear conversation history", "Brain Learning Lab", "Privacy & offline details"};
        new AlertDialog.Builder(this).setTitle("Ammu Tools").setItems(items,(d,which)->{
            switch(which){
                case 0: runCommand("dashboard"); break;
                case 1: startSpeechInput(); break;
                case 2: showSpeechLanguageDialog(); break;
                case 3: teachAliasDialog(); break;
                case 4: runCommand("show custom commands"); break;
                case 5: startBackupExport(); break;
                case 6: startBackupImport(); break;
                case 7: showBrainDiagnostics(); break;
                case 8: confirmClearConversation(); break;
                case 9: showBrainLearningLab(); break;
                case 10: showPrivacyAndOfflineInfo(); break;
            }
        }).setNegativeButton("Close",null).show();
    }

    void startSpeechInput(){
        try{
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String selectedLanguage=prefs.getString("speech_language","default");i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"default".equals(selectedLanguage)?Locale.getDefault().toLanguageTag():selectedLanguage);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to Ammu");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
            // A hint only: the installed Android recognizer may ignore this or still require network access.
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            startActivityForResult(i,REQ_SPEECH_INPUT);
        }catch(Exception e){
            new AlertDialog.Builder(this).setTitle("Voice input unavailable")
                .setMessage("No compatible Android speech-recognition activity was found. You can still type commands. For offline speech, install an engine with your language downloaded for offline use.")
                .setPositiveButton("OK",null).show();
        }
    }

    void showSpeechLanguageDialog(){
        String[] labels={"Use device language", "English (India)", "Hindi", "Kannada"};String[] tags={"default","en-IN","hi-IN","kn-IN"};
        String current=prefs.getString("speech_language","default");int checked=0;for(int i=0;i<tags.length;i++)if(tags[i].equals(current))checked=i;
        new AlertDialog.Builder(this).setTitle("Voice input language").setSingleChoiceItems(labels,checked,(dialog,which)->{prefs.edit().putString("speech_language",tags[which]).apply();dialog.dismiss();addMessage("Ammu","Voice input language set to "+labels[which]+". Recognition depends on installed language support.",false);}).setNegativeButton("Cancel",null).show();
    }

    void teachAliasDialog(){
        EditText entry=new EditText(this);entry.setSingleLine(true);entry.setTextColor(fg);entry.setHint("e.g. morning check => time");
        new AlertDialog.Builder(this).setTitle("Teach a command phrase")
            .setMessage("Teach Ammu a shortcut phrase for a supported command. English, Hindi, or Kannada phrases can be used. Format: phrase => target. Examples: morning check => time; open my camera => open app camera; show my plan => what next. Targets are allow-listed actions; Ammu cannot execute arbitrary shell commands.")
            .setView(entry).setPositiveButton("Save",(d,w)->{String out=handleCommandAliasCommand("teach phrase "+entry.getText().toString());addMessage("Ammu",out==null?"I couldn't save that shortcut.":out,true);if(speakEnabled&&tts!=null&&out!=null)tts.speak(out,TextToSpeech.QUEUE_FLUSH,null,"ammu-alias");})
            .setNegativeButton("Cancel",null).show();
    }

    // Ammu 1.0 local learning queue: user-approved labels are exported for offline retraining.
    // This does not silently modify the bundled neural-network weights.
    String handleBrainLearningCommand(String raw){
        String low=raw.toLowerCase(Locale.ROOT).trim();
        if(low.equals("show brain examples")||low.equals("list brain examples"))return listBrainExamples();
        if(low.equals("report last mistake")||low.equals("correct last command")||low.equals("report brain mistake")){openPhoneCorrectionDialog();return "Choose the correct intent in the correction dialog. The phrase and Ammu’s prediction will be saved locally only after you confirm.";}
        if(low.equals("brain benchmark")||low.equals("run brain benchmark")||low.equals("test brain"))return runLocalBrainBenchmark();
        if(low.equals("run development brain benchmark")||low.equals("development brain benchmark")||low.equals("run independent brain benchmark")||low.equals("independent brain benchmark"))return runDevelopmentBrainBenchmark();
        if(low.equals("run unseen brain benchmark")||low.equals("run second brain benchmark")||low.equals("run brain benchmark v2"))return runUnseenBrainBenchmark();
        if(low.equals("brain learning status")||low.equals("learning status"))return brainLearningStatus();
        if(low.equals("brain mistake report")||low.equals("phone mistake report")||low.equals("show brain mistakes"))return phoneMistakeReport();
        if(low.equals("export phone mistakes")||low.equals("export brain mistakes")){startPhoneMistakesExport();return "Opening the save picker for phone-reported mistakes only. The file is created locally; nothing is uploaded.";}
        if(low.equals("import brain examples")||low.equals("import learning examples")){startBrainExamplesImport();return "Opening the file picker for a local Ammu labeled-examples JSON file. Existing examples will be kept; invalid or duplicate rows will be skipped.";}
        if(low.equals("undo last brain example")){
            if(brainExamples.length()==0)return "There is no labeled example to undo.";
            brainExamples.remove(brainExamples.length()-1);save("brain_examples",brainExamples);
            return "Removed the most recent labeled example from the local queue. The bundled model is unchanged.";
        }
        if(low.startsWith("correct last intent to ")){
            String label=low.substring("correct last intent to ".length()).trim().replace(' ','_');
            return saveLastPhoneCorrection(label);
        }
        if(low.equals("export brain examples")||low.equals("export learning examples")){startBrainExamplesExport();return "Opening the save picker for your local labeled examples. Nothing is uploaded.";}
        if(low.startsWith("teach intent phrase ")){
            String rest=raw.substring("teach intent phrase ".length()).trim();int split=rest.lastIndexOf("=>");
            if(split<0)return "Use: teach intent phrase <example phrase> => <intent label>. Run 'show brain labels' to see labels.";
            String phrase=rest.substring(0,split).trim();String label=rest.substring(split+2).trim().toLowerCase(Locale.ROOT).replace(' ','_');
            if(phrase.length()<2||phrase.length()>180)return "The example phrase must be 2–180 characters.";
            if(label.equals("unknown")||!Arrays.asList(brainLabels).contains(label))return "That label is not a trainable intent. Use 'show brain labels' to see available labels; don't label uncertain examples as unknown.";
            String key=normalizeAlias(phrase);for(int i=0;i<brainExamples.length();i++){JSONObject old=brainExamples.optJSONObject(i);if(old!=null&&normalizeAlias(old.optString("text")).equals(key)&&old.optString("label").equals(label))return "That labeled example is already saved.";}
            if(brainExamples.length()>=500)return "The local learning queue is full (500 examples). Export it, then clear the queue before adding more.";
            try{brainExamples.put(new JSONObject().put("text",phrase).put("label",label).put("date",now()));save("brain_examples",brainExamples);return "Saved a labeled training example locally: '"+phrase+"' → "+label+". The live brain weights have not changed. Export examples and retrain offline to evaluate a candidate model.";}catch(Exception e){return "Couldn't save that example. Please try again.";}
        }
        if(low.equals("show brain labels")){StringBuilder b=new StringBuilder("Trainable intent labels:\n");for(String label:brainLabels)if(!"unknown".equals(label))b.append("• ").append(label).append("\n");return b.toString().trim();}
        if(low.equals("clear brain examples")){new AlertDialog.Builder(this).setTitle("Clear labeled examples?").setMessage("This removes only the local learning queue. It does not change the bundled model or your other data.").setNegativeButton("Cancel",null).setPositiveButton("Clear queue",(d,w)->{brainExamples=new JSONArray();save("brain_examples",brainExamples);addMessage("Ammu","Local labeled examples cleared.",false);}).show();return "Please confirm before clearing the learning queue.";}
        return null;
    }

    String saveLastPhoneCorrection(String label){
        String phrase=prefs.getString("last_learning_phrase","").trim();
        String predicted=prefs.getString("last_learning_predicted","unknown").trim();
        if(phrase.isEmpty())return "I don't have a previous command to correct. Run a normal command first.";
        if(label.equals("unknown")||!Arrays.asList(brainLabels).contains(label))return "That isn't a trainable label. Run 'show brain labels' and choose one of those labels.";
        if(phrase.length()<2||phrase.length()>180)return "The previous phrase is too short or too long to label.";
        if(brainExamples.length()>=500)return "The local learning queue is full. Export it, then clear the queue before adding more.";
        String key=normalizeAlias(phrase);for(int i=0;i<brainExamples.length();i++){JSONObject old=brainExamples.optJSONObject(i);if(old!=null&&normalizeAlias(old.optString("text")).equals(key)&&old.optString("label").equals(label))return "That correction is already in the local learning queue.";}
        try{brainExamples.put(new JSONObject().put("text",phrase).put("label",label).put("date",now()).put("source","phone_mistake").put("predicted_label",predicted));save("brain_examples",brainExamples);return "Saved phone correction locally: '"+phrase+"' — Ammu predicted '"+predicted+"', you corrected it to '"+label+"'. Export, retrain, and evaluate before considering a model update.";}catch(Exception e){return "Couldn't save that correction. Please try again.";}
    }

    void openPhoneCorrectionDialog(){
        String phrase=prefs.getString("last_learning_phrase","").trim();
        String predicted=prefs.getString("last_learning_predicted","unknown").trim();
        if(phrase.isEmpty()){new AlertDialog.Builder(this).setTitle("Correct last command").setMessage("Type a normal command first, then open Brain Learning Lab and choose Report last mistake.").setPositiveButton("OK",null).show();return;}
        ArrayList<String> labels=new ArrayList<>();for(String label:brainLabels)if(!"unknown".equals(label))labels.add(label);
        if(labels.isEmpty()){addMessage("Ammu","No trainable intent labels are available.",false);return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        TextView details=txt("Command: "+phrase+"\nAmmu predicted: "+predicted+"\n\nChoose the correct intent. This saves a local training example; it does not change model weights.",14,fg,false);box.addView(details);
        Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);spinner.setAdapter(adapter);box.addView(spinner);
        new AlertDialog.Builder(this).setTitle("Report a brain mistake").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save correction",(dialog,which)->{String label=String.valueOf(spinner.getSelectedItem());String result=saveLastPhoneCorrection(label);addMessage("Ammu",result,false);}).show();
    }

    String brainLearningStatus(){
        HashMap<String,Integer> counts=new HashMap<>();int phoneMistakes=0;
        for(int i=0;i<brainExamples.length();i++){JSONObject o=brainExamples.optJSONObject(i);if(o!=null){String label=o.optString("label","?");counts.put(label,counts.getOrDefault(label,0)+1);if("phone_mistake".equals(o.optString("source")))phoneMistakes++;}}
        StringBuilder b=new StringBuilder("Local brain learning status\n\nLabeled examples: ").append(brainExamples.length()).append(" / 500\nPhone-reported mistakes: ").append(phoneMistakes).append("\nDistinct labels represented: ").append(counts.size()).append("\n\nPer-label counts:");
        if(counts.isEmpty())b.append("\nNo examples collected yet.");else{ArrayList<String> keys=new ArrayList<>(counts.keySet());Collections.sort(keys);for(String k:keys)b.append("\n• ").append(k).append(": ").append(counts.get(k));}
        b.append("\n\nUse 'brain mistake report' to see prediction-to-correction pairs, or 'export phone mistakes' to export only real phone corrections.");
        b.append("\n\nExamples do not change the live model. Export, retrain, evaluate on held-out phrases, and only then consider promoting a candidate.");
        return b.toString();
    }

    String phoneMistakeReport(){
        int total=0;HashMap<String,Integer> pairs=new HashMap<>();HashMap<String,Integer> correctedLabels=new HashMap<>();
        for(int i=0;i<brainExamples.length();i++){JSONObject o=brainExamples.optJSONObject(i);if(o==null||!"phone_mistake".equals(o.optString("source")))continue;String predicted=o.optString("predicted_label","unknown"),correct=o.optString("label","unknown");if(predicted.isEmpty())predicted="unknown";total++;String pair=predicted+" → "+correct;pairs.put(pair,pairs.getOrDefault(pair,0)+1);correctedLabels.put(correct,correctedLabels.getOrDefault(correct,0)+1);}
        StringBuilder b=new StringBuilder("Phone mistake analysis\n\nGenuine corrections collected: ").append(total).append("\n");
        if(total==0){b.append("No phone-reported mistakes yet. Use Ammu normally, then type 'report last mistake' when it misunderstands. This report will analyze real corrections, not invented examples.");return b.toString();}
        ArrayList<Map.Entry<String,Integer>> sorted=new ArrayList<>(pairs.entrySet());sorted.sort((a,c)->Integer.compare(c.getValue(),a.getValue()));b.append("\nMost common prediction → correction pairs:");for(int i=0;i<Math.min(10,sorted.size());i++)b.append("\n• ").append(sorted.get(i).getKey()).append(": ").append(sorted.get(i).getValue());
        ArrayList<Map.Entry<String,Integer>> labels=new ArrayList<>(correctedLabels.entrySet());labels.sort((a,c)->Integer.compare(c.getValue(),a.getValue()));b.append("\n\nCorrect labels represented:");for(Map.Entry<String,Integer> e:labels)b.append("\n• ").append(e.getKey()).append(": ").append(e.getValue());
        b.append("\n\nThese counts describe only mistakes you explicitly corrected on this phone; they do not automatically retrain the brain.");return b.toString();
    }
    // A fixed, non-mutating on-device smoke benchmark. It measures this installed model, not general intelligence.
    String runLocalBrainBenchmark(){
        if(!brainReady)return "Brain benchmark unavailable: the bundled model did not load successfully. Open TOOLS → Brain diagnostics.";
        String[][] cases={
            {"hello","greeting"},{"hey ammu","greeting"},{"help me","help"},{"what time is it","time"},
            {"current time","time"},{"what is today's date","date"},{"check battery","battery"},
            {"show device info","device_info"},{"calculate 5 plus 6","calculate"},{"what is 12 times 4","calculate"},
            {"goodbye","goodbye"},{"never mind","cancel"},{"show my notes","note_list"},
            {"search my notes","note_search"},{"open settings","open_settings"},{"show my tasks","task_list"},
            {"add a task","task_add"},{"remember that my favorite color is blue","memory_save"},
            {"what do you remember about me","memory_recall"},{"write in my journal","journal_add"},
            {"show my journal","journal_list"},{"backup my data","backup"},{"search my tasks","task_search"},
            {"xyzzy blorb","unknown"}
        };
        int correct=0;StringBuilder misses=new StringBuilder();
        for(String[] item:cases){String got=predict(item[0]);if(item[1].equals(got))correct++;else if(misses.length()<900)misses.append("\n• ").append(item[0]).append(" → expected ").append(item[1]).append(", got ").append(got);}
        double pct=100.0*correct/cases.length;
        StringBuilder out=new StringBuilder("Local brain benchmark\n\nCorrect: ").append(correct).append(" / ").append(cases.length).append(" (").append(String.format(Locale.US,"%.1f",pct)).append("%)\n\n");
        if(misses.length()==0)out.append("No misses in this small smoke set.");else out.append("Phrases to review:").append(misses);
        out.append("\n\nThis is a fixed 24-phrase smoke test, not a held-out scientific evaluation. It does not change the model or your saved data.");
        return out.toString();
    }
    // Fixed independent set is bundled separately from training examples. It is not used to update weights.
    String runDevelopmentBrainBenchmark(){
        if(!brainReady)return "Independent benchmark unavailable: the bundled model did not load successfully.";
        try(InputStream in=getAssets().open("brain-benchmark-v1.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>0){out.write(buffer,0,n);if(out.size()>512*1024)throw new IOException("Benchmark asset is too large");}
            JSONObject root=new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
            if(!"ammu-development-eval-v1".equals(root.optString("format")))return "Development benchmark asset format is invalid.";
            JSONArray rows=root.optJSONArray("examples");if(rows==null||rows.length()<100||rows.length()>300)return "Independent benchmark row count is invalid.";
            HashMap<String,int[]> counts=new HashMap<>();int correct=0;StringBuilder misses=new StringBuilder();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;String phrase=row.optString("text","").trim(),expected=row.optString("label","");
                if(phrase.length()<2||phrase.length()>180||!Arrays.asList(brainLabels).contains(expected))return "Independent benchmark contains an invalid row.";
                int[] c=counts.get(expected);if(c==null){c=new int[2];counts.put(expected,c);}c[1]++;
                String got=predict(phrase);if(expected.equals(got)){correct++;c[0]++;}else if(misses.length()<1400)misses.append("\n• ").append(phrase).append(" → expected ").append(expected).append(", got ").append(got);
            }
            double pct=100.0*correct/rows.length();StringBuilder report=new StringBuilder("Independent brain benchmark\n\nCorrect: ").append(correct).append(" / ").append(rows.length()).append(" (").append(String.format(Locale.US,"%.1f",pct)).append("%)\n\nPer-intent recall:");
            TreeMap<String,int[]> ordered=new TreeMap<>(counts);for(Map.Entry<String,int[]> entry:ordered.entrySet()){int[] c=entry.getValue();report.append("\n• ").append(entry.getKey()).append(": ").append(c[0]).append("/").append(c[1]);}
            if(misses.length()>0)report.append("\n\nMissed phrases (up to 1400 characters):").append(misses);
            report.append("\n\nThis fixed 130-phrase development set is excluded from training. It has guided development and is not an untouched blind test. It tests this installed classifier, not general intelligence. Running it does not change saved data or model weights.");return report.toString();
        }catch(Exception e){return "Couldn't run the independent benchmark: "+e.getMessage();}
    }

    // Secondary exact-phrase-disjoint benchmark introduced in Testing Build 5. It is read-only and never trains the model.
    String runUnseenBrainBenchmark(){
        if(!brainReady)return "Secondary benchmark unavailable: the bundled model did not load successfully.";
        try(InputStream in=getAssets().open("brain-benchmark-v2.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>0){out.write(buffer,0,n);if(out.size()>512*1024)throw new IOException("Benchmark asset is too large");}
            JSONObject root=new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
            if(!"ammu-development-eval-v2".equals(root.optString("format")))return "Secondary benchmark format is invalid.";
            JSONArray rows=root.optJSONArray("examples");if(rows==null||rows.length()!=104)return "Secondary benchmark row count is invalid.";
            HashMap<String,int[]> counts=new HashMap<>();HashSet<String> seen=new HashSet<>();int correct=0;StringBuilder misses=new StringBuilder();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)return "Secondary benchmark has an invalid row.";String phrase=row.optString("text","").trim(),expected=row.optString("label","");String key=normalizeAlias(phrase);if(phrase.length()<2||phrase.length()>180||!Arrays.asList(brainLabels).contains(expected)||!seen.add(key))return "Secondary benchmark contains an invalid or duplicate row.";int[] c=counts.get(expected);if(c==null){c=new int[2];counts.put(expected,c);}c[1]++;String got=predict(phrase);if(expected.equals(got)){correct++;c[0]++;}else if(misses.length()<1400)misses.append("\n• ").append(phrase).append(" → expected ").append(expected).append(", got ").append(got);}
            if(counts.size()!=brainLabels.length)return "Secondary benchmark does not cover every intent class.";for(Map.Entry<String,int[]> entry:counts.entrySet())if(entry.getValue()[1]!=4)return "Secondary benchmark must contain exactly four phrases per intent.";
            double pct=100.0*correct/rows.length();StringBuilder report=new StringBuilder("Secondary benchmark v2 — Ammu 1.0\n\nCorrect: ").append(correct).append(" / ").append(rows.length()).append(" (").append(String.format(Locale.US,"%.1f",pct)).append("%)\n\nPer-intent recall:");
            TreeMap<String,int[]> ordered=new TreeMap<>(counts);for(Map.Entry<String,int[]> entry:ordered.entrySet()){int[] c=entry.getValue();report.append("\n• ").append(entry.getKey()).append(": ").append(c[0]).append("/").append(c[1]);}
            if(misses.length()>0)report.append("\n\nMissed phrases (up to 1400 characters):").append(misses);report.append("\n\nThis separately authored 104-phrase development set is excluded from training. Results measure intent classification only, not general intelligence. Running this test does not change saved data or model weights.");return report.toString();
        }catch(Exception e){return "Couldn't run secondary benchmark: "+e.getMessage();}
    }

    String listBrainExamples(){if(brainExamples.length()==0)return "No labeled examples yet. Try: teach intent phrase what time is it => time. Use 'show brain labels' for valid labels.";StringBuilder b=new StringBuilder("Recent local labeled examples (newest last):\n");for(int i=Math.max(0,brainExamples.length()-15);i<brainExamples.length();i++){JSONObject o=brainExamples.optJSONObject(i);if(o!=null)b.append(i+1).append(". [").append(o.optString("label")).append("] ").append(o.optString("text")).append("\n");}return b.toString().trim();}
    void showBrainLearningLab(){String message="This lab collects examples you explicitly label. You can export/import reviewed examples locally. It does not train the model on the phone or silently change its weights. Export the examples, merge them into the training set, then evaluate a candidate before replacing the bundled brain.\n\nSaved examples: "+brainExamples.length()+" / 500\n\nCommands:\n• teach intent phrase what time is it => time\n• after a command: report last mistake (opens intent picker)\n• or type: correct last intent to time\n• brain learning status\n• brain mistake report (prediction → correction counts)\n• export phone mistakes (real corrections only)\n• run brain benchmark\n• run development brain benchmark (130 phrases)\n• run unseen brain benchmark (104 additional phrases)\n• undo last brain example\n• show brain labels\n• show brain examples\n• export brain examples\n• import brain examples\n• clear brain examples";new AlertDialog.Builder(this).setTitle("Brain Learning Lab").setMessage(message).setPositiveButton("Export examples",(d,w)->startBrainExamplesExport()).setNeutralButton("Import examples",(d,w)->startBrainExamplesImport()).setNegativeButton("Show labels",(d,w)->addMessage("Ammu",handleBrainLearningCommand("show brain labels"),false)).show();}
    void startBrainExamplesExport(){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"ammu-brain-examples-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".json");startActivityForResult(i,REQ_EXPORT_BRAIN_EXAMPLES);}catch(Exception e){addMessage("Ammu","The save picker isn't available on this device.",false);}}
    void exportBrainExamples(Uri uri){try{JSONObject root=new JSONObject();root.put("format","ammu-brain-examples-v1");root.put("created_at",now());JSONArray rows=new JSONArray();for(int i=0;i<brainExamples.length();i++){JSONObject item=brainExamples.optJSONObject(i);if(item!=null){JSONObject row=new JSONObject().put("text",item.optString("text")).put("label",item.optString("label"));if(item.has("source"))row.put("source",item.optString("source"));if(item.has("predicted_label"))row.put("predicted_label",item.optString("predicted_label"));if(item.has("date"))row.put("date",item.optString("date"));rows.put(row);}}root.put("examples",rows);writeLearningJson(uri,root,"Exported "+rows.length()+" labeled examples, preserving correction metadata.");}catch(Exception e){addMessage("Ammu","Couldn't export examples. Choose another writable location and retry.",false);}}
    void startPhoneMistakesExport(){try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"ammu-phone-mistakes-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".json");startActivityForResult(i,REQ_EXPORT_PHONE_MISTAKES);}catch(Exception e){addMessage("Ammu","The save picker isn't available on this device.",false);}}
    void exportPhoneMistakes(Uri uri){try{JSONObject root=new JSONObject();root.put("format","ammu-brain-examples-v1");root.put("created_at",now());JSONArray rows=new JSONArray();for(int i=0;i<brainExamples.length();i++){JSONObject item=brainExamples.optJSONObject(i);if(item!=null&&"phone_mistake".equals(item.optString("source"))){rows.put(new JSONObject().put("text",item.optString("text")).put("label",item.optString("label")).put("predicted_label",item.optString("predicted_label","unknown")).put("date",item.optString("date")));}}root.put("examples",rows);writeLearningJson(uri,root,"Exported "+rows.length()+" phone-reported mistakes only. Review for private text before sharing.");}catch(Exception e){addMessage("Ammu","Couldn't export phone mistakes. Choose another writable location and retry.",false);}}
    void writeLearningJson(Uri uri,JSONObject root,String success)throws IOException,JSONException{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("No output stream");out.write((root.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}addMessage("Ammu",success+" The file stays where you chose to save it; nothing was uploaded.",false);}
    void startBrainExamplesImport(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,REQ_IMPORT_BRAIN_EXAMPLES);}catch(Exception e){addMessage("Ammu","The file picker isn't available on this device.",false);}}
    void importBrainExamples(Uri uri){
        final int maxBytes=1024*1024;
        try{
            byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("No input stream");byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))>0){total+=n;if(total>maxBytes)throw new IOException("File too large");out.write(buf,0,n);}bytes=out.toByteArray();}
            JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if(!"ammu-brain-examples-v1".equals(root.optString("format")))throw new IOException("Unsupported format");
            JSONArray rows=root.optJSONArray("examples");if(rows==null||rows.length()>500)throw new IOException("Invalid example list");
            JSONArray merged=new JSONArray();HashSet<String> seen=new HashSet<>();
            for(int i=0;i<brainExamples.length();i++){JSONObject old=brainExamples.optJSONObject(i);if(old!=null){String text=old.optString("text","").trim(),label=old.optString("label","");String key=normalizeAlias(text)+"::"+label;if(!text.isEmpty()&&Arrays.asList(brainLabels).contains(label)&&seen.add(key))merged.put(new JSONObject(old.toString()));}}
            int added=0,duplicates=0,invalid=0,full=0;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null){invalid++;continue;}String text=row.optString("text","").trim(),label=row.optString("label","").trim();if(text.length()<2||text.length()>180||label.equals("unknown")||!Arrays.asList(brainLabels).contains(label)){invalid++;continue;}String key=normalizeAlias(text)+"::"+label;if(!seen.add(key)){duplicates++;continue;}if(merged.length()>=500){full++;continue;}JSONObject imported=new JSONObject().put("text",text).put("label",label).put("date",row.optString("date",now())).put("source",row.optString("source","imported_reviewed"));if(row.has("predicted_label"))imported.put("predicted_label",row.optString("predicted_label"));merged.put(imported);added++;}
            brainExamples=merged;save("brain_examples",brainExamples);
            addMessage("Ammu","Import complete. Added: "+added+"; duplicates skipped: "+duplicates+"; invalid rows skipped: "+invalid+"; skipped because queue reached 500: "+full+". Existing valid examples were preserved. No examples were uploaded and the live model weights did not change.",false);
        }catch(Exception e){addMessage("Ammu","Couldn't import these examples. The file must be a valid Ammu JSON export under 1 MB. Your existing learning queue was not changed.",false);}
    }

    String normalizeAlias(String value){return value.toLowerCase(Locale.ROOT).trim().replaceAll("[^\\p{L}\\p{M}\\p{N} ]"," ").replaceAll("\\s+"," ").trim();}
    String handleCommandAliasCommand(String raw){
        String low=raw.toLowerCase(Locale.ROOT).trim();
        if(low.equals("show custom commands")||low.equals("list custom commands")){
            if(customAliases.length()==0)return "You haven't taught me any command shortcuts yet. Open TOOLS → Teach a command phrase.";
            StringBuilder b=new StringBuilder("Your custom command shortcuts:\n");
            java.util.Iterator<String> it=customAliases.keys();while(it.hasNext()){String k=it.next();b.append("• ").append(k).append(" → ").append(customAliases.optString(k)).append("\n");}
            return b.toString().trim();
        }
        if(low.startsWith("forget command ")||low.startsWith("delete command phrase ")){
            String phrase=low.startsWith("forget command ")?raw.substring("forget command ".length()):raw.substring("delete command phrase ".length());
            String key=normalizeAlias(phrase);if(key.isEmpty())return "Tell me which phrase to forget, for example: forget command morning check.";
            if(customAliases.has(key)){customAliases.remove(key);prefs.edit().putString("command_aliases",customAliases.toString()).apply();return "Forgot the custom command phrase: "+key+".";}
            return "I couldn't find a custom command named '"+key+"'.";
        }
        if(low.startsWith("teach phrase ")||low.startsWith("learn command phrase ")){
            String prefix=low.startsWith("teach phrase ")?"teach phrase ":"learn command phrase ";
            String rest=raw.substring(prefix.length()).trim();int split=rest.indexOf("=>");
            if(split<0)return "Use this format: teach phrase morning check => time.";
            String phrase=normalizeAlias(rest.substring(0,split));String target=normalizeAlias(rest.substring(split+2));
            Set<String> allowed=aliasTargets();
            if(phrase.length()<2||phrase.length()>60)return "The shortcut phrase must be between 2 and 60 characters.";
            if(allowed.contains(phrase))return "That phrase is a built-in command. Choose a different shortcut name.";
            if(!allowed.contains(target))return "That target isn't allowed. Choose one of the built-in targets listed in TOOLS → Teach a command phrase.";
            try{if(customAliases.length()>=100&&!customAliases.has(phrase))return "You have reached the 100-shortcut limit. Forget an old shortcut first.";customAliases.put(phrase,target);prefs.edit().putString("command_aliases",customAliases.toString()).apply();return "Learned shortcut '"+phrase+"' → '"+target+"'. This is a local command example, not a change to neural-network weights. Typing a close one-character typo may also work.";}catch(Exception e){return "Couldn't save that shortcut. Please try again.";}
        }
        return null;
    }

    boolean isAllowedAliasTarget(String target){return aliasTargets().contains(target);}
    Set<String> aliasTargets(){return new HashSet<>(Arrays.asList(
        "time","date","battery","device info","show my notes","show my tasks","show my journal","show mood log","what do you remember","help","open settings","goodbye","dashboard","check brain",
        "open app camera","open app calculator","open app clock","open app calendar","open app contacts","open app files","open app photos","open app maps","open app music","open app youtube","what next"));}
    String resolveCommandAlias(String normalized){
        String key=normalizeAlias(normalized);String target=customAliases.optString(key,"");if(isAllowedAliasTarget(target))return target;
        // Conservative typo tolerance for taught phrases: require long phrases and a clear best match.
        if(key.length()<6||customAliases.length()==0)return normalized;
        String bestTarget="";int bestDistance=Integer.MAX_VALUE,secondDistance=Integer.MAX_VALUE;String bestKey="";
        java.util.Iterator<String> it=customAliases.keys();while(it.hasNext()){String candidate=it.next();if(candidate.length()<6)continue;int d=editDistanceAtMost(candidate,key,3);if(d<bestDistance){secondDistance=bestDistance;bestDistance=d;bestKey=candidate;bestTarget=customAliases.optString(candidate,"");}else if(d<secondDistance)secondDistance=d;}
        int maxLen=Math.max(key.length(),bestKey.length());double similarity=maxLen==0?0.0:1.0-(double)bestDistance/maxLen;
        if(bestDistance<=2&&similarity>=0.88&&secondDistance-bestDistance>=1&&isAllowedAliasTarget(bestTarget))return bestTarget;
        return normalized;
    }
    int editDistanceAtMost(String a,String b,int limit){
        if(Math.abs(a.length()-b.length())>limit)return limit+1;
        int[] prev=new int[b.length()+1],cur=new int[b.length()+1];for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){cur[0]=i;int min=cur[0];for(int j=1;j<=b.length();j++){cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));if(cur[j]<min)min=cur[j];}if(min>limit)return limit+1;int[] t=prev;prev=cur;cur=t;}return prev[b.length()];
    }

    boolean isCalculationRequest(String s,String intent){
        String q=s.toLowerCase(Locale.ROOT);
        boolean explicit=q.matches(".*\\b(calculate|compute|solve|evaluate|work out)\\b.*");
        boolean operator=q.matches(".*[0-9].*")&&q.matches(".*(\\+|\\-|\\*|/|%|\\^| plus | minus | times | multiplied by | divided by |power of|raised to| modulo ).*");
        return explicit||operator||(intent.equals("calculate")&&q.matches(".*\\b(plus|minus|times|divided|multiplied|power|modulo)\\b.*"));
    }
    boolean hasNegationBeforeAction(String q,String actions){
        return q.matches(".*\\b(don't|dont|do not|never|not|didn't|did not|cannot|can't|cant|shouldn't|should not|no)\\b.*\\b("+actions+")\\b.*");
    }
    boolean isExplicitNoteAddCommand(String s){
        String q=s.toLowerCase(Locale.ROOT).trim();
        if(hasNegationBeforeAction(q,"save|add|write|jot|keep|take"))return false;
        return q.startsWith("save a note")||q.startsWith("add a note")||q.startsWith("remember this note")||q.startsWith("note:")||q.startsWith("jot down")||q.startsWith("write down")||q.startsWith("keep this note")||q.startsWith("take a note")||q.startsWith("note this")||q.startsWith("write a quick note")||q.startsWith("add this line to my notes")||q.matches(".*\\b(save|add|write|jot|keep)\\b.*\\b(note|notes)\\b.*");
    }
    boolean isExplicitSettingsOpenCommand(String s){String q=s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ");return q.matches("^(please )?(open|go to|navigate to|launch|show|take me to) (android |phone |system )?settings$")||q.equals("settings");}
    boolean isExplicitSingleNoteDelete(String s){String q=s.toLowerCase(Locale.ROOT);return !hasNegationBeforeAction(q,"delete|remove|erase")&&q.matches(".*\\b(delete|remove|erase)\\b.*\\b(note)\\b.*\\d+.*");}
    String noteBodyFromCommand(String raw){
        String body=stripPrefix(raw,"save a note","add a note","note:","remember this note","jot down","write down","keep this note","take a note","note this down","note this","write a quick note about","add this line to my notes","ಟಿಪ್ಪಣಿ ಉಳಿಸು","ನೋಟ್ ಉಳಿಸು","ಇದನ್ನು ಟಿಪ್ಪಣಿಯಾಗಿ ಉಳಿಸು","ಟಿಪ್ಪಣಿ ಬರೆಯು","नोट सेव करो","टिप्पणी सेव करो","नोट लिखो","इसे नोट में सेव करो","एक नोट बनाओ");
        if(body.equals(raw.trim())){Matcher m=Pattern.compile("(?i)\\b(save|add|write|jot|keep|take)\\s+(.+)$").matcher(raw);if(m.find())body=m.group(2);}
        body=body.replaceFirst("(?i)^(that|this)\\s+","").replaceFirst("(?i)\\s+in my notes$","").trim();return body;
    }
    boolean isExplicitTaskAddCommand(String s){
        String q=s.toLowerCase(Locale.ROOT).trim();
        if(hasNegationBeforeAction(q,"add|create|make|put|remind|save"))return false;
        return q.startsWith("add a task")||q.startsWith("add task")||q.startsWith("new task")||q.startsWith("create task")||q.startsWith("create a task")||q.startsWith("remind me to")||q.matches(".*\\b(add|create|make|put|remind|save)\\b.*\\b(task|tasks|todo|to do|reminder|reminders|chores)\\b.*");
    }
    String taskBodyFromCommand(String raw){
        String body=stripPrefix(raw,"add a task","add task","new task","create a task","create task","remind me to","put","make a task to","add a to do","add to my to do list","add to my task list","add","ನನ್ನ ಕೆಲಸಗಳಿಗೆ ಸೇರಿಸು","ಕೆಲಸದ ಪಟ್ಟಿಗೆ ಸೇರಿಸು","ಹೊಸ ಕೆಲಸ ಸೇರಿಸು","ಕೆಲಸ ಸೇರಿಸು","मेरे काम में जोड़ो","काम जोड़ो","कार्य जोड़ें","एक काम बनाओ");
        if(body.equals(raw.trim())){Matcher m=Pattern.compile("(?i)\\b(add|create|make|put)\\s+(.+)$").matcher(raw);if(m.find())body=m.group(2);}
        body=body.replaceFirst("(?i)^(a task to|a task for|task to|a new task to)\\s+","");
        body=body.replaceFirst("(?i)\\s+(to my (to-do|todo|to do|task) list|on my (to-do|todo|to do|task) list|to my tasks|to my chores)$","").trim();
        return body;
    }
    boolean isExplicitTaskCompletion(String s){
        String q=s.toLowerCase(Locale.ROOT).trim();
        if(hasNegationBeforeAction(q,"mark|complete|completed|finish|finished|check off"))return false;
        return q.matches(".*\\b(mark|complete|completed|finish|finished|check off)\\b.*\\b(task|item)\\b.*\\d+.*")||q.matches(".*\\b(mark|complete|finish|check off)\\b\\s+(task|item)\\s+\\d+.*");
    }
    boolean isExplicitMemorySaveCommand(String s){String q=s.toLowerCase(Locale.ROOT).trim();if(hasNegationBeforeAction(q,"remember|save|store|keep"))return false;return q.startsWith("remember")||q.startsWith("save to memory")||q.startsWith("store fact that")||q.startsWith("keep in mind that");}

    int nextTaskId(){int max=0;for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t!=null)max=Math.max(max,t.optInt("id",0));}return max+1;}
    int findTaskIndexById(int id){for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t!=null&&t.optInt("id")==id)return i;}return -1;}
    void confirmDeleteNoteAtIndex(int index){
        if(index<0||index>=notes.length()){addMessage("Ammu","That note no longer exists.",false);return;}
        JSONObject item=notes.optJSONObject(index);String preview=item==null?"(empty note)":item.optString("text","(empty note)");if(preview.length()>240)preview=preview.substring(0,237)+"…";
        new AlertDialog.Builder(this).setTitle("Delete saved note "+(index+1)+"?").setMessage("Review this note before deleting it:\n\n“"+preview+"”\n\nThis cannot be undone unless you restore a backup.").setNegativeButton("Keep note",(d,w)->addMessage("Ammu","Canceled. The note is still saved.",false)).setPositiveButton("Delete note",(d,w)->{if(index>=0&&index<notes.length()){notes.remove(index);save("notes",notes);addMessage("Ammu","Deleted note "+(index+1)+" after your confirmation.",false);}else addMessage("Ammu","The notes changed before confirmation, so I did not delete anything.",false);}).show();
    }
    void confirmForgetMemoryAtIndex(int index){
        if(index<0||index>=memory.length()){addMessage("Ammu","That memory item no longer exists.",false);return;}
        JSONObject item=memory.optJSONObject(index);String preview=item==null?"(empty fact)":item.optString("fact","(empty fact)");if(preview.length()>240)preview=preview.substring(0,237)+"…";
        new AlertDialog.Builder(this).setTitle("Forget this saved fact?").setMessage("Memory item "+(index+1)+":\n\n“"+preview+"”\n\nRemove this fact from Ammu's local memory?").setNegativeButton("Keep fact",(d,w)->addMessage("Ammu","Canceled. The fact remains saved.",false)).setPositiveButton("Forget fact",(d,w)->{if(index>=0&&index<memory.length()){memory.remove(index);save("memory",memory);addMessage("Ammu","Removed memory item "+(index+1)+" after your confirmation.",false);}else addMessage("Ammu","Memory changed before confirmation, so I did not remove anything.",false);}).show();
    }
    void confirmCompleteTask(int id){
        int index=findTaskIndexById(id);if(index<0){addMessage("Ammu","Task not found.",false);return;}JSONObject task=tasks.optJSONObject(index);String label=task==null?"(unnamed task)":task.optString("text","(unnamed task)");
        new AlertDialog.Builder(this).setTitle("Mark task complete?").setMessage("Task #"+id+":\n\n“"+label+"”\n\nConfirm that this task is finished.").setNegativeButton("Not yet",(d,w)->addMessage("Ammu","Canceled. The task remains pending.",false)).setPositiveButton("Mark complete",(d,w)->{int at=findTaskIndexById(id);if(at>=0){JSONObject current=tasks.optJSONObject(at);if(current!=null){try{current.put("done",true);save("tasks",tasks);addMessage("Ammu","Marked task "+id+" complete after your confirmation.",false);}catch(JSONException e){addMessage("Ammu","Task data changed; nothing was updated.",false);}}else addMessage("Ammu","Task data changed; nothing was updated.",false);}else addMessage("Ammu","Task no longer exists; nothing was updated.",false);}).show();
    }


    String dashboardSummary(){
        int pending=0,done=0,high=0;
        for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t!=null){if(t.optBoolean("done"))done++;else{pending++;if("high".equalsIgnoreCase(t.optString("priority")))high++;}}}
        return "Your local dashboard\n\nNotes: "+notes.length()+"\nSaved facts: "+memory.length()+"\nJournal entries: "+journal.length()+"\nMood check-ins: "+moodLog.length()+"\nTasks pending: "+pending+"\nTasks completed: "+done+"\nHigh-priority pending tasks: "+high+"\nCustom shortcuts: "+customAliases.length()+"\n\nAll counts are calculated from data stored on this phone.";
    }

    void confirmClearConversation(){
        new AlertDialog.Builder(this).setTitle("Clear conversation history?")
            .setMessage("This removes saved conversation context from this phone. Notes, tasks, journal, and memory facts will remain.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear history",(d,w)->{
                conversationHistory=new JSONArray();save("conversation",conversationHistory);lastEmotion="";lastTopic="";lastAssistantQuestion="";
                prefs.edit().remove("last_emotion").remove("last_topic").apply();messages.removeAllViews();
                addMessage("Ammu","Conversation history cleared. Your notes, tasks, journal, and saved facts are unchanged.",false);
            }).show();
    }

    void confirmClearMoodHistory(){
        new AlertDialog.Builder(this).setTitle("Clear mood history?")
            .setMessage("This permanently removes saved mood check-ins from this phone. Other data will remain.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear mood history",(d,w)->{
                moodLog=new JSONArray();save("mood",moodLog);addMessage("Ammu","Mood history cleared.",false);
            }).show();
    }

    void showPrivacyAndOfflineInfo(){
        String msg = "WHAT STAYS ON THIS PHONE\n"+
            "Ammu stores notes, tasks, saved facts, journal entries, mood history, conversation history, command shortcuts, training examples, settings, and recorded voice clips in app-private storage on this device. Ammu does not automatically upload these records.\n\n"+
            "OFFLINE OPERATION\n"+
            "Ammu's command logic and custom neural intent model run locally. The app manifest has no INTERNET permission and Ammu does not call a cloud AI endpoint.\n\n"+
            "VOICE INPUT LIMITATION\n"+
            "Voice input opens the Android recognizer selected on your device. The offline preference is only a request; some recognizers may process audio online. For a guaranteed local interaction, type your message or use a recognizer with the needed language installed for offline use. Speech output also depends on your installed Android text-to-speech engine and voice.\n\n"+
            "BACKUPS AND VOICE CLIPS\n"+
            "Exported backup files may contain private personal information and voice recordings. Save them in a location you trust, do not share them publicly, and keep a separate copy of your release signing key. Uninstalling Ammu removes app-private data, so export a backup before switching signing keys or uninstalling.";
        new AlertDialog.Builder(this).setTitle("Privacy & offline operation").setMessage(msg)
            .setPositiveButton("OK",null)
            .setNeutralButton("Export backup",(d,w)->startBackupExport())
            .show();
    }

    void showBrainDiagnostics(){
        int hidden=bias1==null?0:bias1.length;String model=brainReady?"Loaded and validated":"Unavailable";
        String msg="Ammu 1.0 local diagnostics\nModel: "+model+(brainReady?"":"\nModel issue: "+brainLoadIssue)+"\nIntent categories: "+brainLabels.length+"\nText features: "+vocabIndex.size()+"\nHidden units: "+hidden+"\nConfidence threshold: "+(brain==null?"n/a":brain.optDouble("threshold",0.48))+"\nCustom shortcuts: "+customAliases.length()+"\nLabeled learning examples: "+(brainExamples==null?0:brainExamples.length())+" / 500\nData read warnings: "+(dataLoadWarnings.isEmpty()?"none":dataLoadWarnings)+"\n\nThis is a small custom intent classifier, not a general-purpose language model. Corrections are reviewed and stored locally; they do not alter live weights. Voice input uses Android's selected recognition service; the offline preference is not a guarantee.";
        new AlertDialog.Builder(this).setTitle("Brain diagnostics").setMessage(msg).setPositiveButton("OK",null).show();
    }

    void startBackupExport(){
        try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"ammu-backup-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".zip");startActivityForResult(i,REQ_EXPORT_BACKUP);}
        catch(Exception e){addMessage("Ammu","The file-save picker isn't available on this device.",false);}
    }
    void startBackupImport(){
        try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");startActivityForResult(i,REQ_IMPORT_BACKUP);}
        catch(Exception e){addMessage("Ammu","The file picker isn't available on this device.",false);}
    }
    void writeZipEntry(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    JSONObject buildBackupJson()throws JSONException{
        JSONObject o=new JSONObject();o.put("format","ammu-backup");o.put("version",5);o.put("created_at",now());
        o.put("notes",new JSONArray(prefs.getString("notes","[]")));o.put("tasks",new JSONArray(prefs.getString("tasks","[]")));
        o.put("memory",new JSONArray(prefs.getString("memory","[]")));o.put("journal",new JSONArray(prefs.getString("journal","[]")));
        o.put("mood",new JSONArray(prefs.getString("mood","[]")));o.put("conversation",new JSONArray(prefs.getString("conversation","[]")));
        o.put("brain_examples",new JSONArray(prefs.getString("brain_examples","[]")));
        o.put("command_aliases",new JSONObject(prefs.getString("command_aliases","{}")));
        o.put("reply_style",prefs.getString("reply_style","balanced"));o.put("last_emotion",prefs.getString("last_emotion",""));o.put("last_topic",prefs.getString("last_topic",""));
        o.put("speak_enabled",prefs.getBoolean("speak_enabled",true));o.put("prefer_recorded_voice",prefs.getBoolean("prefer_recorded_voice",true));o.put("speech_language",prefs.getString("speech_language","default"));return o;
    }
    void exportBackup(Uri uri){
        try(ZipOutputStream zip=new ZipOutputStream(getContentResolver().openOutputStream(uri))){
            if(zip==null)throw new IOException("Could not open destination");
            writeZipEntry(zip,"backup.json",buildBackupJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File[] clips=voiceDirectory().listFiles();int count=0;
            if(clips!=null)for(File f:clips){if(f.isFile()&&f.length()>0&&f.length()<8*1024*1024&&f.getName().matches("[a-z_]+\\.(m4a|wav|mp3|ogg|aac|3gp)")){byte[] bytes=readFileBounded(f,8*1024*1024);writeZipEntry(zip,"voicepack/"+f.getName(),bytes);count++;}}
            addMessage("Ammu","Backup exported successfully, including local app data, labeled brain examples, and "+count+" voice clip(s). Keep this ZIP somewhere safe; it may contain private journal entries and voice recordings.",false);
        }catch(Exception e){addMessage("Ammu","Backup export failed. Check that you selected a writable destination and try again.",false);}
    }
    byte[] readFileBounded(File f,int max)throws IOException{if(f.length()>max)throw new IOException("File too large");try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0){if(out.size()+n>max)throw new IOException("File too large");out.write(b,0,n);}return out.toByteArray();}}
    void confirmBackupImport(Uri uri){new AlertDialog.Builder(this).setTitle("Replace local Ammu data?").setMessage("Importing a backup replaces the current notes, tasks, memory, journal, mood history, conversation history, settings, aliases, labeled brain examples, and voice clips. Export a backup first if you need to keep current data.").setPositiveButton("Import and replace",(d,w)->importBackup(uri)).setNegativeButton("Cancel",null).show();}
    void importBackup(Uri uri){
        JSONObject backup=null;HashMap<String,byte[]> clipBytes=new HashMap<>();int total=0;
        try(ZipInputStream zip=new ZipInputStream(getContentResolver().openInputStream(uri))){
            if(zip==null)throw new IOException("No input stream");ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){String name=entry.getName();
                boolean keep=!entry.isDirectory()&&(name.equals("backup.json")||name.matches("voicepack/[a-z_]+\\.(m4a|wav|mp3|ogg|aac|3gp)"));
                ByteArrayOutputStream out=keep?new ByteArrayOutputStream():null;byte[] b=new byte[8192];int n;while((n=zip.read(b))>0){total+=n;if(total>MAX_BACKUP_BYTES)throw new IOException("Backup too large");if(out!=null)out.write(b,0,n);}
                if(keep&&name.equals("backup.json")){if(out.size()>4*1024*1024)throw new IOException("Backup metadata too large");backup=new JSONObject(out.toString("UTF-8"));}
                else if(keep)clipBytes.put(name.substring("voicepack/".length()),out.toByteArray());zip.closeEntry();
            }
            if(backup==null||!"ammu-backup".equals(backup.optString("format"))||backup.optInt("version",0)<1||backup.optInt("version",0)>5)throw new IOException("Not a supported Ammu backup");
            String[] arrayKeys={"notes","tasks","memory","journal","mood","conversation"};
            int[] arrayLimits={1000,500,1000,500,200,12};
            SharedPreferences.Editor editor=prefs.edit();
            for(int i=0;i<arrayKeys.length;i++){
                String key=arrayKeys[i];JSONArray a=backup.optJSONArray(key);
                if(a==null)throw new IOException("Missing data section: "+key);
                if(a.length()>arrayLimits[i])throw new IOException("Data section exceeds the safe limit: "+key);
                editor.putString(key,a.toString());
            }
            JSONArray examples=backup.optJSONArray("brain_examples");
            if(examples!=null){
                if(examples.length()>500)throw new IOException("Too many labeled brain examples");
                for(int i=0;i<examples.length();i++){
                    JSONObject item=examples.optJSONObject(i);if(item==null)throw new IOException("Invalid brain example");
                    String phrase=item.optString("text","").trim(),label=item.optString("label","");
                    if(phrase.length()<2||phrase.length()>180||"unknown".equals(label)||!Arrays.asList(brainLabels).contains(label))throw new IOException("Invalid or unsupported brain example label");
                }
            }
            editor.putString("brain_examples",examples==null?"[]":examples.toString());
            JSONObject aliasesRaw=backup.optJSONObject("command_aliases");JSONObject aliasesSafe=new JSONObject();
            if(aliasesRaw!=null){java.util.Iterator<String> aliasKeys=aliasesRaw.keys();while(aliasKeys.hasNext()&&aliasesSafe.length()<100){String originalKey=aliasKeys.next();String key=normalizeAlias(originalKey);String target=normalizeAlias(aliasesRaw.optString(originalKey,""));if(key.length()>=2&&key.length()<=60&&!isAllowedAliasTarget(key)&&isAllowedAliasTarget(target))aliasesSafe.put(key,target);}}
            editor.putString("command_aliases",aliasesSafe.toString());
            String style=backup.optString("reply_style","balanced");if(!Arrays.asList("balanced","short","detailed","gentle","direct").contains(style))style="balanced";
            editor.putString("reply_style",style).putString("last_emotion",backup.optString("last_emotion","")).putString("last_topic",backup.optString("last_topic",""));
            editor.putBoolean("speak_enabled",backup.optBoolean("speak_enabled",true)).putBoolean("prefer_recorded_voice",backup.optBoolean("prefer_recorded_voice",true));
            String lang=backup.optString("speech_language","default");if(!Arrays.asList("default","en-IN","hi-IN","kn-IN").contains(lang))lang="default";editor.putString("speech_language",lang);
            if(!editor.commit())throw new IOException("Could not write app data");
            // Only write known voice-slot names/extensions; archive paths never become filesystem paths.
            for(String name:voiceSlotKeys)deleteVoiceClip(name);
            for(Map.Entry<String,byte[]> e:clipBytes.entrySet()){
                String filename=e.getKey();boolean allowed=false;for(String key:voiceSlotKeys)if(filename.matches(Pattern.quote(key)+"\\.(m4a|wav|mp3|ogg|aac|3gp)")){allowed=true;break;}
                if(!allowed||e.getValue().length==0)continue;File dest=new File(voiceDirectory(),filename);try(OutputStream out=new FileOutputStream(dest)){out.write(e.getValue());}
            }
            loadData();replyStyle=prefs.getString("reply_style","balanced");lastEmotion=prefs.getString("last_emotion","");lastTopic=prefs.getString("last_topic","");speakEnabled=prefs.getBoolean("speak_enabled",true);preferRecordedVoice=prefs.getBoolean("prefer_recorded_voice",true);
            addMessage("Ammu","Backup restored. Your local data and voice clips have been reloaded.",false);
        }catch(Exception e){addMessage("Ammu","Couldn't restore that backup. Use a ZIP exported by Ammu V4, and keep a separate copy of your current data before importing.",false);}
    }

    String handlePreferenceOrCorrection(String s, String raw){
        if(s.matches(".*\\b(i prefer short replies|keep it short|be concise|short answers)\\b.*")){replyStyle="short";prefs.edit().putString("reply_style",replyStyle).apply();return "Got it. I'll aim for shorter replies. You can say 'use detailed replies' to change this.";}
        if(s.matches(".*\\b(use detailed replies|be more detailed|explain in detail)\\b.*")){replyStyle="detailed";prefs.edit().putString("reply_style",replyStyle).apply();return "I'll aim to give more detailed explanations.";}
        if(s.matches(".*\\b(be more gentle|be gentle|softer tone)\\b.*")){replyStyle="gentle";prefs.edit().putString("reply_style",replyStyle).apply();return "I'll use a gentler tone where it fits.";}
        if(s.matches(".*\\b(be direct|be more direct|no sugarcoating)\\b.*")){replyStyle="direct";prefs.edit().putString("reply_style",replyStyle).apply();return "I'll be more direct while staying respectful.";}
        if(s.startsWith("you misunderstood me")||s.startsWith("that's not what i meant")||s.startsWith("that is not what i meant")||s.startsWith("correct that")){
            String correction=stripPrefix(raw,"you misunderstood me","that's not what i meant","that is not what i meant","correct that");
            if(correction.startsWith("because ")) correction=correction.substring(8).trim();
            String emotionText=correction.toLowerCase(Locale.ROOT).replaceAll("\\b(not|never)\\s+(happy|great|excited|sad|upset|angry|anxious|lonely|confused|tired)\\b", "");
            String correctedEmotion=detectEmotion(emotionText);
            if(correctedEmotion!=null){lastEmotion=correctedEmotion;lastTopic=correction;prefs.edit().putString("last_emotion",lastEmotion).putString("last_topic",lastTopic).apply();try{moodLog.put(new JSONObject().put("emotion",correctedEmotion).put("text",correction).put("date",now()).put("corrected",true));trimOldest(moodLog,200);save("mood",moodLog);}catch(JSONException e){android.util.Log.e("Ammu","Could not save corrected mood history.",e);}return "Thanks for correcting me. I'll treat what you said as '"+correctedEmotion+"' rather than assuming I got it right the first time. What would help most?";}
            if(!correction.isEmpty()){lastTopic=correction;prefs.edit().putString("last_topic",lastTopic).apply();return "Thanks for correcting me. I'll use that context in this conversation. What did I miss?";}
            return "Thanks for telling me. What did I misunderstand? You can explain it in your own words.";
        }
        if(s.contains("forget my mood history")){moodLog=new JSONArray();save("mood",moodLog);return "Your local mood history has been cleared.";}
        if(s.contains("show my reply style"))return "Your reply style is set to "+replyStyle+".";
        return null;
    }
    boolean isFollowUp(String s){return s.matches(".*\\b(what should i do|what now|and then|tell me more|go on|why do i feel this way|can you help me)\\b.*") && !lastEmotion.isEmpty();}
    String followUpReply(String s){
        if(lastEmotion.equals("anxious"))return "Let's make this manageable. From what you shared earlier ("+lastTopic+"), what is the specific thing you can influence in the next ten minutes? We can choose one small step together.";
        if(lastEmotion.equals("sad")||lastEmotion.equals("lonely"))return "We don't have to fix everything immediately. About what you shared earlier ("+lastTopic+"), would you prefer to talk through what happened, think of one person you trust to contact, or take a gentle distraction break?";
        if(lastEmotion.equals("angry"))return "Before acting, it may help to pause and separate what happened from what you want to happen next. About this: "+lastTopic+" — do you want to vent first or plan what to say?";
        if(lastEmotion.equals("overwhelmed"))return "Let's reduce the load. Based on what you told me ("+lastTopic+"), name one urgent thing and one thing that can wait. I'll help sort them.";
        return "I remember you mentioned: "+lastTopic+". I don't want to guess what you need. Would listening, practical suggestions, or a distraction help most?";
    }
    boolean isKnownAppRequest(String s){
        return s.matches(".*\\b(camera|settings|clock|calculator|calendar|contacts|phone|dialer|messages|messaging|files|photos|gallery|maps|music|youtube)\\b.*");
    }
    String openKnownApp(String command){
        String q=command.toLowerCase(Locale.ROOT).replaceFirst("^(open app|launch app|open)\\s*","").trim();
        if(q.contains("settings")){try{startActivity(new Intent(Settings.ACTION_SETTINGS));return "Opened Android Settings.";}catch(Exception e){return "Android Settings could not be opened.";}}
        if(q.contains("camera")){try{startActivity(new Intent("android.media.action.IMAGE_CAPTURE"));return "Opened the camera app if one is available.";}catch(Exception e){return "No camera app could be opened.";}}
        String[] labels={"clock","calculator","calendar","contacts","phone","dialer","messages","messaging","files","photos","gallery","music","maps","youtube"};
        String[] packages={"com.google.android.deskclock","com.google.android.calculator","com.google.android.calendar","com.google.android.contacts","com.google.android.dialer","com.google.android.dialer","com.google.android.apps.messaging","com.google.android.apps.messaging","com.google.android.documentsui","com.google.android.apps.photos","com.google.android.apps.photos","com.google.android.apps.youtube.music","com.google.android.apps.maps","com.google.android.youtube"};
        for(int i=0;i<labels.length;i++)if(q.contains(labels[i])){
            try{Intent launch=getPackageManager().getLaunchIntentForPackage(packages[i]);if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);return "Opened "+labels[i]+".";}}
            catch(Exception ignored){}
            return "I couldn't find a launchable "+labels[i]+" app. Package names vary by phone; open it manually or teach a supported shortcut.";
        }
        return "For safety, I can only launch a small allowlist of apps. Try: open app camera, open app calculator, open app clock, or open settings.";
    }
    String makeLocalPlan(String goal){
        if(goal==null||goal.trim().isEmpty())return "Tell me the goal, for example: break down: prepare for DBMS exam.";
        String g=goal.trim().replaceAll("[\\r\\n]+"," ");
        String lower=g.toLowerCase(Locale.ROOT);
        ArrayList<String> steps=new ArrayList<>();
        if(lower.matches(".*\\b(exam|study|revise|revision|syllabus|dbms|math|mathematics|assignment|learn|course|test)\\b.*")){
            steps.add("Write the exam/deadline, syllabus, and marks or learning goal in one place.");
            steps.add("Split the syllabus into small topics; mark each as easy, medium, or difficult.");
            steps.add("Start with one high-value difficult topic and study it for 25 minutes without distractions.");
            steps.add("Close your notes and solve 3–5 questions from memory; check mistakes immediately.");
            steps.add("Take a 5-minute break, then repeat with the next topic.");
            steps.add("Finish with a short recall sheet and a timed practice set; revise only the mistakes you find.");
        } else if(lower.matches(".*\\b(app|android|website|web site|code|coding|program|software|bug|feature|project|github|deploy|build)\\b.*")){
            steps.add("Define the exact user-visible result and list what must work on the first release.");
            steps.add("Reproduce the current behavior and record the failing case before changing code.");
            steps.add("Implement one small end-to-end feature; avoid changing unrelated parts at the same time.");
            steps.add("Test success, invalid input, permission denied, offline mode, and app restart behavior.");
            steps.add("Review logs and edge cases, then make a separate backup or Git commit.");
            steps.add("Test on the target phone and keep a known-good build before starting the next feature.");
        } else if(lower.matches(".*\\b(draw|design|art|illustrat|animate|animation|video|reel|photo|edit|content|storyboard)\\b.*")){
            steps.add("Define the audience, mood, format, dimensions, and final deliverable.");
            steps.add("Collect only the references and assets needed; make a simple storyboard or composition sketch.");
            steps.add("Create a rough first version quickly, without polishing details yet.");
            steps.add("Check composition, readability, pacing, contrast, and consistency at phone size.");
            steps.add("Polish the most visible problems first, then export a draft and inspect it end to end.");
            steps.add("Save the editable project and a separate final export with clear filenames.");
        } else if(lower.matches(".*\\b(job|apply|application|resume|interview|career|recruit)\\b.*")){
            steps.add("Confirm eligibility, location, deadline, pay range, and whether the employer is genuine.");
            steps.add("Prepare a tailored resume and collect only the documents explicitly required.");
            steps.add("Complete the application using the employer's official channel; never pay a recruitment fee without verification.");
            steps.add("Save the submission receipt and note the date, role, and follow-up date.");
            steps.add("Prepare examples that demonstrate your skills and practice likely interview questions.");
        } else {
            steps.add("Define what a successful outcome looks like and when you need it.");
            steps.add("Write down what you already know, what is missing, and any real constraints.");
            steps.add("Choose the smallest useful next action that can be finished in 10–25 minutes.");
            steps.add("Complete that action and check the result against your goal.");
            steps.add("Use what you learned to choose the next action; change the plan if the evidence changes.");
        }
        StringBuilder b=new StringBuilder("Offline plan for: ").append(g).append("\n\n");
        for(int i=0;i<steps.size();i++)b.append(i+1).append(". ").append(steps.get(i)).append("\n");
        b.append("\nThese are suggested steps, not completed actions. Add them to your task list with 'add plan to tasks'. Tell me your deadline or available time to tailor the sequence.");
        lastPlanGoal=g;lastPlanText=b.toString();prefs.edit().putString("last_plan_goal",lastPlanGoal).putString("last_plan_text",lastPlanText).apply();
        return lastPlanText;
    }

    String nextPlanStep(){
        if(lastPlanText.trim().isEmpty())return "I don't have a plan to continue yet. Try: break down: prepare for DBMS exam.";
        ArrayList<String> steps=new ArrayList<>();Matcher matcher=Pattern.compile("(?m)^\\d+\\. (.+)$").matcher(lastPlanText);while(matcher.find())steps.add(matcher.group(1).trim());
        if(steps.isEmpty())return "I couldn't recover the steps from the saved plan. Create the plan again.";
        boolean tracked=false;
        for(String step:steps){JSONObject matched=null;for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task!=null&&step.equals(task.optString("text"))){matched=task;break;}}
            if(matched==null)return "Next step for "+lastPlanGoal+": "+step+"\n\nSay 'add plan to tasks' to track the complete plan.";
            tracked=true;if(!matched.optBoolean("done"))return "Your next step for "+lastPlanGoal+" is:\n\n"+step+"\n\nWhen finished, say 'mark task "+matched.optInt("id")+" done'.";
        }
        return tracked?"All tracked steps for '"+lastPlanGoal+"' are marked complete. Review the result, then create a new plan for the next goal.":"I couldn't find tracked steps. Say 'add plan to tasks' to save the plan.";
    }

    String saveMemoryFact(String fact){
        String clean=fact==null?"":fact.trim().replaceAll("\\s+"," ");
        if(clean.isEmpty())return "Tell me the fact after 'remember that'.";
        for(int i=0;i<memory.length();i++){JSONObject o=memory.optJSONObject(i);if(o!=null&&o.optString("fact").trim().equalsIgnoreCase(clean))return "That fact is already saved in Ammu's local memory.";}
        if(memory.length()>=1000)return "Your local memory limit (1,000 facts) is reached. Export a backup, then delete facts you no longer need.";try{memory.put(new JSONObject().put("fact",clean).put("date",now()));save("memory",memory);return "Saved that fact in Ammu's local memory on this phone.";}catch(Exception e){return "I couldn't save that fact. Check the app's available storage and try again.";}
    }

    String searchRelevantMemory(String query){
        String q=query==null?"":query.toLowerCase(Locale.ROOT).trim();
        if(q.startsWith("for "))q=q.substring(4).trim();
        if(q.isEmpty())return list(memory,"fact","No saved facts yet.","I remember:");
        HashSet<String> stop=new HashSet<>(Arrays.asList("the","and","for","that","this","what","about","with","from","have","has","you","your","my","are","was","were","is","do","did","me","to","of","in","on","a","an","i","it","be","at","as"));
        HashSet<String> terms=new HashSet<>();Matcher m=Pattern.compile("[\\p{L}\\p{M}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS).matcher(q);while(m.find()){String term=m.group();if(term.length()>1&&!stop.contains(term))terms.add(term);}
        if(terms.isEmpty())return "Please include a specific topic, for example: search memory for DBMS exam.";
        ArrayList<JSONObject> hits=new ArrayList<>();ArrayList<Integer> scores=new ArrayList<>();
        for(int i=0;i<memory.length();i++){JSONObject item=memory.optJSONObject(i);if(item==null)continue;String fact=item.optString("fact","").toLowerCase(Locale.ROOT);HashSet<String> factTerms=new HashSet<>();Matcher fm=Pattern.compile("[\\p{L}\\p{M}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS).matcher(fact);while(fm.find())factTerms.add(fm.group());int score=0;if(fact.equals(q))score+=20;else if(fact.contains(q))score+=8;for(String term:terms){if(factTerms.contains(term))score+=4;else if(term.length()>=5&&fact.contains(term))score+=1;}if(score>0){hits.add(item);scores.add(score);}}
        if(hits.isEmpty())return "I couldn't find a saved fact closely related to '"+query+"'. Try 'what do you remember' to review all saved facts.";
        ArrayList<Integer> order=new ArrayList<>();for(int i=0;i<hits.size();i++)order.add(i);order.sort((a,b)->{int c=Integer.compare(scores.get(b),scores.get(a));if(c!=0)return c;return hits.get(b).optString("date","").compareTo(hits.get(a).optString("date",""));});
        StringBuilder out=new StringBuilder("Related local memories (best matches first):\n");int shown=0;for(int ix:order){JSONObject item=hits.get(ix);out.append("• ").append(item.optString("fact")).append(" (saved ").append(item.optString("date","date unknown")).append(")\n");if(++shown>=8)break;}return out.toString().trim();
    }

    String summarizeRecentConversation(){
        if(conversationHistory==null||conversationHistory.length()==0)return "There isn't any saved conversation history yet.";
        StringBuilder out=new StringBuilder("Recent conversation recap (stored locally):\n");int start=Math.max(0,conversationHistory.length()-6);int count=0;
        for(int i=start;i<conversationHistory.length();i++){JSONObject turn=conversationHistory.optJSONObject(i);if(turn==null)continue;String user=turn.optString("user","").trim();if(user.isEmpty())continue;out.append("• You: ").append(user).append("\n");count++;}
        if(count==0)return "There isn't any usable saved conversation history yet.";
        out.append("\nThis is an extractive recap of your recent messages, not an AI-generated interpretation.");return out.toString();
    }

    boolean isExplicitDeleteAllNotes(String s){String q=s.toLowerCase(Locale.ROOT).trim();return !hasNegationBeforeAction(q,"delete|remove|erase|clear")&&q.matches("^(delete|remove|erase|clear) (all notes|my notes|all my notes|the notes|all saved notes)$");}
    boolean isExplicitJournalAddCommand(String s){String q=s.toLowerCase(Locale.ROOT).trim();if(hasNegationBeforeAction(q,"write|add|save"))return false;return q.startsWith("write in my journal")||q.startsWith("journal:")||q.startsWith("write diary")||q.startsWith("journal entry:")||q.startsWith("add an entry to my diary")||q.startsWith("save my thoughts in the journal")||q.startsWith("write in my diary")||q.startsWith("diary:");}
    void confirmClearNotes(){new AlertDialog.Builder(this).setTitle("Delete all saved notes?").setMessage("This permanently removes all notes stored by Ammu on this phone. Tasks, facts, journal entries, and chat history stay intact.").setNegativeButton("Cancel",null).setPositiveButton("Delete notes",(d,w)->{notes=new JSONArray();save("notes",notes);addMessage("Ammu","All saved notes were deleted from this phone.",false);}).show();}

    void confirmClearMemory(){
        new AlertDialog.Builder(this).setTitle("Forget all saved facts?")
            .setMessage("This permanently removes every saved memory fact from this phone. Notes, tasks, journal, and conversation history will remain.")
            .setNegativeButton("Cancel",null).setPositiveButton("Forget all facts",(d,w)->{memory=new JSONArray();save("memory",memory);addMessage("Ammu","All saved facts were removed from local memory.",false);}).show();
    }

    void showAddPlanToTasksDialog(){
        ArrayList<String> steps=new ArrayList<>();Matcher matcher=Pattern.compile("(?m)^\\d+\\. (.+)$").matcher(lastPlanText);while(matcher.find())steps.add(matcher.group(1).trim());
        if(steps.isEmpty()){addMessage("Ammu","I couldn't find steps in the last plan. Create a new plan and try again.",false);return;}
        if(tasks.length()+steps.size()>500){addMessage("Ammu","This plan would exceed the 500-task local limit. Finish or remove existing tasks first; no tasks were added.",false);return;}
        StringBuilder preview=new StringBuilder("This will add ").append(steps.size()).append(" new tasks. Existing tasks will not be changed.\n\n").append(lastPlanGoal).append("\n\n");
        for(int i=0;i<steps.size();i++)preview.append(i+1).append(". ").append(steps.get(i)).append("\n");
        new AlertDialog.Builder(this).setTitle("Add plan steps as tasks?")
            .setMessage(preview.toString())
            .setPositiveButton("Add tasks",(d,w)->{for(String step:steps){try{tasks.put(new JSONObject().put("id",nextTaskId()).put("text",step).put("priority","normal").put("done",false).put("date",now()));}catch(Exception ignored){}}save("tasks",tasks);addMessage("Ammu","Added "+steps.size()+" plan steps to your local task list.",true);})
            .setNegativeButton("Cancel",null).show();
    }

    String contextualAdvice(){if(lastEmotion.isEmpty())return "Tell me a little about what's happening and what outcome you want. I'll help you think through options without assuming I know the whole situation.";return followUpReply("what should i do");}
    void trimOldest(JSONArray array,int max){while(array!=null&&array.length()>max)array.remove(0);}
    void rememberTurn(String user, String assistant){try{if(conversationHistory==null)conversationHistory=new JSONArray();conversationHistory.put(new JSONObject().put("user",user).put("assistant",assistant).put("date",now()));while(conversationHistory.length()>12)conversationHistory.remove(0);save("conversation",conversationHistory);}catch(Exception ignored){}}

    // Lightweight, rule-based emotional cue detection. This is not a diagnosis or a claim to read minds.
    boolean isFeelingStatement(String s){
        return s.matches(".*\\b(i feel|i'm feeling|i am feeling|i\\'m|i am|feeling|i\\'ve been feeling|i have been feeling)\\b.*")
            || s.matches(".*\\b(i hate my life|nobody cares about me|i feel alone|i feel lonely)\\b.*")
            || containsAny(s,"मैं खुश हूँ","मैं खुश हूं","मुझे खुशी है","मैं दुखी हूँ","मैं दुखी हूं","मुझे दुख हो रहा है","मुझे चिंता हो रही है","मैं चिंतित हूँ","मुझे गुस्सा आ रहा है","मैं अकेला महसूस कर रहा हूँ","मैं अकेली महसूस कर रही हूँ","मैं थक गया हूँ","मैं थक गई हूँ","मुझे समझ नहीं आ रहा",
                "ನಾನು ಸಂತೋಷವಾಗಿದ್ದೇನೆ","ನನಗೆ ಸಂತೋಷವಾಗಿದೆ","ನನಗೆ ದುಃಖವಾಗಿದೆ","ನನಗೆ ಚಿಂತೆ ಆಗುತ್ತಿದೆ","ನನಗೆ ಕೋಪ ಬಂದಿದೆ","ನನಗೆ ಒಂಟಿತನವಾಗಿದೆ","ನಾನು ದಣಿದಿದ್ದೇನೆ","ನನಗೆ ಗೊಂದಲವಾಗಿದೆ");
    }
    boolean containsAny(String text,String... phrases){for(String phrase:phrases)if(text.contains(phrase))return true;return false;}
    String detectEmotion(String s){
        if(s.matches(".*\\b(suicid|kill myself|end my life|hurt myself|self harm|want to die|better off dead|can't go on|cannot go on|don't want to live|do not want to live)\\b.*")
            || containsAny(s,"मैं मरना चाहता हूँ","मैं मरना चाहती हूँ","मुझे जीना नहीं है","मैं खुद को नुकसान पहुँचाना चाहता हूँ","मैं खुद को नुकसान पहुँचाना चाहती हूँ","ನಾನು ಸಾಯಬೇಕು ಎಂದು ಅನಿಸುತ್ತಿದೆ","ನನಗೆ ಬದುಕಲು ಇಷ್ಟವಿಲ್ಲ","ನಾನು ನನ್ನನ್ನು ಹಾನಿ ಮಾಡಿಕೊಳ್ಳಬೇಕು")) return "crisis";
        if(containsAny(s,"मैं खुश हूँ","मैं खुश हूं","मुझे खुशी है")) return "happy";
        if(containsAny(s,"मैं दुखी हूँ","मैं दुखी हूं","मुझे दुख हो रहा है")) return "sad";
        if(containsAny(s,"मुझे चिंता हो रही है","मैं चिंतित हूँ","मुझे डर लग रहा है")) return "anxious";
        if(containsAny(s,"मुझे गुस्सा आ रहा है","मैं नाराज़ हूँ")) return "angry";
        if(containsAny(s,"मैं अकेला महसूस कर रहा हूँ","मैं अकेली महसूस कर रही हूँ")) return "lonely";
        if(containsAny(s,"मैं बहुत थक गया हूँ","मैं थक गया हूँ","मैं थक गई हूँ")) return "tired";
        if(containsAny(s,"मुझे समझ नहीं आ रहा","मैं उलझन में हूँ")) return "confused";
        if(containsAny(s,"ನಾನು ಸಂತೋಷವಾಗಿದ್ದೇನೆ","ನನಗೆ ಸಂತೋಷವಾಗಿದೆ")) return "happy";
        if(containsAny(s,"ನನಗೆ ದುಃಖವಾಗಿದೆ","ನನಗೆ ಬೇಸರವಾಗಿದೆ")) return "sad";
        if(containsAny(s,"ನನಗೆ ಚಿಂತೆ ಆಗುತ್ತಿದೆ","ನನಗೆ ಭಯವಾಗುತ್ತಿದೆ")) return "anxious";
        if(containsAny(s,"ನನಗೆ ಕೋಪ ಬಂದಿದೆ","ನನಗೆ ಕಿರಿಕಿರಿಯಾಗುತ್ತಿದೆ")) return "angry";
        if(containsAny(s,"ನನಗೆ ಒಂಟಿತನವಾಗಿದೆ","ನಾನು ಒಂಟಿಯಾಗಿದ್ದೇನೆ")) return "lonely";
        if(containsAny(s,"ನಾನು ದಣಿದಿದ್ದೇನೆ","ನನಗೆ ಆಯಾಸವಾಗಿದೆ")) return "tired";
        if(containsAny(s,"ನನಗೆ ಗೊಂದಲವಾಗಿದೆ","ಏನು ಮಾಡಬೇಕು ತಿಳಿಯುತ್ತಿಲ್ಲ")) return "confused";
        // Remove clearly negated cues before checking for a positive cue elsewhere in the sentence.
        String t=s.replaceAll("\\b(?:not|never|isn't|is not|wasn't|was not|don't feel|do not feel|not feeling)\\s+(?:very\\s+)?(?:happy|great|excited|sad|upset|angry|anxious|lonely|confused|tired|worried|calm)\\b", " ");
        if(t.matches(".*\\b(happy|great|good mood|excited|proud|joyful|glad|grateful|awesome)\\b.*")) return "happy";
        if(t.matches(".*\\b(sad|upset|heartbroken|depressed|down|crying|miserable)\\b.*")) return "sad";
        if(t.matches(".*\\b(anxious|anxiety|nervous|worried|panic|scared|afraid|overthinking)\\b.*")) return "anxious";
        if(t.matches(".*\\b(angry|mad|furious|irritated|frustrated)\\b.*")) return "angry";
        if(t.matches(".*\\b(lonely|alone|isolated|nobody cares|no one cares)\\b.*")) return "lonely";
        if(t.matches(".*\\b(overwhelmed|too much|burnt out|burned out|exhausted|stressed|pressure)\\b.*")) return "overwhelmed";
        if(t.matches(".*\\b(tired|sleepy|drained|no energy)\\b.*")) return "tired";
        if(t.matches(".*\\b(confused|lost|don't know what to do|do not know what to do)\\b.*")) return "confused";
        return null;
    }

    boolean isNegatedFeeling(String s){
        if(containsAny(s,"मैं खुश नहीं हूँ","मैं खुश नहीं हूं","मैं दुखी नहीं हूँ","मैं दुखी नहीं हूं","मुझे चिंता नहीं है","मुझे गुस्सा नहीं है","ನಾನು ಸಂತೋಷವಾಗಿಲ್ಲ","ನನಗೆ ದುಃಖವಾಗಿಲ್ಲ","ನನಗೆ ಚಿಂತೆ ಇಲ್ಲ","ನನಗೆ ಕೋಪ ಇಲ್ಲ"))return true;
        return s.matches(".*\\b(?:not|never|isn't|is not|wasn't|was not|don't feel|do not feel|not feeling)\\s+(?:very\\s+)?(?:happy|great|excited|sad|upset|angry|anxious|lonely|confused|tired|worried|calm)\\b.*")
            && detectEmotion(s)==null;
    }
    String negatedFeelingReply(String s){
        if(s.matches(".*\\b(not|never)\\s+(?:very\\s+)?(?:sad|angry|anxious|lonely|tired|worried)\\b.*"))
            return "Thanks for clarifying what you aren't feeling. I don't want to infer the opposite automatically. What word best describes how you feel right now, if any?";
        return "I hear that 'not happy' doesn't necessarily mean you're sad. Would you describe the feeling as neutral, mixed, frustrated, or something else?";
    }
    String mixedEmotionReply(String s){
        boolean positive=s.matches(".*\\b(happy|excited|glad|grateful|hopeful|proud)\\b.*");
        boolean difficult=s.matches(".*\\b(sad|anxious|worried|angry|frustrated|lonely|overwhelmed|scared)\\b.*");
        if(positive && difficult && isFeelingStatement(s)) return "It sounds like there may be more than one feeling here. Mixed feelings can happen; I won't force them into one label. Which part feels strongest right now, or would you rather explain it in your own words?";
        return null;
    }
    String conversationFallback(String s, String raw, String intent){
        if(s.matches(".*\\b(why|how come)\\b.*") && !lastEmotion.isEmpty())
            return "I can't know the exact reason from one message. You mentioned: "+lastTopic+". We can explore possible causes without treating a guess as fact. What happened just before you started feeling this way?";
        if(s.matches(".*\\b(help me decide|should i|which one|what do you think)\\b.*"))
            return "I can help compare options. Tell me the choices, what's most important to you, and any limits you have; I'll lay out pros and cons rather than pretend to know your priorities.";
        if(s.matches(".*\\b(i don't know|i do not know|i'm not sure|im not sure)\\b.*"))
            return "That's okay. We can start small. Are you unsure what you feel, what happened, or what to do next?";
        if(!lastEmotion.isEmpty() && s.matches(".*\\b(continue|about that|same thing|it still hurts|still feel that way)\\b.*"))
            return "I'm connecting this with what you shared earlier: "+lastTopic+". Is that still the main issue, or has something changed?";
        return "I don't have enough information to answer that reliably yet, and I don't want to make something up. Could you give me a little more context or tell me what outcome you want? You can also type 'help' for the features I can run offline.";
    }
    String emotionReply(String emotion,String original){
        if(replyStyle.equals("short")){
            switch(emotion){case "happy": return "I'm glad to hear that. What made today good?";case "sad": return "I'm sorry it's hard right now. Want to tell me what happened?";case "anxious": return "Let's take one step at a time. What's worrying you most?";case "angry": return "That sounds frustrating. Want to vent or plan a next step?";case "lonely": return "That can hurt. Want to tell me what's been happening?";case "overwhelmed": return "Let's break it down. What's the first urgent thing?";case "tired": return "You sound drained. Can you take a short rest?";default:return "I'm listening. What would help most right now?";}
        }
        switch(emotion){
            case "happy": return "That sounds like a good moment. I'm glad you shared it with me. What made you feel this way?";
            case "sad": return "I'm sorry things feel heavy right now. I can't know exactly what this is like for you, but I can listen without judging. Would you like to tell me what happened, or would a small distraction help?";
            case "anxious": return "That sounds stressful. Let's take it one step at a time. If you want, try a gentle breath in and a slow breath out, then tell me what's worrying you most.";
            case "angry": return "It sounds like you're really frustrated. You don't have to solve it this second. Would you like to vent, or should we work out a calm next step together?";
            case "lonely": return "Feeling alone can hurt. I'm here to talk with you right now, though I can't replace support from people in your life. Is there someone safe you could message too? What has been making you feel isolated?";
            case "overwhelmed": return "That sounds like a lot to carry at once. We can break it into smaller pieces. What is the one thing that needs your attention first?";
            case "tired": return "You sound worn out. If you can, consider a short pause, some water, or rest. Is this more physical tiredness, or are you emotionally drained too?";
            case "confused": return "It's okay not to have the answer yet. Tell me the situation and we can separate what you know, what you don't know, and one possible next step.";
            default: return "Thanks for telling me. I can listen and try to help you think it through. What would feel most useful right now?";
        }
    }
    String safetyResponse(String s){
        if(s.matches(".*\\b(suicid|kill myself|end my life|hurt myself|self harm|want to die|better off dead|can't go on|cannot go on|don't want to live|do not want to live)\\b.*")
            || containsAny(s,"मैं मरना चाहता हूँ","मैं मरना चाहती हूँ","मुझे जीना नहीं है","मैं खुद को नुकसान पहुँचाना चाहता हूँ","मैं खुद को नुकसान पहुँचाना चाहती हूँ","ನಾನು ಸಾಯಬೇಕು ಎಂದು ಅನಿಸುತ್ತಿದೆ","ನನಗೆ ಬದುಕಲು ಇಷ್ಟವಿಲ್ಲ","ನಾನು ನನ್ನನ್ನು ಹಾನಿ ಮಾಡಿಕೊಳ್ಳಬೇಕು")){
            return "I'm really sorry you're in this much pain. I'm glad you told me. If you might act on these thoughts or are in immediate danger, please move near a trusted person and call India's emergency number 112 now. In India, Tele-MANAS mental-health support is available at 14416. If you can, tell someone you trust plainly: 'I'm not safe alone right now.' Are you in immediate danger right now?";
        }
        return null;
    }

    // The small model is decoded once at startup; prediction avoids repeated JSONObject parsing.
    String predict(String text){
        try {
            if(!brainReady || brainLabels.length==0 || weights1.length==0 || weights2.length==0) return "unknown";
            Matcher m=Pattern.compile("[\\p{L}\\p{M}\\p{N}_\']+").matcher(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
            ArrayList<String> ts=new ArrayList<>(); while(m.find())ts.add(m.group());
            HashSet<String> fs=new HashSet<>(ts); for(int i=0;i<ts.size()-1;i++)fs.add("bi:"+ts.get(i)+"_"+ts.get(i+1));
            for(String token:ts)addCharacterFeatures(fs,token);
            ArrayList<String> corrected=new ArrayList<>();for(String token:ts)corrected.add(correctKnownToken(token));
            for(int i=0;i<corrected.size();i++){String token=corrected.get(i);if(!token.equals(ts.get(i))){fs.add(token);addCharacterFeatures(fs,token);}if(i<corrected.size()-1){String bi="bi:"+token+"_"+corrected.get(i+1);if(!token.equals(ts.get(i))||!corrected.get(i+1).equals(ts.get(i+1)))fs.add(bi);}}
            ArrayList<Integer> activeIndices=new ArrayList<>();
            for(String f:fs){Integer idx=vocabIndex.get(f);if(idx!=null && idx<vocabIndex.size())activeIndices.add(idx);}
            double[] h=new double[bias1.length];
            // Sparse projection: only active word/bigram/character features are multiplied.
            // This avoids scanning all 6,000+ vocabulary entries for every hidden unit.
            for(int j=0;j<h.length;j++){double z=bias1[j];for(int activeId:activeIndices)z+=weights1[activeId][j];h[j]=Math.tanh(z);}
            double[] z=new double[brainLabels.length];double max=-Double.MAX_VALUE;
            for(int k=0;k<z.length;k++){double value=bias2[k];for(int j=0;j<h.length;j++)value+=h[j]*weights2[j][k];z[k]=value;if(value>max)max=value;}
            double sum=0.0;for(int k=0;k<z.length;k++){z[k]=Math.exp(z[k]-max);sum+=z[k];}
            if(!Double.isFinite(sum)||sum<=0.0)return "unknown";
            int best=0;for(int k=1;k<z.length;k++)if(z[k]>z[best])best=k;
            return z[best]/sum>=brain.optDouble("threshold",0.48)?brainLabels[best]:"unknown";
        } catch(Exception e){return "unknown";}
    }
    // Character n-grams share partial spelling patterns between unseen words, forms and minor typos.
    void addCharacterFeatures(Set<String> features,String token){
        if(token==null)return;
        for(int size=3;size<=4;size++)for(int start=0;start+size<=token.length();start++)features.add("ch"+size+":"+token.substring(start,start+size));
    }

    // Correct only an unknown alphabetic token when there is exactly one vocabulary word at edit distance 1.
    // This limits accidental intent changes. It is a spelling aid, not semantic understanding.
    String correctKnownToken(String token){
        if(token==null||token.length()<5||token.matches(".*\\d.*")||vocabIndex.containsKey(token))return token;
        String candidate=null;
        for(int length=Math.max(5,token.length()-1);length<=token.length()+1;length++){
            ArrayList<String> words=typoWordsByLength.get(length);if(words==null)continue;
            for(String word:words){if(editDistanceAtMost(word,token,1)==1){if(candidate!=null&&!candidate.equals(word))return token;candidate=word;}}
        }
        return candidate==null?token:candidate;
    }

    // ---------- Personal voice studio: local recordings take priority for selected reply types. ----------
    void showVoiceStudio(){
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(18),dp(8),dp(18),dp(4));
        TextView intro=txt("Record a phrase in your own voice, or import an audio clip. Ammu plays these clips for matching reply types; changing words such as times, calculations, or task names still uses Android TTS. No audio is uploaded.",13,muted,false);intro.setPadding(0,0,0,dp(12));layout.addView(intro);
        voiceSlotPicker=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,voiceSlotLabels);voiceSlotPicker.setAdapter(adapter);layout.addView(voiceSlotPicker);
        TextView prompt=txt("",14,fg,false);prompt.setPadding(0,dp(10),0,dp(8));layout.addView(prompt);
        voiceStatus=txt("Choose a phrase to begin.",12,muted,false);voiceStatus.setPadding(0,dp(3),0,dp(8));layout.addView(voiceStatus);
        voicePackCheckBox=new CheckBox(this);voicePackCheckBox.setText("Prefer my recorded clips");voicePackCheckBox.setTextColor(fg);voicePackCheckBox.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));voicePackCheckBox.setChecked(preferRecordedVoice);layout.addView(voicePackCheckBox);
        speechCheckBox=new CheckBox(this);speechCheckBox.setText("Enable spoken replies (Android TTS fallback)");speechCheckBox.setTextColor(fg);speechCheckBox.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));speechCheckBox.setChecked(speakEnabled);layout.addView(speechCheckBox);
        LinearLayout row1=new LinearLayout(this);row1.setOrientation(LinearLayout.HORIZONTAL);row1.setPadding(0,dp(8),0,0);layout.addView(row1);
        voiceRecordButton=button("Record my voice",true);row1.addView(voiceRecordButton,new LinearLayout.LayoutParams(0,dp(44),1));
        Button preview=button("Play clip",false);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(44),1);pp.leftMargin=dp(7);row1.addView(preview,pp);
        LinearLayout row2=new LinearLayout(this);row2.setOrientation(LinearLayout.HORIZONTAL);row2.setPadding(0,dp(7),0,0);layout.addView(row2);
        Button importAudio=button("Import audio",false);row2.addView(importAudio,new LinearLayout.LayoutParams(0,dp(42),1));
        Button remove=button("Remove clip",false);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(42),1);rp.leftMargin=dp(7);row2.addView(remove,rp);
        Button engineSettings=button("Choose a full-text voice engine",false);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(44));ep.topMargin=dp(7);layout.addView(engineSettings,ep);
        TextView engineHint=txt("A compatible installed offline TTS engine can speak new/dynamic replies in a cloned voice. Set it as Android's default engine; Ammu uses the system default.",12,muted,false);engineHint.setPadding(0,dp(4),0,dp(4));layout.addView(engineHint);
        voiceSlotPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> a,View v,int pos,long id){prompt.setText("Suggested recording: “"+voicePromptFor(voiceSlotKeys[pos])+"”");updateVoiceStatus();}public void onNothingSelected(android.widget.AdapterView<?> a){}});
        voiceRecordButton.setOnClickListener(v->{if(isRecording)stopVoiceRecording(true);else startVoiceRecording();});
        preview.setOnClickListener(v->playSelectedVoiceClip());
        importAudio.setOnClickListener(v->chooseVoiceAudio());
        remove.setOnClickListener(v->{String key=currentVoiceSlot();deleteVoiceClip(key);updateVoiceStatus("Removed the selected recording.");});
        engineSettings.setOnClickListener(v->openSystemTtsSettings());
        voicePackCheckBox.setOnCheckedChangeListener((b,checked)->{preferRecordedVoice=checked;prefs.edit().putBoolean("prefer_recorded_voice",checked).apply();});
        speechCheckBox.setOnCheckedChangeListener((b,checked)->{speakEnabled=checked;prefs.edit().putBoolean("speak_enabled",checked).apply();});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ammu · My Voice").setView(layout).setPositiveButton("Done",(d,w)->{if(isRecording)stopVoiceRecording(true);}).create();
        dialog.setOnDismissListener(d->{if(isRecording)stopVoiceRecording(true);voiceSlotPicker=null;voiceStatus=null;voiceRecordButton=null;voicePackCheckBox=null;speechCheckBox=null;});
        dialog.show(); updateVoiceStatus();
    }
    String currentVoiceSlot(){if(voiceSlotPicker==null)return "greeting";int i=voiceSlotPicker.getSelectedItemPosition();if(i<0||i>=voiceSlotKeys.length)return "greeting";return voiceSlotKeys[i];}
    String voicePromptFor(String key){switch(key){
        case "greeting":return "Hello! I'm here. What would you like to do?";
        case "help":return "I can help with tasks, notes, calculations, and talking through how you're feeling.";
        case "confirmation":return "Done. I've saved that for you on this phone.";
        case "fallback":return "I'm not sure I understood. Could you explain that another way?";
        case "goodbye":return "See you later. Your information is saved on this phone.";
        case "emotion_happy":return "I'm glad to hear that. What made today good?";
        case "emotion_sad":return "I'm sorry this feels hard. Would you like to tell me what happened?";
        case "emotion_anxious":return "Let's take one step at a time. What's worrying you most?";
        case "emotion_angry":return "That sounds frustrating. Would you like to vent or plan a next step?";
        case "emotion_lonely":return "That can hurt. Would you like to tell me what's been happening?";
        case "emotion_overwhelmed":return "Let's break this down. What's the first thing to handle?";
        case "emotion_tired":return "You sound drained. Can you take a short rest?";
        case "emotion_confused":return "It's okay not to have the answer yet. What part feels confusing?";
        default:return "Hello.";
    }}
    File voiceDirectory(){File dir=new File(getFilesDir(),"voicepack");if(!dir.exists())dir.mkdirs();return dir;}
    File findVoiceClip(String key){String[] exts={"m4a","wav","mp3","ogg","aac","3gp"};for(String ext:exts){File f=new File(voiceDirectory(),key+"."+ext);if(f.isFile()&&f.length()>0)return f;}return null;}
    void deleteVoiceClip(String key){String[] exts={"m4a","wav","mp3","ogg","aac","3gp"};for(String ext:exts)new File(voiceDirectory(),key+"."+ext).delete();}
    void updateVoiceStatus(){updateVoiceStatus(null);}
    void updateVoiceStatus(String message){if(voiceStatus==null)return;if(message!=null){voiceStatus.setText(message);return;}String key=currentVoiceSlot();File clip=findVoiceClip(key);voiceStatus.setText(clip==null?"No recording for this phrase yet. Use Record my voice or Import audio.":"Ready: "+clip.getName()+" ("+(clip.length()/1024)+" KB)");if(voiceRecordButton!=null)voiceRecordButton.setText(isRecording?"Stop & save":"Record my voice");}
    void startVoiceRecording(){
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO_PERMISSION);return;}
        recordingSlot=currentVoiceSlot();voiceDirectory();activeRecording=new File(voiceDirectory(),recordingSlot+"-temporary.m4a");if(activeRecording.exists())activeRecording.delete();
        try{recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setMaxDuration(20000);recorder.setOutputFile(activeRecording.getAbsolutePath());recorder.prepare();recorder.start();isRecording=true;if(voiceSlotPicker!=null)voiceSlotPicker.setEnabled(false);updateVoiceStatus("Recording now. Read the suggested phrase clearly, then tap Stop & save.");if(voiceRecordButton!=null)voiceRecordButton.setText("Stop & save");}
        catch(Exception e){if(recorder!=null){try{recorder.reset();}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}recorder=null;}isRecording=false;if(activeRecording!=null)activeRecording.delete();updateVoiceStatus("Couldn't start recording. Check microphone permission and try again.");}
    }
    void stopVoiceRecording(boolean saveClip){
        boolean valid=false;try{if(recorder!=null){recorder.stop();valid=true;}}catch(Exception ignored){}finally{if(recorder!=null){try{recorder.reset();}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}recorder=null;}}
        isRecording=false;if(voiceSlotPicker!=null)voiceSlotPicker.setEnabled(true);if(voiceRecordButton!=null)voiceRecordButton.setText("Record my voice");
        if(activeRecording!=null){if(saveClip&&valid&&activeRecording.exists()&&activeRecording.length()>500){deleteVoiceClip(recordingSlot);File dest=new File(voiceDirectory(),recordingSlot+".m4a");boolean moved=activeRecording.renameTo(dest);if(!moved){try(InputStream in=new FileInputStream(activeRecording);OutputStream out=new FileOutputStream(dest)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)out.write(buf,0,n);activeRecording.delete();moved=true;}catch(Exception e){dest.delete();}}updateVoiceStatus(moved?"Saved your recording for "+recordingSlot+".":"The recording could not be saved.");}else{activeRecording.delete();updateVoiceStatus("Recording stopped; no usable clip was saved. Try recording the phrase again.");}}
        activeRecording=null;
    }
    void playSelectedVoiceClip(){File f=findVoiceClip(currentVoiceSlot());if(f==null){updateVoiceStatus("No clip to play yet. Record or import one first.");return;}playVoiceFile(f,true);}
    boolean playVoiceFile(File f,boolean updateStatus){return playVoiceFile(f,updateStatus,null);}
    boolean playVoiceFile(File f,boolean updateStatus,String fallbackText){stopVoicePlayer();if(tts!=null)tts.stop();try{voicePlayer=new MediaPlayer();voicePlayer.setDataSource(f.getAbsolutePath());voicePlayer.setOnCompletionListener(mp->{try{mp.release();}catch(Exception ignored){}if(voicePlayer==mp)voicePlayer=null;});voicePlayer.setOnErrorListener((mp,what,extra)->{try{mp.release();}catch(Exception ignored){}if(voicePlayer==mp)voicePlayer=null;if(updateStatus)updateVoiceStatus("This audio file could not be played. Try importing WAV, M4A, MP3, or OGG audio.");else if(fallbackText!=null&&speakEnabled&&tts!=null)tts.speak(fallbackText,TextToSpeech.QUEUE_FLUSH,null,"ammu-voice-fallback");return true;});voicePlayer.prepare();voicePlayer.start();if(updateStatus)updateVoiceStatus("Playing your recording…");return true;}catch(Exception e){stopVoicePlayer();if(updateStatus)updateVoiceStatus("Couldn't play this recording. Try another audio file.");return false;}}
    void stopVoicePlayer(){if(voicePlayer!=null){try{if(voicePlayer.isPlaying())voicePlayer.stop();}catch(Exception ignored){}try{voicePlayer.release();}catch(Exception ignored){}voicePlayer=null;}}
    void openSystemTtsSettings(){
        refreshTtsOnResume=true;
        try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}
        catch(Exception e){try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception ignored){refreshTtsOnResume=false;updateVoiceStatus("Open Android Settings and search for Text-to-speech output.");}}
    }
    void chooseVoiceAudio(){importTargetVoiceSlot=currentVoiceSlot();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");try{startActivityForResult(i,REQ_IMPORT_AUDIO);}catch(Exception e){updateVoiceStatus("The file picker isn't available on this device.");}}
    String audioExtension(String mime){if(mime==null)return "m4a";String m=mime.toLowerCase(Locale.ROOT);if(m.contains("wav"))return "wav";if(m.contains("mpeg"))return "mp3";if(m.contains("ogg"))return "ogg";if(m.contains("aac"))return "aac";if(m.contains("3gpp"))return "3gp";return "m4a";}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null)return;
        if(requestCode==REQ_SPEECH_INPUT){
            ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(results!=null&&!results.isEmpty()&&results.get(0)!=null&&!results.get(0).trim().isEmpty()){
                String spoken=results.get(0).trim();runCommand(spoken);
            }else addMessage("Ammu","I didn't receive a clear transcript. You can try again or type your command.",false);
            return;
        }
        if(data.getData()==null)return;Uri uri=data.getData();
        if(requestCode==REQ_EXPORT_BACKUP){exportBackup(uri);return;}
        if(requestCode==REQ_EXPORT_BRAIN_EXAMPLES){exportBrainExamples(uri);return;}
        if(requestCode==REQ_EXPORT_PHONE_MISTAKES){exportPhoneMistakes(uri);return;}
        if(requestCode==REQ_IMPORT_BRAIN_EXAMPLES){importBrainExamples(uri);return;}
        if(requestCode==REQ_IMPORT_BACKUP){confirmBackupImport(uri);return;}
        if(requestCode==REQ_IMPORT_AUDIO){
            String key=importTargetVoiceSlot;String ext=audioExtension(getContentResolver().getType(uri));File dest=new File(voiceDirectory(),key+"."+ext);
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(dest)){
                if(in==null)throw new IOException("No file stream");byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)out.write(buf,0,n);
                if(dest.length()<100){dest.delete();updateVoiceStatus("That audio file is empty or too small.");}
                else{deleteVoiceClipExcept(key,ext);updateVoiceStatus("Imported audio for "+key+". Preview it before using it.");}
            }catch(Exception e){dest.delete();updateVoiceStatus("Couldn't import that audio file. Try a standard audio file instead.");}
        }
    }
    void deleteVoiceClipExcept(String key,String keepExt){String[] exts={"m4a","wav","mp3","ogg","aac","3gp"};for(String ext:exts)if(!ext.equals(keepExt))new File(voiceDirectory(),key+"."+ext).delete();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_AUDIO_PERMISSION){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startVoiceRecording();else updateVoiceStatus("Microphone permission is needed to record your own voice. You can still import a recording.");}}
    String voiceSlotForReply(String raw,String normalized,String intent,String answer){
        // Never replace crisis/safety advice with a short prerecorded clip.
        if(safetyResponse(normalized)!=null)return null;
        if(mixedEmotionReply(normalized)!=null || isNegatedFeeling(normalized))return null;
        if(normalized.matches(".*\\b(hello|hi|hey ammu|good morning|good evening)\\b.*"))return "greeting";
        if(normalized.equals("goodbye")||normalized.equals("exit")||intent.equals("goodbye"))return "goodbye";
        if(normalized.equals("help")||intent.equals("help"))return "help";
        String e=detectEmotion(normalized);if(e!=null&&isFeelingStatement(normalized)){String k="emotion_"+e;for(String allowed:voiceSlotKeys)if(allowed.equals(k))return k;}
        String low=answer.toLowerCase(Locale.ROOT);
        if(low.startsWith("saved")||low.startsWith("added task")||low.startsWith("marked task")||low.startsWith("done. i")||low.startsWith("i've saved"))return "confirmation";
        if(low.startsWith("i don't have enough information")||low.startsWith("i'm not sure i understood"))return "fallback";
        return null;
    }
    void speakReply(String raw,String normalized,String intent,String answer){
        if(preferRecordedVoice){String slot=voiceSlotForReply(raw,normalized,intent,answer);File clip=slot==null?null:findVoiceClip(slot);if(clip!=null&&playVoiceFile(clip,false,answer))return;}
        stopVoicePlayer();
        if(tts!=null)tts.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"ammu-reply");
    }

    String stripPrefix(String raw,String... prefixes){String s=raw.trim();for(String p:prefixes)if(s.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))){s=s.substring(p.length()).replaceFirst("^[\\s:,-]+","").trim();break;}return s;}
    String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date());}
    JSONArray flattenVector(JSONArray value)throws JSONException{if(value.length()==1 && value.opt(0) instanceof JSONArray)return value.getJSONArray(0);return value;}
    void loadBrain(){
        brainReady=false;
        brainLoadIssue="";
        try {
            brain=new JSONObject(readAsset("brain.json"));
            JSONArray labels=brain.getJSONArray("labels"), vocab=brain.getJSONArray("vocab"), w1=brain.getJSONArray("W1"), b1=brain.getJSONArray("b1"), w2=brain.getJSONArray("W2"), b2=brain.getJSONArray("b2");
            JSONArray v1=flattenVector(b1), v2=flattenVector(b2);
            if(labels.length()==0||vocab.length()==0)throw new IllegalStateException("empty labels or vocabulary");
            HashSet<String> uniqueLabels=new HashSet<>(), uniqueVocab=new HashSet<>();
            brainLabels=new String[labels.length()];
            for(int i=0;i<labels.length();i++){String label=labels.getString(i);if(label.trim().isEmpty()||!uniqueLabels.add(label))throw new IllegalStateException("duplicate or empty intent label");brainLabels[i]=label;}
            vocabIndex.clear();typoWordsByLength.clear();
            for(int i=0;i<vocab.length();i++){
                String token=vocab.getString(i);if(token.trim().isEmpty()||!uniqueVocab.add(token))throw new IllegalStateException("duplicate or empty vocabulary feature");vocabIndex.put(token,i);
                if(!token.startsWith("bi:")&&!token.startsWith("ch3:")&&!token.startsWith("ch4:"))typoWordsByLength.computeIfAbsent(token.length(),k->new ArrayList<>()).add(token);
            }
            if(v1.length()==0||v2.length()!=labels.length()||w1.length()!=vocab.length()||w2.length()!=v1.length())throw new IllegalStateException("model layer dimensions do not match");
            bias1=new double[v1.length()];for(int i=0;i<v1.length();i++){bias1[i]=v1.getDouble(i);if(!Double.isFinite(bias1[i]))throw new IllegalStateException("non-finite hidden bias");}
            bias2=new double[v2.length()];for(int i=0;i<v2.length();i++){bias2[i]=v2.getDouble(i);if(!Double.isFinite(bias2[i]))throw new IllegalStateException("non-finite output bias");}
            weights1=new double[w1.length()][bias1.length];
            for(int i=0;i<w1.length();i++){JSONArray row=w1.getJSONArray(i);if(row.length()!=bias1.length)throw new IllegalStateException("input layer row size mismatch");for(int j=0;j<row.length();j++){double value=row.getDouble(j);if(!Double.isFinite(value))throw new IllegalStateException("non-finite input weight");weights1[i][j]=value;}}
            weights2=new double[w2.length()][bias2.length];
            for(int i=0;i<w2.length();i++){JSONArray row=w2.getJSONArray(i);if(row.length()!=bias2.length)throw new IllegalStateException("output layer row size mismatch");for(int j=0;j<row.length();j++){double value=row.getDouble(j);if(!Double.isFinite(value))throw new IllegalStateException("non-finite output weight");weights2[i][j]=value;}}
            double threshold=brain.optDouble("threshold",0.48);if(!Double.isFinite(threshold)||threshold<=0.0||threshold>=1.0)throw new IllegalStateException("invalid confidence threshold");
            if(!uniqueLabels.contains("unknown"))throw new IllegalStateException("missing unknown intent class");
            brainReady=true;
        } catch(Exception e){
            brainLoadIssue=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            brain=new JSONObject();brainLabels=new String[0];vocabIndex.clear();typoWordsByLength.clear();weights1=new double[0][0];weights2=new double[0][0];bias1=new double[0];bias2=new double[0];brainReady=false;
        }
    }
    String readAsset(String name)throws Exception{InputStream is=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=is.read(buf))>0)out.write(buf,0,n);is.close();return out.toString("UTF-8");}
    void loadData(){notes=load("notes");tasks=load("tasks");memory=load("memory");journal=load("journal");moodLog=load("mood");conversationHistory=load("conversation");brainExamples=load("brain_examples");try{customAliases=new JSONObject(prefs.getString("command_aliases","{}"));}catch(Exception e){customAliases=new JSONObject();}}
    JSONArray load(String key){
        String raw=prefs.getString(key,"[]");
        try{JSONArray array=new JSONArray(raw);if(array.length()>5000){addDataLoadWarning(key+" exceeds the safe item limit");return new JSONArray();}return array;}
        catch(Exception e){addDataLoadWarning(key+" is malformed");return new JSONArray();}
    }
    void addDataLoadWarning(String warning){if(dataLoadWarnings.isEmpty())dataLoadWarnings=warning;else if(!dataLoadWarnings.contains(warning))dataLoadWarnings+=", "+warning;}
    void save(String key,JSONArray a){prefs.edit().putString(key,a.toString()).apply();}
    String list(JSONArray a,String field,String empty,String heading){if(a.length()==0)return empty;StringBuilder b=new StringBuilder(heading+"\n");for(int i=Math.max(0,a.length()-10);i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)b.append(i+1).append(". ").append(o.optString(field)).append("\n");}return b.toString().trim();}
    String search(JSONArray a,String field,String q,String empty){StringBuilder b=new StringBuilder();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&o.optString(field).toLowerCase(Locale.ROOT).contains(q))b.append(i+1).append(". ").append(o.optString(field)).append("\n");}return b.length()==0?empty:b.toString().trim();}
    void addMessage(String who,String body,boolean speak){TextView name=txt(who,11,who.equals("Ammu")?accent:muted,true);name.setPadding(dp(4),dp(10),0,dp(4));messages.addView(name);TextView bubble=txt(body,15,fg,false);bubble.setPadding(dp(13),dp(12),dp(13),dp(12));bubble.setBackground(round(panel,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(4);messages.addView(bubble,p);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
    TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(fg);b.setPadding(dp(5),0,dp(5),0);b.setBackground(round(primary?accent:panel,14));return b;}
    android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onResume(){super.onResume();if(refreshTtsOnResume){refreshTtsOnResume=false;initializeTts();}}
    @Override protected void onPause(){if(isRecording)stopVoiceRecording(true);stopVoicePlayer();if(tts!=null)tts.stop();super.onPause();}
    @Override protected void onDestroy(){if(isRecording)stopVoiceRecording(true);stopVoicePlayer();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
    static class Calc{String s;int i=0;Calc(String x){s=x.replaceAll("[^0-9.+*/%^() -]","").replaceAll("\\s+","");}double parse(){double x=expr();if(i<s.length()||!Double.isFinite(x))throw new RuntimeException();return x;}double expr(){double x=term();while(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-')){char c=s.charAt(i++);double y=term();x=c=='+'?x+y:x-y;}return x;}double term(){double x=unary();while(i<s.length()&&(s.charAt(i)=='*'||s.charAt(i)=='/'||s.charAt(i)=='%')){char c=s.charAt(i++);double y=unary();if((c=='/'||c=='%')&&y==0)throw new RuntimeException();x=c=='*'?x*y:(c=='/'?x/y:x%y);}return x;}double unary(){if(i<s.length()&&s.charAt(i)=='+'){i++;return unary();}if(i<s.length()&&s.charAt(i)=='-'){i++;return -unary();}return power();}double power(){double x=primary();if(i<s.length()&&s.charAt(i)=='^'){i++;double y=unary();x=Math.pow(x,y);}return x;}double primary(){if(i<s.length()&&s.charAt(i)=='('){i++;double x=expr();if(i>=s.length()||s.charAt(i++)!=')')throw new RuntimeException();return x;}int st=i;while(i<s.length()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))i++;if(st==i)throw new RuntimeException();double value=Double.parseDouble(s.substring(st,i));if(!Double.isFinite(value))throw new RuntimeException();return value;}}
}
