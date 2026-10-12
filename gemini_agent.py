"""
Multilingual AI Brain for Aura Android Assistant (100% Python).
Supports:
1. Gemini 3.8 Flash (`gemini-3.8-flash`) via Interactions API (`client.interactions.create`)
   — 100% Free-Tier friendly, stateful multi-turn conversation & tool calling.
2. Gemini 3.8 Live (`gemini-3.8-live`) via Live WebSocket API (`client.aio.live.connect`)
   — Real-time bidirectional 16kHz/24kHz audio streaming with NON_BLOCKING tool execution.
3. Multi-Turn Conversational Slot Filling (`pending_intent`) — If you say "Call karo"
   without a name, or "WhatsApp Amma" without a message, Aura asks follow-up questions
   in your language and completes the action on your next reply.
4. Indian Speech Gap & Pause Normalizer (`normalize_indian_utterance`) — Handles pauses
   between words, split syllables ('ra hul', 'am ma', 'whats app'), fused words ('ammako'),
   mixed Indic/English scripts, and free word order ('Amma call', 'Call karo Amma ko').
"""

from __future__ import annotations

import datetime
import json
import os
import re
from typing import Any, Dict, List, Optional

from google import genai
from google.genai import types

from android_automation import AndroidAutomationEngine
from contact_manager import clean_text_for_tts, collapse_stt_gaps, transliterate_indic_to_latin
from memory_manager import MemoryManager
from workflow_manager import WorkflowManager


SYSTEM_PROMPT = """You are Aura, the user's personal Siri-like Android AI Voice & Automation Assistant.

CRITICAL MULTILINGUAL & INDIAN SPEECH RULE:
- Automatically detect the exact language, script, or dialect the owner uses (e.g., Hindi, Hinglish, Tamil, Telugu, Kannada, Malayalam, Bengali, Marathi, Spanish, French, English, etc.).
- Indian voice input often has pauses/gaps between words, split syllables (e.g., 'Ra hul ko call kar o'), or mixed script ('अम्मा को call करो'). Intelligently join split syllables and understand the full intent.
- ALWAYS reply naturally and concisely in that EXACT same language and tone, as a spoken voice assistant.
- Keep spoken responses short (1-2 sentences) and friendly so they sound natural when read aloud.

AUTOMATION CAPABILITIES:
- You have real Android automation tools to search contacts, add new contacts (`add_new_contact`), place phone calls, send WhatsApp or SMS messages, open installed Android apps, set alarms/timers, control hardware settings (volume, flashlight, Wi-Fi, Bluetooth), run multi-step routines (`run_workflow`), save personal owner facts (`remember_fact`), and read/tap elements on the phone screen.
- Whenever the owner asks to call someone, message someone, open an app, set an alarm/timer, run a routine, or change a setting, ALWAYS invoke the appropriate tool immediately.

MORAL VALUES, PRIVACY & ETHICAL CONDUCT:
- Embody core moral values: Respect for Parents & Elders ('Matru Devo Bhava, Pitru Devo Bhava'), Truthfulness ('Satyam'), Kindness ('Daya'), Humility, and strict Respect for Owner Privacy.
- Never access UPI/payment apps, never scan photos on the screen, never access Photos/Files/Camera without explicit permission, and refuse to send hurtful, abusive, or dishonest messages.
"""


def normalize_indian_utterance(raw_text: str) -> str:
    """
    Normalizes Indian Speech-to-Text output where pauses between words or accents cause:
    - Split compound words ('whats app' -> 'whatsapp', 'you tube' -> 'youtube', 'kar o' -> 'karo')
    - Split contact names ('am ma' -> 'amma', 'ra hul' -> 'rahul', 'ap pa' -> 'appa')
    - Fused postpositions ('ammako' -> 'amma ko', 'callkaro' -> 'call karo')
    - Repeated filler pauses ('...' or multiple spaces)
    """
    s = re.sub(r"[.…,;!?]+", " ", raw_text.strip())
    s = re.sub(r"\s+", " ", s).strip()

    replacements = [
        (r"\bwhats?\s+app\b", "whatsapp"),
        (r"व्हाट्स\s*ऐप|वाट्स\s*ऐप|వాట్సాప్|వాట్స్\s*యాప్|வாட்ஸ்அப்", "whatsapp"),
        (r"\byou\s+tube\b", "youtube"),
        (r"यू\s*ट्यूब|यूट्यूब|యూట్యూబ్|యూ\s*ట్యూబ్|யூடியூப்", "youtube"),
        (r"\bflash\s+light\b", "flashlight"),
        (r"\bgoogle\s+map(?:s)?\b", "google maps"),
        (r"\bkar\s+o\b", "karo"),
        (r"\blaga\s+o\b", "lagao"),
        (r"\bbhej\s+o\b", "bhejo"),
        (r"\bkhol\s+o\b", "kholo"),
        (r"\bpann\s+u\b", "pannu"),
        (r"\bche\s+y\b", "chey"),
        (r"\bpett\s+u\b", "pettu"),
        (r"\bpamp\s+u\b", "pampu"),
        (r"\bam\s+ma\b", "amma"),
        (r"\bap\s+pa\b", "appa"),
        (r"\bpa\s+pa\b", "papa"),
        (r"\bnan\s+na\b", "nanna"),
        (r"\bra\s+hul\b", "rahul"),
        (r"\bpri\s+ya\b", "priya"),
        (r"\bcall\s*karo\b", "call karo"),
        (r"\bphone\s*karo\b", "phone karo"),
        (r"\bcall\s*chey\b", "call chey"),
        (r"\bphone\s*chey\b", "phone chey"),
        (r"ఓపెన్\s*చేయి|తెరువు", "open chey"),
        (r"திற", "open pannu"),
        (r"\bcall\s+amma\b", "call amma"),
        (r"\b([a-z]{3,})(ko|ku|ki)\b", r"\1 \2"),
    ]
    for pattern, repl in replacements:
        s = re.sub(pattern, repl, s, flags=re.IGNORECASE)

    s = re.sub(r"\b([a-zA-Z])\s+(?=[a-zA-Z]\b)", r"\1", s)
    return re.sub(r"\s+", " ", s).strip()


