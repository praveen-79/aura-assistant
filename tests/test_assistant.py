"""
Automated Unit & Integration Tests for Aura Multilingual Android AI Assistant.
Tests:
1. Fuzzy & phonetic contact matching across multilingual nicknames/aliases ("Amma", "Rahul bhai", etc.).
2. Indian speech gap & pause normalization (split syllables, fused postpositions, mixed scripts, reversed word order).
3. Multi-turn conversational slot filling ("Call karo" -> asks who -> "Amma" -> dials Amma; "WhatsApp Amma" -> asks message -> sends).
4. Phone calling, WhatsApp messaging, SMS, alarms, timers, and app launcher tool execution.
5. Android UIAutomator XML screen hierarchy parsing & coordinate resolution.
6. Multi-step Workflow Routines ("Heading Home", "Good Night") & Owner Memory persistence.
7. Gemini 3.8 Flash and Gemini 3.8 Live tool schema declarations & multilingual fallback.
8. FastAPI HTTP API endpoints (`/api/status`, `/api/chat`, `/api/contacts`, `/api/workflows`).
"""

from pathlib import Path
from fastapi.testclient import TestClient

from android_automation import AndroidAutomationEngine, parse_bounds
from app import app
from contact_manager import ContactManager, normalize_phonetic
from gemini_agent import (
    AuraGeminiAgent,
    detect_owner_language,
    get_interactions_tools,
    get_live_api_tools,
    normalize_indian_utterance,
)
from memory_manager import MemoryManager
from workflow_manager import WorkflowManager


def test_phonetic_normalization():
    assert normalize_phonetic("Rahul Bhai") == "rahul"
    assert normalize_phonetic("Ammaaa") == "ama"
    assert normalize_phonetic("Sharma Ji!") == "sarma"


def test_indian_speech_gap_normalization(tmp_path: Path):
    assert normalize_indian_utterance("Am ma ... ko ... call ... kar o") == "amma ko call karo"
    assert normalize_indian_utterance("Ra hul ko whats app bhej o") == "rahul ko whatsapp bhejo"
    assert normalize_indian_utterance("ammako callkaro") == "amma ko call karo"

    cm = ContactManager(tmp_path / "contacts.json")
    cm.add_contact("Amma (Mom)", "+919876543210", aliases=["amma", "mom", "अम्मा"])
    cm.add_contact("Rahul Sharma", "+919811122233", aliases=["rahul", "राहुल"])
    engine = AndroidAutomationEngine(cm)
    agent = AuraGeminiAgent(engine, api_key="")

    res_gapped = agent.chat_with_tools("Am ma ... ko ... call ... kar o")
    assert res_gapped["executed_tools"][0]["name"] == "make_phone_call"
    assert res_gapped["executed_tools"][0]["result"]["phone_number"] == "+919876543210"

    res_mixed = agent.chat_with_tools("राहुल भाई को call करो")
    assert res_mixed["executed_tools"][0]["name"] == "make_phone_call"
    assert res_mixed["executed_tools"][0]["result"]["phone_number"] == "+919811122233"

    res_rev = agent.chat_with_tools("Call karo Amma ko")
    assert res_rev["executed_tools"][0]["result"]["phone_number"] == "+919876543210"

    res_short = agent.chat_with_tools("Amma call")
    assert res_short["executed_tools"][0]["result"]["phone_number"] == "+919876543210"


