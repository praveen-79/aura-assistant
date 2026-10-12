# Aura — 2-Minute Live Demo Script & Viva Q&A Guide

Use this quick guide during your project demonstration or evaluation.

---

## 🎬 2-Minute Live Demo Flow (At `http://localhost:8000`)

1. **Show the UI & Privacy Shield (15 sec)**:
   - Point out the **`🔒 100% Local Data & Privacy Shield`** badge and the **`⏱️ Word Gap: 2.4s (Indian Natural)`** selector.
   - Explain: *"Aura runs 100% locally on our machine. Personal contacts, files, and photos are never uploaded to any external server."*
2. **Demonstrate Indian Speech Gap & Telugu Voice Output (30 sec)**:
   - Click **`🇮🇳 అమ్మ కి కాల్ చేయి`** (or say *"Amma ... ki ... call ... chey"* with pauses between words).
   - Point out how Aura joins the paused words, resolves `"Amma"` to her phone number, triggers the call action, and speaks back in native **Telugu** (`"సరే, Amma కి కాల్ చేస్తున్నాను"`).
3. **Demonstrate Moral Values & Cultural Ethics (20 sec)**:
   - Click **`🌸 Moral Value (మంచి మాట)`** (or say *"Manchi maata cheppu"*).
   - Aura speaks the moral value (*"మాతృ దేవో భవ, పితృ దేవో భవ"* — Respect for parents, truth, and kindness) and blocks any abusive/hurtful messages.
4. **Demonstrate Strict Privacy & Permission Gates (30 sec)**:
   - Click **`🤳 Camera (Asks Permission)`** (or type `"Open Gallery"` / `"Open PhonePe"`):
     - Show that **UPI/Payment apps (`PhonePe`, `GPay`) are strictly blocked** for privacy.
     - Show that **Photos on the screen (`ImageView`) can never be scanned**.
     - Show that **Camera / Gallery / Files** ask for your explicit permission first (*"Say 'Yes' or 'Avunu' to confirm"*) before opening!
5. **1-Click Full Showcase (`🎬 Auto-Demo`) (25 sec)**:
   - Click **`🎬 Auto-Demo`** in the top-right corner to watch Aura automatically execute 5 multilingual commands back-to-back.

---

## 💡 Top 5 Viva / Evaluator Questions & Answers

1. **Q: How did you solve the problem of Indian speakers taking pauses/gaps between words?**
   - **A:** On the frontend ([`static/index.html`](file:///C:/Users/HP/.gemini/antigravity/scratch/aura-assistant/static/index.html)), we built a continuous Web Speech buffer with an adjustable `2.4s` silence timer (`flushSpokenBufferNow`) so pauses between words don't cut off the mic. On the backend ([`gemini_agent.py`](file:///C:/Users/HP/.gemini/antigravity/scratch/aura-assistant/gemini_agent.py)), `normalize_indian_utterance()` and `collapse_stt_gaps()` merge split syllables (like `"am ma"` $\rightarrow$ `"amma"`).
2. **Q: Why does Telugu speak clearly even on Windows PCs that don't have a Telugu OS voice installed?**
   - **A:** We engineered a **3-Tier TTS system**: Tier 1 synthesizes native Telugu MP3 audio via `/api/tts` in [`app.py`](file:///C:/Users/HP/.gemini/antigravity/scratch/aura-assistant/app.py) and caches it in `tts_cache/`. Tier 2 checks for a native OS voice. Tier 3 uses our matra-aware Indic-to-Latin transliterator (`transliterate_indic_to_latin` in [`contact_manager.py`](file:///C:/Users/HP/.gemini/antigravity/scratch/aura-assistant/contact_manager.py)) as an offline phonetic fallback.
3. **Q: Is the user's personal data sent to any server?**
   - **A:** No. The FastAPI server runs locally on `127.0.0.1` (`localhost`), and in Smart Offline Mode zero data leaves the device. Even if an optional Gemini API key is used, `_build_system_instruction()` never sends local contacts, files, or memory dumps—only the single requested command is processed.
4. **Q: How does Aura protect user privacy regarding UPI, Photos, and Screen Scanning?**
   - **A:**
     - **UPI Block**: `PhonePe`, `GPay`, `Paytm`, and `BHIM` are hard-blocked at the top of the intent router.
     - **No Screen-Photo Scanning**: `parse_ui_hierarchy_xml()` and `AuraAccessibilityService.kt` explicitly skip `android.widget.ImageView` nodes and block screenshots so photos on the screen can never be scanned.
     - **Permission Gate**: Accessing Photos, Gallery, Files, or Camera requires an explicit multi-turn `"Yes" / "Avunu"` permission confirmation from the owner.
5. **Q: How are Moral Values integrated into the assistant?**
   - **A:** Aura includes both a proactive **Moral Wisdom Responder** (`"Manchi maata cheppu"` / `"Aaj ka suvichar"`) teaching respect for parents/elders (*Matru Devo Bhava, Pitru Devo Bhava*), honesty, and kindness, and a **Moral Anti-Harm Guardrail** that refuses to send insulting, abusive, or dishonest messages.