TOOL_DEFINITIONS: List[Dict[str, Any]] = [
    {
        "name": "search_contacts",
        "description": "Searches the owner's Android contacts by name, nickname, or relationship (e.g., 'Amma', 'Mom', 'Rahul').",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The spoken name, nickname, or relationship to search for.",
                }
            },
            "required": ["query"],
        },
    },
    {
        "name": "add_new_contact",
        "description": "Saves a new contact name and phone number into the owner's contact book.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "The contact's display name.",
                },
                "phone": {
                    "type": "string",
                    "description": "The contact's phone number.",
                },
            },
            "required": ["name", "phone"],
        },
    },
    {
        "name": "make_phone_call",
        "description": "Places a direct phone call on the Android phone to a contact name or phone number.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name_or_number": {
                    "type": "string",
                    "description": "The contact name (e.g., 'Amma', 'Rahul', 'Dad') or phone number to call.",
                },
                "speakerphone": {
                    "type": "boolean",
                    "description": "Whether to turn on speakerphone during the call.",
                },
            },
            "required": ["contact_name_or_number"],
        },
    },
    {
        "name": "send_whatsapp_message",
        "description": "Sends a WhatsApp message to a contact name or phone number.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name_or_number": {
                    "type": "string",
                    "description": "The recipient contact name or phone number.",
                },
                "message": {
                    "type": "string",
                    "description": "The text message to send on WhatsApp.",
                },
                "auto_send": {
                    "type": "boolean",
                    "description": "Whether to automatically tap the Send button.",
                },
            },
            "required": ["contact_name_or_number", "message"],
        },
    },
    {
        "name": "send_sms_message",
        "description": "Sends a standard SMS text message to a contact name or phone number.",
        "parameters": {
            "type": "object",
            "properties": {
                "contact_name_or_number": {
                    "type": "string",
                    "description": "The recipient contact name or phone number.",
                },
                "message": {
                    "type": "string",
                    "description": "The SMS message body.",
                },
            },
            "required": ["contact_name_or_number", "message"],
        },
    },
    {
        "name": "open_android_app",
        "description": "Launches an installed Android application by name (e.g., YouTube, WhatsApp, Instagram, Maps, Spotify, Camera, Settings).",
        "parameters": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "Name of the Android app to open.",
                }
            },
            "required": ["app_name"],
        },
    },
    {
        "name": "set_alarm_or_timer",
        "description": "Sets an Android system alarm (at hour:minute) or countdown timer (in seconds).",
        "parameters": {
            "type": "object",
            "properties": {
                "mode": {
                    "type": "string",
                    "enum": ["alarm", "timer"],
                    "description": "Whether to set an 'alarm' or a 'timer'.",
                },
                "hour": {
                    "type": "integer",
                    "description": "Hour in 24-hour format (0-23) for an alarm.",
                },
                "minute": {
                    "type": "integer",
                    "description": "Minute (0-59) for an alarm.",
                },
                "duration_seconds": {
                    "type": "integer",
                    "description": "Countdown duration in seconds for a timer.",
                },
                "label": {
                    "type": "string",
                    "description": "Label or title for the alarm/timer.",
                },
            },
            "required": ["mode"],
        },
    },
    {
        "name": "control_device_setting",
        "description": "Controls Android hardware or navigation settings such as volume, flashlight, Wi-Fi, Bluetooth, Home, Back, or Notifications.",
        "parameters": {
            "type": "object",
            "properties": {
                "setting": {
                    "type": "string",
                    "enum": [
                        "volume_up",
                        "volume_down",
                        "mute",
                        "flashlight",
                        "wifi",
                        "bluetooth",
                        "home",
                        "back",
                        "notifications",
                        "media_play_pause",
                    ],
                    "description": "The hardware or system setting to control.",
                },
                "action": {
                    "type": "string",
                    "description": "'on', 'off', or 'toggle'.",
                },
            },
            "required": ["setting"],
        },
    },
    {
        "name": "run_workflow",
        "description": "Executes a multi-step automation routine (e.g., 'Heading Home', 'Good Night', 'Workout Mode').",
        "parameters": {
            "type": "object",
            "properties": {
                "workflow_name_or_trigger": {
                    "type": "string",
                    "description": "Name or trigger phrase of the routine to run.",
                }
            },
            "required": ["workflow_name_or_trigger"],
        },
    },
    {
        "name": "remember_fact",
        "description": "Saves a personal fact, note, or preference about the phone owner into long-term memory.",
        "parameters": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Short topic key (e.g., 'owner_name', 'favorite_music', 'home_address').",
                },
                "value": {
                    "type": "string",
                    "description": "The fact or preference to remember.",
                },
            },
            "required": ["key", "value"],
        },
    },
    {
        "name": "read_screen_elements",
        "description": "Reads all visible text and clickable buttons currently displayed on the Android phone screen.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "tap_screen_element",
        "description": "Taps a button or UI element on the Android screen matching the specified text.",
        "parameters": {
            "type": "object",
            "properties": {
                "target_text": {
                    "type": "string",
                    "description": "The visible text or label of the button to tap on screen.",
                }
            },
            "required": ["target_text"],
        },
    },
    {
        "name": "play_youtube_media",
        "description": "Searches and plays a song, music artist, or video on YouTube.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The song, artist, or video query to play on YouTube.",
                }
            },
            "required": ["query"],
        },
    },
    {
        "name": "navigate_to_location",
        "description": "Starts Google Maps turn-by-turn navigation to a destination place or address.",
        "parameters": {
            "type": "object",
            "properties": {
                "destination": {
                    "type": "string",
                    "description": "The destination place, landmark, or address.",
                }
            },
            "required": ["destination"],
        },
    },
    {
        "name": "get_battery_status",
        "description": "Checks the Android phone's current battery percentage and charging status.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "search_google_web",
        "description": "Searches Google Web on the Android phone for any topic, news, or weather query.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The web search query.",
                }
            },
            "required": ["query"],
        },
    },
    {
        "name": "calculate_math",
        "description": "Evaluates a mathematical arithmetic or percentage expression.",
        "parameters": {
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "description": "The math expression (e.g., '15 percent of 2400', '125 * 18').",
                }
            },
            "required": ["expression"],
        },
    },
    {
        "name": "scroll_screen",
        "description": "Scrolls or swipes the Android screen hands-free ('down', 'up', 'left', 'right').",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["down", "up", "left", "right"],
                    "description": "Direction to scroll.",
                }
            },
            "required": ["direction"],
        },
    },
    {
        "name": "take_screenshot",
        "description": "Captures a screenshot of the current Android phone screen.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "take_camera_photo",
        "description": "Opens the Android camera and snaps a photo or selfie hands-free.",
        "parameters": {
            "type": "object",
            "properties": {
                "mode": {
                    "type": "string",
                    "enum": ["photo", "selfie"],
                    "description": "'photo' for rear camera or 'selfie' for front camera.",
                }
            },
        },
    },
    {
        "name": "control_media_playback",
        "description": "Controls music or video playback ('play', 'pause', 'next', 'previous').",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["play", "pause", "next", "previous"],
                    "description": "Playback command to execute.",
                }
            },
            "required": ["command"],
        },
    },
    {
        "name": "trigger_emergency_sos",
        "description": "Triggers an Emergency SOS call on speakerphone and WhatsApp SOS alert to the primary family contact.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
]