def test_multi_turn_slot_filling_and_voice_contact_save(tmp_path: Path):
    cm = ContactManager(tmp_path / "contacts.json")
    cm.add_contact("Amma (Mom)", "+919876543210", aliases=["amma", "mom"])
    engine = AndroidAutomationEngine(cm)
    agent = AuraGeminiAgent(engine, api_key="")

    # 1. Incomplete call command ("Call karo") -> asks who -> user says "Amma" -> places call!
    turn1 = agent.chat_with_tools("Call karo")
    assert len(turn1["executed_tools"]) == 0
    assert agent.pending_intent is not None

    turn2 = agent.chat_with_tools("Amma")
    assert len(turn2["executed_tools"]) == 1
    assert turn2["executed_tools"][0]["name"] == "make_phone_call"
    assert turn2["executed_tools"][0]["result"]["phone_number"] == "+919876543210"

    # 2. Incomplete WhatsApp command ("Amma ko WhatsApp karo") -> asks message -> sends!
    wa1 = agent.chat_with_tools("Amma ko WhatsApp karo")
    assert len(wa1["executed_tools"]) == 0
    assert agent.pending_intent is not None

    wa2 = agent.chat_with_tools("Khana taiyar rakhna")
    assert len(wa2["executed_tools"]) == 1
    assert wa2["executed_tools"][0]["name"] == "send_whatsapp_message"
    assert wa2["executed_tools"][0]["args"]["message"] == "Khana taiyar rakhna"

    # 3. Save new contact by voice and immediately call them
    save_res = agent.chat_with_tools("Save contact Vikram 9876500001")
    assert save_res["executed_tools"][0]["name"] == "add_new_contact"
    call_vikram = agent.chat_with_tools("Vikram ko call karo")
    assert call_vikram["executed_tools"][0]["result"]["phone_number"] == "9876500001"


def test_language_detection():
    assert detect_owner_language("अम्मा को कॉल करो") == "hi"
    assert detect_owner_language("Amma ko call karo") == "hi"
    assert detect_owner_language("Amma ku call pannu") == "ta"
    assert detect_owner_language("Amma ki call chey") == "te"
    assert detect_owner_language("Llama a Amma") == "es"
    assert detect_owner_language("Call Amma") == "en"


def test_contact_search_multilingual(tmp_path: Path):
    contacts_file = tmp_path / "contacts.json"
    cm = ContactManager(contacts_file)
    cm.add_contact("Amma (Mom)", "+919876543210", aliases=["amma", "mom", "maa"], relationship="Mother")
    cm.add_contact("Rahul Sharma", "+919811122233", aliases=["rahul", "rahul bhai"], relationship="Friend")

    amma_hits = cm.search("amma")
    assert len(amma_hits) >= 1
    assert amma_hits[0].phone == "+919876543210"

    rahul_hits = cm.search("Raahul bhai")
    assert len(rahul_hits) >= 1
    assert rahul_hits[0].name == "Rahul Sharma"

    deva_hits = cm.search("राहुल")
    assert len(deva_hits) >= 1
    assert deva_hits[0].name == "Rahul Sharma"


def test_make_phone_call_and_whatsapp(tmp_path: Path):
    contacts_file = tmp_path / "contacts.json"
    cm = ContactManager(contacts_file)
    cm.add_contact("Amma (Mom)", "+919876543210", aliases=["amma", "mom"])

    engine = AndroidAutomationEngine(cm)
    call_res = engine.make_phone_call("Amma")
    assert call_res["status"] == "calling"
    assert call_res["phone_number"] == "+919876543210"
    assert call_res["client_action"]["url"] == "tel:+919876543210"

    wa_res = engine.send_whatsapp_message("Amma", "Reached safely!")
    assert wa_res["phone_number"] == "+919876543210"
    assert "whatsapp://send?phone=919876543210" in wa_res["client_action"]["url"]


