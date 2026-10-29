# Ammu 1.0 voice guide

## Tap-to-talk input

Tap the microphone button beside the message field. Android launches the recognizer selected by the device and passes the most likely transcript to Ammu. Ammu requests offline recognition via `EXTRA_PREFER_OFFLINE`, but Android's recognition provider can ignore that preference. For offline use, check that the chosen recognition provider and the language pack are specifically marked as usable offline.

## Your recorded voice

Open **MY VOICE**, select a reply type, record the phrase shown on-screen, and stop/save. Or import an audio file. Recordings stay in Ammu's private files. These are fixed clips, not voice cloning.

## Dynamic cloned voice (optional)

For new/dynamic text spoken in a voice resembling yours, use a compatible Android TTS engine that explicitly supports reference-audio voice cloning with a model it supports. Set it as the system default Text-to-speech output engine, then disable **Prefer my recorded clips** when you want the dynamic TTS engine to handle those replies. Engine setup and model download are external to Ammu; compatibility and speed vary by model and phone.

Only clone your own voice or a voice you have permission to use. Voice samples and transcripts can be identifying personal data.

## Private backup

Use **TOOLS → Export complete backup** to export the voice clips with other Ammu data. The ZIP is not encrypted, so store it carefully. Restore through **TOOLS → Import backup**. Import replaces the app's current data and asks for confirmation.
