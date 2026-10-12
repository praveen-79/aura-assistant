# Aura v2 — Multilingual AI Voice & Android Automation Assistant

**Aura v2** is a Siri-like multilingual Android AI Voice & Automation Assistant that understands and speaks in the phone owner's natural language (**Telugu, Tamil, Hindi, Hinglish, Bengali, Marathi, Spanish, and English**), handles natural Indian speech pauses between words, embodies **Moral Values & Strict Privacy Guardrails**, and executes real Android device automations both online (powered by **Gemini 3.8 Flash** & **Gemini 3.8 Live** bidirectional audio) and 100% offline.

---

## ✨ Key Highlights

1. **Indian Speech Pause & Gap Normalization**:
   - Continuous Web Speech buffer (`1.4s` / `2.4s` / `3.4s` adjustable pause tolerance) that waits for natural pauses between words (*"Amma ... ki ... call ... chey"*) without cutting off mid-sentence.
   - Backend phonetic & syllable de-gapping (`collapse_stt_gaps`, `normalize_indian_utterance`) that merges split syllables (`"am ma"` $\rightarrow$ `"amma"`, `"వా ట్సా ప్"` $\rightarrow$ `"వాట్సాప్"`).
2. **3-Tier Native Telugu & Indic Voice Synthesis (`/api/tts`)**:
   - **Tier 1**: Backend neural MP3 speech synthesis (`/api/tts?text=...&lang=te`) with persistent local disk caching (`tts_cache/`) so Telugu, Tamil, Hindi, Bengali, Marathi, Spanish, and English speak aloud with authentic native pronunciation on any device.
   - **Tier 2**: Native OS `SpeechSynthesis` voice matching (`te-IN`, `ta-IN`, `hi-IN`, `en-IN`).
   - **Tier 3**: Matra-aware Indic-to-Latin phonetic transliteration (`transliterate_indic_to_latin`) fallback so even offline PCs without Indic SAPI5 voices pronounce Telugu/Tamil/Hindi replies clearly.
3. **100% Local Data, Privacy Shield & Moral Values**:
   - **Local-Only Personal Data**: Your contacts (`contacts.json`), memories (`memory.json`), and files stay **100% on your own device** (`localhost`) and are never uploaded to any external server. Only the single requested command is processed.
   - **Strict UPI & Payment Privacy Block**: Aura never accesses or opens UPI, banking, or payment apps (`PhonePe`, `GPay`, `Paytm`, `BHIM`).
   - **On-Screen Photo Protection**: Photos and images displayed on the screen (`android.widget.ImageView`) are strictly ignored and can never be scanned or captured.
   - **Explicit Permission Gate for Photos, Files & Camera**: Aura never accesses Photos, Gallery, File Manager, or Camera without first asking for your explicit permission and receiving your `"Yes" / "Allow" / "Avunu" / "Haan"` confirmation.
   - **Moral Values & Ethical Conduct**: Shares inspiring moral wisdom (*"Matru Devo Bhava, Pitru Devo Bhava"*, *Satyam*, *Daya*) on request (*"Manchi maata cheppu"*, *"Aaj ka suvichar"*, *"Moral value"*) and refuses to send abusive, insulting, or dishonest messages.
4. **Android Device Automation Capabilities**:
   - **Calls & Messaging**: `make_phone_call` (with speakerphone support), `send_whatsapp_message`, `send_sms_message`, `search_contacts`, `add_new_contact`.
   - **Navigation, Media & Web**: `play_youtube_media`, `navigate_to_location` (Google Maps), `search_google_web`, `control_media_playback` (`play`, `pause`, `next`, `previous`).
   - **Hands-Free Screen Gestures & Camera**: `scroll_screen` (`down`, `up`, `left`, `right`), `tap_screen_element` (UIAutomator XML text button parser), `take_camera_photo` (permission-gated).
   - **System, Safety & Productivity**: `trigger_emergency_sos`, `open_android_app`, `set_alarm_or_timer`, `control_device_setting` (Flashlight, Wi-Fi, Bluetooth, Volume, Mute), `get_battery_status`, `calculate_math`, `run_workflow` (Multi-Step Routines), `remember_fact` (Long-Term Owner Memory).

---

## 🚀 Quick Start (1-Click Launch)

### Option A: Windows 1-Click Batch Launcher
Double-click `run_aura.bat` or run in PowerShell:
```powershell
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```
Open **`http://localhost:8000`** in Chrome or Edge (or open the Wi-Fi URL shown in **📱 Phone Link** on your Android phone). Click **`🎬 Auto-Demo`** in the top bar for a 1-click live showcase!

### Option B: Run Automated Test Suite
```powershell
python -m pytest tests/test_assistant.py -v
```
All **15 automated unit and integration tests** pass out of the box.

### Option C: Install the Compiled Android APK (`AuraAssistant.apk` — v2.0.0)
A pre-built, signed native Android APK is ready at `AuraAssistant.apk` (v2.0.0, Play Protect Safe):
1. **Over Wi-Fi (Easiest — No Cable Needed)**:
   - Open your PC's Wi-Fi URL (shown in **📱 Phone Link**, or `http://<YOUR_PC_IP>:8000/download/AuraAssistant.apk`) on your Android phone and tap **`📲 APK`** to download & install `AuraAssistant.apk`.
2. **Via USB Cable (1-Click ADB Installer)**:
   - Connect your Android phone with USB Debugging enabled and double-click `install_apk_usb.bat`.
3. **Rebuild APK Anytime**:
   - Double-click `build_apk.bat` (uses the bundled Gradle 8.7 Wrapper + OpenJDK 17 + Android SDK 36).