LOCALIZED_REPLIES: Dict[str, Dict[str, str]] = {
    "hi": {
        "calling": "ठीक है, {name} ({phone}) को कॉल लगा रही हूँ।",
        "ask_call_who": "आप किसको कॉल लगाना चाहते हैं? कृपया नाम बताएं।",
        "ask_wa_msg": "{name} को व्हाट्सऐप पर क्या संदेश भेजना है?",
        "contact_saved": "नया संपर्क सेव कर लिया गया है: {name} ({phone})।",
        "whatsapp": "{name} को व्हाट्सऐप संदेश भेज रही हूँ: \"{msg}\"",
        "sms": "{name} को एसएमएस भेज रही हूँ: \"{msg}\"",
        "open_app": "{app} खोल रही हूँ।",
        "youtube": "यूट्यूब पर '{query}' चला रही हूँ।",
        "navigate": "गूगल मैप्स में '{dest}' का रास्ता खोल रही हूँ।",
        "battery": "आपके फोन की बैटरी अभी {level}% है।",
        "web_search": "गूगल पर '{query}' खोज रही हूँ।",
        "calc": "{expr} का उत्तर {result} है।",
        "recalled": "मुझे याद है: {key} = {value}।",
        "scroll": "स्क्रीन {direction} स्क्रॉल कर दिया है।",
        "screenshot": "आपकी गोपनीयता (Privacy) के लिए स्क्रीन पर मौजूद किसी भी फोटो या इमेज को स्कैन या कैप्चर नहीं किया जा सकता।",
        "ask_permission": "आपकी गोपनीयता के लिए '{target}' एक्सेस करने से पहले आपकी अनुमति आवश्यक है। अनुमति देने के लिए 'हाँ' या 'Allow' कहें।",
        "permission_denied": "ठीक है, आपकी अनुमति के बिना '{target}' एक्सेस नहीं किया गया।",
        "photo": "आपकी अनुमति से कैमरा खोलकर फोटो खींच रही हूँ।",
        "privacy_upi": "आपकी गोपनीयता और सुरक्षा के लिए यूपीआई (UPI) और पेमेंट ऐप्स को ऑरा कभी एक्सेस नहीं करती है।",
        "moral_value": "आज का नैतिक मूल्य (Moral Value): माता-पिता और बड़ों का आदर करना, सत्य बोलना और सबके प्रति दया रखना ही सबसे बड़ा धर्म है। 'मातृ देवो भव, पितृ देवो भव'।",
        "moral_guard": "नैतिक मूल्यों (Moral Values) के कारण ऑरा किसी को ठेस पहुँचाने वाले या अपमानजनक संदेश नहीं भेजती है। कृपया सम्मान और प्रेम से बात करें।",
        "media": "मीडिया कंट्रोल: {detail} कर दिया गया है।",
        "sos": "आपातकालीन एसओएस (SOS) सक्रिय! {name} को तुरंत कॉल और संदेश भेजा जा रहा है।",
        "alarm": "आपका अलार्म सेट कर दिया गया है: {detail}",
        "timer": "{detail} का टाइमर शुरू कर दिया गया है।",
        "setting": "{detail} कर दिया गया है।",
        "time": "अभी समय {time} है।",
        "remembered": "मैंने याद रख लिया है: {key} = {value}।",
        "not_found": "क्षमा करें, मुझे '{query}' नाम का कोई संपर्क नहीं मिला।",
    },
    "ta": {
        "calling": "சரி, {name} ({phone}) எண்ணிற்கு அழைக்கிறேன்.",
        "ask_call_who": "யாரை அழைக்க வேண்டும்? பெயரைச் சொல்லுங்கள்.",
        "ask_wa_msg": "{name}-க்கு என்ன வாட்ஸ்அப் செய்தி அனுப்ப வேண்டும்?",
        "contact_saved": "புதிய தொடர்பு சேமிக்கப்பட்டது: {name} ({phone}).",
        "whatsapp": "{name}-க்கு வாட்ஸ்அப் செய்தி அனுப்புகிறேன்: \"{msg}\"",
        "sms": "{name}-க்கு குறுஞ்செய்தி அனுப்புகிறேன்: \"{msg}\"",
        "open_app": "{app} செயலியைத் திறக்கிறேன்.",
        "youtube": "யூடியூபில் '{query}' இயக்குகிறேன்.",
        "navigate": "கூகுள் மேப்ஸில் '{dest}' வழியைக் காட்டுகிறேன்.",
        "battery": "உங்கள் போன் பேட்டரி இப்போது {level}% உள்ளது.",
        "web_search": "கூகுளில் '{query}' தேடுகிறேன்.",
        "calc": "{expr}-ன் விடை {result}.",
        "recalled": "எனக்கு நினைவிருக்கிறது: {key} = {value}.",
        "scroll": "திரையை {direction} நகர்த்தியுள்ளேன்.",
        "screenshot": "உங்கள் தனியுரிமைக்காக திரையில் உள்ள புகைப்படங்களை ஸ்கேன் செய்யவோ படம் எடுக்கவோ முடியாது.",
        "ask_permission": "உங்கள் தனியுரிமைக்காக '{target}' அணுக உங்கள் அனுமதி தேவை. அனுமதிக்க 'Yes' அல்லது 'Allow' என்று சொல்லுங்கள்.",
        "permission_denied": "சரி, உங்கள் அனுமதியின்றி '{target}' அணுகப்படவில்லை.",
        "photo": "உங்கள் அனுமதியுடன் கேமராவைத் திறந்து புகைப்படம் எடுக்கிறேன்.",
        "privacy_upi": "உங்கள் தனியுரிமை மற்றும் பாதுகாப்பிற்காக UPI மற்றும் கட்டண செயலிகளை ஆரா அணுகாது.",
        "moral_value": "இன்றைய நீதி மொழி (Moral Value): தாய் தந்தையரைப் போற்றுவதும், உண்மை பேசுவதும், பிறருக்கு அன்புடன் உதவுவதுமே சிறந்த அறம்.",
        "moral_guard": "நன்னெறி விதிகளின்படி பிறரைப் புண்படுத்தும் செய்திகளை ஆரா அனுப்பாது. அன்புடன் பேசுவோம்.",
        "media": "மீடியா கட்டுப்பாடு: {detail}.",
        "sos": "அவசர உதவி (SOS)! {name}-க்கு உடனடியாக அழைப்பும் செய்தியும் அனுப்பப்படுகிறது.",
        "alarm": "அலாரம் அமைக்கப்பட்டது: {detail}",
        "timer": "டைமர் தொடங்கப்பட்டது: {detail}",
        "setting": "{detail} மாற்றப்பட்டது.",
        "time": "இப்போது நேரம் {time}.",
        "remembered": "நினைவில் வைத்துக்கொண்டேன்: {key} = {value}.",
        "not_found": "மன்னிக்கவும், '{query}' என்ற தொடர்பு கிடைக்கவில்லை.",
    },
    "te": {
        "calling": "సరే, {name} ({phone}) కి కాల్ చేస్తున్నాను.",
        "ask_call_who": "ఎవరికి కాల్ చేయాలి? దయచేసి పేరు చెప్పండి.",
        "ask_wa_msg": "{name} కి వాట్సాప్ లో ఏమి మెసేజ్ పంపాలి?",
        "contact_saved": "కొత్త కాంటాక్ట్ సేవ్ చేయబడింది: {name} ({phone}).",
        "whatsapp": "{name} కి వాట్సాప్ మెసేజ్ పంపుతున్నాను: \"{msg}\"",
        "sms": "{name} కి SMS పంపుతున్నాను: \"{msg}\"",
        "open_app": "{app} తెరుస్తున్నాను.",
        "youtube": "యూట్యూబ్ లో '{query}' ప్లే చేస్తున్నాను.",
        "navigate": "గూగుల్ మ్యాప్స్ లో '{dest}' కి దారి చూపిస్తున్నాను.",
        "battery": "మీ ఫోన్ బ్యాటరీ ఇప్పుడు {level} శాతం ఉంది.",
        "web_search": "గూగుల్ లో '{query}' కోసం వెతుకుతున్నాను.",
        "calc": "{expr} జవాబు {result}.",
        "recalled": "నాకు గుర్తుంది: {key} = {value}.",
        "scroll": "స్క్రీన్ {direction} కి స్క్రోల్ చేశాను.",
        "screenshot": "మీ గోప్యత (Privacy) కోసం స్క్రీన్ పై ఉన్న ఫోటోలను స్కాన్ చేయడం లేదా స్క్రీన్ షాట్ తీయడం నిలిపివేయబడింది.",
        "ask_permission": "మీ గోప్యత కోసం '{target}' యాక్సెస్ చేయడానికి మీ అనుమతి (Permission) కావాలి. అనుమతించడానికి 'అవును' లేదా 'Allow' అని చెప్పండి.",
        "permission_denied": "సరే, మీ అనుమతి లేకుండా '{target}' యాక్సెస్ చేయలేదు.",
        "photo": "మీ అనుమతితో కెమెరా తెరిచి ఫోటో తీస్తున్నాను.",
        "privacy_upi": "మీ గోప్యత (Privacy) మరియు భద్రత కోసం యూపీఐ (UPI) మరియు పేమెంట్ యాప్స్ ని ఆరా ఓపెన్ చేయదు.",
        "moral_value": "నేటి మంచి మాట (Moral Value): తల్లిదండ్రులను, పెద్దలను గౌరవించడం, సత్యం మాట్లాడటం, మరియు ఇతరులకు సహాయం చేయడం నిజమైన మానవత్వం. 'మాతృ దేవో భవ, పితృ దేవో భవ'.",
        "moral_guard": "నైతిక విలువలు (Moral Values) ప్రకారం ఇతరులను బాధపెట్టే లేదా అసభ్యకరమైన సందేశాలను ఆరా పంపదు. దయతో మాట్లాడుకుందాం.",
        "media": "మీడియా కంట్రోల్: {detail} చేశాను.",
        "sos": "అత్యవసర సహాయం (SOS)! వెంటనే {name} కి కాల్ మరియు మెసేజ్ పంపుతున్నాను.",
        "alarm": "అలారం సెట్ చేయబడింది: {detail}",
        "timer": "టైమర్ ప్రారంభించబడింది: {detail}",
        "setting": "{detail} మార్చబడింది.",
        "time": "ఇప్పుడు సమయం {time}.",
        "remembered": "గుర్తుంచుకున్నాను: {key} = {value}.",
        "not_found": "క్షమించండి, '{query}' అనే కాంటాక్ట్ దొరకలేదు.",
    },
    "es": {
        "calling": "Llamando a {name} ({phone}).",
        "ask_call_who": "¿A quién te gustaría llamar?",
        "ask_wa_msg": "¿Qué mensaje quieres enviar por WhatsApp a {name}?",
        "contact_saved": "Contacto guardado: {name} ({phone}).",
        "whatsapp": "Enviando WhatsApp a {name}: \"{msg}\"",
        "sms": "Enviando SMS a {name}: \"{msg}\"",
        "open_app": "Abriendo {app}.",
        "youtube": "Reproduciendo '{query}' en YouTube.",
        "navigate": "Iniciando navegación hacia '{dest}' en Google Maps.",
        "battery": "La batería de tu teléfono está al {level}%.",
        "web_search": "Buscando '{query}' en Google.",
        "calc": "El resultado de {expr} es {result}.",
        "recalled": "Recuerdo que {key} es {value}.",
        "scroll": "Desplazando la pantalla hacia {direction}.",
        "screenshot": "Por privacidad, las fotos en pantalla no se pueden escanear ni capturar.",
        "ask_permission": "Por tu privacidad, necesito permiso antes de acceder a '{target}'. Di 'Sí' o 'Allow' para confirmar.",
        "permission_denied": "De acuerdo, no se accedió a '{target}' sin tu permiso.",
        "photo": "Abriendo la cámara con tu permiso y tomando una foto.",
        "privacy_upi": "Por razones de privacidad y seguridad, Aura nunca accede a aplicaciones de pago o UPI.",
        "moral_value": "Valor Moral: Honra a tus padres y mayores, habla siempre con la verdad y trata a todos con empatía y bondad.",
        "moral_guard": "Guiada por valores morales, Aura no envía mensajes ofensivos ni dañinos.",
        "media": "Control multimedia: {detail}.",
        "sos": "¡Alerta SOS de emergencia activada! Llamando a {name}.",
        "alarm": "Alarma configurada: {detail}",
        "timer": "Temporizador iniciado: {detail}",
        "setting": "Ajuste actualizado: {detail}",
        "time": "Son las {time}.",
        "remembered": "He recordado que {key} es {value}.",
        "not_found": "No encontré ningún contacto llamado '{query}'.",
    },
    "en": {
        "calling": "Calling {name} at {phone}.",
        "ask_call_who": "Who would you like me to call? Just say their name.",
        "ask_wa_msg": "What message should I send to {name} on WhatsApp?",
        "contact_saved": "Saved new contact: {name} ({phone}).",
        "whatsapp": "Opening WhatsApp to message {name}: \"{msg}\".",
        "sms": "Sending SMS to {name}: \"{msg}\".",
        "open_app": "Opening {app}.",
        "youtube": "Playing '{query}' on YouTube.",
        "navigate": "Starting Google Maps navigation to {dest}.",
        "battery": "Your phone battery is currently at {level}%.",
        "web_search": "Searching Google for '{query}'.",
        "calc": "The answer to {expr} is {result}.",
        "recalled": "I remember that your {key} is {value}.",
        "scroll": "Scrolled the screen {direction}.",
        "screenshot": "For your privacy, photos and images present on the screen cannot be scanned or captured.",
        "ask_permission": "For your privacy, I need your permission before accessing '{target}'. Say 'Yes' or 'Allow' to confirm.",
        "permission_denied": "Okay, I did not access '{target}' without your permission.",
        "photo": "With your permission, opened camera and captured a photo.",
        "privacy_upi": "For your privacy and security, Aura never accesses UPI or payment apps.",
        "moral_value": "Moral Value of the Day: Respect your parents and elders ('Matru Devo Bhava, Pitru Devo Bhava'), always speak the truth with kindness, and help those in need.",
        "moral_guard": "Guided by moral values and respect, Aura does not send hurtful, abusive, or dishonest messages. Let's always communicate with kindness.",
        "media": "Media playback: {detail}.",
        "sos": "Emergency SOS activated! Calling and alerting {name} immediately.",
        "alarm": "Alarm set: {detail}.",
        "timer": "Timer started: {detail}.",
        "setting": "Updated device setting: {detail}.",
        "time": "The current time is {time}.",
        "remembered": "Got it! I'll remember that {key} is {value}.",
        "not_found": "I couldn't find a contact matching '{query}'.",
    },
}