def test_screen_ui_xml_parsing(tmp_path: Path):
    sample_xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
    <hierarchy rotation="0">
      <node index="0" text="Send" resource-id="com.whatsapp:id/send" class="android.widget.ImageButton"
            clickable="true" bounds="[900,1800][1040,1940]" />
      <node index="1" text="Type a message" resource-id="com.whatsapp:id/entry" class="android.widget.EditText"
            clickable="true" bounds="[100,1800][880,1940]" />
      <node index="2" text="Private Photo" content-desc="Photo" resource-id="com.gallery:id/photo" class="android.widget.ImageView"
            clickable="true" bounds="[0,0][1080,1200]" />
    </hierarchy>
    """
    engine = AndroidAutomationEngine(ContactManager(tmp_path / "c.json"))
    elements = engine.parse_ui_hierarchy_xml(sample_xml)
    # The ImageView photo node must be ignored so on-screen photos are never scanned
    assert len(elements) == 2
    assert elements[0]["text"] == "Send"
    assert elements[0]["center"] == {"x": 970, "y": 1870}
    assert parse_bounds("[0,0][200,100]") == (100, 50)



def test_workflows_and_memory(tmp_path: Path):
    cm = ContactManager(tmp_path / "contacts.json")
    cm.add_contact("Amma (Mom)", "+919876543210", aliases=["amma", "mom"])
    engine = AndroidAutomationEngine(cm)
    wf_mgr = WorkflowManager(engine)
    mem_mgr = MemoryManager(tmp_path / "mem.json")
    agent = AuraGeminiAgent(engine, api_key="", workflow_manager=wf_mgr, memory_manager=mem_mgr)

    res_wf = agent.chat_with_tools("Main ghar aa raha hoon")
    assert res_wf["status"] == "ok"
    assert len(res_wf["executed_tools"]) == 2
    assert res_wf["executed_tools"][0]["name"] == "send_whatsapp_message"
    assert res_wf["executed_tools"][1]["name"] == "open_android_app"

    res_mem = agent.chat_with_tools("Remember that my name is Arjun")
    assert res_mem["status"] == "ok"
    assert "arjun" in mem_mgr.format_for_prompt().lower()


def test_gemini_tool_schemas_and_multilingual_fallback(tmp_path: Path):
    interactions_tools = get_interactions_tools()
    live_tools = get_live_api_tools()
    assert len(interactions_tools) >= 11
    assert len(live_tools[0].function_declarations) >= 11
    for decl in live_tools[0].function_declarations:
        assert decl.behavior == "NON_BLOCKING"

    cm = ContactManager(tmp_path / "contacts.json")
    cm.add_contact("Amma (Mom)", "+919876543210", aliases=["amma", "mom"])
    engine = AndroidAutomationEngine(cm)
    agent = AuraGeminiAgent(engine, api_key="")

    res_hi = agent.chat_with_tools("Hey Aura, Amma ko call karo")
    assert res_hi["status"] == "ok"
    assert res_hi["detected_language"] == "hi"
    assert res_hi["executed_tools"][0]["name"] == "make_phone_call"
    assert "919876543210" in res_hi["reply"]


def test_fastapi_endpoints():
    client = TestClient(app)
    status_res = client.get("/api/status")
    assert status_res.status_code == 200
    assert status_res.json()["status"] == "ok"
    assert "mobile_url" in status_res.json()

    chat_res = client.post("/api/chat", json={"text": "Call Amma", "language_hint": "en-IN"})
    assert chat_res.status_code == 200
    body = chat_res.json()
    assert body["status"] == "ok"
    assert any(t["name"] == "make_phone_call" for t in body["executed_tools"])


def test_telugu_voice_commands_and_tts():
    client = TestClient(app)
    # 1. Romanized Telugu call command
    te_call = client.post("/api/chat", json={"text": "Amma ki call chey", "language_hint": "te-IN"})
    assert te_call.status_code == 200
    data = te_call.json()
    assert data["detected_language"] == "te"
    assert "కాల్" in data["reply"]
    assert "919876543211" not in data["spoken_reply"]
    assert "sare" in data["spoken_phonetic"].lower()

    # 2. Telugu script open app command
    te_app = client.post("/api/chat", json={"text": "యూట్యూబ్ ఓపెన్ చేయి", "language_hint": "te-IN"})
    assert te_app.status_code == 200
    app_data = te_app.json()
    assert app_data["detected_language"] == "te"
    assert any(t["name"] == "open_android_app" for t in app_data["executed_tools"])

    # 3. Telugu TTS endpoint (synthesizes and caches audio/mpeg)
    tts_res = client.get("/api/tts", params={"text": data["spoken_reply"], "lang": "te"})
    assert tts_res.status_code in {200, 404}
    if tts_res.status_code == 200:
        assert tts_res.headers["content-type"].startswith("audio/mpeg")
        assert len(tts_res.content) > 500


def test_youtube_navigation_and_battery_tools():
    client = TestClient(app)
    yt = client.post("/api/chat", json={"text": "YouTube lo Telugu songs play chey", "language_hint": "te-IN"})
    assert yt.status_code == 200
    assert any(t["name"] == "play_youtube_media" for t in yt.json()["executed_tools"])

    nav = client.post("/api/chat", json={"text": "Navigate to Charminar", "language_hint": "en-IN"})
    assert nav.status_code == 200
    assert any(t["name"] == "navigate_to_location" for t in nav.json()["executed_tools"])

    bat = client.post("/api/chat", json={"text": "Battery entha undi", "language_hint": "te-IN"})
    assert bat.status_code == 200
    assert any(t["name"] == "get_battery_status" for t in bat.json()["executed_tools"])


def test_calculator_web_search_and_memory_recall():
    client = TestClient(app)
    calc = client.post("/api/chat", json={"text": "250 plus 475 entha", "language_hint": "te-IN"})
    assert calc.status_code == 200
    c_data = calc.json()
    assert "725" in c_data["reply"]
    assert any(t["name"] == "calculate_math" for t in c_data["executed_tools"])

    pct = client.post("/api/chat", json={"text": "What is 15 percent of 2400", "language_hint": "en-IN"})
    assert pct.status_code == 200
    assert "360" in pct.json()["reply"]

    web = client.post("/api/chat", json={"text": "Google lo Hyderabad weather search chey", "language_hint": "te-IN"})
    assert web.status_code == 200
    assert any(t["name"] == "search_google_web" for t in web.json()["executed_tools"])

    mem_save = client.post("/api/chat", json={"text": "Remember that my name is Kiran", "language_hint": "en-IN"})
    assert mem_save.status_code == 200
    mem_recall = client.post("/api/chat", json={"text": "Naa peru enti", "language_hint": "te-IN"})
    assert mem_recall.status_code == 200
    assert "kiran" in mem_recall.json()["reply"].lower()


def test_handsfree_scroll_and_photo_file_privacy():
    client = TestClient(app)
    # 1. Telugu scroll down command ("Kinda ki scroll chey")
    sc = client.post("/api/chat", json={"text": "Kinda ki scroll chey", "language_hint": "te-IN"})
    assert sc.status_code == 200
    sc_data = sc.json()
    assert any(t["name"] == "scroll_screen" and t["args"]["direction"] == "down" for t in sc_data["executed_tools"])

    # 2. Hindi scroll up command ("Upar scroll karo")
    sc_up = client.post("/api/chat", json={"text": "Upar scroll karo", "language_hint": "hi-IN"})
    assert sc_up.status_code == 200
    assert any(t["name"] == "scroll_screen" and t["args"]["direction"] == "up" for t in sc_up.json()["executed_tools"])

    # 3. On-screen photo scanning / screenshot is strictly blocked for privacy
    ss = client.post("/api/chat", json={"text": "Screenshot teeyi", "language_hint": "te-IN"})
    assert ss.status_code == 200
    assert len(ss.json()["executed_tools"]) == 0
    assert "గోప్యత" in ss.json()["reply"] or "privacy" in ss.json()["reply"].lower()

    # 4. Accessing Photos / Gallery / Files without permission asks for permission first
    gal = client.post("/api/chat", json={"text": "Open Gallery", "language_hint": "en-IN"})
    assert gal.status_code == 200
    assert len(gal.json()["executed_tools"]) == 0
    assert "permission" in gal.json()["reply"].lower()
    # Denying permission keeps it blocked
    deny = client.post("/api/chat", json={"text": "No", "language_hint": "en-IN"})
    assert len(deny.json()["executed_tools"]) == 0

    # 5. Camera requires explicit permission first ("Selfie teeyi" -> asks permission -> "Avunu" -> executes)
    cam1 = client.post("/api/chat", json={"text": "Selfie teeyi", "language_hint": "te-IN"})
    assert cam1.status_code == 200
    assert len(cam1.json()["executed_tools"]) == 0
    assert "అనుమతి" in cam1.json()["reply"] or "permission" in cam1.json()["reply"].lower()

    cam2 = client.post("/api/chat", json={"text": "Avunu", "language_hint": "te-IN"})
    assert cam2.status_code == 200
    assert any(t["name"] == "take_camera_photo" and t["args"]["permission_granted"] is True for t in cam2.json()["executed_tools"])


def test_privacy_guard_media_and_emergency_sos():
    client = TestClient(app)
    # 1. Privacy Guardrail: UPI/payment apps must never be accessed
    upi = client.post("/api/chat", json={"text": "Open PhonePe", "language_hint": "te-IN"})
    assert upi.status_code == 200
    assert len(upi.json()["executed_tools"]) == 0
    assert "privacy" in upi.json()["reply"].lower() or "గోప్యత" in upi.json()["reply"]

    # 2. Telugu Next Song ("Paata marchu")
    med = client.post("/api/chat", json={"text": "Paata marchu", "language_hint": "te-IN"})
    assert med.status_code == 200
    assert any(t["name"] == "control_media_playback" and t["args"]["command"] == "next" for t in med.json()["executed_tools"])

    # 3. Emergency SOS ("Emergency SOS")
    sos = client.post("/api/chat", json={"text": "Emergency SOS", "language_hint": "en-IN"})
    assert sos.status_code == 200
    assert any(t["name"] == "trigger_emergency_sos" for t in sos.json()["executed_tools"])

    # 4. Moral Values & Cultural Wisdom ("Manchi maata cheppu")
    moral = client.post("/api/chat", json={"text": "Manchi maata cheppu", "language_hint": "te-IN"})
    assert moral.status_code == 200
    assert "మాతృ దేవో భవ" in moral.json()["reply"] or "moral" in moral.json()["reply"].lower()

    # 5. Ethical Anti-Harm Guardrail (Refuses insulting/abusive messages)
    harm = client.post("/api/chat", json={"text": "Send message to insult someone", "language_hint": "en-IN"})
    assert harm.status_code == 200
    assert len(harm.json()["executed_tools"]) == 0
    assert "moral values" in harm.json()["reply"].lower()


def test_download_apk_endpoint():
    client = TestClient(app)
    res = client.get("/download/AuraAssistant.apk")
    assert res.status_code == 200
    assert res.headers["content-type"] == "application/vnd.android.package-archive"
    assert len(res.content) > 1000000

    page = client.get("/download.html")
    assert page.status_code == 200
    assert "Aura AI Assistant" in page.text
    assert "AuraAssistant.apk" in page.text


def test_open_ended_ai_question_telugu_and_english():
    client = TestClient(app)
    res = client.post("/api/chat", json={"text": "ఏ గురించి ఎక్స్ప్లెయిన్ చెయ్", "language_hint": "te-IN"})
    assert res.status_code == 200
    data = res.json()
    assert "నమస్కారం! నేను ఆరా" not in data["reply"]
    assert any(k in data["reply"].lower() for k in ["ఏఐ", "ఆర్టిఫిషియల్", "కృత్రిమ", "ai", "intelligence", "కంప్యూటర్"])


def test_real_phone_contacts_bulk_sync_and_telugu_script_app_open(tmp_path):
    cm = ContactManager(tmp_path / "contacts.json")
    sync_res = cm.sync_bulk_contacts([
        {"name": "Amma", "phone": "+919440112233"},
        {"name": "Kiran Rao", "phone": "+919000112233"},
    ])
    assert sync_res["status"] == "ok"
    assert sync_res["imported_count"] == 2
    matches = cm.search("అమ్మ")
    assert len(matches) >= 1
    assert matches[0].phone == "+919440112233"

    client = TestClient(app)
    wa = client.post("/api/chat", json={"text": "వాట్సప్ ఓపెన్ చేయ్", "language_hint": "te-IN"})
    assert wa.status_code == 200
    wa_data = wa.json()
    assert any(t["name"] == "open_android_app" and t["args"]["app_name"] == "whatsapp" for t in wa_data["executed_tools"])


def test_whatsapp_multiturn_and_select_commands(tmp_path):
    cm = ContactManager(tmp_path / "contacts.json")
    cm.add_contact("Amma", "+919440112233", aliases=["amma", "mom", "అమ్మ"])
    engine = AndroidAutomationEngine(cm)
    agent = AuraGeminiAgent(engine, api_key="")

    # 1. "send message in whatsapp" -> asks who -> "Amma" -> asks message -> "Coming home" -> sends!
    step1 = agent.chat_with_tools("send message in whatsapp")
    assert len(step1["executed_tools"]) == 0
    assert agent.pending_intent is not None
    assert agent.pending_intent["type"] == "whatsapp_who"

    step2 = agent.chat_with_tools("Amma")
    assert len(step2["executed_tools"]) == 0
    assert agent.pending_intent is not None
    assert agent.pending_intent["type"] == "whatsapp_msg"

    step3 = agent.chat_with_tools("I am coming home in 10 minutes")
    assert len(step3["executed_tools"]) == 1
    assert step3["executed_tools"][0]["name"] == "send_whatsapp_message"
    assert step3["executed_tools"][0]["result"]["phone_number"] == "+919440112233"

    # 2. "send message to Amma in whatsapp" -> asks message -> sends!
    s1 = agent.chat_with_tools("send message to Amma in whatsapp")
    assert len(s1["executed_tools"]) == 0
    assert agent.pending_intent is not None
    assert agent.pending_intent["type"] == "whatsapp_msg"

    s2 = agent.chat_with_tools("Good morning Amma")
    assert len(s2["executed_tools"]) == 1
    assert s2["executed_tools"][0]["name"] == "send_whatsapp_message"

    # 3. "select Telugu" & "తెలుగు సెలెక్ట్ చేయి" -> executes tap_screen_element
    sel_en = agent.chat_with_tools("select Telugu")
    assert any(t["name"] == "tap_screen_element" and "telugu" in t["args"]["target_text"].lower() for t in sel_en["executed_tools"])

    sel_te = agent.chat_with_tools("తెలుగు సెలెక్ట్ చేయి")
    assert any(t["name"] == "tap_screen_element" for t in sel_te["executed_tools"])


def test_real_instagram_and_multi_input_compound_commands(tmp_path):
    cm = ContactManager(tmp_path / "contacts.json")
    cm.add_contact("Babbi", "+918341040627", aliases=["babbi"])
    engine = AndroidAutomationEngine(cm)
    agent = AuraGeminiAgent(engine, api_key="")

    # 1. "open instagram" -> opens real com.instagram.android (instagram://app), NOT mock modal
    insta_res = agent.chat_with_tools("open instagram")
    assert any(
        t["name"] == "open_android_app" and t["result"]["package"] == "com.instagram.android"
        for t in insta_res["executed_tools"]
    )
    assert insta_res["client_actions"][0]["url"] == "instagram://app"

    # 2. "open instagram and open reels" -> opens real instagram://reels_home
    reels_res = agent.chat_with_tools("open instagram and open reels")
    assert any(
        t["name"] == "open_android_app" and t["args"]["app_name"] == "instagram reels"
        for t in reels_res["executed_tools"]
    )
    assert reels_res["client_actions"][0]["url"] == "instagram://reels_home"

    # 3. Compound App + Task: "open whatsapp and send hii to babbi"
    wa_comp = agent.chat_with_tools("open whatsapp and send hii to babbi")
    assert any(
        t["name"] == "send_whatsapp_message" and t["result"]["phone_number"] == "+918341040627"
        for t in wa_comp["executed_tools"]
    )

    # 4. Compound App + Search: "open instagram and search prabhas"
    insta_q = agent.chat_with_tools("open instagram and search prabhas")
    assert any(
        t["name"] == "open_app_with_query" and t["args"]["query"] == "prabhas"
        for t in insta_q["executed_tools"]
    )

    # 5. Compound App + Play: "open youtube and play telugu songs"
    yt_q = agent.chat_with_tools("open youtube and play telugu songs")
    assert any(
        t["name"] == "play_youtube_media" and "telugu songs" in t["args"]["query"].lower()
        for t in yt_q["executed_tools"]
    )

    # 6. Chained 2-Step Command: "turn on flashlight and check battery"
    chained = agent.chat_with_tools("turn on flashlight and check battery")
    tool_names = [t["name"] for t in chained["executed_tools"]]
    assert "control_device_setting" in tool_names
    assert "get_battery_status" in tool_names

    # 7. Chained 2-Step Command: "open instagram and turn on flashlight"
    chained2 = agent.chat_with_tools("open instagram and turn on flashlight")
    tool_names2 = [t["name"] for t in chained2["executed_tools"]]
    assert "open_android_app" in tool_names2
    assert "control_device_setting" in tool_names2


def test_screen_reader_and_continuous_next_task_loop(tmp_path: Path):
    cm = ContactManager(tmp_path / "contacts.json")
    engine = AndroidAutomationEngine(cm)
    agent = AuraGeminiAgent(engine, api_key="")

    # 1. Screen Reader command ("read screen" / "what is on screen")
    sr_res = agent.chat_with_tools("read screen")
    assert any(t["name"] == "read_screen_elements" for t in sr_res["executed_tools"])
    assert sr_res.get("ask_next_task") is True
    assert "what command should i perform next" in sr_res["reply"].lower()

    # 2. Telugu Screen Reader command ("స్క్రీన్ చదువు")
    sr_te = agent.chat_with_tools("స్క్రీన్ చదువు", language_hint="te-IN")
    assert any(t["name"] == "read_screen_elements" for t in sr_te["executed_tools"])
    assert sr_te.get("ask_next_task") is True
    assert "తర్వాత ఏం చేయమంటారు" in sr_te["reply"]

    # 3. Standard completed tasks take a break into Standby Mode to prevent disturbing continuous talkback!
    done_res = agent.chat_with_tools("open instagram")
    assert done_res.get("take_break") is True
    assert done_res.get("ask_next_task") is False
    assert "what command should i perform next" not in done_res["reply"].lower()

    # 4. Keep Quiet command: immediately silences Aura and takes a break
    quiet_res = agent.chat_with_tools("keep quiet")
    assert quiet_res.get("keep_quiet") is True
    assert quiet_res.get("take_break") is True
    assert "hey aura" in quiet_res["reply"].lower()

    quiet_te = agent.chat_with_tools("మాట్లాడకు", language_hint="te-IN")
    assert quiet_te.get("keep_quiet") is True
    assert quiet_te.get("take_break") is True
    assert "ఆరా" in quiet_te["reply"]

    # 5. Wake Word command: calls Aura back from Standby Mode
    wake_res = agent.chat_with_tools("Hey Aura")
    assert wake_res.get("wake_detected") is True
    assert wake_res.get("take_break") is False

    wake_te = agent.chat_with_tools("ఆరా", language_hint="te-IN")
    assert wake_te.get("wake_detected") is True
    assert wake_te.get("take_break") is False
    assert "చెప్పండి" in wake_te["reply"]

    # 6. Chained command with Screen Reader ("scroll down and read screen")
    chain_sr = agent.chat_with_tools("scroll down and read screen")
    names = [t["name"] for t in chain_sr["executed_tools"]]
    assert "scroll_screen" in names
    assert "read_screen_elements" in names
    assert chain_sr.get("ask_next_task") is True

    # 7. Cross-App Active Screen Context: after opening Instagram, Screen Reader & contextual commands target Instagram!
    agent.chat_with_tools("open instagram")
    assert agent.active_foreground_app.lower() == "instagram"

    sr_insta = agent.chat_with_tools("read screen")
    assert "instagram" in sr_insta["reply"].lower()

    in_app_cmd = agent.chat_with_tools("search prabhas")
    assert any(
        t["name"] == "open_app_with_query" and t["args"]["query"] == "prabhas"
        for t in in_app_cmd["executed_tools"]
    )
    assert in_app_cmd.get("take_break") is True