def detect_owner_language(text: str, language_hint: Optional[str] = None) -> str:
    """Detects language family ('hi', 'ta', 'te', 'es', 'en') from script, keywords, or UI hint."""
    lower = text.lower()
    if re.search(r"[\u0900-\u097F]", text):
        return "hi"
    if re.search(r"[\u0B80-\u0BFF]", text):
        return "ta"
    if re.search(r"[\u0C00-\u0C7F]", text):
        return "te"

    if any(
        re.search(rf"\b{w}\b", lower)
        for w in [
            "ko", "karo", "bhejo", "kholo", "lagao", "baje", "kya", "hai",
            "namaste", "ghar", "ratri", "yaad", "mila", "milao",
        ]
    ):
        return "hi"
    if any(
        re.search(rf"\b{w}\b", lower)
        for w in ["ku", "pannu", "pannunga", "unga", "vanakkam", "thira"]
    ):
        return "ta"
    if any(
        re.search(rf"\b{w}\b", lower)
        for w in [
            "ki", "chey", "cheyandi", "andi", "namaskaram", "teruvu", "pettu",
            "pampu", "entha", "ippudu", "vastunna", "vastunnanu", "nanna",
            "garu", "penchu", "tagginchu", "aapu",
        ]
    ):
        return "te"
    if any(re.search(rf"\b{w}\b", lower) for w in ["llama", "llamar", "abre", "enviar", "hola"]):
        return "es"

    if language_hint:
        prefix = language_hint.split("-")[0].lower()
        if prefix in LOCALIZED_REPLIES:
            return prefix
    return "en"


def has_call_intent(text: str) -> bool:
    lower = text.lower().strip()
    call_indicators = [
        "call", "phone", "dial", "ring",
        "कॉल", "फोन", "फ़ोन", "मिलाओ", "लगाओ",
        "கால்", "போன்", "அழை",
        "కాల్", "ఫోన్",
        "llama", "llamar",
    ]
    if not any(ind in lower for ind in call_indicators):
        return False
    if "whatsapp" in lower and "call" not in lower and "कॉल" not in lower and "కాల్" not in lower:
        return False
    return True


def extract_call_target_flexible(text: str) -> Optional[str]:
    """
    Flexibly extracts the contact name from paused or mixed-order Indian speech.
    Returns None if no call intent, or "" (empty string) if call intent was spoken without a target name!
    """
    if not has_call_intent(text):
        return None

    lower = text.lower().strip()
    noise_words = [
        r"\bplease\b", r"\bplz\b", r"\bzara\b", r"\bजरा\b", r"\bथोड़ा\b",
        r"\bmake\s+a\b", r"\ba\b", r"\bto\b", r"\bfor\b", r"\bsomeone\b", r"\bkisi\b",
        r"\bcall\b", r"\bphone\b", r"\bdial\b", r"\bring\b",
        r"\bkaro\b", r"\bkarna\b", r"\bkar\b", r"\bdo\b", r"\blagao\b", r"\blaga\b", r"\bmilao\b", r"\bmila\b",
        r"\bpannu\b", r"\bpannunga\b", r"\bchey\b", r"\bcheyandi\b", r"\bpettu\b",
        r"\bllama\b", r"\bllamar\b",
        r"\bko\b", r"\bku\b", r"\bki\b", r"\bse\b", r"\bpe\b", r"\bpar\b",
        "कॉल", "फोन", "फ़ोन", "करो", "लगाओ", "लगा", "दो", "मिलाओ", "मिला", "को", "से", "पर", "जरा", "किसी",
        "கால்", "போன்", "பண்ணு", "கு",
        "కాల్", "ఫోన్", "చేయి", "చేయండి", "పెట్టు", "కి", "కు",
    ]
    cleaned = lower
    for nw in noise_words:
        cleaned = re.sub(nw, " ", cleaned, flags=re.IGNORECASE)

    cleaned = re.sub(r"[^\w\s\u0900-\u097F\u0B80-\u0BFF\u0C00-\u0C7F+]", " ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def get_interactions_tools() -> List[Dict[str, Any]]:
    """Returns tool declarations formatted for `client.interactions.create` (`gemini-3.8-flash`)."""
    return [
        {
            "type": "function",
            "name": t["name"],
            "description": t["description"],
            "parameters": t["parameters"],
        }
        for t in TOOL_DEFINITIONS
    ]


def get_live_api_tools() -> List[types.Tool]:
    """Returns NON_BLOCKING tool declarations formatted for `gemini-3.8-live` WebSocket sessions."""
    declarations = [
        types.FunctionDeclaration(
            name=t["name"],
            description=t["description"],
            behavior="NON_BLOCKING",
            parameters=t["parameters"],
        )
        for t in TOOL_DEFINITIONS
    ]
    return [types.Tool(function_declarations=declarations)]


class AuraGeminiAgent:
    def __init__(
        self,
        automation: AndroidAutomationEngine,
        api_key: Optional[str] = None,
        workflow_manager: Optional[WorkflowManager] = None,
        memory_manager: Optional[MemoryManager] = None,
    ):
        self.automation = automation
        self.workflows = workflow_manager or WorkflowManager(automation)
        self.memory = memory_manager or MemoryManager()
        self.api_key = api_key or os.environ.get("GEMINI_API_KEY", "").strip()
        self.previous_interaction_id: Optional[str] = None
        self.preferred_language: str = "auto"
        # Stores multi-turn slot-filling state when the owner says an incomplete command
        self.pending_intent: Optional[Dict[str, Any]] = None

    def set_api_key(self, api_key: str) -> None:
        self.api_key = api_key.strip()
        os.environ["GEMINI_API_KEY"] = self.api_key
        self.previous_interaction_id = None

    @staticmethod
    def _finalize_response(payload: Dict[str, Any]) -> Dict[str, Any]:
        """Attaches clean spoken_reply (no raw phone digits) and spoken_phonetic (Romanized Indic fallback)."""
        reply_text = payload.get("reply", "")
        spoken = clean_text_for_tts(reply_text)
        phonetic = clean_text_for_tts(transliterate_indic_to_latin(spoken))
        phonetic = re.sub(r"\s+", " ", phonetic).strip()
        payload["spoken_reply"] = spoken
        payload["spoken_phonetic"] = phonetic
        return payload

    def execute_tool(self, name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """Routes a Gemini function_call to AndroidAutomationEngine, WorkflowManager, or MemoryManager."""
        if name == "run_workflow":
            return self.workflows.execute_workflow(args.get("workflow_name_or_trigger", ""))
        if name == "remember_fact":
            return self.memory.remember_fact(args.get("key", "note"), args.get("value", ""))
        if name == "add_new_contact":
            c = self.automation.contacts.add_contact(
                name=args.get("name", ""),
                phone=args.get("phone", ""),
            )
            return {"status": "ok", "contact": c.to_dict()}

        fn = getattr(self.automation, name, None)
        if not callable(fn):
            return {"status": "error", "message": f"Unknown tool: {name}"}
        try:
            return fn(**args)
        except Exception as exc:
            return {"status": "error", "message": str(exc)}

    def _build_system_instruction(self, language_hint: Optional[str] = None) -> str:
        """
        PRIVACY GUARANTEE: Never attaches local contacts, photos, files, or personal memory dumps
        to any external API system prompt. Only the requested command is sent.
        """
        hint = language_hint or self.preferred_language
        if hint and hint != "auto":
            return f"{SYSTEM_PROMPT}\nOwner's active language setting: {hint}. Always reply in the language the owner speaks."
        return SYSTEM_PROMPT

    def chat_with_tools(self, user_text: str, language_hint: Optional[str] = None) -> Dict[str, Any]:
        """
        Normalizes Indian speech gaps/pauses first, then processes via `gemini-3.8-flash`
        or the local multilingual smart engine.
        """
        normalized_input = normalize_indian_utterance(user_text)
        cleaned_input = re.sub(
            r"^(?:hey\s+aura|ok\s+aura|hi\s+aura|aura|हे\s+ऑरा|ऑरा|ఆరా|ஆரா)[\s,.:;-]*",
            "",
            normalized_input,
            flags=re.IGNORECASE,
        ).strip()
        if not cleaned_input:
            cleaned_input = normalized_input

        if not self.api_key:
            return self._offline_multilingual_fallback(cleaned_input, language_hint=language_hint)

        client = genai.Client(api_key=self.api_key)
        tools = get_interactions_tools()
        sys_instruction = self._build_system_instruction(language_hint)

        executed_tools: List[Dict[str, Any]] = []
        client_actions: List[Dict[str, Any]] = []

        try:
            create_kwargs: Dict[str, Any] = {
                "model": "gemini-3.8-flash",
                "input": cleaned_input,
                "system_instruction": sys_instruction,
                "tools": tools,
            }
            if self.previous_interaction_id:
                create_kwargs["previous_interaction_id"] = self.previous_interaction_id

            interaction = client.interactions.create(**create_kwargs)
            self.previous_interaction_id = interaction.id

            max_rounds = 4
            rounds = 0
            while rounds < max_rounds:
                rounds += 1
                fc_steps = [s for s in (interaction.steps or []) if getattr(s, "type", None) == "function_call"]
                if not fc_steps:
                    break

                result_inputs = []
                for fc in fc_steps:
                    fn_name = fc.name
                    fn_args = fc.arguments if isinstance(fc.arguments, dict) else {}
                    tool_output = self.execute_tool(fn_name, fn_args)

                    executed_tools.append({
                        "name": fn_name,
                        "args": fn_args,
                        "result": tool_output,
                    })
                    if isinstance(tool_output, dict):
                        if tool_output.get("client_action"):
                            client_actions.append(tool_output["client_action"])
                        if tool_output.get("client_actions"):
                            client_actions.extend(tool_output["client_actions"])

                    result_inputs.append({
                        "type": "function_result",
                        "name": fn_name,
                        "call_id": fc.id,
                        "result": [{"type": "text", "text": json.dumps(tool_output, ensure_ascii=False)}],
                    })

                interaction = client.interactions.create(
                    model="gemini-3.8-flash",
                    input=result_inputs,
                    system_instruction=sys_instruction,
                    tools=tools,
                    previous_interaction_id=interaction.id,
                )
                self.previous_interaction_id = interaction.id

            reply_text = interaction.output_text or "Done."
            det_lang = detect_owner_language(reply_text, language_hint)
            return self._finalize_response({
                "status": "ok",
                "mode": "gemini-3.8-flash",
                "normalized_input": cleaned_input,
                "detected_language": det_lang,
                "reply": reply_text,
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })
        except Exception as exc:
            fallback = self._offline_multilingual_fallback(cleaned_input, language_hint=language_hint)
            fallback["warning"] = f"Gemini API notice: {exc}"
            return fallback

    def _offline_multilingual_fallback(self, user_text: str, language_hint: Optional[str] = None) -> Dict[str, Any]:
        """
        Comprehensive local multilingual intent & automation parser resilient to word gaps,
        plus multi-turn conversational slot filling (`pending_intent`).
        """
        text = normalize_indian_utterance(user_text)
        lower = text.lower()
        lang = detect_owner_language(text, language_hint)
        tpl = LOCALIZED_REPLIES.get(lang, LOCALIZED_REPLIES["en"])

        executed_tools: List[Dict[str, Any]] = []
        client_actions: List[Dict[str, Any]] = []

        # ---------------------------------------------------------------------
        # MULTI-TURN SLOT FILLING: Check if Aura asked a follow-up question on the previous turn
        # ---------------------------------------------------------------------
        if self.pending_intent:
            pending = self.pending_intent
            self.pending_intent = None
            p_lang = pending.get("lang", lang)
            p_tpl = LOCALIZED_REPLIES.get(p_lang, tpl)

            if pending["type"] == "confirm_permission":
                granted = any(
                    w in lower
                    for w in ["yes", "allow", "grant", "ok", "sure", "avunu", "sare", "haan", "ha", "అవును", "సరే", "हाँ", "ஆம்", "sí"]
                )
                target_label = pending.get("target", "Camera / Files")
                if not granted:
                    return self._finalize_response({
                        "status": "ok",
                        "mode": "offline_smart_engine",
                        "normalized_input": text,
                        "detected_language": p_lang,
                        "reply": p_tpl["permission_denied"].format(target=target_label),
                        "executed_tools": [],
                        "client_actions": [],
                    })
                if pending.get("action") == "camera":
                    cam_mode = pending.get("mode", "photo")
                    res = self.automation.take_camera_photo(mode=cam_mode, permission_granted=True)
                    executed_tools.append({"name": "take_camera_photo", "args": {"mode": cam_mode, "permission_granted": True}, "result": res})
                    return self._finalize_response({
                        "status": "ok",
                        "mode": "offline_smart_engine",
                        "normalized_input": text,
                        "detected_language": p_lang,
                        "reply": p_tpl["photo"],
                        "executed_tools": executed_tools,
                        "client_actions": client_actions,
                    })
                if pending.get("action") == "open_app":
                    app_name = pending.get("app_name", target_label)
                    res = self.automation.open_android_app(app_name, permission_granted=True)
                    executed_tools.append({"name": "open_android_app", "args": {"app_name": app_name, "permission_granted": True}, "result": res})
                    if res.get("client_action"):
                        client_actions.append(res["client_action"])
                    return self._finalize_response({
                        "status": "ok",
                        "mode": "offline_smart_engine",
                        "normalized_input": text,
                        "detected_language": p_lang,
                        "reply": p_tpl["open_app"].format(app=app_name.title()),
                        "executed_tools": executed_tools,
                        "client_actions": client_actions,
                    })

            if pending["type"] == "call_who":
                res = self.automation.make_phone_call(text, speakerphone=pending.get("speaker", False))
                executed_tools.append({
                    "name": "make_phone_call",
                    "args": {"contact_name_or_number": text, "speakerphone": pending.get("speaker", False)},
                    "result": res,
                })
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                reply = (
                    p_tpl["calling"].format(name=res["contact_name"], phone=res["phone_number"])
                    if res["status"] == "calling"
                    else p_tpl["not_found"].format(query=text)
                )
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": p_lang,
                    "reply": reply,
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

            if pending["type"] == "whatsapp_msg":
                target_name = pending["target"]
                res = self.automation.send_whatsapp_message(target_name, text)
                executed_tools.append({
                    "name": "send_whatsapp_message",
                    "args": {"contact_name_or_number": target_name, "message": text},
                    "result": res,
                })
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                reply = p_tpl["whatsapp"].format(name=res.get("contact_name", target_name), msg=text)
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": p_lang,
                    "reply": reply,
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 0a. Strict Privacy Guardrail: Never touch UPI or payment apps (PhonePe, GPay, Paytm, BHIM)
        if any(
            w in lower
            for w in [
                "upi",
                "qr scan",
                "scan qr",
                "scan and pay",
                "gpay",
                "google pay",
                "phonepe",
                "phone pe",
                "paytm",
                "bhim",
                "క్యూఆర్",
                "యూపీఐ",
                "यूपीआई",
                "क्यूआर",
            ]
        ):
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["privacy_upi"],
                "executed_tools": [],
                "client_actions": [],
            })

        # 0a-2. Moral Values & Ethical Conduct Guardrail (Refuse abusive/dishonest/harmful messages)
        if any(
            re.search(rf"\b{w}\b", lower)
            for w in ["abuse", "insult", "threaten", "scam", "cheat", "idiot", "stupid", "fool", "gaali", "boothulu"]
        ):
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["moral_guard"],
                "executed_tools": [],
                "client_actions": [],
            })

        # 0a-3. Spoken Moral Values, Ethics & Cultural Wisdom ("manchi maata cheppu", "moral value", "suvichar", "neethi vakyam")
        if any(
            w in lower
            for w in [
                "moral value",
                "moral story",
                "tell me a moral",
                "good thought",
                "manchi maata",
                "manchi mata",
                "neethi vakyam",
                "neethi",
                "suvichar",
                "naitik",
                "sanskar",
                "thirukkural",
                "మంచి మాట",
                "నీతి వాక్యం",
                "सुविचार",
                "नैतिक मूल्य",
                "திருக்குறள்",
            ]
        ):
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["moral_value"],
                "executed_tools": [],
                "client_actions": [],
            })

        # 0. Check Multi-Step Workflows / Routines
        matched_wf = self.workflows.match_workflow(lower)
        if matched_wf:
            wf_res = self.workflows.execute_workflow(matched_wf["id"], lang=lang)
            executed_tools.extend(wf_res.get("executed_steps", []))
            client_actions.extend(wf_res.get("client_actions", []))
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": wf_res["reply"],
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 0b. Voice Contact Saving ("save contact Rohit 9876543210", "add contact Neha 9988776655")
        save_contact_match = re.search(
            r"(?:save|add)\s+contact\s+([a-zA-Z\s]+?)\s+(\+?\d[\d\s-]{6,})",
            text,
            flags=re.IGNORECASE,
        )
        if save_contact_match:
            c_name = save_contact_match.group(1).strip()
            c_phone = re.sub(r"[^\d+]", "", save_contact_match.group(2))
            saved = self.automation.contacts.add_contact(name=c_name, phone=c_phone)
            executed_tools.append({
                "name": "add_new_contact",
                "args": {"name": c_name, "phone": c_phone},
                "result": saved.to_dict(),
            })
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["contact_saved"].format(name=saved.name, phone=saved.phone),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 0c. Check Owner Memory ("remember that my name is X", "yaad rakhna mera naam X hai", "naa peru X ani gurthupettuko")
        mem_patterns = [
            r"^remember(?:\s+that)?\s+my\s+([a-z0-9\s]+?)\s+is\s+(.+)$",
            r"^yaad\s+rakhna\s+mera\s+([a-z0-9\s]+?)\s+(.+?)\s+hai$",
            r"^naa\s+([a-z0-9\s]+?)\s+(.+?)\s+ani\s+gurthupettuko$",
        ]
        for pat in mem_patterns:
            mm = re.search(pat, lower)
            if mm:
                k, v = mm.group(1).strip(), mm.group(2).strip()
                if k in {"naam", "peru"}:
                    k = "name"
                res = self.memory.remember_fact(k, v)
                executed_tools.append({"name": "remember_fact", "args": {"key": k, "value": v}, "result": res})
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["remembered"].format(key=k, value=v),
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 0d. Memory Recall ("what is my name", "mera naam kya hai", "naa peru enti")
        recall_match = re.search(
            r"^(?:what\s+is\s+my\s+([a-z0-9_]+)|mera\s+([a-z0-9_]+)\s+kya\s+hai|naa\s+([a-z0-9_]+)\s+enti)$",
            lower,
        )
        if recall_match:
            raw_key = (recall_match.group(1) or recall_match.group(2) or recall_match.group(3) or "name").strip()
            lookup_key = "name" if raw_key in {"name", "naam", "peru"} else raw_key
            memories = {m.get("key", ""): m.get("value", "") for m in self.memory.list_memories()}
            val = memories.get(lookup_key) or memories.get(raw_key)
            if val:
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["recalled"].format(key=lookup_key, value=val),
                    "executed_tools": [],
                    "client_actions": [],
                })


        speaker = any(w in lower for w in ["on speaker", "speakerphone", "speaker pe", "speaker par", "स्पीकर पर", "स्पीकर", "స్పీకర్"])
        clean_target_text = re.sub(r"\b(?:on speakerphone|on speaker|speaker pe|speaker par|स्पीकर पर|स्पीकर|స్పీకర్)\b", "", text, flags=re.IGNORECASE).strip()

        # 1. Flexible Call Intent Extraction (with multi-turn prompt if no name was spoken yet!)
        call_target = extract_call_target_flexible(clean_target_text)
        if call_target is not None:
            if call_target == "":
                # User said "Call karo" or "Call chey" without saying who -> Ask follow-up!
                self.pending_intent = {"type": "call_who", "speaker": speaker, "lang": lang}
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["ask_call_who"],
                    "executed_tools": [],
                    "client_actions": [],
                })

            res = self.automation.make_phone_call(call_target, speakerphone=speaker)
            executed_tools.append({
                "name": "make_phone_call",
                "args": {"contact_name_or_number": call_target, "speakerphone": speaker},
                "result": res,
            })
            if res.get("client_action"):
                client_actions.append(res["client_action"])

            if res["status"] == "calling":
                reply = tpl["calling"].format(name=res["contact_name"], phone=res["phone_number"])
            else:
                reply = tpl["not_found"].format(query=call_target)
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": reply,
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 2. Detect WhatsApp intent (check incomplete command without message body first!)
        wa_incomplete = re.search(
            r"^(?:(.+?)\s+(?:ko|ku|ki|को|కి|கு)\s+whatsapp\s*(?:karo|bhejo|chey|cheyandi|pampu|pannu|करो|भेजो|చేయి|పంపు|பண்ணு)?|(?:send\s+)?whatsapp(?:\s+to)?\s+(\S+))$",
            lower,
        )
        if wa_incomplete:
            target_name = (wa_incomplete.group(1) or wa_incomplete.group(2) or "").strip()
            if target_name and target_name not in {"open", "launch", "kholo", "teruvu", "thira"}:
                self.pending_intent = {"type": "whatsapp_msg", "target": target_name, "lang": lang}
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["ask_wa_msg"].format(name=target_name.title()),
                    "executed_tools": [],
                    "client_actions": [],
                })

        wa_patterns = [
            r"^(.+?)\s+(?:ko|ku|ki|को|కి|கு)\s+whatsapp\s+(?:karo|bhejo|message karo|chey|cheyandi|pampu|pannu|करो|भेजो|చేయి|పంపు|பண்ணு)\s+(.+)$",
            r"^(?:send\s+)?whatsapp(?:\s+message)?(?:\s+to)?\s+(\S+)\s+(?:saying|that|message|:)?\s*(.+)$",
        ]
        for pat in wa_patterns:
            wa_match = re.search(pat, lower)
            if wa_match:
                target_name, msg = wa_match.group(1).strip(), wa_match.group(2).strip()
                res = self.automation.send_whatsapp_message(target_name, msg)
                executed_tools.append({
                    "name": "send_whatsapp_message",
                    "args": {"contact_name_or_number": target_name, "message": msg},
                    "result": res,
                })
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                reply = tpl["whatsapp"].format(name=res.get("contact_name", target_name), msg=msg)
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": reply,
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 3. Detect SMS intent
        sms_match = re.search(r"^(?:send\s+sms\s+to|sms|text)\s+(\S+)\s+(?:saying|that|:)?\s*(.+)$", lower)
        if sms_match:
            target_name, msg = sms_match.group(1).strip(), sms_match.group(2).strip()
            res = self.automation.send_sms_message(target_name, msg)
            executed_tools.append({
                "name": "send_sms_message",
                "args": {"contact_name_or_number": target_name, "message": msg},
                "result": res,
            })
            if res.get("client_action"):
                client_actions.append(res["client_action"])
            reply = tpl["sms"].format(name=res.get("contact_name", target_name), msg=msg)
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": reply,
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 4. Detect Alarm or Timer intent
        timer_match = re.search(r"(\d+)\s*(second|seconds|sec|minute|minutes|min|मिनट|सेकंड|నిమిషాలు|నిమిషం|సెకన్లు)", lower)
        if any(w in lower for w in ["timer", "टाइमर", "టైమర్", "டைமர்"]) and timer_match:
            amount = int(timer_match.group(1))
            unit = timer_match.group(2)
            seconds = amount * 60 if (unit.startswith("min") or unit in {"मिनट", "నిమిషాలు", "నిమిషం"}) else amount
            res = self.automation.set_alarm_or_timer(mode="timer", duration_seconds=seconds)
            executed_tools.append({"name": "set_alarm_or_timer", "args": {"mode": "timer", "duration_seconds": seconds}, "result": res})
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["timer"].format(detail=res["detail"]),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        alarm_match = re.search(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)?", lower)
        if any(w in lower for w in ["alarm", "अलार्म", "అలారం", "அலாரம்"]) and alarm_match:
            hour = int(alarm_match.group(1))
            minute = int(alarm_match.group(2) or 0)
            meridiem = alarm_match.group(3)
            if meridiem == "pm" and hour < 12:
                hour += 12
            elif meridiem == "am" and hour == 12:
                hour = 0
            res = self.automation.set_alarm_or_timer(mode="alarm", hour=hour, minute=minute)
            executed_tools.append({"name": "set_alarm_or_timer", "args": {"mode": "alarm", "hour": hour, "minute": minute}, "result": res})
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["alarm"].format(detail=res["detail"]),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 4b. Detect YouTube Song / Video Search ("play Telugu songs on youtube", "youtube lo Telugu songs play chey", "youtube par Arijit Singh chalao")
        yt_patterns = [
            r"^(?:play|search)\s+(.+?)\s+(?:on|in)\s+youtube$",
            r"^youtube\s+(?:par|pe|lo|on|లో|पर)\s+(.+?)\s+(?:play\s*chey|play\s*karo|chalao|lagao|play|చాలావు|ప్లే\s*చేయి|चलाओ)$",
            r"^play\s+(.+?)\s+(?:songs?|music|video)$",
        ]
        for pat in yt_patterns:
            yt_m = re.search(pat, lower)
            if yt_m:
                q = yt_m.group(1).strip()
                res = self.automation.play_youtube_media(q)
                executed_tools.append({"name": "play_youtube_media", "args": {"query": q}, "result": res})
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["youtube"].format(query=q.title()),
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 4c. Detect Google Maps Navigation ("navigate to Charminar", "Charminar ki route chupinchu", "Charminar ka rasta dikhao")
        nav_patterns = [
            r"^(?:navigate\s+to|take\s+me\s+to|directions\s+to)\s+(.+)$",
            r"^(.+?)\s+(?:ka\s+rasta\s+dikhao|ki\s+route\s+chupinchu|ki\s+daari\s+chupinchu|కి\s+దారి\s+చూపించు|का\s+रास्ता\s+दिखाओ)$",
        ]
        for pat in nav_patterns:
            nav_m = re.search(pat, lower)
            if nav_m:
                dest = nav_m.group(1).strip()
                res = self.automation.navigate_to_location(dest)
                executed_tools.append({"name": "navigate_to_location", "args": {"destination": dest}, "result": res})
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["navigate"].format(dest=dest.title()),
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 4d. Detect Battery Status Check ("battery kitni hai", "battery entha undi", "what is my battery")
        if any(w in lower for w in ["battery", "बैटरी", "బ్యాటరీ", "பேட்டரி"]):
            res = self.automation.get_battery_status()
            executed_tools.append({"name": "get_battery_status", "args": {}, "result": res})
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["battery"].format(level=res["level"]),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 5. Detect Open App intent
        open_patterns = [
            r"^([a-z0-9\s]+?)\s+(?:kholo|chalu karo|open karo|open chey|open cheyandi|teruvu|open pannu|thira|खोलो|चालू करो|ఓపెన్ చేయి|తెరువు|திற)$",
            r"^(?:open|launch|start|abre)\s+([a-z0-9\s]+)$",
        ]
        for pat in open_patterns:
            open_match = re.search(pat, collapse_stt_gaps(lower))
            if open_match:
                app_name = open_match.group(1).strip()
                res = self.automation.open_android_app(app_name)
                if res.get("status") == "blocked_for_privacy":
                    return self._finalize_response({
                        "status": "ok",
                        "mode": "offline_smart_engine",
                        "normalized_input": text,
                        "detected_language": lang,
                        "reply": tpl["privacy_upi"],
                        "executed_tools": [],
                        "client_actions": [],
                    })
                if res.get("status") == "permission_required":
                    self.pending_intent = {
                        "type": "confirm_permission",
                        "action": "open_app",
                        "app_name": app_name,
                        "target": app_name.title(),
                        "lang": lang,
                    }
                    return self._finalize_response({
                        "status": "ok",
                        "mode": "offline_smart_engine",
                        "normalized_input": text,
                        "detected_language": lang,
                        "reply": tpl["ask_permission"].format(target=app_name.title()),
                        "executed_tools": [],
                        "client_actions": [],
                    })
                executed_tools.append({"name": "open_android_app", "args": {"app_name": app_name}, "result": res})
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["open_app"].format(app=app_name.title()),
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 6. Detect Hardware / System Settings
        setting_keywords = [
            (["flashlight", "torch", "टॉर्च", "फ्लैशलाइट", "టార్చ్", "ఫ్లాష్ లైట్"], "flashlight"),
            (["volume up", "louder", "आवाज बढ़ाओ", "awaz badhao", "వాల్యూమ్ పెంచు", "volume penchu"], "volume_up"),
            (["volume down", "quieter", "आवाज कम करो", "awaz kam karo", "వాల్యూమ్ తగ్గించు", "volume tagginchu"], "volume_down"),
            (["mute", "silent", "म्यूट", "साइलेंट", "సైలెంట్", "మ్యూట్"], "mute"),
            (["wifi", "wi-fi", "वाईफाई", "వైఫై"], "wifi"),
            (["bluetooth", "ब्लूटूथ", "బ్లూటూత్"], "bluetooth"),
            (["go home", "home screen"], "home"),
            (["go back"], "back"),
            (["notification", "notifications"], "notifications"),
        ]
        for keywords, setting_name in setting_keywords:
            if any(k in lower for k in keywords):
                action = "off" if any(w in lower for w in ["off", "disable", "band", "बंद", "ఆఫ్", "ఆపు", "aapu"]) else "on"
                res = self.automation.control_device_setting(setting_name, action)
                executed_tools.append({
                    "name": "control_device_setting",
                    "args": {"setting": setting_name, "action": action},
                    "result": res,
                })
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["setting"].format(detail=res["detail"]),
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 7. Screen Tap
        tap_match = re.search(r"^(?:tap|click)\s+(?:on\s+)?(.+)$", lower)
        if tap_match:
            target_btn = tap_match.group(1).strip()
            res = self.automation.tap_screen_element(target_btn)
            executed_tools.append({"name": "tap_screen_element", "args": {"target_text": target_btn}, "result": res})
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": f"Tapped '{target_btn}' on screen." if res.get("status") == "ok" else res.get("message", "Could not tap."),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 7b. Voice Math & Percentage Calculator ("15 percent of 2400", "250 plus 475 entha", "125 into 18 kitna hota hai")
        calc_res = self.automation.calculate_math(lower)
        if calc_res.get("status") == "ok":
            executed_tools.append({
                "name": "calculate_math",
                "args": {"expression": calc_res["expression"]},
                "result": calc_res,
            })
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["calc"].format(expr=calc_res["expression"], result=calc_res["result"]),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 7c. Google Web Search ("search google for Hyderabad weather", "google lo Hyderabad weather search chey", "google par IPL score dekho")
        web_patterns = [
            r"^(?:search\s+google\s+for|google\s+search|search\s+for)\s+(.+)$",
            r"^google\s+(?:par|pe|lo|on|లో|पर)\s+(.+?)\s+(?:search\s*chey|search\s*karo|dekho|khojo|చూడు|సెర్చ్\s*చేయి|खोजो|देखो)$",
        ]
        for pat in web_patterns:
            wm = re.search(pat, lower)
            if wm:
                q = wm.group(1).strip()
                res = self.automation.search_google_web(q)
                executed_tools.append({"name": "search_google_web", "args": {"query": q}, "result": res})
                if res.get("client_action"):
                    client_actions.append(res["client_action"])
                return self._finalize_response({
                    "status": "ok",
                    "mode": "offline_smart_engine",
                    "normalized_input": text,
                    "detected_language": lang,
                    "reply": tpl["web_search"].format(query=q),
                    "executed_tools": executed_tools,
                    "client_actions": client_actions,
                })

        # 7d. Hands-Free Screen Scrolling ("scroll down", "neeche scroll karo", "kindaki scroll chey", "scroll up")
        if "scroll" in lower or "स्क्रॉल" in lower or "స్క్రోల్" in lower or "swipe" in lower:
            direction = "down"
            if any(w in lower for w in ["up", "upar", "ऊपर", "paiki", "పైకి"]):
                direction = "up"
            elif any(w in lower for w in ["left", "baye", "बाएं"]):
                direction = "left"
            elif any(w in lower for w in ["right", "daye", "दाएं"]):
                direction = "right"
            res = self.automation.scroll_screen(direction)
            executed_tools.append({"name": "scroll_screen", "args": {"direction": direction}, "result": res})
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["scroll"].format(direction=direction),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 7e. Privacy Block: Screen Photo Scanning / Screenshot Capture Disabled
        if any(
            w in lower
            for w in [
                "screenshot",
                "screen shot",
                "scan photo",
                "scan image",
                "photo scan",
                "read photo",
                "स्क्रीनशॉट",
                "స్క్రీన్ షాట్",
                "ஸ்கிரீன்ஷாட்",
            ]
        ):
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["screenshot"],
                "executed_tools": [],
                "client_actions": [],
            })

        # 7f. Camera Photo / Selfie — Requires Explicit Owner Permission First
        if any(
            w in lower
            for w in [
                "take a photo",
                "take a selfie",
                "photo khicho",
                "selfie khicho",
                "selfie lo",
                "photo teeyu",
                "photo teeyi",
                "selfie teeyi",
                "selfie teeyu",
                "ఫోటో తీయు",
                "సెల్ఫీ తీయు",
                "फोटो खींचो",
                "सेल्फी लो",
            ]
        ):
            cam_mode = "selfie" if ("selfie" in lower or "సెల్ఫీ" in lower or "सेल्फी" in lower) else "photo"
            self.pending_intent = {
                "type": "confirm_permission",
                "action": "camera",
                "mode": cam_mode,
                "target": "Camera",
                "lang": lang,
            }
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["ask_permission"].format(target="Camera"),
                "executed_tools": [],
                "client_actions": [],
            })

        # 7g. Privacy Guardrail: Never touch UPI or payment apps
        if any(
            w in lower
            for w in [
                "upi",
                "qr scan",
                "scan qr",
                "scan and pay",
                "gpay",
                "google pay",
                "phonepe",
                "paytm",
                "bhim",
                "క్యూఆర్",
                "యూపీఐ",
                "यूपीआई",
                "क्यूआर",
            ]
        ):
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["privacy_upi"],
                "executed_tools": [],
                "client_actions": [],
            })

        # 7h. Media Playback Controls ("pause music", "next song", "paata marchu", "agla gaana", "previous song")
        media_cmd = None
        if any(w in lower for w in ["next song", "next track", "skip song", "paata marchu", "agla gaana", "తర్వాతి పాట", "अगला गाना"]):
            media_cmd = "next"
        elif any(w in lower for w in ["previous song", "previous track", "pichla gaana", "mundu paata", "पिछला गाना"]):
            media_cmd = "previous"
        elif any(w in lower for w in ["pause music", "pause song", "stop music", "paata aapu", "gaana roko", "పాట ఆపు", "गाना रोको"]):
            media_cmd = "pause"
        elif any(w in lower for w in ["resume music", "play music", "continue music"]):
            media_cmd = "play"
        if media_cmd:
            res = self.automation.control_media_playback(media_cmd)
            executed_tools.append({"name": "control_media_playback", "args": {"command": media_cmd}, "result": res})
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["media"].format(detail=res["detail"]),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 7i. Emergency SOS ("emergency sos", "sos alert", "bachao", "apada lo unnanu", "help me emergency")
        if any(w in lower for w in ["emergency sos", "sos alert", "trigger sos", "bachao", "apada lo unnanu", "बचाओ", "ఆపదలో ఉన్నాను"]):
            res = self.automation.trigger_emergency_sos()
            executed_tools.append({"name": "trigger_emergency_sos", "args": {}, "result": res})
            if res.get("client_actions"):
                client_actions.extend(res["client_actions"])
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["sos"].format(name=res["emergency_contact"]),
                "executed_tools": executed_tools,
                "client_actions": client_actions,
            })

        # 8. Time & Date Queries
        if any(w in lower for w in ["time", "समय", "kitne baje", "hora", "టైమ్", "సమయం", "entha", "நேரம்"]):
            now_str = datetime.datetime.now().strftime("%I:%M %p")
            return self._finalize_response({
                "status": "ok",
                "mode": "offline_smart_engine",
                "normalized_input": text,
                "detected_language": lang,
                "reply": tpl["time"].format(time=now_str),
                "executed_tools": [],
                "client_actions": [],
            })

        if lang == "hi":
            default_reply = (
                "नमस्ते! मैं ऑरा (Aura) हूँ। आप आराम से रुक-रुक कर भी बोल सकते हैं: "
                "'अम्मा ... को ... कॉल ... करो', 'राहुल को व्हाट्सऐप करो', या 'यूट्यूब खोलो'।"
            )
        elif lang == "ta":
            default_reply = (
                "வணக்கம்! நான் ஆரா (Aura). 'அம்மா கு கால் பண்ணு', 'யூடியூப் திற' "
                "போன்ற கட்டளைகளை உங்கள் மொழியிலேயே சொல்லலாம்!"
            )
        elif lang == "te":
            default_reply = (
                "నమస్కారం! నేను ఆరా (Aura). 'అమ్మ కి కాల్ చేయి', 'యూట్యూబ్ ఓపెన్ చేయి' "
                "అని మీ భాషలోనే చెప్పండి!"
            )
        else:
            default_reply = (
                "I'm ready! You can speak naturally with pauses—try saying 'Amma ... ko ... call ... karo', "
                "'Call ... Rahul', 'Heading home', or 'Set alarm for 7:00 AM'!"
            )

        return self._finalize_response({
            "status": "ok",
            "mode": "offline_smart_engine",
            "normalized_input": text,
            "detected_language": lang,
            "reply": default_reply,
            "executed_tools": executed_tools,
            "client_actions": client_actions,
        })
